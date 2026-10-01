---
tags: [Java, 安全, 反序列化, 类加载, 靶场, 专题]
created: 2026-10-01
阶段: 安全专题（续 10 篇）
主要参考: 本机实测输出 `code/sec/本机实测输出.txt`（2026-10-01，JDK 25.0.4.1 / Eclipse Adoptium，九段日志） · 本机靶场源码 `code/sec/`（SecDeserBasic / SecDeserFilter / SecClassLoader / SecAgent / Victim / Payload / SecDangerApis / SecJndi / run.sh） · JEP 290（反序列化过滤器） · JEP 335（移除 Nashorn） · JEP 403（JDK 17 起强封装） · JEP 411（弃用 Security Manager） · JDK 25 API 文档的 `ObjectInputFilter` / `Instrumentation` / `ClassLoader` / `ObjectInputStream` · CWE-502（不可信数据反序列化）与 OWASP 反序列化备忘单 · 同套《C语言》12 篇的「先看地图」「边界与心态」分节结构
---

# 11. 漏洞利用实践：反序列化与类加载（本地靶场）

> 一句话：C 那条线的利用是**把控制流劫持走**（12 篇）；Java 这条线的利用是**让数据自己变成代码**——反序列化把「读一个对象」变成「调一串方法」，类加载把「一份字节数组」变成「一个没有磁盘来源的类」。这一篇把这两件事拆成四个能在这台机器上跑起来的靶子，全部自建、全部本地、全部可重跑。

> 本文出现的每一个数字都来自 `code/sec/本机实测输出.txt`（本机实测（2026-10-01），JDK 25.0.4.1 / Eclipse Adoptium），并在每处注明它出自哪一段；跑不通的地方一律写「为什么跑不通」，不拿网上的模板充数。

> **先读 §1 再来。**

## 1. 先立边界（动手之前先读这一节）

### 1.1 三条硬门槛

1. **只在本机自建靶场上打，只打自己写的程序。** 本文四个靶子（`SecDeserBasic`、`SecDeserFilter`、`SecClassLoader`、`SecAgent` + `Victim`）全部是自己写的，服务只绑 `127.0.0.1`，不下载、不落地、不对外——`run.sh` 的注释里也写着这一条。
2. **不写、不提供可直接用于攻击他人系统的完整载荷。** 本文只讲机制：入口在哪里、方法怎么被串起来、触发点落在哪一步。**不给完整 gadget 链、不给可用的 JNDI 利用代码、不给 webshell、不给绕过检测的技巧清单。**
3. **授权测试要有书面授权；真实漏洞走协调披露。** 授权要写明白范围（哪些 IP 或域名）、时间窗口、测试方式、联系人，口头说一声不算。

### 1.2 我国法律的相关边界（**是边界说明，不是法律意见**；要做事请查条文原文或咨询专业人士）

- **《中华人民共和国刑法》第 285 条**：第 1 款是非法侵入国家事务、国防建设、尖端科学技术领域的计算机信息系统；第 2 款是非法获取计算机信息系统数据、非法控制计算机信息系统罪；第 3 款是把侵入、非法控制计算机信息系统的程序与工具**提供**给他人的行为。
- **《中华人民共和国刑法》第 286 条**：破坏计算机信息系统罪（对系统的功能、数据、应用程序）。
- **《最高人民法院、最高人民检察院关于办理危害计算机信息系统安全刑事案件应用法律若干问题的解释》（法释〔2011〕19 号）第 1 条**给出了「情节严重」的量化标准，例如获取身份认证信息若干组以上、非法控制计算机信息系统若干台以上、违法所得或损失达到一定数额。
- 本文所有动作都落在「我自己的机器 + 我自己的程序」这一侧，与上面几条描述的形态不同：**踩线的从来不是这些靶子，而是把同一段字节流指向别人的系统**。上面只引条文号，具体的适用与解释以条文原文和专业意见为准。

### 1.3 本机这套 JDK 的安全画像（SecBoundary 第一节）

先看清自己站在哪块地基上，再动手。下表每一行都能在 `本机实测输出.txt` 的 `SecBoundary（本机 JDK 25 安全画像）` 一段里查到（本机实测（2026-10-01））。

| 项目 | 本机读数 | 说明 |
|---|---|---|
| `java.version` | `25.0.4.1` | 本文所有「本机是这样」都指这一套 |
| `System.getSecurityManager()` | `null` | 沙箱时代的遗产，见 JEP 411 |
| `System.setSecurityManager(null)` | 抛 `UnsupportedOperationException: Setting a Security Manager is not supported` | 连「显式关掉」都不再允许 |
| `jdk.serialFilter` | `(未设置 → 用 JVM 内置默认)` | JEP 290 的进程级过滤器本机没配 |
| 可用脚本引擎数 | `0` | 见 JEP 335，§8.2 展开 |
| `jdk.internal.misc.Unsafe` | 抛 `InaccessibleObjectException`（`module java.base does not "opens jdk.internal.misc"`） | 见 JEP 403 |
| 反射改 `String.value` | 抛 `InaccessibleObjectException`（`does not "opens java.lang"`） | 同上 |
| `sun.misc.Unsafe` | 反射拿到实例：`sun.misc.Unsafe@4b67cf4d` | 老后门仍在 |
| 六项攻击面 API 的存在性 | `ObjectInputStream` / `InitialContext` / `Instrumentation` / `Proxy` / `DocumentBuilderFactory` / `jdk.jfr.Recording` **全部「存在」** | **面还在，只是开关收紧了** |

再看**对照组**（SecBoundary 第二节，加了三次 `--add-opens` 再跑一遍，本机实测（2026-10-01））：

| 项目 | 默认跑 | 加 `--add-opens` 后 |
|---|---|---|
| `jdk.internal.misc.Unsafe` | 抛 `InaccessibleObjectException` | 反射拿到实例：`jdk.internal.misc.Unsafe@85ede7b` |
| 反射改 `String.value` | 抛 `InaccessibleObjectException` | `可访问（未强封装）` |
| 可用脚本引擎数 | `0` | `0`（跟开关无关） |

这组对照的意义要说清楚：**强封装是一个默认值，不是一堵砌死的墙**——任何能改启动命令行的人，都可以自己把它拆开（第二节开头那行 `WARNING: package sun.misc not in java.base` 就是拆的时候留下的痕迹）。所以「因为 JDK 把内部 API 封了，所以是安全的」这句话不成立；真正在起作用的是**默认配置**，以及「远程代码没有机会给你加一行启动参数」。

### 1.4 这一篇的态度

- 拿到一段字节流，先问三件事：**谁生成的、能不能被外人改、反序列化入口在哪**。
- 把「默认配置」当成安全边界的一部分来读：很多「现代 JDK 打不动了」的结论，本质是**默认值变了**，不是能力消失了（§8.1 的 JNDI 就是最好的例子）。
- 写不出利用链不丢人，**编一条看起来能用的链**才丢人——本篇凡是本机复现不了的，都标出来了（§8）。

## 2. 地图：Java 反序列化利用的通用三步

先跟 C 那条线对个照，免得把两套直觉混在一起：

| | C 那条线（《C语言》12 篇） | Java 这条线（本篇） |
|---|---|---|
| 利用的本质 | 劫持控制流（覆盖返回地址） | 让数据变成代码（重建对象时调方法） |
| 入口 | 一个可控的缓冲区 | 一个不可信的字节流（或一段 XML、一个名字、一份字节数组） |
| 「中间零件」是什么 | 栈上照抄的地址、程序自带的 gadget | **已加载的类的方法**（gadget） |
| 触发时机 | `ret` 那一刻跳过去 | `readObject`、容器重建结构时的回调被 JVM 调用 |
| 要不要算偏移 | 要 | **不要**——Java 是描述式的，不碰内存地址 |

通用三步（照《C语言》12 篇「利用的通用三步」的结构类比过来）：

| 步 | 要回答的问题 | 本篇在哪回答 |
|---|---|---|
| ① 找入口 | 哪段代码会读我给的字节流？ | §3 `readObject` 钩子、§3.5 XMLDecoder、§8.1 JNDI |
| ② 找可用方法（gadget） | 已有代码里哪些方法能被我串起来？ | §3 容器回调、§4 gadget 链的三个构件与三个条件 |
| ③ 触发 | JVM 到底什么时候会去调它？ | §3 的 0/1/3 与 1/4/16、§6 的 premain、§7 过滤器拦在哪一步 |

> 一句经验：**Java 的利用不比 C「更难写」，而是更难「找」。** 落地那一步的动作往往只是 `Runtime.exec` 或者一次 `lookup`，难的是怎么从「一段任意字节流」走到「有人替我调这个方法」。

还有一条必须扭过来的直觉：C 那侧是「我写一段机器码」；Java 这侧是「**我用别人已经加载好的代码**」。所以 Java 的利用面等于**已加载类的方法池**，而不是「我能不能把 payload 注入进去」。

## 3. 靶场一：反序列化为什么等于执行代码

### 3.1 `readObject` 钩子（SecDeserBasic 第一节）

读数（本机实测（2026-10-01））：

- 序列化后字节数 = **79**，接着开始反序列化；
- 反序列化阶段打印 `>> [副作用触发 #1] readObject 钩子（类=WithHook, tag=原值）`；
- 读回的对象 = `WithHook`，副作用触发次数 = **1**。

机制：`Serializable` 类里那个 `private void readObject(ObjectInputStream in)` 是一个**约定钩子**——不实现任何接口、不需要注册、不用注解，JVM 在读对象时**自动**调用它。写这个方法的人本来想的是「把字段读回来之后做点校验或修补」，但**方法体里能写任何 Java 代码**。这就是「反序列化 = 执行代码」的最小真相。

计数口径也要讲清楚：靶子里的 `Hit.armed` 只在反序列化那一小段为 `true`，所以这个 **1 次**是纯反序列化阶段的读数，不含构造与序列化阶段的调用（SecDeserBasic 第一节的注释里写明了这个设计）。

### 3.2 容器在重建结构时会主动调用元素的方法（SecDeserBasic 第二节）

**PriorityQueue**：元素之间需要比较才能重建堆。

| 元素个数 | 反序列化时 `compareTo` 被调用 |
|---|---|
| 1 个 | **0** 次 |
| 2 个 | **1** 次 |
| 4 个 | **3** 次 |

为什么 1 个元素是 0 次：只有一个元素时，堆不需要做任何比较；**n 个元素建堆需要 n − 1 次比较**，所以 2 → 1、4 → 3。这条不只是记数字，它是「调用次数由数据结构的性质决定」的第一个证据。

**HashMap**：重建哈希表要算每个键的位置，冲突还要判等。

| 键的个数 | 反序列化时 `hashCode` + `equals` 共被调用 |
|---|---|
| 1 个 | **1** 次 |
| 4 个 | **4** 次 |
| 16 个 | **16** 次 |

日志里 16 个键那一组，打出来的 16 条全是 `hashCode（HashMap 重建哈希表时调用）`——本例的键不冲突，所以 `equals` 没有额外触发（SecDeserBasic 第二节）。**这本身就是一个读数**：调用次数取决于「容器要做什么」，不取决于攻击者想做什么。

### 3.3 这条链的内核

把上面两节合起来看：

1. **入口**：类自己的 `readObject` 钩子（§3.1）。
2. **执行者**：容器类在**重建数据结构**的时候，会主动去调用元素的方法——比较、哈希、判等（§3.2）。这些方法在库代码里本来就有，是容器正常工作的一部分。
3. **攻击者做的事**：他**不写任何「执行代码」的代码**，只在一段字节流里**描述一个对象图**（哪些对象、谁引用谁）。然后 JVM 在重建这个图的过程中，自己把已加载类的方法一个个调起来。

所以 §3.2 那三组数字量化的正是：**一段不可信字节流能驱动多少次方法调用**。真实 gadget 链上的每一跳，都是这种调用；本机只是把「一跳」的最小形态摊开在桌面上。

### 3.4 靶子自己的结论（照抄 SecDeserBasic 第三节）

> 不是「反序列化会执行魔法」，而是：`readObject` 钩子 + 容器重建结构时的回调，把「你写的/框架里本来就有的危险方法」串起来执行了。入口只要一个不可信的字节流。

### 3.5 同一族的另外两个入口（本机实测可跑通）

**XMLDecoder**（SecDangerApis 第 3 节，本机实测（2026-10-01））：

- 输入 XML 里写了类名与方法名 → 日志：`>> [副作用触发 #1] Bean.setValue(来自XML的调用)`；
- 还原出的对象 = `SecDangerApis$Bean`，`value = 来自XML的调用`；**setter 被调用次数 = 1**。

它是「数据变代码」的另一个版本：XML 描述的是「构造哪个对象、调哪个 setter」，`XMLDecoder` 就照着做。加固方向：不要用 `XMLDecoder` 读外部数据；必须读时用 `ObjectInputFilter`，或者干脆换成 JSON + 显式校验（靶子注释里也这么写）。

**XXE**（SecDangerApis 第 2 节，本机实测（2026-10-01））：

- 默认 `newInstance()`：解析成功，实体内容 = `"SECRET-CONTENT-1234567890"`（实体指向的是本机一个临时文件）；
- 加固写法（打开 SAX 的 `disallow-doctype-decl` 特性）：被拒，抛 `SAXParseException`——日志原文是「设置为“真”时, 不允许使用 DOCTYPE」。

它不走 Java 序列化协议，但同样是「解析不可信输入时执行了不该执行的动作」。

> 这两个属于**相邻面**：本篇只借它们说明「入口不止一种」，不展开。

## 4. 靶场二：真 gadget 链长什么样（为什么本机复现不了完整的 Commons Collections 链）

### 4.1 一条真实链的三个构件

| 构件 | 作用 | 只讲角色的例子 |
|---|---|---|
| 入口（source） | 一个在反序列化时会被 JVM 调用的方法 | 某个第三方库里的 `readObject` / `readResolve`，或一个容器的回调 |
| 中继（gadget） | 把「一个对象」变成「一次方法调用」，并且**能被连续串起来** | 库里那种「反射式地调用任意方法」的封装类、能连环触发比较或映射的工具类 |
| 落地（sink） | 最终的那个动作 | `Runtime.exec` / `ProcessBuilder` / JNDI `lookup` / 反射写字段 |

### 4.2 构造一条链要同时满足的三个条件

1. **入口可序列化**，并且它的 `readObject`（或同类钩子）里会去调用某个**参数可控**的方法；
2. **中继方法必须接收攻击者可控的入参**——否则链在这一跳就断了，串不到下一环；
3. **链上每个类的字段类型都要能被序列化器原样写回**——否则重建对象图的时候类型对不上，对象图根本建不起来。

这三条解释了为什么「有危险方法」≠「有可用链」：危险方法满地都是，**能串起来、能传参、能被重建**的才叫 gadget。

### 4.3 为什么本机复现不了完整链（如实说明）

- 真实链依赖**第三方库**：那些「中继」和「入口」本身是诸如 Commons Collections 这类库里**正常的工具方法**——它们不是漏洞，是**被串起来之后**才成为链上的一环。
- 本机**没有装 Maven / Gradle，也没有任何第三方库**：`code/sec/` 下所有 `.java` 都是自建的，编译只用 JDK 自带的 `javac`（`run.sh` 第 16、21 行）。
- 所以这一节的做法是：**用自建类把原理摊开**（§3 的 `readObject` 钩子 + 容器回调），而**不是**拼一条能用的链。

### 4.4 「有库才有链」这件事的防守含义

- gadget 链的存在性取决于 **classpath 里有什么**。少一个依赖，可能整条链就断了——所以「缩减依赖面」在 Java 里是实打实的防守动作，不是形式主义（§9 有一行）。
- 但也别指望删依赖能防住全部：**JDK 自带的容器类（`HashMap`、`PriorityQueue`）也是 §3.2 里那个「执行者」**，它们删不掉。

### 4.5 跟 C 那侧的最后一个对照：为什么这边少见「偏移」

C 要算偏移，Java 不用——因为 Java 这边是**描述式**的：攻击者描述一个对象图，`ObjectInputStream` 按描述重建，重建过程中 JVM 自己去调方法。攻击者**不需要知道任何内存地址**，只需要知道**类名与字段名**。

## 5. 靶场三：类加载与「内存马」的原理内核

### 5.1 不绕委派：父加载器赢，字节白读了（SecClassLoader 第 1 节）

读数（本机实测（2026-10-01））：

- 读到 `Payload.class` 字节数 = **342**（从这里开始就不再需要那个文件了）；
- 委派型加载器拿到的类 = `class Payload`，加载器 = `jdk.internal.loader.ClassLoaders$AppClassLoader@105be200`；
- 直接用 `Class.forName` 拿到的类 = `class Payload`，加载器同样是 `AppClassLoader@105be200`；
- **两者是同一个类吗？`true`** ← 靶子注释写得很准：`true` 说明父加载器先命中，`findClass` 根本没被调用（双亲委派在起作用）。

机制：`loadClass` 的默认顺序是「**先问父加载器，父加载器没有再自己 `findClass`**」。`Payload` 就在 classpath 上，应用加载器先命中，`ByteLoader` 里那份 342 字节**从头到尾没被用过**——这就是双亲委派的正常表现。

### 5.2 绕过委派：字节数组变成真正独立的一个类（SecClassLoader 第 2 节）

读数（本机实测（2026-10-01））：

| 观察项 | 读数 |
|---|---|
| 调用结果（`say()` 的返回） | `我是被从内存字节里定义出来的类（磁盘上没有我的定义）` |
| 这个类的加载器 | `SecClassLoader$ByteLoader@7852e922` |
| 和上一步那个类相同吗？ | **`false`** |
| 再 `new` 一个同样的加载器定义一次，还等于上面这个吗？ | **`false`** |

机制三条：

1. `loadClass` 被改写成「**目标类名先自己 `defineClass`，不去问父加载器**」——这一步就是「绕过双亲委派」。
2. **类身份 = 类名 + 定义它的加载器**。所以同名 `Payload` 可以同时存在两份，`==` 是 `false`。
3. **每个加载器实例都是一个新命名空间**：`new ByteLoader(...)` 再定义一次，得到的又是另一个类（第二个 `false`）。

### 5.3 检测视角：磁盘上的类和内存里的类，哪里不一样（SecClassLoader 第 3 节）

| | 绕过委派定义出来的类 | 正常走委派加载的类 |
|---|---|---|
| `CodeSource` | `(null <no signer certificates>)` | `(file:/<用户目录>/AppData/Local/Temp/javasec-classes/ <no signer certificates>)` |

**`null` 就是「没有任何磁盘来源」**。正常部署的类会指向 jar 或 classes 目录；`defineClass` 出来的没有来源可查——这就是「内存马查不到文件」的**机制层面**原因：**磁盘上本来就没有它的定义**。要找它，必须从另一头查：dump 已加载的类、按加载器看命名空间、盯加载器的来源。

### 5.4 双亲委派到底「防」的是什么（SecClassLoader 第 4 节）

把靶子那段话说准确一点，别把两件事混了：

- 默认 `loadClass` 先问父加载器：**同名类以父加载器找到的为准**。所以把 class 塞进 classpath 的**顺序**改不掉结果。
- 但「委派」管的是**「谁的版本赢」**，它**不管那份字节可不可信**。磁盘上的类如果已经被替换，双亲委派并不校验它——负责完整性的是签名校验/哈希校验那一套，不是委派本身。
- 绕开委派之后，`defineClass` 出来的类与父加载器那个同名类**可以并存**，来源为 `null`、不落盘——**这才是「内存马」这个说法在机制上对应的东西**。

### 5.5 「内存马」对应的三个原语

1. **定义不落盘的类**（本节）：`CodeSource` 为 `null`，没有文件可查。
2. **运行期替换已加载的类**（§6）：连新加载器都不用，直接改已有那个类。
3. **让框架自己的加载器去加载它**：这一步依赖具体中间件怎么加载类，本机没有中间件（§8.3），**未实测**。

## 6. 靶场四：运行期植入已加载的类（javaagent）

### 6.1 `premain` 的时序（javaagent 段，本机实测（2026-10-01））

日志原文三行：

- `[agent] premain 已执行，拿到 Instrumentation（主程序还没开始）`——**premain 在主程序之前执行**，这就是「运行期植入」的立足点；
- `[agent] 进程内已加载类数量 = 904`；
- `[agent] 能改动已加载的类吗？true / 能重新定义吗？true`。

机制：`-javaagent:agent.jar` 会让 JVM 在启动早期把 jar 里的 `Premain-Class`（这里是 `SecAgent`）调起来，并交给它一个 `Instrumentation` 句柄。前提是 manifest 里写了 `Can-Redefine-Classes: true`（`run.sh` 第 26 行）。

### 6.2 `redefineClasses` 换掉已加载的实现（javaagent 段，本机实测（2026-10-01））

| 阶段 | 读数 |
|---|---|
| 替换前 | `[主程序] 替换前 status() = 原始实现：我是磁盘上编译出来的那个版本` |
| agent | `[agent] 补丁字节数 = 1871，目标类 = class Victim` |
| agent | `[agent] 该类可被重新定义吗？true` |
| agent | `[agent] redefineClasses 完成 —— 磁盘上没有任何文件被改动` |
| 替换后 | `[主程序] 替换后 status() = 补丁实现：我是被 agent 在运行期塞进去的那份代码` |

- **同一个 `Class` 对象、同一份已加载代码，实现被换掉了**；磁盘上的 `Victim.class` 从头到尾没变（可核对修改时间）。
- 约束：`redefineClasses` 要求 **schema 不变、只换实现**——所以 `patch/Victim.java` 与 `Victim.java` 结构完全一致，只有 `status()` 的返回串不同。

### 6.3 对照：不带 `-javaagent` 跑同一个类（javaagent 段「对照」，本机实测（2026-10-01））

- agent 那一侧打印 `[agent] 没有 Instrumentation，跳过`；
- 替换后 `status()` 仍然是 `原始实现：我是磁盘上编译出来的那个版本`。

这条对照说明：**能力来自那条启动参数，不来自程序本身**。少了 `-javaagent`，同一个 `Victim` 跑出来就是磁盘上的版本。

### 6.4 这是同一套 API 的两个用户

- APM 工具、热部署工具、Profiler 用的就是 `Instrumentation` 的同一组方法（`getAllLoadedClasses` / `redefineClasses` / `retransformClasses`），**攻击者用的是同一套**。
- `SecAgent` 里还留了一个 `agentmain`（运行期附加路径）：先到进程、再从外面挂 agent，和启动期的 `premain` 是同一条路的两个入口（本机演示没走这条 —— **13 篇补上了**：真跑通运行期附加，目标 JVM 不重启、启动参数里也看不出被植入）。
- **防守含义**：这是一个**合法的、设计中的能力**，不能靠「禁用 Instrumentation」来防（那会打死一整套运维工具）。能防的是「**谁有权在启动参数或附加通道里塞一个 agent**」——这就落到主机与部署侧的控制（§9）。

## 7. 防守侧的第一道闸：JEP 290 反序列化过滤器

### 7.1 同一段载荷，三种情形（SecDeserFilter 两段）

| 场景 | 读数 | 出自 |
|---|---|---|
| 不加任何过滤器 | 反序列化成功，副作用触发 **1** 次 ← 这就是被利用了 | SecDeserFilter（无进程级过滤器）第 1 节 |
| 流级过滤器（白名单） | 过滤器看到类 `SecDeserFilter$Dangerous` → 拒绝；抛 `InvalidClassException -> filter status: REJECTED`；副作用触发 **0** 次 | 同段第 2 节 |
| 进程级，但**没设** `-Djdk.serialFilter` | 本进程生效的全局过滤器 = `null` | 同段第 3 节 |
| 进程级，**设了** | 全局过滤器 = `!SecDeserFilter$Dangerous;java.base/*;!*`；结果：全局过滤器拦下 → `InvalidClassException: filter status: REJECTED`；副作用触发 **0** 次 | SecDeserFilter（带 -Djdk.serialFilter）第 3 节 |
| 进程级生效后，调用方「不加任何过滤器」 | 结果：抛 `InvalidClassException: filter status: REJECTED` | 同段第 1 节 |

关键观察：**第 1 行那个 1 与第 4、5 行那些 0，是同一段载荷两次运行的结果**——两段日志都印着 `载荷字节数 = 45`。差别只在有没有过滤器。

### 7.2 过滤器拦在哪一步

过滤器在**类被实例化之前**被问到；它能拿到的信息里最关键的是 `serialClass()`。一旦返回 `REJECTED`，`ObjectInputStream` 就抛 `InvalidClassException: filter status: REJECTED`——**那个类的 `readObject` 根本没跑到**，所以副作用次数是 0。

把这一点和 §3.1 的计数口径连起来看：**0 之所以能当「拦住了」的证据，正是因为靶子只在钩子真的被调用时才计数**。

### 7.3 两级过滤器，各管一段

| | 流级 | 进程级 |
|---|---|---|
| 怎么设 | `ObjectInputStream.setObjectInputFilter(...)` | `-Djdk.serialFilter=...`，或 `ObjectInputFilter.Config.setSerialFilter(...)` |
| 影响范围 | 只影响**这一个流** | **兜底**：任何没设流级过滤器的反序列化点都会先过它一遍 |
| 适合什么 | 我知道这段数据该是什么形状（某个调用点、某块缓存） | 全进程的统一底线 |
| 本机实测 | 危险类被拒、副作用 0 | 设上之后，连「不加过滤器」的调用方也抛了 `REJECTED` |

纪律：**宁肯漏放一个正常业务类，也不要放行一个通配**。进程级过滤器应当写成「白名单 + 末尾 `!*`」，而不是「黑名单 + 放行 `*`」。

### 7.4 语法速记（SecDeserFilter 第 4 节原文）

- 类名或包名；`!` 前缀表示**拒绝**；不带前缀表示**允许**；`*` 通配。
- 例：`-Djdk.serialFilter="!SecDeserFilter$Dangerous;java.base/*;!*"`
- 含义：先明确拒绝危险类，再放行 `java.base` 下的类，最后拒绝其余一切。

规范层面补一句（见 JEP 290 与 `ObjectInputFilter` 文档）：`jdk.serialFilter` 是**一串用分号分隔的模式，按顺序匹配、最后一个命中的生效**，所以那个末尾的 `!*` 才是「其余一律拒绝」；`maxdepth=` 一类限值也可以写在这条串里。**数组形式（多个模式组合）的语义本机未实测**，本文只用到单条 `;` 链。

### 7.5 JEP 290 解决不了的部分

- 它只拦 **Java 序列化**这一条路。XMLDecoder（§3.5）、JNDI（§8.1）、脚本引擎（§8.2）都不走它。
- 它拦的是**类**，拦不住「放行了的类里恰好有危险方法」——所以白名单要从严。
- 它不改变「Java 序列化会把类型信息写进字节流」这个根本问题。设计层面的建议仍然是：**能用 JSON 或显式格式，就不要用 Java 序列化**（见 CWE-502 与 OWASP 的备忘单）。

## 8. 本机上跑不通的那些路（如实标注）

### 8.1 JNDI：两条路径的读数，和一条必须写明的测试局限

读数（SecJndi 段，本机实测（2026-10-01））：

| 步骤 | 读数 |
|---|---|
| 0) 默认属性 | `com.sun.jndi.rmi.object.trustURLCodebase`、`...ldap...`、`...cosnaming...` 三项全部 = `(未设置 → 用 JVM 内置默认)` |
| 1) 本地 HTTP 类托管点 | 监听 `127.0.0.1:64599`，只统计请求数，**不返回任何可加载的类** |
| 2) 路径 A：`NamingManager.getObjectInstance(Reference)` | 返回 `Reference Class Name: Evil`；**HTTP 收到的请求数 = 0** |
| 3) 路径 B：JNDI `lookup` 一个 RMI 上绑定的 Reference | 地址 `rmi://127.0.0.1:2204/evil`；`lookup` 返回 `Proxy[SecJndi$RefService,RemoteObjectInvocationHandler[...]]`（类型 `jdk.proxy1.$Proxy0`）；**HTTP 收到的请求数 = 0** |
| 4) 对照：显式把 `com.sun.jndi.rmi.object.trustURLCodebase` 打开再 lookup | 返回值与上一步相同；**HTTP 收到的请求数 = 0**（开关开了仍没去取） |

结论（靶子第 5 节的原文意思）：JNDI 只是「按名字找东西」的 API；危险的是「**把远程 URL 上的类当工厂加载**」这个能力。本机这套 JDK 的 RMI provider **不支持**远程对象工厂 —— 13 篇把这后半步走完并拿到字节码证据：那条拒绝是**无条件**的（根本不读 `trustURLCodebase`），所以这条老路在本机不是「默认打不动」，而是「**这条路本来就关着**」（[[13-Java运行期植入与字节码改写（深入靶场）]] §5）。

**必须写明的测试局限（路径 B）**：路径 B 里，我们构造的那个远程对象被 JNDI 当成了**普通的 RMI 接口代理**返回——返回值类型是 `jdk.proxy1.$Proxy0`。也就是说，**流程根本没有走到「解码 Reference、去取远程工厂类」那一步**。所以路径 B 那个 `0` 只能记为「**未触发**」，**不能当作「被拦住了」**。要得到「拦住」的结论，必须让流程真的走到 Reference 解码那一步再观察请求数——**本机这一步未复现**。

另一条读数的意思：`com.sun.jndi.rmi.registry.ReferenceWrapper` 与 `RemoteReference` 在这套 JDK 里**仍能 `Class.forName` 加载到**（日志两行都是「可加载」）——它们不是「类没了」。**但要区分「能加载」与「能用」**：这个包在 **`jdk.naming.rmi`** 模块里且未导出，真想 `new` 它得加 `--add-exports jdk.naming.rmi/com.sun.jndi.rmi.registry=ALL-UNNAMED`（本篇写作时只测到「可加载」，这后半步见 13 篇 §5）。

### 8.2 脚本引擎：这条路本机已经断了

- SecBoundary：可用脚本引擎数 = **0**；
- SecDangerApis 第 4 节：`getEngineByName("nashorn") = null`（`null` 表示本 JDK 没有 JS 引擎）。

依据是 JEP 335（在本机这套 JDK 上，Nashorn 已不在）。**所以「用脚本引擎构造 payload」这一类老做法，本机没有任何复现空间。**

顺带一条纪律：也不该为了「复现」去自己装一个脚本引擎——那属于**把攻击面请回自己机器上**。

### 8.3 中间件层的链：本机没有可打的靶子

- 本机**没有 Tomcat / Fastjson / Shiro / WebLogic，也没有任何第三方库**（`code/sec/` 全部自建，只用 `javac` 编译，见 `run.sh`）。
- 因此「中间件或框架层的反序列化链」在本机**一条也复现不了**——不是被拦住，是**靶子不存在**。
- 如实标注的意义：这类链的资料网上很多，但**它们描述的是别人的环境**。本篇凡是标「本机实测」的数字，都不掺这一类内容。

### 8.4 本机能跑通、但不属于本篇主题的相邻面（留作对照）

- **命令执行原语本身**（SecDangerApis 第 1 节）：`cmd /c echo …` → 输出 `hello-from-java`，退出码 0。日志里那句提醒很关键：**Java 不做任何「命令安全」判断，参数怎么拼就怎么执行**；要防的是「把不可信输入拼进这里」，不是这个 API 本身（数组形式不经 shell，分隔符不会被当元字符）。
- **路径遍历**（SecDangerApis 第 5 节）：直接用拼出来的路径读到了 base 之外的文件（内容 `outside`）；规范化后校验 = `true`。纪律：`base.resolve(userInput).normalize().startsWith(base)` 才允许读。

这两条放这里是想说明：**入口的形态很多**（字节流、XML、名字、路径……），而「数据变代码」和「数据越界」这两类问题的根部是一样的——都是**把不可信数据当成了可信的结构**。

## 9. 每个靶子对应的防守动作

下表把「攻击原语 → 本机可验证的防御手段 → 怎么在本机验证」对上（防御手段与验证方式是按靶子读数配的映射，属本人编排）：

| 攻击原语（靶子） | 本机可验证的防御手段 | 怎么在本机验证 |
|---|---|---|
| `readObject` 钩子执行任意代码（SecDeserBasic） | 不让不可信数据走 Java 序列化；必须走则加白名单过滤器 | 跑 SecDeserFilter，看副作用次数从 1 变成 0 |
| 容器重建结构时回调元素方法（SecDeserBasic） | 同上；对「反序列化集合类」的入口格外小心 | 观察 PriorityQueue 的 0/1/3 与 HashMap 的 1/4/16，理解「放行了类 ≠ 放行了行为」 |
| XMLDecoder 数据变代码（SecDangerApis） | 不用 XMLDecoder 读外部数据；换 JSON + 显式校验；必须用则加 `ObjectInputFilter` | 把第 3 节的输入换成外部来源，观察 setter 是否仍被调用 |
| XXE（SecDangerApis） | 解析器显式 `disallow-doctype-decl`，禁用外部实体与 DTD | 关闭后再跑，应得到 `SAXParseException` |
| 命令注入（SecDangerApis） | 不拼串执行；用数组形式的 `exec`／`ProcessBuilder` 列表；对输入做白名单校验 | 把数组形式与拼串形式对照跑 |
| 路径遍历（SecDangerApis） | `base.resolve(userInput).normalize().startsWith(base)` 才允许读 | 用同一份输入，看规范化前后是否放行 |
| 不落盘的类定义（SecClassLoader） | 检测侧：查已加载类，盯 `CodeSource` 为 `null` 的命名空间；限制谁能加载字节码 | 用 SecClassLoader 打出 `CodeSource = null` 的类，练着把它从「已加载类」里找出来 |
| 运行期替换已加载的类（javaagent） | 控制启动参数与附加通道的写权限；对 agent jar 做签名与审计；「谁能加 `-javaagent`」要有台账 | 带与不带 `-javaagent` 各跑一次，对照 `status()` 的差别 |
| 远程类加载（JNDI） | 不把不可信的名字交给 lookup；控制 `trustURLCodebase` 一类开关；库侧不要拿外部数据当 Reference 处理 | SecJndi 两条路径，观察 HTTP 请求数，并**如实记「未触发」而不是「已拦住」** |

（表里每一项在本机都跑得起来；凡是本机做不到的，上一节已经标出来了。）

## 10. 继续学的路线与合法练习环境

### 10.1 顺序（照《C语言》12 篇的做法）

1. 先用**本机 JDK** 把机制跑通——本篇四个靶子就够了，不需要任何第三方依赖。
2. 再上**专用靶场**：本地容器里那些故意留了漏洞的应用。
3. 最后才去读真实世界的攻击链资料——**只读，不把任何字节流指向别人的系统**。

### 10.2 环境怎么搭

- **本机已有的**：JDK 25.0.4.1，`javac` / `java` / `jar` 三个命令就够；`code/sec/run.sh` 是一条能重跑出具全部读数的路。
- **缺中间件**：要练中间件层，用**本地容器**起一个靶场应用，网络只绑 `127.0.0.1`，不暴露公网。
- **缺第三方库**：要理解 gadget 链的构造条件，在**隔离环境**里装库、对着官方仓库的源码读实现（Oracle / OpenJDK 的官方仓库页是可查的实现入口）。日常机器上不要装来「练手」。

### 10.3 边界

- CTF、明确授权的渗透测试、自己搭的靶场——这三类之外，**不要用本文的任何字节流**。
- 每次开始前先问一句：**这台的授权范围是什么**。把这句话变成肌肉记忆，比多会一条链有用。

## 练习

**只给任务、验收标准与建议时长，不给答案代码。全部只用本机 JDK（`javac` / `java` / `jar`）就能完成。**

**A. 反序列化（SecDeserBasic）**

1. 【钩子】自己改 `SecDeserBasic`，让副作用**只在反序列化时触发一次**——把源程序里 `Hit.armed` 那套「只在反序列化阶段记账」的机制去掉，让计数一直开着，再跑一次。**验收**：你能说清「哪几次是构造/序列化阶段触发的、哪几次是反序列化阶段触发的」，并给出两阶段的读数。**建议时长**：30 分钟。
2. 【容器】把 PriorityQueue 那组从 1/2/4 扩到 8 个元素。**先预测** `compareTo` 会被调几次，再跑。**验收**：预测值与实测值一致，并且你能解释「1 个元素为什么是 0 次」。**建议时长**：30 分钟。
3. 【入口】给 `SecDeserBasic` 加一个「反序列化一个 `Object[]`」的用例，数组里放两个反序列化时会触发副作用的对象。**验收**：能说出触发次数与顺序，并解释 `ObjectInputStream` 是按什么顺序重建对象图的。**建议时长**：40 分钟。

**B. 过滤器（SecDeserFilter）**

4. 【白名单】给 `SecDeserFilter` 写一个**白名单**过滤器：自己定一个无害类，只放行它和必要的基础类，其余一律 `REJECTED`——注意现在那个「放行 `java.lang.*` / `java.util.*`」的写法属于反面教材。**验收**：无害载荷能读成功、危险类被拦（副作用 0 次），并把两次的 `filter status` 记下来。**建议时长**：1 小时。
5. 【进程级】用 `-Djdk.serialFilter` 复现「第 1 节那个不加过滤器的调用方也被拦下」的现象。**验收**：你能对比「无过滤器 / 流级 / 进程级」三种情形下同一个载荷的结果，并说清三者各拦在哪一步。**建议时长**：40 分钟。

**C. 类加载（SecClassLoader）**

6. 【不落盘】用 `SecClassLoader` 的 `ByteLoader` 加载一个**你自己写的类**（自己 `javac` 出 `.class`，读字节、绕过委派加载）。**验收**：能证明这个类不落盘——打印它的 `CodeSource` 是 `null`，并且你能在磁盘上找不到它。提示：源程序里那个 `Payload` 还在 classpath 上，你得处理这个同名冲突。**建议时长**：1 小时。
7. 【类身份】同一份字节数组，用**两个不同的加载器实例**各定义一次。**验收**：你能证明得到的是两个不同的类（`false`），并指出「类身份 = 类名 + 定义它的加载器」这句话在代码里对应哪一行。**建议时长**：40 分钟。

**D. 运行期替换（SecAgent / Victim）**

8. 【换实现】把 `patch/Victim.java` 的补丁版本换成**你自己写的实现**（比如让 `status()` 返回一句话，再拼上一个调用次数），照样编译到另一个目录、照样用 agent 换上去。**验收**：带与不带 `-javaagent` 两次运行的输出你能对照讲清；并且你注意到「磁盘文件没变」这一点是可以核对的（看修改时间）。**建议时长**：1 小时。
9. 【约束】故意给补丁版本的 `Victim` 加一个新方法（改变 schema），再试 `redefineClasses`。**验收**：观察它会不会失败、失败信息是什么，并解释「为什么 `redefineClasses` 要求 schema 不变」。**建议时长**：40 分钟。

**E. 检测（选做，本机 JDK 就能做）**

10. 【找出内存马】写一个程序，用 `Instrumentation.getAllLoadedClasses()` 列出进程里所有已加载的类，从中**找出那个由 `ByteLoader` 定义的 `Payload`**。**验收**：能把它与 classpath 上那个同名类区分开，并说出「你是靠哪一条信息区分的」。**建议时长**：1 小时。

## 自我检查清单

- [ ] 我能说清「Java 反序列化利用的三步」，并指出本篇哪个靶子对应哪一步
- [ ] 我知道 `readObject` 是**不用注册**的约定钩子，只要类实现了 `Serializable`
- [ ] 我知道 PriorityQueue 在 1/2/4 个元素时 `compareTo` 调用次数是 0/1/3，并能解释「1 个为什么是 0」
- [ ] 我知道 HashMap 在 1/4/16 个键时 `hashCode` + `equals` 共被调用 1/4/16 次
- [ ] 我能说出 gadget 链的三个构件（入口 / 中继 / 落地）和三个构造条件
- [ ] 我知道本机为什么复现不了完整的 Commons Collections 链（没有第三方库），并且**不会**去编一条
- [ ] 我知道「类身份 = 类名 + 定义它的加载器」，并能用两个加载器实例验证
- [ ] 我知道默认的双亲委派会让**父加载器赢**，`findClass` 根本不执行
- [ ] 我知道内存里 `defineClass` 出来的类 `CodeSource` 是 `null`，这是它「查不到文件」的原因
- [ ] 我知道 `premain` 在主程序之前执行，且它拿到的 `Instrumentation` 能 `redefineClasses`
- [ ] 我知道 `redefineClasses` 要求 schema 不变、只换实现
- [ ] 我知道不带 `-javaagent` 时那个能力就不存在（对照跑过）
- [ ] 我能说清流级过滤器与进程级过滤器分别拦在哪一步、各自适合什么场景
- [ ] 我知道 JEP 290 只拦 Java 序列化，拦不住 XMLDecoder / JNDI / 脚本引擎
- [ ] 我能解释为什么 SecJndi 路径 B 的那个 0 只能算「未触发」、不能算「被拦住」
- [ ] 我知道本机脚本引擎数是 0 的原因（JEP 335）
- [ ] 我知道本机没有中间件，所以中间件层的链一条也没复现
- [ ] 我清楚这些技术的合法边界，并且**只在自己的靶子上做**

## 出处

| 类别 | 来源 | 位置 / 覆盖范围 |
|---|---|---|
| 本机实测（全部数字） | `code/sec/本机实测输出.txt`（2026-10-01，JDK 25.0.4.1 / Eclipse Adoptium） | 九段：SecBoundary ×2、SecDeserBasic、SecDeserFilter ×2、SecClassLoader、SecDangerApis、SecJndi、javaagent |
| 本机靶场源码 | `code/sec/`：SecDeserBasic.java、SecDeserFilter.java、SecClassLoader.java、SecAgent.java、Victim.java、patch/Victim.java、Payload.java、SecDangerApis.java、SecJndi.java、run.sh | 可重跑：`bash run.sh` |
| 规范（JEP） | [JEP 290: Filter Incoming Serialization Data](https://openjdk.org/jeps/290) | 反序列化过滤器、状态与模式语法 |
| 规范（JEP） | [JEP 335: Deprecate the Nashorn JavaScript Engine](https://openjdk.org/jeps/335) | 可用脚本引擎数 = 0 |
| 规范（JEP） | [JEP 403: Strongly Encapsulate JDK Internals](https://openjdk.org/jeps/403) | `InaccessibleObjectException` |
| 规范（JEP） | [JEP 411: Deprecate the Security Manager for Removal](https://openjdk.org/jeps/411) | `setSecurityManager` 抛异常 |
| API 文档 | [ObjectInputFilter（JDK 25 API）](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/ObjectInputFilter.html) | 过滤器状态、语法、`Config` |
| API 文档 | [Instrumentation（JDK 25 API）](https://docs.oracle.com/en/java/javase/25/docs/api/java.instrument/java/lang/instrument/Instrumentation.html) | `redefineClasses` / `getAllLoadedClasses` |
| API 文档 | [ClassLoader（JDK 25 API）](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ClassLoader.html) | `loadClass` / `findClass` / `defineClass` |
| 缺陷分类 | [CWE-502: Deserialization of Untrusted Data](https://cwe.mitre.org/data/definitions/502.html) | 反序列化这一类问题的编号 |
| 防守指南 | [OWASP Cheat Sheet Series](https://owasp.org/www-project-cheat-sheets/) | 反序列化的替代方案与约束 |
| 类型参考（外部） | [MITRE ATT&CK](https://attack.mitre.org/) | 同类技术在真实攻击里的位置（只作分类参照） |

## 依据说明

**一、有官方依据的（给 JEP 编号或规范条款）**

- **JEP 290**——反序列化过滤器：流级 `ObjectInputFilter`、进程级 `jdk.serialFilter`、被拒时抛 `InvalidClassException: filter status: REJECTED`、模式里的 `!`（拒绝）、`*`（通配）与分号串联语义。
- **JEP 411**——Security Manager 的弃用，对应 SecBoundary 里的 `UnsupportedOperationException: Setting a Security Manager is not supported`。
- **JEP 403**——强封装，对应 `InaccessibleObjectException`；对照组用 `--add-opens` 的依据也在这里。
- **JEP 335**——Nashorn 移除，对应「可用脚本引擎数 = 0」与 `getEngineByName("nashorn") = null`。
- **API 契约**：`Serializable` 与 `readObject` 约定钩子（`ObjectInputStream` API 文档）；`ClassLoader` 的 `loadClass` / `findClass` / `defineClass` 委派与命名空间规则（`ClassLoader` API 文档）；类身份含「定义它的加载器」一点，见 JVM 规范里关于运行时类与类加载器的规定；`Instrumentation.redefineClasses` 的「schema 不变、只换实现」约束（`Instrumentation` 与 `ClassDefinition` API 文档）；容器类在反序列化时重建结构（`HashMap`、`PriorityQueue` 的 `readObject`，见 `java.util` API 文档与 JDK 源码）。

**二、本机实测的（逐条列出读数与对应日志节名；本机实测（2026-10-01），JDK 25.0.4.1 / Eclipse Adoptium）**

- **SecBoundary 第一节**：`java.version = 25.0.4.1`；`getSecurityManager() = null`；`setSecurityManager(null)` 抛 `UnsupportedOperationException`；三项 `trustURLCodebase` 未设置；`jdk.serialFilter` 未设置；可用脚本引擎数 = 0；`jdk.internal.misc.Unsafe` 与反射改 `String.value` 抛 `InaccessibleObjectException`；`sun.misc.Unsafe` 反射拿到实例 `sun.misc.Unsafe@4b67cf4d`；六项攻击面 API 全部「存在」。
- **SecBoundary 第二节（`--add-opens` 对照）**：`jdk.internal.misc.Unsafe = 反射拿到实例：jdk.internal.misc.Unsafe@85ede7b`；反射改 `String.value = 可访问（未强封装）`；脚本引擎数仍为 0。
- **SecDeserBasic 第一节**：序列化后字节数 = **79**；`readObject` 钩子触发 1 次；读回对象 = `WithHook`。
- **SecDeserBasic 第二节**：PriorityQueue 元素 1/2/4 个 → `compareTo` 被调用 **0/1/3** 次；HashMap 键 1/4/16 个 → `hashCode + equals` 共被调用 **1/4/16** 次。
- **SecDeserFilter（无进程级过滤器）第一至三节**：载荷字节数 = 45；无过滤器时副作用 **1** 次；流级过滤器 → `InvalidClassException` / `filter status: REJECTED` / 副作用 **0** 次；本进程全局过滤器 = `null`。
- **SecDeserFilter（带 `-Djdk.serialFilter`）第一、三节**：全局过滤器 = `!SecDeserFilter$Dangerous;java.base/*;!*`；第 1 节「不加任何过滤器」抛 `InvalidClassException: filter status: REJECTED`；第 3 节副作用 **0** 次。
- **SecClassLoader 第一至三节**：`Payload.class` 字节数 = **342**；委派型加载器与 `Class.forName` 拿到同一个类（`true`），两者加载器均为 `AppClassLoader@105be200`；绕过委派后加载器 = `SecClassLoader$ByteLoader@7852e922`，与前者相同吗 = `false`，再 `new` 一个加载器定义一次 = `false`；`CodeSource` 分别为 `null` 与 `file:/<用户目录>/AppData/Local/Temp/javasec-classes/`。
- **SecDangerApis 第一至五节**：`cmd /c echo …` → 输出 `hello-from-java`、退出码 0；XXE 默认解析出 `"SECRET-CONTENT-1234567890"`，加固写法抛 `SAXParseException`；XMLDecoder 的 `setValue` 触发 1 次；`getEngineByName("nashorn") = null`；路径遍历读到 `outside`、规范化后校验 = `true`。
- **SecJndi 第 0 至 4 节**：三项默认属性未设置；HTTP 监听 `127.0.0.1:64599`；路径 A 返回 `Reference Class Name: Evil`、HTTP 请求数 **0**；路径 B 地址 `rmi://127.0.0.1:2204/evil`、返回 `Proxy[...]`（类型 `jdk.proxy1.$Proxy0`）、HTTP 请求数 **0**；`ReferenceWrapper` / `RemoteReference` 均可加载；显式打开信任开关后请求数仍 **0**。
- **javaagent 段**：`premain` 先于主程序执行；进程内已加载类数量 = **904**；日志原句「能改动已加载的类吗？true / 能重新定义吗？true」（对应 `isRetransformClassesSupported()` 与 `isRedefineClassesSupported()`）与「该类可被重新定义吗？true」（对应 `isModifiableClass()`——**这三个 API 名是本人加的注解，日志里印的是中文问句与布尔值**）；补丁字节数 = **1871**；替换前后 `status()` 两个返回串；对照段打印 `[agent] 没有 Instrumentation，跳过` 且替换后 `status()` 未变。

**三、属本人编排判断的（不是靶子或规范给的）**

- 分节顺序（先立边界 → 地图三步 → 四个靶场 → 防守过滤器 → 跑不通的路 → 防守动作表 → 路线 → 练习），以及「靶场一~四」这套命名。
- 把 `SecDangerApis` 的 XMLDecoder 与 XXE 放进 §3.5 当作「同族的另外两个入口」，把命令注入与路径遍历放进 §8.4 当对照——这是**编排**，不是靶子自身的分类。
- 「三步」这个说法是从《C语言》12 篇「利用的通用三步」类比过来的；「入口 / 中继 / 落地」这套术语用词为本人所选。
- §9 那张「防守动作」表：每条防御手段与验证方式，都是本人按靶子读数配上去的映射。
- 练习的题目、验收标准与建议时长。

**四、不确定或未实测的（明确标注）**

- **JNDI 路径 B**：**未复现**「走到 Reference 解码那一步」的流程；那个 0 是「未触发」，不是「被拦住」（详见 §8.1）。
- **进程级过滤器的数组形式**（多个模式组合）与逗号/分号的组合语义：**未实测**，本篇只用到单条 `;` 链。
- **真实 gadget 链**（Commons Collections 一类）：**本机未复现、也未尝试**——没有第三方库，也不写可用链。
- **中间件/框架层反序列化链**（Tomcat / Fastjson / Shiro / WebLogic 等）：**本机无靶子，未实测**。
- **内存马在真实框架里「让框架的类加载器加载它」那一步**：**未实测**（本机没有框架）。
- **`--add-opens` 对照组开头那行 `WARNING: package sun.misc not in java.base`**：这是 JVM 输出的告警文本，本文只按原样引用，未逐字考证其来源。
- **各 JEP 的适用范围与后续演进**：以 openjdk.org 上的 JEP 原文与本机 JDK 的实际读数为准，本文不替代规范原文。

---

上一阶段 → [[10-Java安全研究视角 从会写到看懂字节码与攻击面]] · [[00-Java索引]] · 下一篇 → [[12-Java安全视角 ATT&CK与Java漏洞族]]
