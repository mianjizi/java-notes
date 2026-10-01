---
tags: [Java, 安全, 字节码, 攻击面, 反序列化, 专题]
created: 2026-10-01
阶段: 安全专题（第 1 卷，续 00–09）
主要参考: 本机 JDK 25.0.4.1（Eclipse Temurin，Windows 11）上的自建靶场逐条实测（2026-10-01，原始输出见本库 `Java/code/sec/本机实测输出.txt`）· JEP 290 / 403 / 411 / 486 / 335 与 JEP 索引 · 《刑法》第 285/286 条与法释〔2011〕19 号 · OWASP（Java 侧常见缺陷类别）· 本库 01–09 篇正文
---

# 安全研究视角：从「会写 Java」到「看懂攻击面」

> 一句话：Java 帮你挡掉了 C 里那些**内存破坏**类的问题（没有未定义行为、没有栈溢出可打、没有手写指针），代价是把风险挪到了**「代码从哪来、绑定到谁、数据类型何时被当作代码」**这三件事上——反序列化、JNDI、表达式注入、类加载与 agent，全部落在这一层。这一篇不讲怎么打人，讲的是**看懂攻击面、认出机制、把防御装回去**；真正上手的靶场在 [[11-Java漏洞利用实践 反序列化与类加载（本地靶场）]]。
> **定位**：学习材料，不是工具。全篇的「本机实测」都来自本库 `Java/code/sec/` 下自建的无害靶子（不下载、不落地、不对外），原始输出在 `Java/code/sec/本机实测输出.txt`。**不含任何可直接用于攻击他人系统的载荷、利用链或绕过技巧**——这不是能力限制，是第 2 节要讲的边界。

## 1. 心智模型：同一段代码的三层，三层各有各的攻击面

有 C 基础的人学 Java 安全，第一件要换掉的直觉是「漏洞是不是都在内存里」。

| 层 | 你写的东西 | 攻击者关心什么 | Java 特有的危险 |
|---|---|---|---|
| **源码层** | `.java` 里的类与方法 | 逻辑缺陷：认证、权限、拼接 | 命令拼接、路径遍历、SQL 拼接——**与语言无关的老毛病** |
| **字节码层** | `.class`（每大版本加一的版本号） | **代码从哪来**：能不能被替换、能不能被定义 | 类加载器、`defineClass`、`javaagent` 的 `redefineClasses` |
| **运行时层** | 堆里的对象、已加载的类 | **数据什么时候变成代码/类名/方法名** | 反序列化、`XMLDecoder`、JNDI `Reference`、表达式引擎 |

**与 C 的对照（这是本篇的切口）**：

- C 的经典漏洞是「**写坏了内存**」：越界、UAF、格式化串。Java 把这层直接抽掉了——数组带边界检查、没有裸指针、整数溢出**静默回绕但不导致内存破坏**（回绕本身仍是逻辑缺陷，见 [[02-阶段二 面向对象与核心类库]] 的数值一节）。
- Java 的经典漏洞是「**信任了不该信任的数据/位置**」：一个字节流被当成对象图来重建（反序列化）、一个字符串被当成类名或方法名去解析（JNDI、表达式）、一段字节被当成类定义加载（类加载器、agent）。
- 所以学 C 安全练的是「看懂一个二进制的内存布局」，学 Java 安全练的是「**看懂一条从不可信数据到代码执行的路径**」。前者靠 gdb，后者靠读调用链和 `javap`。

一句话记法：**C 的危险在于「数据能覆盖代码」，Java 的危险在于「数据能被当成代码」。**

## 2. 先立边界：动手之前先读这一节

这一节不写「请勿违法」这种空话，只写**具体的线**和**为什么这条线在技术上也成立**。

**法律边界（只引条文号，不解释，实务以律师意见为准）**：

- 《中华人民共和国刑法》**第二百八十五条**（非法侵入计算机信息系统罪、非法获取计算机信息系统数据罪、非法控制计算机信息系统罪）与**第二百八十六条**（破坏计算机信息系统罪）。
- 《最高人民法院、最高人民检察院关于办理危害计算机信息系统安全刑事案件应用法律若干问题的解释》（**法释〔2011〕19 号**）给出了「情节严重」「后果严重」的量化口径（如身份认证信息的条数、经济损失数额等）。
- **这是边界说明，不是法律意见。** 真正的分界线不是「你懂不懂技术」，而是**「你动的是不是别人的系统、有没有授权」**。法院看的也不是你的技术水平，是有没有越权访问与后果。

**技术上的自律（比法律更早起作用）**：

1. **只打自己写的程序**。这一篇和 11 篇的全部靶子都是本库 `Java/code/sec/` 下我们自己写的类——靶子、payload、受害者三样都在自己的进程里。
2. **不写、不留可直接用的载荷**。本库刻意不提供完整的 gadget 链、可用的 JNDI 利用代码、webshell、绕过检测清单。原因有两层：① 这类东西的生命周期就是「拿去打人」；② **理解了机制的人不需要现成载荷**，而只会粘贴载荷的人遇到环境差异就打不动（11 篇第 4 节讲得很具体）。
3. **不碰生产环境、不碰别人的服务**，哪怕只是「测一下能不能连」——扫描与探测本身在多部法律里就已经是行为。
4. **本地靶场也要隔离**：本库的靶场不监听公网 IP（实测里 `HttpServer` 只绑 `127.0.0.1`），跑完即停。

**最后一条心态**：安全方向最容易走偏的地方不是技术，是「我是在学防御，还是想攒一把能打人的枪」。一个简单的自检——**你手上的东西，能不能直接拿去找一个陌生 IP 用？** 能，就说明越界了。

## 3. 本机安全画像：先量清楚「什么能、什么不能」（全部实测）

学安全最忌讳凭印象。下面每一条都在本机 JDK 25.0.4.1 上跑过（`Java/code/sec/SecBoundary.java`，原始输出见日志的 `SecBoundary` 两段）。

| 探测项 | 本机实测结果 | 这条意味着什么 |
|---|---|---|
| `System.getSecurityManager()` | `null` | 沙箱模型已经不在默认链路上 |
| `System.setSecurityManager(null)` | 抛 `UnsupportedOperationException: Setting a Security Manager is not supported` | **Security Manager 被永久禁用**（JEP 411 弃用 → JEP 486 禁用）。想靠它做沙箱的方案，在这套 JDK 上直接不成立 |
| `jdk.internal.misc.Unsafe` 反射取实例 | 默认抛 `InaccessibleObjectException`（`module java.base does not "opens jdk.internal.misc"`） | 强封装（JEP 403）在起作用 |
| 同上，加 `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` | **可拿到实例** | 强封装是「默认关」，**开关一开就回去了**——加固要管的是启动参数 |
| 反射改 `java.lang.String.value` | 默认 `InaccessibleObjectException`；加 `--add-opens java.base/java.lang=ALL-UNNAMED` 后**可以改** | 同上。历史漏洞的复现常需要 `--add-opens`，这也是它们在新 JDK 上失效的原因 |
| `sun.misc.Unsafe` 反射取实例 | **两次运行都拿到了**（`sun.misc.Unsafe@…`） | 别以为「JDK 内部都不能碰」：这个老牌类仍可从反射路径拿到 |
| `com.sun.jndi.rmi.object.trustURLCodebase` 等三个属性 | 未设置（用 JVM 内置默认） | 远程 codebase 的信任由 provider 层默认策略决定，见下面第 5 节与 11 篇第 8 节 |
| `jdk.serialFilter`（进程级反序列化过滤器） | **未设置** | 默认**没有**全局过滤器：反序列化的第一道闸要你自己装（JEP 290，见 11 篇第 7 节） |
| 可用脚本引擎数 | **0** | Nashorn 已移除（JEP 335 弃用 → 后续版本移除），「用 JS 引擎执行代码」这条老路在本机不通 |
| 常见攻击面 API 是否还在 | `ObjectInputStream` / `InitialContext` / `Instrumentation` / `Proxy` / `DocumentBuilderFactory` / `jdk.jfr.Recording` **全部存在** | 攻击面本身没有消失，只是被加了默认限制 |

**这张表的读法**：Java 这十年的安全改进，几乎都是「**把默认值改成安全、把危险动作挪到需要显式开关的位置**」，而不是「删掉能力」。所以安全工作的落点是两件事：**管住启动参数与配置**、**在代码里显式加固**。

## 4. 第二层补课：字节码是「可读的第二份源码」

[[04-阶段四 并发与JVM]] 第 9 节已经讲过类加载与字节码的基础，这里只补安全视角下的读法。

**为什么安全研究必须会读字节码**：

1. **源码可以骗人，字节码不太能**。反编译看到的形态和真实执行的东西可能不一致；混淆、动态生成、运行期 redefine 之后，源码根本不存在。
2. **漏洞的「危险动作」在字节码里是可见的**：一次 `Runtime.exec`、一次 `Class.forName`、一次 `ObjectInputStream.readObject`、一次 `defineClass`，在 `javap -c` 的输出里都是显式的调用指令。
3. **判断老漏洞为什么打不动**，往往就差在字节码/启动参数这一层（比如 `--add-opens` 前后行为不同，见第 3 节的实测对照）。

本机能立刻用的三条命令（都在 `bin/` 下，全部本机自带）：

```bash
javap -c -p <类名>            # 看方法体字节码：找 exec / forName / readObject / defineClass
javap -v <类名>               # 看常量池与版本号（major version 每大版本加一）
jdeps <jar 或 类目录>          # 看依赖了什么模块与包：给一个陌生组件做「它能碰什么」的初判
```

**一个真实的读法示例**（本库实测过、写在 [[04-阶段四 并发与JVM]] 与 02 篇里的现象）：字符串 `+` 拼接编译出来是 `invokedynamic … makeConcatWithConstants`，而**不是**显式的 `StringBuilder` 调用。安全视角的意义在于：**你以为的源码形态和执行形态不一致**，审计时只读源码会漏掉「实际调用了什么」。要养成「拿 `javap` 对一遍」的习惯。

**学习动作**：随便挑本库 `Java/code/probe/` 里一个探针类，`javap -c -p` 看一遍，找出所有「会往外伸手」的调用（`Class.forName`、`Files.*`、`System.getProperty`、`Runtime.*`）。能一眼认出来，审计才算入门。

## 5. 攻击面地图：Java 特有的六个入口

这张表是**机制层**的地图；按漏洞族与 ATT&CK 战术组织的版本在 [[12-Java安全视角 ATT&CK与Java漏洞族]]。

| 入口 | 机制一句话 | 为什么 Java 特有 | 本机能不能复现 |
|---|---|---|---|
| **反序列化**（`ObjectInputStream`） | 读字节流时，JVM 会**调用类自己的 `readObject` 钩子**，容器还会**主动调用元素的方法**来重建结构 | 「数据即对象图」是 Java 独有的默认能力（C 里没有对应物） | **能**，本机自建靶子跑通（11 篇第 3 节） |
| **JNDI / 动态类加载** | 按名字查资源；若命中 `Reference`，会尝试**从远程 URL 加载工厂类** | 「按名字取类」+「远程 codebase」的组合（Log4Shell 的本质） | 部分能：本机量到了两条路径的行为与测试局限（11 篇第 8 节） |
| **表达式 / 模板注入** | SpEL、EL、OGNL 这类引擎能**求值表达式**，而表达式可以调用方法 | 大量框架为了灵活把「字符串当表达式跑」 | **不能**（本机没有任何框架），只讲机制与防守 |
| **XML 解析** | 外部实体会被解析器**展开**，XXE 由此而来 | 与语言无关，但 Java 的默认工厂配置一直是重灾区 | **能**，本机实测默认会展开实体（见下） |
| **类加载与 agent** | `defineClass` 能从内存字节定义类；`javaagent` 能**替换已加载类的实现** | 「无文件」与「运行期植入」的原语 | **能**，本机两类实验都跑通（11 篇第 5、6 节） |
| **依赖供应链** | 一行坐标背后是一棵传递依赖树，每层都可能带漏洞 | Maven/Gradle 生态的规模效应 | **不能**（本机没有构建工具与第三方库），只能讲方法与证据 |

**其中一条值得单独记下来**：本机实测 `DocumentBuilderFactory.newInstance()` 的**默认配置会展开外部实体**——XML 里指向的本机文件内容被完整读了出来（靶子文件写的是 `SECRET-CONTENT-1234567890`，读回来一模一样）。加上 `disallow-doctype-decl` 之后才被拒绝（抛 `SAXParseException`）。所以「默认是安全的」这个假设，在 XML 这一层是错的；**每一处 XML 解析都要显式加固**。

## 6. 一句话统一视角：Java 的安全问题几乎都是「数据变代码」

把上面六个入口串起来看，会发现它们共享同一个内核：

| 表面形式 | 数据被当成了什么 | 本机实测证据 |
|---|---|---|
| 反序列化字节流 | 对象图 → 会执行 `readObject` 钩子 | `readObject` 钩子被调用 1 次；`PriorityQueue` 1/2/4 个元素时 `compareTo` 被调用 **0/1/3** 次；`HashMap` 1/4/16 个键时 `hashCode+equals` 被调用 **1/4/16** 次 |
| `XMLDecoder` 的 XML | 对象 + **类名 + 方法名** | XML 里写了 `Bean` 与 `setValue`，反序列化时 **setter 被调用 1 次** |
| JNDI 名字 | **类名** + 远程 URL | 两条路径都**没有**去下载远程类（但要注意测试局限，见 11 篇第 8 节） |
| 表达式字符串 | **方法调用** | 本机无框架，未实测 |
| class 文件字节 | **类定义** | `defineClass` 直接从内存定义成功，且该类的 `CodeSource` 为 **null**（磁盘上无来源可查） |

**所以防守的思路也是统一的**：**不要让「不可信输入」有机会走到「解释/实例化/加载」那一步**。具体做法有共性——白名单（只允许认识的类/只允许认识的名字）、关掉不需要的能力（不必要的解析器特性）、能不用 Java 原生序列化就不用（用 JSON 之类**数据就是数据**的格式）。

## 7. 本机能做到哪一步（能力清单，如实标注）

**本机能做（都有实测支撑）**：

- 反序列化机制的端到端复现（自建类 + 自建载荷）
- JEP 290 过滤器的三种装法与拦截效果验证
- 类加载：`defineClass`、双亲委派、类身份、`CodeSource` 的对比
- `javaagent` 的 `premain` + `redefineClasses` 运行期替换
- XXE / 路径遍历 / 命令执行原语 / `XMLDecoder` 的默认行为
- 只读安全画像：安全属性、模块封装、`--add-opens` 前后对照
- 取证与体检：`javap`、`jdeps`、`jcmd`、`jmap`、`jstack`、JFR（`jdk.jfr.Recording` 存在）
- 用 `jlink` 出最小镜像（[[05-阶段五 工程实践与生态]] 实测：`java.base` 镜像 50 MB）——**镜像越小，能被攻击的代码面越小**

**本机做不了（别硬编）**：

- **真实的第三方 gadget 链**：需要 Commons Collections 之类的第三方库，本机没有 Maven/Gradle 与任何第三方 jar（11 篇第 4 节讲怎么用自建类替代）。
- **中间件层的链**：Tomcat / Fastjson / Shiro / WebLogic / Spring 都没装。讲这些必须靠公开漏洞库与官方公告，**不能在本机验证**——所以 12 篇里凡涉及它们的结论都会标成「文档口径、本机未复现」。
- **完整可用的 JNDI 利用**：见 11 篇第 8 节的测试局限说明。

**这张清单的意义**：它是你写笔记、做汇报时的**诚实边界**。把「我实测过」和「我引用文档」分开标，是安全方向最能体现专业性的地方——因为在这个领域，**说错一句话的代价远高于承认一个未测**。

## 8. 防御视角：把每个入口翻过来

| 攻击原语 | 防御动作 | 本机怎么验证 |
|---|---|---|
| 反序列化 | **不用 Java 原生序列化**跨信任边界传输；必须用时装 JEP 290 过滤器（进程级 `jdk.serialFilter` 或流级 `setObjectInputFilter`），走**白名单**而非黑名单 | `Java/code/sec/SecDeserFilter.java`：装上后载荷抛 `InvalidClassException`（`filter status: REJECTED`），副作用触发 **0** 次；不装则触发 1 次 |
| JNDI 名字可控 | 不允许用户输入进入 `lookup`；必须解析时限制协议与主机白名单；老环境里显式关掉远程 codebase 信任 | `SecBoundary` 打印三个 `trustURLCodebase` 属性；`SecJndi` 观察 HTTP 请求数 |
| XXE | **显式**关 DTD 与外部实体：`disallow-doctype-decl`、`external-general/parameter-entities`、`ACCESS_EXTERNAL_DTD/SCHEMA` 全设上 | `SecDangerApis` 第 2 节：默认会展开实体；加固后抛 `SAXParseException` |
| `XMLDecoder` 读外部数据 | 直接别用；必须用时换成 JSON + 显式字段校验 | `SecDangerApis` 第 3 节：XML 里点名的方法被调用了 |
| 命令执行 | 不拼接字符串；用 `ProcessBuilder` 的**数组形式**并校验参数白名单；不用 `sh -c` / `cmd /c` 包裹不可信输入 | `SecDangerApis` 第 1 节：数组形式与 shell 形式的差别 |
| 路径遍历 | `base.resolve(userInput).normalize()` 后必须 `startsWith(base)` 才允许读 | `SecDangerApis` 第 5 节：拼 `..` 直接读到了目录外 |
| 运行期植入 / 无文件 | **审计启动参数**（`-javaagent`、`--add-opens`、`-Xbootclasspath`）；用 `jcmd <pid> VM.class_hierarchy`、`GC.class_histogram`、JFR 找「有加载记录但磁盘无来源」的类 | `SecClassLoader`：内存定义的类 `CodeSource` 为 **null**，普通加载的类指向 `file:/…` |
| 反编译/篡改 | 代码签名 + 校验；核心逻辑不要只靠前端混淆 | 本机未实测签名校验，标为待办 |
| 供应链 | 依赖收敛与锁版本；出 SBOM；用 `jdeps --jdk-internals` 找内部 API 依赖 | `jdeps` 本机可用；依赖扫描工具本机**没有**，属文档口径 |
| 运行时暴露面 | 用 `jlink` 裁镜像、只用需要的模块；JDK 内置的 `jcmd`/JMX 端口不要对外 | [[05-阶段五 工程实践与生态]] 的 `jlink` 实测 |
| 沙箱诉求 | **别再用 Security Manager 做隔离**（已永久禁用）——换进程/容器级隔离 | `SecBoundary`：`setSecurityManager` 抛异常 |

## 9. 回读：这套笔记里哪些节，安全视角下值得再看一遍

- [[02-阶段二 面向对象与核心类库]] 的 **`Object` 契约**与 **`equals`/`hashCode`**：反序列化的「容器重建结构」之所以能触发你的方法，前提就是这些约定被 JVM/容器主动调用。
- [[02-阶段二 面向对象与核心类库]] 的 **`record` / `sealed` / 模式匹配**：**不可变 + 穷尽检查**本身就是安全属性（少一类「可变状态被改坏」的缺陷）。
- [[03-阶段三 集合框架与泛型]] 的 **`HashMap` 冲突**一节：全冲突时从 O(1) 掉到线性/对数——**算法复杂度也会变成安全问题**（拒绝服务）。
- [[04-阶段四 并发与JVM]] 的 **类加载与诊断工具**两节：本篇第 4、7 节直接建立在这两节上。
- [[05-阶段五 工程实践与生态]] 的 **模块与打包**、**版本升级**两节：`--add-opens` 的风险、JEP 411/486 的来龙去脉都在那里。
- [[07-关键字与版本差异速查]] 的 **特性 → JEP** 对照表：安全相关的 JEP（290、403、411、486、335）都在表里，方便自查。

## 10. 继续学的路线（从这门语言到安全方向）

```
本篇 + 11 篇（机制与本地靶场）        ← 你现在在这里
   ↓ 补中间件与框架层：Servlet 容器、Spring、序列化库（需要能装依赖的环境）
   ↓ 代码审计：反编译 + javap + 数据流（从「哪里收输入」追到「哪里执行」）
   ↓ 检测与响应：JFR / jcmd 取证、RASP、内存马检测
   ↓ 云原生 Java：容器镜像的攻击面、JVM 参数与安全配置基线
```

**三个判断你是否真的入门的自测**：

1. 拿到一段陌生的 Java 代码，能说出它**可能**从哪收不可信输入（HTTP 参数、队列消息、文件、序列化流）。
2. 听到「反序列化漏洞」，能用自己的话讲清「钩子 + 容器回调」这两件事，而不是背某条链的名字。
3. 看到一条新的 Java 安全公告，能判断它属于第 5 节六个入口里的哪一个——**分类能力比记 payload 有用得多**。

## 练习

> 规矩同前几篇：只给任务与验收标准，**不给答案代码**。全部只用本机 JDK（`javac`/`java`/`jar`/`javap`/`jdeps`）完成。

| # | 任务 | 验收标准 | 建议时长 |
|---|---|---|---|
| 1 | 读一遍 `code/sec/本机实测输出.txt`，把 `SecBoundary` 两段的**每一处不同**列出来 | 能说出「哪几项变了、为什么变（`--add-opens` 改的是什么）」；能解释 `getSecurityManager` 为什么恒为 `null` | 40 分钟 |
| 2 | 用 `javap -c -p` 审计 `SecDeserBasic` 编译出来的类，找出所有会被 JVM 主动调用的方法 | 指出 `readObject`、`compareTo`、`hashCode`/`equals` 各自在字节码里的形态；能说出「为什么容器会调用它们」 | 1 小时 |
| 3 | 自己写一个类放进 `HashMap`，让它的 `hashCode` 在**反序列化时**被调用并打印次数，**构造阶段不许打印** | 输出能分开证明「构造/序列化阶段」与「反序列化阶段」各调用了多少次；数量级与日志里 1/4/16 的规律一致 | 1.5 小时 |
| 4 | 给第 3 题的类装上 JEP 290 过滤器（流级与进程级各来一次） | 两种装法都能拦住：抛 `InvalidClassException`、`filter status: REJECTED`、副作用 0 次；能说出白名单与黑名单写法的差别 | 1 小时 |
| 5 | 用 `XMLDecoder` 复现一次「XML 里的 setter 被调用」，然后写出**为什么不建议用它**的三行理由 | 复现成功（setter 被调用）；三行理由中至少一条指向「数据即代码」的本质，而不是「它不安全」这种空话 | 40 分钟 |
| 6 | 拿 `jdeps` 分析 `code/probe/` 或 `code/sec/` 的编译产物，列出它依赖的模块与包 | 能说出「这个程序能碰到 JDK 的哪些部分」；能指出至少一处「如果给它不可信输入，最可能出问题的地方」 | 40 分钟 |
| 7 | 给自己的一个自写程序做一次「安全体检」并写成三行摘要 | 三行分别是：暴露面、最可能被利用的入口、已加的加固；**必须有一条是「我没测、需要确认」** | 1 小时 |

## 自我检查清单

- [ ] 能说出 Java 安全与 C 安全的**根本差别**（数据变代码 vs 数据覆盖代码），并举出至少三个 Java 特有的入口
- [ ] 能解释 `readObject` 钩子与容器回调为什么让「反序列化 = 执行代码」，而不是笼统地说「反序列化很危险」
- [ ] 知道**本机没有**进程级反序列化过滤器（`jdk.serialFilter` 未设置），并会自己装上
- [ ] 能用一条命令查到「某个类的字节码里有没有对外伸手的调用」
- [ ] 知道 `Security Manager` 在本机已经**永久禁用**，不再指望它做沙箱
- [ ] 知道 `--add-opens` 会让本该被挡住的反射重新可用，并能从日志里指出对应证据
- [ ] 能说清 `CodeSource` 为 `null` 意味着什么，以及它和「查文件查不到内存马」的关系
- [ ] 知道 `DocumentBuilderFactory` 的**默认配置会展开外部实体**，并会写加固代码
- [ ] 能列出至少三条「本机做不了、只能引文档」的事，并说明为什么不能写成「本机实测」
- [ ] 手上没有、也不会去攒一份可直接拿去打陌生系统的载荷

## 出处

| 内容 | 来源 | 链接 |
|---|---|---|
| 安全画像与全部实测读数 | 本机靶场 `Java/code/sec/` 的原始输出 | `Java/code/sec/本机实测输出.txt`（库内文件） |
| 反序列化过滤器（JEP 290） | OpenJDK JEP 索引与 JEP 290 正文 | https://openjdk.org/jeps/290 |
| 强封装 JDK 内部（JEP 403） | OpenJDK JEP 403 | https://openjdk.org/jeps/403 |
| Security Manager 弃用与永久禁用（JEP 411 / JEP 486） | OpenJDK JEP 411、JEP 486 | https://openjdk.org/jeps/411 |
| Nashorn 弃用（JEP 335） | OpenJDK JEP 335 | https://openjdk.org/jeps/335 |
| JEP 全量索引（自查任何特性编号） | OpenJDK | https://openjdk.org/jeps/0 |
| 序列化机制与 `serialVersionUID`、`transient` | JDK 25 API 文档（`java.io`） | https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/ObjectInputStream.html |
| `XMLDecoder` 的语义与已知问题 | JDK 25 API 文档（`java.beans`） | https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/beans/XMLDecoder.html |
| JNDI 与 `Reference` / 远程 codebase | JDK 25 API 文档（`javax.naming`） | https://docs.oracle.com/en/java/javase/25/docs/api/java.naming/javax/naming/Reference.html |
| XML 解析加固（外部实体与安全处理） | JDK 25 API 文档（JAXP） | https://docs.oracle.com/en/java/javase/25/docs/api/java.xml/javax/xml/parsers/DocumentBuilderFactory.html |
| `java.lang.instrument`（agent 与重定义类） | JDK 25 API 文档 | https://docs.oracle.com/en/java/javase/25/docs/api/java.instrument/java/lang/instrument/Instrumentation.html |
| 诊断工具（`jcmd`/JFR 取证） | JDK 25 开发指南与故障排查指南 | https://docs.oracle.com/en/java/javase/25/troubleshoot/ |
| Java 侧常见缺陷类别（XXE、注入、不安全反序列化） | OWASP | https://owasp.org/www-project-top-ten/ |
| 法律条文号 | 《刑法》第 285/286 条；法释〔2011〕19 号 | 以正式公布文本为准（本笔记只引条文号） |

## 依据说明

- **有官方依据的部分**：`readObject` 钩子的调用时机、序列化机制、`serialVersionUID` 与 `transient` 的语义（`java.io` API 文档）；`ObjectInputFilter` 的三种作用范围与过滤器语法（JEP 290 与 API 文档）；`SecurityManager` 的两个阶段（JEP 411 弃用、JEP 486 永久禁用）；JDK 内部强封装（JEP 403）；Nashorn 弃用与移除（JEP 335 及后续移除版本）；`Instrumentation` / `premain` / `redefineClasses` 的契约与「agent jar 被追加到系统类路径」的行为（`java.lang.instrument` 包文档）；`XMLDecoder` 会把 XML 里的类名与方法名还原成调用（API 文档）；JAXP 的 `disallow-doctype-decl` 等特性名（JAXP 文档）；`ProcessBuilder` 与 `Runtime.exec` 的参数语义（API 文档）。法律部分只引条文号，不解释适用。
- **本机实测（2026-10-01，JDK 25.0.4.1 / Eclipse Temurin，Windows 11；原始输出在 `Java/code/sec/本机实测输出.txt`）**：`setSecurityManager` 抛 `UnsupportedOperationException`（日志 `SecBoundary`）；`jdk.internal.misc.Unsafe` 与 `String.value` 的反射可达性在 `--add-opens` 前后不同、`sun.misc.Unsafe` 两次都可拿到（`SecBoundary` 两段对照）；可用脚本引擎数 0；六个攻击面 API 均存在；`readObject` 钩子被调用 1 次、`PriorityQueue` 1/2/4 元素对应 `compareTo` 0/1/3 次、`HashMap` 1/4/16 键对应 `hashCode+equals` 1/4/16 次（`SecDeserBasic`）；JEP 290 过滤器流级与进程级都拦下、抛 `InvalidClassException` 且 `filter status: REJECTED`、副作用 0 次（`SecDeserFilter` 两段）；`defineClass` 的类 `CodeSource` 为 `null`、走委派时父加载器胜出、绕过委派后类身份不同（`SecClassLoader`）；`DocumentBuilderFactory` 默认展开外部实体、加固后抛 `SAXParseException`、`XMLDecoder` 的 setter 被调用 1 次、`nashorn` 为 `null`、拼 `..` 读到目录外（`SecDangerApis`）；JNDI 两条路径 HTTP 请求数均为 0 且 RMI/HTTP 线程为非守护线程（`SecJndi`，局限见 11 篇第 8 节）；`javaagent` 的 `premain` 在主程序前执行、进程内已加载类 904 个、`redefineClasses` 前后 `status()` 变化而磁盘文件未动（日志 `javaagent` 段）。
- **属本人编排判断的部分**：「源码/字节码/运行时三层」的切法、「数据变代码」这条统一主线、把六个入口归成一张机制表、第 8 节「攻击原语 → 防御动作 → 本机验证」的表格结构、第 9 节「回读哪几节」的挑选、以及全部练习与其时长估计。官方材料里没有「Java 安全六个入口」这种分类法，这是我的取舍。
- **不确定、未实测、请勿当结论的部分**：(1) **表达式与模板注入（SpEL/EL/OGNL）本机完全没有实测**——没有框架、没有相应依赖，全篇只在第 5 节的表格里作为类别出现，不给任何机制细节与代码；(2) **中间件与第三方库的漏洞链**（Tomcat/Fastjson/Shiro/WebLogic/Spring/Commons Collections）本机无法复现，12 篇里凡涉及它们的内容都是文档口径；(3) **JNDI 的完整利用路径未复现**：本机只量到「两条路径都没去下载远程类」，其中 RMI 路径的测试存在局限（见 11 篇第 8 节），因此**不能**把它读成「本机挡住了」；(4) 代码签名校验、RASP、内存马检测的具体工具与效果**均未实测**；(5) `jdeps` 的依赖分析与 SBOM 工具链只给了命令与思路，没有本机输出。

---

下一篇 → [[11-Java漏洞利用实践 反序列化与类加载（本地靶场）]] · [[00-Java索引]] · 攻击面全景 → [[12-Java安全视角 ATT&CK与Java漏洞族]]
