---
tags: [Java, 安全, javaagent, 字节码, JFR, JNDI, 深入靶场, 专题]
created: 2026-10-01
阶段: 安全专题（第 4 卷，续 10–12）
主要参考: 本机实测输出 `code/sec2/本机实测输出.txt`（2026-10-01，JDK 25.0.4.1 / Eclipse Adoptium，含四个实验与 JNDI 六档模式）· 本机靶场源码 `code/sec2/`（VictimA / SecAttachAgent / SecAttach / SecRewriteAgent / SecFilterAdv / SecFilterArray / SecJndi2 / SecJndi3 / run.sh）· JEP 484（Class-File API）、JEP 451（动态加载 agent 的告警）· `javap -c` 反编译本机 `jdk.naming.rmi` 模块得到的字节码 · JDK 25 API 文档（`Instrumentation` / `ObjectInputFilter` / `NamingManager` / `Reference`）· 10–12 篇留存的四个「未复现」
---

# 运行期植入与字节码改写（深入靶场）

> 一句话：上一篇（11 篇）把四个靶子搭起来了，但有四处当时只能写「本机没做到底」——**运行期附加没走、字节码是「两份编译产物互换」、JNDI 那条 0 只能算未触发、过滤器只测了放行与拒绝**。这一卷把这四处一个个做到能下结论。四个实验全部只用本机 JDK、全部本地自建、全部可重跑；原始读数在 `code/sec2/本机实测输出.txt`。
> **定位**：学习材料，不是工具。**不含任何可直接用于攻击他人系统的载荷、利用链或绕过技巧**；被远程取走的那个工厂类是本地自建的无害类（构造函数只打印一行），HTTP 只绑 `127.0.0.1`。

## 1. 这一卷补的是什么

| 11 篇当时的写法 | 这一卷做到哪一步 | 在哪一节 |
|---|---|---|
| 「`agentmain` 是运行期附加路径，**本机演示没走这条**」 | 走通了：目标 JVM **已经在跑**、启动参数里没有任何 agent，外部进程 `jdk.attach` 连上去把它换掉 | §2 |
| 「补丁字节 = 另一份编译产物」 | 真字节码改写：读原类字节 → **进程内**用 Class-File API 换掉方法体 → `redefineClasses`，**不写第二份源码** | §3 |
| 「JNDI 路径 B 的 0 只能记『未触发』」 | 走到了解码那一步，拿到**明确的拒绝证据**；并查出这一版 JDK 的闸门已经换人 | §5 |
| 「过滤器只测了『放行/拒绝某个类』」 | 限值、数组写法、顺序、只设一次、流级能否覆盖进程级 —— 五组都测了 | §4 |

**为什么要费这个劲**：安全材料里最没价值的一句话是「理论上可以」。上面四处正好是「理论上」和「实测过」的分界，而分界线上往往藏着最值钱的知识点（本卷 §4.3、§5.3 两条都是在这个分界线上挖出来的）。

## 2. 实验 1：运行期附加 —— 目标 JVM 不重启

### 2.1 三个角色

| 角色 | 是什么 | 本机实现 |
|---|---|---|
| **目标** | 一个已经在跑的普通 Java 进程（启动时**没有任何 agent 参数**） | `VictimA.java`：每 0.5 秒打印一次 `status()`，共 26 次 |
| **附加方** | 另一个进程，负责「找到目标 → 连上去 → 把 agent 塞进去」 | `SecAttach.java`，用 JDK 自带的 `com.sun.tools.attach`（`jdk.attach` 模块） |
| **agent** | 被塞进目标的那段代码，入口是 `agentmain`（不是 `premain`） | `SecAttachAgent.java` |

`premain` 与 `agentmain` 的分工，是整个 Java 运行期植入的枢纽：

| | `premain` | `agentmain` |
|---|---|---|
| 什么时候被调 | JVM 启动时，`main` 之前 | 目标**已经跑起来之后**，由附加方触发 |
| 靠什么进进程 | 启动参数 `-javaagent:xxx.jar` | `VirtualMachine.loadAgent(jar, options)` |
| 需要重启吗 | ——（本来就在启动） | **不需要** |
| 启动参数里看得出吗 | 看得出 | **看不出**（见 §2.3） |
| JFR 里的 `dynamic` 字段 | `false` | **`true`** |

### 2.2 读数：第 6 次与原第 7 次之间发生了什么

目标进程的输出（`本机实测输出.txt` 实验 1，原文照抄）：

```
    [目标] 第06次 status() = 磁盘上的原始实现（字节码未被改动）
WARNING: A Java agent has been loaded dynamically (<用户目录>\AppData\Local\Temp\javasec2\agent\attach-agent.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
    [attach-agent] agentmain 已执行 —— 目标 JVM 没有重启
    [attach-agent] 能重定义已加载的类吗？true
    [attach-agent] 补丁字节数 = 1897，目标类 = class VictimA
    [attach-agent] redefineClasses 完成 —— 磁盘上的 VictimA.class 没被碰过
    [目标] 第07次 status() = 补丁实现（来自另一份编译产物，经外部进程 loadAgent 换上去）
```

**四行 WARNING 是这一节的第一个知识点**：JVM 自己把这件事喊了出来（JEP 451 引入）。它同时给了两个信息：① 这是**动态加载**（区别于启动期）；② 这个能力**将来会默认关掉**。原文那句 `Dynamic loading of agents will be disallowed by default in a future release` 就是官方路线图。

### 2.3 检测侧：三条痕迹，两条看不见

| 观察点 | 结果 | 说明 |
|---|---|---|
| 目标自己的 `jcmd <pid> VM.command_line` | **只显示原始参数**（`java_command: VictimA 26`，`jvm_args` 里没有任何 agent） | 植入物**不留启动参数痕迹**——查命令行这一招在这里失效 |
| JVM 自己往 **stderr** 打的 WARNING | 四行，见上 | 但只有在进程的 stderr 被收走的地方才看得到（日志/IDE 控制台） |
| **JFR** `jdk.JavaAgent` 事件 | `dynamic = true`、`name = ...attach-agent.jar`、**`options = .../patch/VictimA.class`** | 这是最硬的一条：**连传进去的 options 都被记下来了** |

对照 12 篇那批体检读数：启动期 `-javaagent` 那次同一个事件的 `dynamic = **false**`。**同一个字段，直接区分「启动期植入」与「运行期植入」**——这是本卷最实用的一条检测口径。

顺带一个诚实的失败读数：`jcmd <pid> VM.class_loaders` 在本机返回
`java.lang.IllegalArgumentException: Unknown diagnostic command`——**这条命令在这套 JDK 上不存在**，别照着别处的清单抄。

### 2.4 这扇门是谁的

`jdk.attach` 就是 `jcmd` / `jstack` / `jmap` / `jinfo` 背后同一套机制。所以：

- 你**不能**靠「禁用 Instrumentation」来防——那会打死一整套运维工具（这也是 JEP 451 选择「告警 + 将来默认关」而不是「直接删」的原因）。
- 能防的是「**谁有权在启动参数里加 agent、谁有权按 attach 这扇门铃**」：主机与部署侧的控制（§7）。

## 3. 实验 2：真正的字节码改写（Class-File API，JEP 484）

### 3.1 与「两份编译产物互换」的差别

| | 实验 1 的做法（11 篇同款） | 实验 2 的做法（本卷） |
|---|---|---|
| 补丁字节从哪来 | **另一份源码** `patch/VictimA.java` 编译出来的 `.class` | **读原类的字节，在进程内改掉方法体** |
| 需要几份源码 | 两份（结构必须一致） | **一份** |
| 能改什么 | 只能换成「事先写好的另一版」 | 可以按运行期条件**现算**新实现（真实 gadget 链、RASP、APM 都是这条） |
| 用到的 API | 只有 `Instrumentation` | `Instrumentation` + `java.lang.classfile` |

### 3.2 骨架（`SecRewriteAgent.java` 的核心 12 行）

```java
ClassFile cf = ClassFile.of();
ClassModel cm = cf.parse(classBytes);                 // 解析原字节
return cf.build(cm.thisClass().asSymbol(), cb -> {    // 重建这个类
    for (ClassElement ce : cm) {                      // 逐个元素搬运
        if (ce instanceof MethodModel mm && mm.methodName().stringValue().equals("status")) {
            cb.withMethodBody(mm.methodName().stringValue(), mm.methodTypeSymbol(),
                    mm.flags().flagsMask(), cob -> {  // 换掉方法体
                cob.ldc(newReturn);
                cob.areturn();
            });
        } else {
            cb.with(ce);
        }
    }
});
```

### 3.3 实测读数（实验 2 段）

- **原字节数 = 1865，改写后字节数 = 1879，两份不同吗？`true`**；
- 把改写后的字节**再解析一遍**做反查，`status()` 的代码属性已经变成：
  `[LoadConstant[OP=LDC, val=被 Class-File API 在进程内改写的实现（磁盘上没有这份字节）], Return[OP=ARETURN]]`
  ——**"改了什么"是可以让程序自己打印出来自证的**，不用靠肉眼；
- 行为对照：不带 agent 跑 4 次全是原始实现；带 agent 跑 4 次，**第 1 次就已经是改写后的实现**（因为 premain 在 `main` 之前）；
- 磁盘上两份 class 的 md5 全程不变：`394a0dd51672c873d221d7c86ccb0e8f`（原类）、`3f87630343d3feb3c9256eff219702bf`（补丁类）。

> 这条为什么值得单独学：**"内存马查不到文件"的最后一层就在这**。文件没变、启动参数没变、连第二次编译都不需要，实现却换了。要查，只能查运行期（§6）。

## 4. 实验 3：反序列化过滤器的进阶面

11 篇讲了「放行/拒绝某个类」。实际落地时更常遇到的是下面五件事。

### 4.1 限值：拦「形状」而不是「类名」

| 过滤器 | 载荷 | 结果 |
|---|---|---|
| `maxdepth=4;*` | 链长 2 的对象链（86 字节） | 读成功 |
| `maxdepth=4;*` | 链长 6 的对象链（126 字节） | **拦下**：`InvalidClassException: filter status: REJECTED` |
| `maxrefs=8;java.base/*;*` | `ArrayList` 里 30 个元素（640 字节） | **拦下** |
| `maxarray=8;java.base/*;*` | `String[20]`、`int[20]` | **都拦下** |

意义：**类名白名单管不住「放行了类、但对象图深得离谱」这种打法**；`maxdepth` / `maxrefs` / `maxarray` 是配套的另一半。

### 4.2 数组怎么写：一个必须实测才知道的坑

我先按直觉写了 `![Ljava.lang.String;;java.base/*;!*`，**结果读 `String[20]` 居然放行**。把它当课题测了一遍（`SecFilterArray`）：

| 过滤器的写法 | `String[20]` | `int[20]` | 结论 |
|---|---|---|---|
| `java.base/*;!*` | 放行 | 放行 | 基线 |
| `[;java.base/*;!*` | 放行 | 放行 | 「任意数组」这种写法**在这里没起作用** |
| `[Ljava.lang.String;;java.base/*;!*` | 放行 | 放行 | **裸写数组描述符无效** |
| `![Ljava.lang.String;;java.base/*;!*` | 放行 | 放行 | 加 `!` 也无效 |
| `![I;java.base/*;!*` | 放行 | 放行 | 基本类型数组描述符同样无效 |
| `maxarray=8;*` | **拦下** | **拦下** | 用限值 |
| `!java.lang.String;java.base/*;!*` | **拦下** | 放行 | **用元素类名**（不动 `int[20]`，粒度正好） |

**原因**：数组的类名是描述符形式 `[Ljava.lang.String;`，**结尾那个 `;` 与过滤器的模式分隔符是同一个字符**——写成字符串之后，模式被从中间拆开，实际生效的是一条半截模式。所以：**要精确拒绝一个数组，写元素类名（或包前缀），或用 `maxarray=`；不要指望在字符串过滤器里写数组描述符。**

### 4.3 顺序：先命中者生效（实测一对对照）

| 过滤器 | `String[20]` |
|---|---|
| `!java.lang.String;java.base/*` | **拦下**（先命中拒绝） |
| `java.base/*;!java.lang.String` | 放行（先命中放行） |

这条决定了白名单只能写成「**具体拒绝 → 必要的放行 → 兜底 `!*`**」的顺序，写反了等于没写。

### 4.4 进程级只能设一次；流级能盖住进程级

- `ObjectInputFilter.Config.setSerialFilter(...)` 第二次调用抛
  `java.lang.IllegalStateException: Serial filter can only be set once`
  ——**一个进程只有一个全局过滤器，装晚了就装不上了**，这也是为什么它适合当启动参数（`-Djdk.serialFilter=`）。
- **流级 `setObjectInputFilter(ALLOWED)` 可以盖过进程级**：同一份载荷，流级全放行时读成功；不设流级时被进程级拦下。所以「装了进程级过滤器」不等于「每个反序列化点都受它管」——**库代码有机会在流上覆盖它**。这是白名单纪律要写在代码评审里的原因。

## 5. 实验 4：JNDI 那条链到底能不能走到远端（本卷最值钱的一节）

11 篇在这里只能写「两条路径 HTTP 请求数都是 0，其中路径 B 的 0 只能算『未触发』」。这一节把它走到底。

### 5.1 先犯一个错，再让报错教我

要走进 JNDI 的解码路径，绑到 RMI 上的对象必须是 provider 认得的内部包装类 `com.sun.jndi.rmi.registry.ReferenceWrapper`。我第一次把模块名写成 `java.naming`，实测报错原文：

```
构造失败：java.lang.IllegalAccessException: class SecJndi2 cannot access class
com.sun.jndi.rmi.registry.ReferenceWrapper (in module jdk.naming.rmi) because module
jdk.naming.rmi does not export com.sun.jndi.rmi.registry to unnamed module @45fe3ee3
```

**报错信息自己给出了正确模块名**：它在 **`jdk.naming.rmi`** 里，不在 `java.naming` 里。于是正确的启动参数是 `--add-exports jdk.naming.rmi/com.sun.jndi.rmi.registry=ALL-UNNAMED`（完整那一行见 `code/sec2/run.sh` 里「模式 B」）。

> 记这条的用处不是记 flag，而是记**方法**：模块名、包名、属性名这类东西，凭记忆写几乎必错；**让运行时报错来纠正你，比查半天文档快**。

### 5.2 六档模式的读数（全部实测）

| 模式 | 启动参数 | 内部包装类能构造吗 | `lookup` 的结果 | HTTP 收到请求数 |
|---|---|---|---|---|
| A | 无 | 不能（`IllegalAccessException`） | 跳过了 | 0 |
| B0 | `--add-exports java.naming/...`（**写错**） | 不能（同一个报错） | 跳过了 | 0 |
| B | `--add-exports jdk.naming.rmi/...` | **能** | **`ConfigurationException: Remote object factories are not supported`** | **0** |
| C | B + `-Dcom.sun.jndi.rmi.object.trustURLCodebase=true` | 能 | 同上（**老属性没有改变任何行为**） | 0 |
| D | B + 注册一个返回 `null` 的 `ObjectFactoryBuilder` | 能 | `NamingException`，原因 `NullPointerException: ... because "factory" is null` | 0 |
| E | D + `-Djdk.jndi.rmi.object.factoriesFilter=*` | 能 | 与 D 相同 | 0 |
| F | E + `-Djdk.jndi.object.factoriesFilter=*` | 能 | 与 D 相同 | 0 |

**模式 B 与 C 的区别是本节的转折点**：B 已经走到了解码那一步（不再是我测试没打到点上），拿到的是**明确的拒绝**——11 篇那个「未触发」升级成了「**走到了、被明确拒绝**」。而 C 说明：**把老属性打开，一点用都没有。**

### 5.3 为什么老属性没用：去字节码里看

用 `javap -c --module jdk.naming.rmi com.sun.jndi.rmi.registry.RegistryContext` 反编译 `decodeObject`，关键几行是：

```
58: invokestatic  #328  // com/sun/naming/internal/NamingManagerHelper.getObjectFactoryBuilder()
61: ifnonnull     88
64: aload 4             // 那个 Reference
66: ifnull        88
69-71: invokevirtual  // Reference.getFactoryClassLocation()
74: ifnull        88
77: new  #222          // javax/naming/ConfigurationException
81: ldc_w #337         // String  Remote object factories are not supported
87: athrow
88: ... getObjectInstance(obj, name, this, environment, predicate)
```

**读法**：只要「没有注册 `ObjectFactoryBuilder`」且「Reference 带了工厂类位置」，**无条件抛**——这里**根本没有属性检查**。也就是说这个异常跟「信任开关」没关系，是这版实现**不支持**远程对象工厂。

再确认一次：把 `java.naming` 与 `jdk.naming.rmi` 两个模块的类文件全提出来搜字符串，`com.sun.jndi.rmi.object.trustURLCodebase` **一次都不出现**。而实际存在的属性是这一族：

| 属性 | 默认值（从字节码常量池读出） | 作用 |
|---|---|---|
| `jdk.jndi.object.factoriesFilter` | `*` | 全局：哪些对象工厂类允许 |
| `jdk.jndi.ldap.object.factoriesFilter` | `java.naming/com.sun.jndi.ldap.**;!*` | LDAP provider：**只放行自己的内部包，其余全拒** |
| `jdk.jndi.rmi.object.factoriesFilter` | `jdk.naming.rmi/com.sun.jndi.rmi.**;!*` | RMI provider：同上（注意模块名是 `jdk.naming.rmi`） |
| `jdk.jndi.ldap.mechsAllowedToSendCredentials` | （另有） | LDAP 凭据发送的机制白名单 |
| `com.sun.jndi.ldap.object.trustSerialData` | （另有） | 老属性的另一个成员 |

这些名字出自本机模块里 `com.sun.naming.internal.ObjectFactoriesFilter` 的常量池，它们被交给 `ObjectInputFilter.Config.createFilter(...)`——**换句话说：工厂类能不能用，现在是按「反序列化过滤器」那套白名单语法管的**。

### 5.4 诚实边界（这一节别读过头）

1. 上面这些**全部是 RMI provider 的读数**。历史上 Log4Shell 走的是 **LDAP** 那条路，本机没有 LDAP 服务端，**未测**——所以「LDAP 路在 JDK 25 上会怎样」本篇不下结论，只给出它的默认过滤器长什么样（§5.3 表里第二行）。
2. 模式 D/E/F 里我注册的 `ObjectFactoryBuilder` 是**我自己写的、返回 null 的**。真实世界里框架（容器）注册的 builder 可能自己去取工厂类——**那种情形本机未测**。
3. 「HTTP 请求数 = 0」只证明**这一次、这一档配置下**没有发生远端取类；它不能反推「任何 JNDI 用法都不会取远端类」。
4. 被托管的 `EvilFactory.class` 是我们自己写的无害类（构造函数只打印一行），**不含任何落地动作**。

## 6. 检测视角：三件事各留下什么痕迹

| 手法 | 启动参数 | stderr | JFR | 进程内其它线索 |
|---|---|---|---|---|
| 启动期 `-javaagent`（10/11 篇） | **看得到** | 无特殊告警 | `jdk.JavaAgent` **`dynamic=false`** | —— |
| 运行期 attach（§2） | **看不到** | 四行 WARNING（含"将来默认禁止"） | `jdk.JavaAgent` **`dynamic=true`**，且 `options` 被记录 | `jcmd` 的 attach 记录；`VM.class_loaders` 在本机不可用 |
| 进程内改字节码（§3） | 看不到（可无 agent 参数，走同一套 Instrumentation） | 同上（只要有动态加载） | `jdk.ClassRedefinition` / `jdk.RedefineClasses` 各 1 条 | 类定义**没有磁盘来源**（`CodeSource` 为 `null`，见 11 篇） |

**一条能落地的检测纪律**：把 JFR 那两个事件（`jdk.JavaAgent` 看 `dynamic`、`jdk.ClassRedefinition`/`jdk.RedefineClasses` 看有没有人动过已加载的类）当基线采集；只有启动参数那一栏是**靠不住**的。

## 7. 防守动作

| 面 | 动作 | 本机怎么自查 |
|---|---|---|
| 谁能加启动参数 | `-javaagent`、`--add-exports`、`-Xbootclasspath` 这类参数要有台账；容器/编排层的启动参数按代码审 | `jcmd <pid> VM.command_line` 看现状（但注意：**看不出运行期植入**） |
| 谁能按 attach 的门铃 | 限制 JVM 运行账户的权限；不要任意账户都能写进程的临时目录（attach 靠那个通信） | 本卷实验 1 就是从外部账户角度演示的 |
| 动态加载要不要开 | 本机这套 JDK 默认允许但**告警**；可以用 `-XX:-EnableDynamicAgentLoading` 之类收紧，同时接受运维工具受影响 | 实验 1 的 WARNING 原文 |
| 反序列化过滤器 | 进程级 `-Djdk.serialFilter=` 写成「具体拒绝 → 必要放行 → `!*`」，并配 `maxdepth`/`maxrefs`/`maxarray`；**注意数组描述符写不进去，要用元素类名** | §4 四张表 |
| 工厂类加载 | 不要放开 `jdk.jndi.*.object.factoriesFilter`；不要在不清楚后果时给 JVM 注册 `ObjectFactoryBuilder` | §5.2 模式 D/E/F |
| 运行期取证 | 采 JFR 基线（`jdk.JavaAgent`/`ClassRedefinition`/`RedefineClasses`/`ClassDefine`/`Deserialization`），找「有加载记录但磁盘无来源」的类 | 12 篇体检 7 + 本卷 §6 |

## 8. 与 10–12 篇的接口

- **11 篇**：§8.1 那条「未触发」已按本卷 §5 回填成「走到了、被明确拒绝」；§6.4 那句「本机演示没走这条」现在有了下篇。
- **12 篇**：§4 里 JNDI 族的读数与本卷 §5 同源；§5 的体检清单可以补上 `jdk.JavaAgent.dynamic` 这条判据（本卷 §6）。
- **10 篇**：§5 攻击面地图里「类加载与 agent」一行，机制细节在本卷 §2–§3；「运行期植入」的防守动作在本卷 §7。

## 练习

> 规矩同前几卷：只给任务与验收标准，**不给答案代码**。全部只用本机 JDK。

| # | 任务 | 验收标准 | 建议时长 |
|---|---|---|---|
| 1 | 自己跑一遍实验 1，并**在目标运行期间**用 `jcmd` 找证据 | 能说出「哪条命令看不到植入、哪条看得到」，并把 JFR 里 `dynamic` 的两种情况都指出来 | 1 小时 |
| 2 | 把实验 1 的补丁换成一个**只在特定条件**才生效的实现（例如只在某次调用后改变行为） | 能用输出证明「改的是已加载的实现，且磁盘文件没动」；能说出 `redefineClasses` 的 schema 约束 | 1.5 小时 |
| 3 | 用 Class-File API 改一个**别的方法体**（不是 `status()`），例如让 `main` 里多打印一行 | 改写后的字节能 `parse` 回来并通过反查打印出你插入的指令；两次运行对照 | 1.5 小时 |
| 4 | 给 `SecFilterArray` 增加三种数组写法（`Object[]`、`String[][]`、`Integer[]`），验证 §4.2 的结论是否仍然成立 | 结论保持一致或你找出反例，并说明原因 | 1 小时 |
| 5 | 给一个自写程序装上「具体拒绝 → 必要放行 → `!*`」的进程级过滤器，再用流级覆盖它 | 能演示「流级能盖过进程级」这一条，并写出为什么这要求代码评审也看过滤器 | 1 小时 |
| 6 | 复现 §5.1 的报错：故意把 `--add-exports` 的模块名写错，从报错里读出正确模块名 | 能说出你是从报错哪一句读出模块名的 | 20 分钟 |
| 7 | 用 `javap -c --module jdk.naming.rmi ...` 自己把 §5.3 那几条字节码找出来 | 指出「无条件抛」对应哪几条指令、`ifnonnull 88` 那个分支跳过了什么 | 1 小时 |

## 自我检查清单

- [ ] 我能说清 `premain` 与 `agentmain` 的四个区别，并知道 JFR 里哪个字段区分它们
- [ ] 我知道「运行期植入」在启动参数里**看不出**，但在 stderr 与 JFR 里**看得出**
- [ ] 我知道 `jdk.attach` 与 `jcmd`/`jstack` 是同一套机制，所以「禁用 Instrumentation」不是可行的防守
- [ ] 我能说出「两份编译产物互换」与「进程内改字节码」的差别，并知道后者才是 RASP/内存马的技术底座
- [ ] 我知道 `redefineClasses` 要求 schema 不变、只换实现
- [ ] 我能说出反序列化过滤器的三类限值（`maxdepth`/`maxrefs`/`maxarray`）各拦什么
- [ ] 我知道**字符串过滤器里写不出数组描述符**（`;` 冲突），要改用元素类名或 `maxarray=`
- [ ] 我知道过滤器「第一个命中的模式生效」，所以白名单顺序不能写反
- [ ] 我知道进程级过滤器只能设一次，且**流级可以覆盖它**
- [ ] 我知道 JNDI 解密的内部包装类在 **`jdk.naming.rmi`** 模块，需要 `--add-exports` 才能构造
- [ ] 我知道本机 JDK 25 的 RMI provider **无条件**拒绝带 factoryLocation 的 Reference，且**不读** `trustURLCodebase`
- [ ] 我能说出这一版 JDK 上「工厂类能不能用」是由哪一族属性管的
- [ ] 我知道 §5 的边界：LDAP 路、框架注册 builder 的情形**本机未测**，不下结论
- [ ] 我清楚这一卷的所有动作都落在「我自己的机器 + 我自己的程序」范围内

## 出处

| 内容 | 来源 | 位置 |
|---|---|---|
| 四个实验的全部读数 | 本机靶场 `code/sec2/` 的原始输出 | `Java/code/sec2/本机实测输出.txt`（深度靶场批次） |
| 靶场源码（可重跑：`bash run.sh`） | `code/sec2/` | VictimA / SecAttachAgent / SecAttach / SecRewriteAgent / SecFilterAdv / SecFilterArray / SecJndi2 / SecJndi3 / codebase-src/EvilFactory / run.sh |
| 动态加载 agent 的告警与"将来默认禁止" | OpenJDK JEP 451 | https://openjdk.org/jeps/451 |
| Class-File API（JEP 484） | OpenJDK JEP 484 与 JDK 25 API 文档 | https://openjdk.org/jeps/484 · https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/classfile/package-summary.html |
| `Instrumentation`（`premain`/`agentmain`/`redefineClasses`/`isModifiableClass`） | JDK 25 API 文档 | https://docs.oracle.com/en/java/javase/25/docs/api/java.instrument/java/lang/instrument/Instrumentation.html |
| attach 机制（`com.sun.tools.attach`） | JDK 25 API 文档（`jdk.attach` 模块） | https://docs.oracle.com/en/java/javase/25/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html |
| 反序列化过滤器语法与限值 | JEP 290 与 `ObjectInputFilter` 文档 | https://openjdk.org/jeps/290 · https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/ObjectInputFilter.html |
| `ObjectInputFilter.Config.setSerialFilter` 只能设一次 | 同上（API 文档的异常说明） | 同上 |
| `NamingManager.setObjectFactoryBuilder` 与工厂查找 | JDK 25 API 文档（`javax.naming.spi`） | https://docs.oracle.com/en/java/javase/25/docs/api/java.naming/javax/naming/spi/NamingManager.html |
| `Reference` / `getFactoryClassLocation` | JDK 25 API 文档（`javax.naming`） | https://docs.oracle.com/en/java/javase/25/docs/api/java.naming/javax/naming/Reference.html |
| `RegistryContext.decodeObject` 的字节码与属性常量 | **本机 JDK 的模块**（`javap -c --module jdk.naming.rmi`、`jimage list/extract` + 常量池检索） | 本机 `jdk.naming.rmi` / `java.naming` 模块 |
| 安全边界（法条） | 《刑法》第 285/286 条；法释〔2011〕19 号（只引条文号） | 以正式公布文本为准 |

## 依据说明

- **有官方依据的部分**：`premain` / `agentmain` 的调用时机、`-javaagent` 与 `loadAgent` 的区别、「agent jar 被追加到系统类路径」的契约、`redefineClasses` 的「schema 不变、只换实现」约束、`isModifiableClass` / `isRedefineClassesSupported` 的语义（`java.lang.instrument` API 文档）；动态加载 agent 的告警文本与「将来默认禁止」的路线（JEP 451）；Class-File API 的 `parse` / `build` / `ClassTransform` / `CodeBuilder` 契约（JEP 484 与 `java.lang.classfile` 文档）；`ObjectInputFilter` 的模式语法、`maxdepth`/`maxrefs`/`maxarray` 限值、`Config.setSerialFilter` 的「只能设一次」、流级与进程级的优先关系（JEP 290 与 API 文档）；`NamingManager.setObjectFactoryBuilder` 的用途与工厂查找顺序（`javax.naming.spi` 文档）；`Reference.getFactoryClassLocation` 的语义（`javax.naming` 文档）。
- **本机实测（2026-10-01，JDK 25.0.4.1 / Eclipse Adoptium；原始输出在 `code/sec2/本机实测输出.txt`，批次头为「深度靶场批次」）**：目标进程第 06 次输出原始实现、第 07 次起变为补丁实现（`redefineClasses` 前后）；JVM 往 stderr 打的四行 WARNING（含 `Dynamic loading of agents will be disallowed by default in a future release`）；`jcmd VM.command_line` 只显示原始参数；`jcmd VM.class_loaders` 报 `Unknown diagnostic command`；JFR `jdk.JavaAgent` 的 `dynamic = true`（运行期）与 `dynamic = false`（启动期，见 `code/sec/本机实测输出.txt` 体检批次）、`options` 字段内容、`jdk.ClassRedefinition`/`jdk.RedefineClasses` 各 1 条；改写 1865 → 1879 字节、反查出的 `status()` 代码属性为 `LDC + ARETURN`、两份 class 的 md5 未变；过滤器的 `maxdepth=4`（链 2 通过 / 链 6 拒绝）、`maxrefs=8`（30 元素拒绝）、`maxarray=8`（两种数组拒绝）、数组限制模式全部放行、`!java.lang.String` 拦 `String[20]` 但不拦 `int[20]`、顺序对照一对、`setSerialFilter` 第二次抛 `IllegalStateException`、流级 `ALLOWED` 覆盖进程级；JNDI 六档模式（A/B0/B/C/D/E/F）的构造结果、`lookup` 异常原文与 HTTP 请求数（全 0）；`javap -c` 反编译 `RegistryContext.decodeObject` 得到的「无条件抛」字节码序列；从模块常量池检索到的 `jdk.jndi.*` 属性名与默认值。
- **属本人编排判断的部分**：本卷的四个实验选题（正是 10–12 篇留下的四处未复现）、§2.1 的角色划分与 §2.3 的检测三点、§3.1 的对比表结构、§4 的五组测法及其顺序、§5.2 的六档阶梯（A/B0/B/C/D/E/F 是我为了让「哪一步改变了行为」可读而设计的）、§6 的痕迹对照表、§7 的防守清单，以及全部练习与时长估计。
- **不确定、未实测、请勿当结论的部分**：(1) **LDAP provider 的远端工厂/序列化数据路径未测**——本机没有 LDAP 服务端，§5.3 只给出它的默认过滤器字符串（从常量池读出），不下「能不能打」的结论；(2) **框架/容器注册的 `ObjectFactoryBuilder` 自己去取工厂类的情形未测**（我注册的那个 builder 返回 `null`）；(3) 模式 D/E/F 里出现的 `NullPointerException: ... because "factory" is null` 是**实测现象**，本卷只如实记录与解读为「工厂没被解析出来」，**没有去读 JDK 源码判定这属于设计还是缺陷**；(4) `jcmd` 的其它诊断命令本机未逐条清点（只确认 `VM.class_loaders` 不存在）；(5) 不同 JDK 发行版/版本上 `jdk.jndi.*` 属性的默认值与这版实现的拒绝行为**可能不同**，本卷所有结论的前提都是「本机这一套 25.0.4.1」；(6) 代码签名、RASP、真实内存马的检测效果本卷仍未实测。

---

上一篇 → [[12-Java安全视角 ATT&CK与Java漏洞族]] · [[00-Java索引]] · 动手靶场 → [[11-Java漏洞利用实践 反序列化与类加载（本地靶场）]]
