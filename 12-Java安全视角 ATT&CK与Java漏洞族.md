---
tags: [Java, 安全, ATT&CK, CVE, CWE, 供应链, 防守, 专题]
created: 2026-10-01
阶段: 安全专题（续 10、11 篇）
主要参考: MITRE ATT&CK（attack.mitre.org，**内容版本 v19.2**，2026-10-01 逐条核对战术/技术/缓解编号）· NVD（nvd.nist.gov 条目与其官方 REST API `services.nvd.nist.gov`，2026-10-01 逐条核对描述/CWE/CVSS）· CWE（cwe.mitre.org，**CWE 版本 4.20**，2026-10-01 核对定义页与 CWE Top 25 (2024) 榜）· OpenJDK JEP 索引与各 JEP（openjdk.org/jeps）· JDK 25 API 文档与 Java SE 25 开发指南（docs.oracle.com）· OWASP Top 10:2021 / Top 10:2025 / Cheat Sheet Series / Dependency-Check（owasp.org）· 本机实测：`code/sec/本机实测输出.txt`（JDK 25.0.4.1 / Eclipse Temurin，Windows 11，2026-10-01）
---

# 12. 网络安全视角：ATT&CK 与 Java 漏洞族

> 一句话：10、11 篇讲的是「机制怎么被利用」，这一篇换到**防守方**的位置——把 Java 的攻击面画成一张地图（**语言构造 → 缺陷类别 → CWE → ATT&CK 技术**），把反序列化、JNDI 与动态类加载、注入、XXE、SSRF、路径遍历、供应链这些家族逐个拆开，再用**核对过官方条目**的六个真实漏洞收口。
> **本文纪律**：只写机制、检测与防守，**不写任何可利用的载荷、不写利用步骤、不写绕过检测的技巧**。
> **核对口径（2026-10-01）**：ATT&CK 内容版本 **v19.2**；CWE 版本 **4.20**；CVE 事实逐条取自 NVD（条目页与官方 REST API）。凡核对不到的一律写明「未能核对」。法律边界见 §10——**那是边界说明，不是法律意见**。

## 1. 为什么需要框架：一个漏洞 ≠ 一次入侵

新手的两种错：**「找到一个漏洞 = 攻破了」**、**「没有漏洞 = 安全」**。Java 里这两种错得尤其隐蔽，因为它把「一个缺陷」和「一次入侵」之间的距离拉得更清楚了：

- **Java 没有内存破坏**（没有缓冲区溢出、UAF、未定义行为——这正是 01 篇「Java 把行为写进规范」的另一面）。攻击者拿不到「改写返回地址」这种一步到位的原语，**必须让程序自己调用已有的危险方法**。
- **所以 Java 的攻击链天生是「两段式」**：一段**入口**（能喂进不可信数据的地方）+ 一段 **gadget**（程序里本来就存在、能产生副作用的类与方法）。单看都不算漏洞，**串起来才是**。
- **反过来，防守也因此有多个抓手**：入口能拦（过滤器、白名单）、gadget 能禁（不让不可信数据参与选类）、副作用能监测（JFR 里那几类事件）。**这正是「需要框架」的理由——你得有一套统一语言，才说得清自己拦住了哪一段、漏了哪一段。**

于是需要三种「索引」，回答三个不同的问题：

| 框架 | 回答什么问题 | 颗粒度 | 本文用法 |
|---|---|---|---|
| **CWE** | 这段代码**错在哪**（缺陷类型） | 代码/设计层 | §3 索引表的「学名」列、§4 每族标题 |
| **CVE** | **这一次**具体的漏洞（某组件的某版本） | 单个实例 | §7 六个案例，每个都有官方条目 |
| **ATT&CK** | 攻击者**做了什么**（行为/技术），怎么防、怎么测 | 行为层 | §3 的映射列、§8 的防守矩阵 |

一个完整的例子（§7.1 会拆）：

```text
CWE-502（Deserialization of Untrusted Data）        ← 缺陷类型
   ↓ 具体化
CVE-2021-44228（Apache Log4j2 的 JNDI 特性）         ← 具体漏洞（NVD 核对过）
   ↓ 被真实攻击者用上
ATT&CK: T1190（Exploit Public-Facing Application）  ← 攻击者行为
```

**本文定位**：把 10、11 篇的攻击机制挂到 ATT&CK 的行为树上（§2、§3），按 CWE 把 Java 漏洞族排一遍（§4），穿插本机 JDK 上的可复现实测（§5），最后讲防守（§8）、真实案例（§7）与法律边界（§10）。

## 2. MITRE ATT&CK 速览（防守方视角的战术与技术）

### 2.1 它是什么

ATT&CK（Adversarial Tactics, Techniques, and Common Knowledge）是 MITRE 维护的**攻击者行为知识库**，内容来自公开的真实攻击报告。用途主要是**防守**：让防御方有一套统一语言描述「我防住了什么、漏了什么」。

| 层 | 含义 | 例子 |
|---|---|---|
| **战术 (Tactic)** | 攻击者的**目标 / 为什么** | 初始访问、持久化、隐身 |
| **技术 (Technique)** | 达成目标的**手段 / 怎么做** | 利用面向公众的应用（T1190） |
| **子技术 (Sub-technique)** | 技术的细分 | Web Shell（T1505.003） |
| **程序 (Procedure)** | **具体谁**怎么用的 | 某组织用过某个 CVE |

编号：技术 `Txxxx`，子技术 `Txxxx.yyy`，组织 `Gxxxx`，软件 `Sxxxx`，缓解 `Mxxxx`。三个域：**Enterprise**（本文用）、Mobile、ICS。

> **核对声明**：本节编号与名称**于 2026-10-01 逐条核对 attack.mitre.org**；站点显示的 ATT&CK 内容版本为 **v19.2**。**ATT&CK 是活文档**，引用时请写清核对日期与版本。

### 2.2 企业战术全景（v19.2，共 **15** 个）

| ID | 战术 | 一句话 |
|---|---|---|
| TA0043 | Reconnaissance | 侦察：收集可用于策划的信息 |
| TA0042 | Resource Development | 资源准备：建立可用的基础设施 |
| TA0001 | **Initial Access** | 初始访问：进你的网——**Java 漏洞的主战场** |
| TA0002 | Execution | 执行：跑起恶意代码 |
| TA0003 | **Persistence** | 持久化（内存马、Web Shell 在这里） |
| TA0004 | Privilege Escalation | 提权 |
| TA0005 | **Stealth** | 隐身：藏着，看起来像正常行为 |
| TA0112 | Defense Impairment | 破坏防御：让防守方看不到、不敢信 |
| TA0006 | **Credential Access** | 凭据访问（配置文件里的密钥在这里） |
| TA0007 | Discovery | 发现：摸清环境 |
| TA0008 | Lateral Movement | 横向移动 |
| TA0009 | Collection | 收集：找感兴趣的数据 |
| TA0011 | Command and Control | 命令与控制 |
| TA0010 | Exfiltration | 外传数据 |
| TA0040 | Impact | 影响（拒绝服务在这里） |

> **一个值得记住的版本变化**：v19 起，原来的「Defense Evasion」被拆成 **Stealth（TA0005）** 与新增的 **Defense Impairment（TA0112）**。所以现在查到的 T1211 叫 **Exploitation for Stealth**（挂 TA0005）。**凡是「背下来的编号」，都有过期的风险。**

### 2.3 本文会用到的技术（全部核对过：编号 / 名称 / 所属战术）

| 技术 | 名称（核对所得） | 所属战术 | 为什么 Java 要关心 |
|---|---|---|---|
| **T1190** | Exploit Public-Facing Application | Initial Access | 反序列化、注入、SSRF 都从这里「进网」 |
| **T1195** | Supply Chain Compromise | Initial Access | 父技术，下面两条子技术更常用 |
| **T1195.001** | Supply Chain Compromise: Compromise Software Dependencies and Development Tools | Initial Access | 依赖与构建工具；**官方检测栏明确把 maven/gradle 列为包管理器** |
| **T1195.002** | Supply Chain Compromise: Compromise Software Supply Chain | Initial Access | 构件仓库与世界传递的那份产物被替换 |
| **T1505.003** | Server Software Component: Web Shell | Persistence | 内存马（运行期改类/defineClass）落在这里 |
| **T1055** | Process Injection | Privilege Escalation / Stealth | **对照项**：Java 侧的「注入」长在 JVM 内部，与原生注入不是一回事 |
| **T1059** | Command and Scripting Interpreter | Initial Access / Execution | 命令注入落到这一条 |
| **T1078** | Valid Accounts | Initial Access / Persistence / Privilege Escalation / Stealth | 硬编码账号/密钥能直接「合法登录」 |
| **T1027** | Obfuscated Files or Information | Stealth | 混淆、编码、加壳载荷 |
| **T1211** | Exploitation for Stealth | Stealth | 目标是**不被发现**（绕过日志/监控） |
| **T1574.001** | Hijack Execution Flow: DLL | Execution / Stealth | **对照项**：Java 的类加载劫持与原生 DLL 劫持不同（§4.12） |
| **T1552.001** | Unsecured Credentials: Credentials In Files | Credential Access | 配置文件、`.properties` 里的明文凭据 |
| **T1499** | Endpoint Denial of Service | Impact | XXE「十亿笑声」、反序列化炸弹 |
| **T1005** | Data from Local System | Collection | 读本机文件（XXE 读 `file://`、路径遍历） |
| **T1567.002** | Exfiltration Over Web Service: Exfiltration to Cloud Storage | Exfiltration | 数据被传到外部存储 |
| **T1070** | Indicator Removal | Stealth | 清日志、删文件（配套手法） |

### 2.4 边界：ATT&CK 里**找不到**「Java 反序列化」

1. **ATT&CK 不是漏洞清单**。官方定义 Tactics 是「**为什么**」、Techniques 是「**怎么做**」，记录的是**已观测到的真实对手行为**。
2. 因此**反序列化、XXE、SQL 注入这些「代码缺陷」本身在 ATT&CK 里没有条目**——承载它们的是 **CWE**（CWE-502、CWE-611、CWE-89……）。ATT&CK 承载的是**这些缺陷被利用后攻击者做了什么**（§2.3 那些）。
3. **官方自己就承认**：**T1190 页面正文原话**——"For websites and databases, the OWASP top 10 and CWE top 25 highlight the most common web-based vulnerabilities."（**2026-10-01 核对原文**）。官方就是让你去用 OWASP 与 CWE 补「漏洞」这一层。
4. 第 2 条的表述方式属**分析判断**（依据是官方定义与 T1190 那句指引）——**官方没有逐字说过「ATT&CK 不覆盖编码缺陷」**。

### 2.5 官方资源与本文用到的缓解编号

ATT&CK Navigator（把技术做成矩阵**着色**，做覆盖度评估最直观）与 STIX 数据（GitHub `mitre-attack/attack-stix-data`）都在 `attack.mitre.org` 生态里；**每条技术页的 Mitigations 与 Detection Strategy 两栏是防守方最该读的部分**。

本文用到的缓解（**逐个核对**）：**M1051** Update Software、**M1050** Exploit Protection、**M1048** Application Isolation and Sandboxing、**M1042** Disable or Remove Feature or Program、**M1026** Privileged Account Management、**M1038** Execution Prevention、**M1054** Software Configuration、**M1017** User Training。（**M1013** Application Developer Guidance 在核对时属 **Mobile** 域条目，故未作企业侧引用。）

## 3. Java 的攻击面地图（本篇索引表）

**左边是 Java 的语言构造或平台特性，右边是它在真实攻击里长什么样，最后一列告诉你本库哪一篇讲过它。**

| Java 的构造 / 特性 | 典型缺陷类别 | CWE | ATT&CK 技术 | 本库对应篇目 |
|---|---|---|---|---|
| **`ObjectInputStream.readObject`**、`Serializable`、`readObject`/`readResolve` 钩子 | 不可信数据反序列化（可执行代码） | **CWE-502** | **T1190**（进网）→ T1059/T1005（落地后的动作） | §4.1、§5.1；11 篇（本地靶场） |
| **JNDI**（`InitialContext.lookup`）、**动态类加载**、远程 codebase（`Reference`/`ObjectFactory`） | 用外部可控输入选择要加载的类/代码（不安全反射） | **CWE-470**（另可归 CWE-502） | **T1190**、T1574.001（对照） | §4.2；11 篇 |
| **表达式/模板注入**：SpEL、EL、OGNL、各类模板引擎 | Expression Language Injection / Template Engine 注入 | **CWE-917**、**CWE-1336** | **T1190** | §4.3（只讲类别与机制） |
| **XML 解析**：`DocumentBuilderFactory`、SAX、StAX | 外部实体引用未限制（XXE） | **CWE-611** | **T1005**、T1499 | §4.5 |
| **`XMLDecoder`**、各种「XML/文本 → 对象」还原器 | 数据被当代码（第二条反序列化入口） | **CWE-502** / CWE-470 | T1190 | §4.5 |
| **`URL` / `URLConnection` / `HttpClient`** 拿用户给的 URL 去请求 | 服务端请求伪造（SSRF） | **CWE-918** | **T1190**、T1005 | §4.6 |
| **`Files` / `Paths` / `File`** 拼接用户给的路径 | 路径遍历 | **CWE-22** | T1005 | §4.7 |
| **`Runtime.exec` / `ProcessBuilder`** 拼接用户输入 | 命令注入 | **CWE-78** | **T1059** | §4.4 |
| **`Statement` 拼字符串** 而不是 `PreparedStatement` | SQL 注入 | **CWE-89** | T1190、T1005 | §4.8；**05 篇 §7.2** |
| **Maven/Gradle 依赖**、传递依赖、构件仓库、SBOM 缺失 | 依赖脆弱第三方组件 / 使用无人维护的组件 | **CWE-1395**、**CWE-1104** | **T1195**、T1195.001/002 | §6；**05 篇 §4** |
| **配置与凭据**：`.properties`、日志输出 | 硬编码凭据 / 敏感信息进日志 | **CWE-798**、**CWE-532** | **T1078**、**T1552.001** | §4.10；05 篇 §6 |
| **模块封装与安全机制**：Security Manager（**JEP 411 → JEP 486**）、强封装（**JEP 403**）、`--add-opens` | 权限管理不当 / 依赖内部 API | **CWE-269** | T1211、T1078（对照） | §4.11、§5.2；**05 篇 §9.3** |
| **`javaagent` / `Instrumentation.redefineClasses` / 自定义 `ClassLoader`** | 运行期替换已加载代码（内存马） | CWE-470 / CWE-269 | **T1505.003**、T1211 | §4.12、§5.2；11 篇 |

> **读法**：从左往右，看清「我写的这个小构造能连到多大的事」；从右往左，看清「防守某个战术时，代码层面能在哪一关拦下它」。**中间两列是可查的锚点**——CWE 去 `cwe.mitre.org`、ATT&CK 去 `attack.mitre.org` 核对。

## 4. Java 漏洞族逐个拆（机制 → 本机能不能复现 → 怎么防）

每族按同一套骨架写：**机制 → 历史上为什么会发生 → 现代 JDK 的变化（给 JEP 编号）→ 本机能不能复现 → 防守动作**。

> **声明**：本节所有「本机实测」都能在 `code/sec/本机实测输出.txt` 的对应**节名**里查到。所有靶子都是自建程序，**不下载、不落地、不对外**。

### 4.1 反序列化族（CWE-502 Deserialization of Untrusted Data）

**机制**。Java 序列化格式里，字节流不只带「数据」，还带**类名**与**结构信息**。`ObjectInputStream.readObject()` 在重建对象时会做两件你没显式要求它做的事：① 调用目标类自己的**反序列化钩子**（`readObject`/`readResolve`/`readExternal`）；② 容器类在**重建内部结构**时主动调用元素的方法——`PriorityQueue` 重建堆要 `compareTo`，`HashMap` 重建哈希表要 `hashCode`/`equals`。**这两件事合起来就是真实 gadget 链的内核**。

**本机实测（节名：`SecDeserBasic（反序列化会执行代码：最小复现）`）**：

| 观测量 | 实测值 |
|---|---|
| 只用 `readObject` 钩子 | 序列化后字节数 **79**；反序列化时钩子被调用，**副作用触发 1 次** |
| `PriorityQueue` 元素 1 / 2 / 4 个 | 反序列化时 `compareTo` 被调用 **0 / 1 / 3** 次 |
| `HashMap` 键 1 / 4 / 16 个 | 反序列化时 `hashCode`+`equals` 共被调用 **1 / 4 / 16** 次 |

**三个读数的教学价值**：① 「1 个元素的 `PriorityQueue` 调用 0 次」说明**能不能触发取决于数据形状**；② 计数随元素数线性增长，说明这是**结构重建的必然产物**，不是某个类的 bug；③ 日志第三节自己给了结论——「不是『反序列化会执行魔法』，而是 readObject 钩子 + 容器重建结构时的回调，把危险方法串起来执行了」。

**本机实测：防得住吗？防得住**（节名：`SecDeserFilter（无进程级过滤器）` / `SecDeserFilter（带 -Djdk.serialFilter 的进程级过滤器）`）：

| 配置 | 结果（实测原文摘要） |
|---|---|
| 不加任何过滤器 | 反序列化成功，**副作用触发 1 次**（载荷字节数 45） |
| 流级过滤器（白名单思路） | 看到类 `SecDeserFilter$Dangerous` → **拒绝**；抛 `InvalidClassException -> filter status: REJECTED`；副作用 **0** |
| 进程级过滤器 `-Djdk.serialFilter=!SecDeserFilter$Dangerous;java.base/*;!*` | **全局过滤器拦下 → `InvalidClassException: filter status: REJECTED`；副作用 0 次** |

**过滤器语法（日志原文口径）**：类名或包名，`!` 表示拒绝、不带前缀表示允许、`*` 通配；上例含义是「先明确拒绝危险类，再放行 `java.base` 下的类，最后拒绝其余一切」。

**历史上为什么会发生**。序列化是 JDK 1.1 就有的机制，当年假设的对手是「同一台机器上互不信任的代码」，**没假设过「字节流来自网络」**。等大型应用普遍把序列化用在网络传输、缓存、会话、消息队列上时，这个假设已经改不回来了。

**现代 JDK 的变化（JEP 编号）**：

| JEP | 内容 | 进的是哪个发行版 |
|---|---|---|
| **JEP 290** | Filter Incoming Serialization Data（序列化过滤器：`ObjectInputFilter` 与 `jdk.serialFilter` 这套机制） | **JDK 9** |
| **JEP 415** | Context-Specific Deserialization Filters（按上下文装过滤器） | **JDK 17** |

**防守动作**：① 能不用 Java 序列化就**不用**（换 JSON + 显式字段校验）；② 必须用时**进程级兜底**：`-Djdk.serialFilter=` 给一条白名单式规则（注意结尾那个 `!*`），或按 JEP 415 在入口装上下文过滤器；③ 入口处**不把不可信数据当类名**（这条同时防 §4.2）；④ 留证据（§5.2、§8）。

### 4.2 JNDI 与动态类加载族（CWE-470 Use of Externally-Controlled Input to Select Classes or Code）

**机制**。JNDI 做的是「**按名字找东西**」：`InitialContext.lookup(name)` 把名字交给 provider（RMI、LDAP、DNS、CORBA……），拿回来的可能是对象，也可能是**一张指向某处的「配方」**（`Reference`）。`NamingManager.getObjectInstance(Reference, ...)` 的设计意图是「从指定位置取回一个工厂并让它造对象」，而「指定位置」可以是**远程 URL（codebase）**。危险的不是 JNDI，而是**「把远程 URL 上的类当工厂加载」这个能力**与**「名字的来源不可信」**同时成立。

**本机实测（节名：`SecJndi（JNDI 注入：默认会不会去下载远程类）`）——必须如实写，包括局限**：

| 路径 | 实测结果 | 老实说，它证明了什么 |
|---|---|---|
| 默认属性 | `com.sun.jndi.rmi.object.trustURLCodebase` / `ldap.*` / `cosnaming.*` 三个都显示「未设置 → 用 JVM 内置默认」 | 只说明**没人为改过**，没直接读到内置默认值 |
| 路径 A：`NamingManager.getObjectInstance(Reference)` | 返回 `Reference Class Name: Evil`；**HTTP 请求数 = 0** | **没去下载远程工厂类** |
| 路径 B：JNDI `lookup` 一个绑在 RMI 上的 `Reference` | 返回 `Proxy[SecJndi$RefService, RemoteObjectInvocationHandler[...]]`（类型 `jdk.proxy1.$Proxy0`）；**HTTP 请求数 = 0** | 见下的「测试局限」 |
| 对照：显式把 `trustURLCodebase` 打开再 lookup | 返回同上；**HTTP 请求数仍 = 0** | 「开关开了仍没去取（还有别的拦截）」 |

**测试局限（不写明就是错的结论）**：**路径 B 没走完那条经典链**——我绑上去的是一个 `RefServiceImpl`（普通 RMI 接口实现），`lookup` 拿回来的是**该远端对象的代理**，`Reference` 的解码/工厂实例化**根本没被触发**。所以这两行「HTTP = 0」**只说明「在这两次构造里没触发」**，**不能**推出「本机 JDK 一定不会去取远程类」。**路径 A 才是真正走到 `getObjectInstance(Reference)` 的那一次，它读到 0，这一条有意义**：默认不信任远程 codebase。

> **方法论意义**：**「实验没触发」≠「机制不存在」。** 日志自己给的结论也是克制口径——「现代 JDK 在 provider 层默认不信任远程 codebase，所以这条老路默认打不动；而库自己拿不可信数据去调 `NamingManager` 时，局面取决于库怎么做」。**「我证明了 A 没发生」和「我证明了 A 不可能发生」是两句话。**

**同节另一条读数**：`com.sun.jndi.rmi.registry.ReferenceWrapper` 与 `RemoteReference` 在本 JDK **可加载**——那块「构造用的内部类」并没有被删掉。

**历史上为什么会发生**。远程 codebase 在 1990 年代末（applet 时代，见 06 篇）是**特性**：为了在客户端动态拉取类。当它被用在服务端日志/配置解析上，同一个特性就变成了「让攻击者指定去哪儿取代码」。

**现代 JDK 的变化**：`trustURLCodebase` 类开关的默认值改为**不信任远程 codebase**（**属实现变更、不是 JEP**；本机只读到「未设置 → 用内置默认」，**未直接读到默认值**）；**JEP 320** Remove the Java EE and CORBA Modules（**JDK 11**）把一批老技术栈整体移除。

**防守动作**：① **绝不让不可信数据成为 JNDI 名字的一部分**（名字当白名单里的枚举值，而不是自由字符串）；② 不用远程 codebase，并在**网络层**限制应用外连（出网白名单）——**比任何应用层判断都硬**；③ 检查依赖里有没有库在做「把用户输入交给 `NamingManager`/`lookup`」（这才是真正的风险点）；④ 记录并告警「本进程出现 JNDI 外连」（§8）。

### 4.3 表达式 / 模板注入族（CWE-917 / CWE-1336）

> **只讲类别与机制，不写可用 payload。**

**机制**。SpEL、EL、OGNL 与各类**模板引擎**的共同点是：它们的输入**本来就是一段「程序」**（表达式/模板），不是纯数据。应用把用户输入**当表达式或模板去编译执行**，就等于把「求值器」交给了对方。这与 §4.1 是**同一条线的两个面**：反序列化是「把**一个对象图**解释成动作」，表达式/模板注入是「把**一段文本**解释成动作」。**共同病根都是「数据被当成了代码」。**

**历史上为什么会发生**。这类能力一开始是为了**让配置更灵活**（表达式做条件、模板做渲染），库作者假设「写表达式的人是开发自己」。一旦框架把它用在**用户可控的字段**上（排序字段、过滤条件、模板片段、路由断言），假设就崩了。**CVE-2022-22947**（§7.3）就是这一类在真实组件里的样本。

**现代 JDK 的变化——这里要诚实**：根因在**各自的库**里，不在 JDK 里；**JDK 没有为 SpEL/OGNL/模板引擎提供统一的加固开关**。JDK 侧能谈的是**相关的能力收缩**：**JEP 335** Deprecate the Nashorn JavaScript Engine for Removal（**JDK 11**）、**JEP 372** Remove the Nashorn JavaScript Engine（**JDK 15**）。**本机实测（节名：`SecBoundary（本机 JDK 25 安全画像）`）**：**可用脚本引擎数 = 0**；`SecDangerApis` 一节里 `getEngineByName("nashorn") = null` 是同一件事的另一次读数。**含义**：「从 Java 里执行任意脚本」这条老路在本机走不通了——**但它对表达式/模板注入不提供任何保护**，因为那些库自带解释器。

**防守动作**：① 表达式与模板**只来自开发者**，用户输入永远走**参数/插值**；② 需要「可配置逻辑」时用**受限的自定义 DSL/枚举**，不要暴露通用表达式语言；③ 关掉框架里不必要的动态能力（**以该框架作者文档为准**）；④ 代码审查时把「用户输入 → 表达式求值 API」当作与命令注入同等严重。

### 4.4 命令注入族（CWE-78）

**机制**。`Runtime.exec` / `ProcessBuilder` 把「要不要执行一条系统命令」的决定权交给调用方；**Java 不做任何「命令安全」判断，参数怎么拼就怎么执行**（本机日志原话）。

**本机实测（节名：`SecDangerApis（常见攻击面 API 的默认行为）`）**：执行 `cmd /c echo …` → 输出 `hello-from-java`，退出码 0；**数组形式 `Runtime.exec(String[])` 可用，且不经 shell、分隔符不会被当元字符**；`ProcessBuilder` 重定向到文件也是同一个原语。**两条读数正好把防守方向说清**：**用数组形式** + **不接受拼接出来的命令**（程序来自白名单）。危险的是「把不可信输入拼进这里」，**不是这个 API 本身**。

**历史上为什么会发生**。把外部输入拼进 shell 命令行是跨语言的老毛病；Java 的特别之处是 `exec(String)` 这个**单字符串重载**会走分词，让人误以为「它替我处理了」。

**现代 JDK 的变化**：**没有针对性的 JEP**——这个 API 的行为一直没变，变的应该是**用法**。相关的一条能力收缩是 Security Manager（§4.11）：过去可靠它给「能不能 fork 进程」加一道运行期门，**这条路现在没了**。

**防守动作**：能不用外部命令就不用；必须用时走**数组形式** + 程序名白名单；参数做**严格校验**（长度、字符集、格式），不靠「转义危险字符」；这条路径**加日志与告警**。

### 4.5 XML 与外实体族（CWE-611）

**机制**。XML 允许在文档头定义**实体**，实体可指向**外部资源**（本机文件、URL）。解析器默认**解析并展开**它们——于是「解析一份 XML」变成了「读一个文件」或「发起一次网络请求」。

**本机实测（节名：`SecDangerApis（常见攻击面 API 的默认行为）`）**：

| 配置 | 实测结果 |
|---|---|
| 默认 `newInstance()` | **解析成功，实体内容 = `SECRET-CONTENT-1234567890`**——实体指向的本机文件被读出来了。日志原文：「默认配置下外部实体**被展开了**」 |
| 加固写法（禁用 DTD 与外部实体） | 被拒：`SAXParseException: 将功能 "http://apache.org/xml/features/disallow-doctype-decl" 设置为"真"时, 不允许使用 DOCTYPE` |

**同一节的第二个反序列化入口（`XMLDecoder`）**：输入 XML 里写类名与方法名，**`setter` 被调用 1 次**——日志结论是「又一处『数据变代码』」。加固口径也写在日志里：不要用 `XMLDecoder` 读外部数据；必须读时用 `ObjectInputFilter`，或换成 JSON + 显式校验。

**历史上为什么会发生**。外部实体是**规范里的特性**（共享 DTD、模块化文档），解析器默认开它是为了兼容性；等 XML 成了 Web 服务的通用数据格式，默认值就成了风险默认值。

**现代 JDK 的变化**：**JDK 没把 XXE 默认关掉**——本机实测的默认行为就是**解析外部实体**。JDK 给的是**加固接口**（设置 feature 关掉 DOCTYPE 与外部实体），**默认值由你自己定**。这与「Security Manager/JNDI 那两族由平台改默认值」形成对比：**平台只在该出手时出手，不该出手时把开关留给你。**

**防守动作**：建工厂时**显式**关闭 DTD 与外部实体（`disallow-doctype-decl`、`external-general-entities`、`external-parameter-entities` 等，具体 feature 名以你用的解析器文档为准）；关掉 `XInclude` 与外部 schema；不需要 XML 就别用；`XMLDecoder` 只用于**完全可信**输入。

### 4.6 SSRF 族（CWE-918）

**机制**。服务端用**用户给的 URL** 发起请求时，对方控制的不只是「取什么」，还有「**向谁取**」——包括内网地址、回环地址、云元数据地址与各种非 HTTP scheme。Java 的落点是 `java.net.URL` / `URLConnection` / `HttpClient` 及其各种封装。

**现代 JDK 的变化（如实说）**：**JDK 没有内置 SSRF 防护**，`java.net.URL` 不会替你判断「这个目标该不该连」。相关的**收窄**只有能力层面：Security Manager 退役后（§4.11），过去靠它限制「允许连哪儿」的做法**也一并消失**——所以 SSRF 的防线必须落在**应用层与网络层**。

**本机能不能复现**：**未在本机做**（会真的发起网络请求，与本套笔记「不下载、不落地、不对外」的纪律冲突）。**本文不把它算作本机实测。**

**防守动作**：URL 走**白名单（scheme + 主机 + 端口）**而不是黑名单；**解析后校验目标 IP**（并防 DNS 重绑定——解析与连接要针对同一个结果）；关掉不需要的 scheme（`file:`、`gopher:`、`jar:` 等）；用**独立出网策略**（出网白名单、独立网段、元数据端点加鉴权）——**这一层最硬，因为应用层判断总有漏**；记录并告警外连。

### 4.7 路径遍历族（CWE-22）

**机制**。`Path.resolve(userInput)` 看起来像「在 base 下面拼一段」，但用户输入里的 `..` 会**跨出 base**。**API 层不管 `..`，要自己规范化**（本机日志原话）。

**本机实测（节名：`SecDangerApis（常见攻击面 API 的默认行为）`）**：直接拼出来的路径读文件 → **读到了内容 `outside`**（跨出了 base 目录）；规范化后判断为 `true`。日志给的纪律：`base.resolve(userInput).normalize().startsWith(base)` 才允许读。

**现代 JDK 的变化**：**没有 JEP**——`Path.normalize()` 与 `Path.startsWith()` 一直都在，**这是「用对 API」而不是「等平台变好」的典型一族**。

**防守动作**：**规范化后做前缀判断**（本机给的就是这条），且用 `Path` 而不是字符串；**能设计成「只有 ID、没有路径」的接口就别用路径**（最彻底）；落盘时再夹一层「只允许落在某个目录」；对越界尝试**记录与告警**（这类尝试通常成串出现）。

### 4.8 SQL 注入族（CWE-89）

**机制**。SQL 是**文本语言**——把值拼进语句文本里，值里的内容就可能改变语句结构。

**本库已有前作，口径一致**：**05 篇 §7.2** 已写透——「这不是风格问题，是正确性问题」，判据是「**SQL 里凡是会变的东西，都走 `?` 占位 + `setXxx`**」。本机现状（05 篇 §7.3 有原文）：`java.sql` 可编译，但驱动是第三方 jar，**本机一个第三方 jar 都没有**，所以 `DriverManager.getConnection("jdbc:h2:mem:test")` 实测抛 `No suitable driver found`。

**历史上为什么会发生**。`Statement` 与 `PreparedStatement` 并存，语法上「拼字符串」更顺手；很多老代码是「字符串模板」思维写过来的。

**现代 JDK 的变化**：JDBC **未变也不需要变**——`PreparedStatement` 从 JDK 1.1 起就在。相关的一条是**驱动自动发现**（05 篇 §7.3 已讲），它让老代码里的 `Class.forName("...")` 变成历史遗留，**但与注入防护无关**。

**防守动作**：`PreparedStatement` + 占位符；表名/列名这类**不能参数化**的部分用**白名单枚举**；数据库账号最小权限；05 篇 §7 的整套 JDBC 纪律照旧。

### 4.9 依赖供应链族（CWE-1395 / CWE-1104）

机制上它不是「代码写错」，而是「**你信任的东西被别人改了**」。**供应链攻击落在 ATT&CK 的 T1195 家族**（T1195.001 依赖与开发工具、T1195.002 软件供应链），而 ATT&CK 官方在 **T1195.001** 里**明确把 maven/gradle 列入包管理器**——Java 生态是它点名覆盖的对象之一。因为有 §6 一整节，细节放那里。

### 4.10 配置与凭据族（CWE-798 / CWE-532）

**机制**。两件事经常一起发生：**密钥被写进代码或打进产物**（CWE-798）、**敏感值被写进日志**（CWE-532）。它们分别对应 **T1078 Valid Accounts** 与 **T1552.001 Credentials In Files**——**「不攻也能进」**的那一类。

**本机实测能提供的观察角度**：`SecBoundary` 一节用 `System.getProperty` 打了一批 `java.*` 属性（`java.version`、`java.vendor` 等）。**这些本身不是秘密**，但它示范了「属性/环境变量是很容易被随手打进日志的东西」——**同一行代码换成打印一个 token，就变成了 CWE-532。**

**现代 JDK 的变化**：**没有针对性的 JEP**。相关的是**平台在收缩「运行期改权限」的能力**（JEP 411 → JEP 486，§4.11）——这意味着**「配错了靠沙箱兜住」这条路也没了**，只能靠「别配错」。

**防守动作**：密钥不进代码、不进产物；**日志按字段白名单输出**，敏感值一律屏蔽；配置走**运行期注入**（环境变量/密钥管理服务）而不是构建期烘焙；把「产物里搜不到密钥」做成 CI 门禁。

### 4.11 模块封装与安全机制族（JEP 411 → JEP 486、JEP 403）

这一族**不是「缺陷」，是「平台的旧防线退役」**——它决定了前面几族「还能不能靠平台兜底」。

**本机实测（节名：`SecBoundary（本机 JDK 25 安全画像）` 与它的 `--add-opens` 对照组）**：

| 观测点 | 默认运行 | 加 `--add-opens` 后 |
|---|---|---|
| `System.getSecurityManager()` | `null` | `null` |
| `System.setSecurityManager(null)` | 抛 `UnsupportedOperationException: Setting a Security Manager is not supported` | 同样抛 |
| `jdk.internal.misc.Unsafe`（反射取实例） | `InaccessibleObjectException: … module java.base does not "opens jdk.internal.misc" to unnamed module` | **反射拿到实例**（松动成功） |
| 反射改 `String.value` | `InaccessibleObjectException: … does not "opens java.lang"` | **可访问（未强封装）** |
| 可用脚本引擎数 | **0** | 0 |

**这张表一次说了三件事**：① **Security Manager 已彻底不可用**（不是「不建议」，是 `setSecurityManager` 直接抛）；② **模块强封装是真的**，`--add-opens` 能松动——**这是运维的真实杠杆，也是攻击者的真实杠杆**；③ **能力收缩是主旋律**（脚本引擎 0、内部 API 拿不到）。

**JEP 编号与发行版（逐个核对）**：**JEP 411** Deprecate the Security Manager for Removal（**JDK 17**）、**JEP 486** Permanently Disable the Security Manager（**JDK 24**）、**JEP 403** Strongly Encapsulate JDK Internals（**JDK 17**）、**JEP 261** Module System（**JDK 9**）——状态均为 Closed / Delivered。（05 篇 §9.3 已有同一口径的一句话。）

**防守动作（这一族要反过来读）**：① **不要再把 Security Manager 当防线**，把它当「已经拆掉的脚手架」；② 能用 `jlink` 裁模块就裁（§5.3）——**少一个模块就少一块攻击面**；③ `--add-opens` 是**扩大**攻击面的开关，生产要有清单与理由；④ 依赖内部 API（`sun.misc.Unsafe`、`jdk.internal.*`）的库，升级时要重点看（`jdeprscan`/`jdeps` 能帮你找，见 §5）。

### 4.12 类加载与内存马族（T1505.003 Web Shell）

**机制**（内存马其实完全可以摊开讲）：① **类身份 = 类名 + 定义它的加载器**；② **绕过双亲委派**就能在内存里造出**一个同名但独立的类**；③ **`javaagent` + `Instrumentation.redefineClasses`** 还能把**已加载的类**换成另一份字节码。

**本机实测（节名：`SecClassLoader（内存马原理：从字节数组定义类）`）**：不绕委派时「两者是同一个类吗？**true**」（父加载器先命中，`findClass` 没被调用）；绕过委派后「和上一步那个类相同吗？**false**」、「再 new 一个同样的加载器定义一次：结果还等于上面这个吗？**false**」；**检测视角**——绕委派的那个类 `CodeSource = (null <no signer certificates>)`，正常部署的类 `CodeSource = (file:/C:/.../javasec-classes/ ...)`。日志结论：「内存里 `defineClass` 出来的没有来源可查」——**这就是「内存马查不到文件」的机制层面原因**。

**本机实测（节名：`javaagent（运行期替换已加载的类：内存马/热部署的同一套 API）`）**：`premain` 已执行，进程内**已加载类数量 = 904**，「能改动已加载的类吗？**true** / 能重新定义吗？**true**」；补丁字节数 **1871**，`redefineClasses` 完成，**「磁盘上没有任何文件被改动」**；同一个 `Class` 对象、同一份已加载代码，**实现被换掉了**，磁盘上的 `Victim.class` 从头到尾没变。对照（不带 `-javaagent`）：「没有 Instrumentation，跳过」，输出保持原始实现。

**为什么这一族对防守方最重要**：它给出**两条可操作的检测线**——① **看来源**（`CodeSource` 为 `null` 的绕委派类，或来源与部署目录不符）；② **看事件**（运行期改类会在 JFR 里留下事件，§5.2 有本机实证）。

**历史上为什么会发生**。`javaagent` 与自定义 `ClassLoader` 都是**正当的工程工具**（热部署、APM 探针、字节码增强），**同一套 API 被用来做持久化后门时就成了内存马**——「能力中性、用途分明」的教科书例子。

**现代 JDK 的变化**：**没有把这两条路关掉**（本机实测全部可用）。JDK 能做的是**让它们留下痕迹**：`jdk.JavaAgent`、`jdk.ClassRedefinition`、`jdk.RedefineClasses`、`jdk.ClassDefine` 这些 JFR 事件类型（本机核对存在）——**「预设能力不变、把可观测性做强」**。

**防守动作**：启动参数与 `-javaagent` 有**白名单**；把 JFR 那几类事件**接进监控**；定期比对「内存里加载的类」与「磁盘上的产物」；限制自定义 `ClassLoader` 的使用范围（框架行为要能被解释）。

## 5. 本机实测：给自己的 Java 程序做一次安全体检

防守方最日常的一件事不是「攻击」，而是**清点**：我的程序有多少暴露面、有多依赖内部 API、跑起来之后能看见什么。**本节全部只用 JDK 自带工具**，命令可原样重跑；产物与读数见 `code/sec/本机实测输出.txt` 的 **`【体检批次】`** 一节（脚本 `code/sec/checkup.sh`，与 `run.sh` 共用同一份追加日志）。**判读总原则**：**先问「这个工具能看到什么」，再问「我的项目里有没有」。**

### 5.1 静态四件套：javac → jdeprscan → jdeps → javap

```bash
javac -J-Dstderr.encoding=UTF-8 -encoding UTF-8 -Xlint:all -d out *.java
jdeprscan -J-Dstdout.encoding=UTF-8 --release 25 --for-removal --class-path out out
jdeps --jdk-internals -cp out out/SecBoundary.class out/SecJndi.class out/SecClassLoader.class out/SecAgent.class
jdeps -summary out/*.class
javap -p out/SecDeserBasic\$WithHook.class
```

**本机读数（体检 1–5）**：

| 工具 | 读数 | 发现什么算问题 |
|---|---|---|
| `javac -Xlint:all` | **3 个警告**：`[removal] System 中的 getSecurityManager() 已过时, 且标记为待删除`（×2，`get`/`set` 各一）、`[serial] 可序列化类RefServiceImpl没有 serialVersionUID 的定义`（**日志原文如此，类名两侧无空格**） | `[removal]` = **你踩在了正被摘掉的 API 上**（§4.11）；`[serial]` = 参与序列化却没钉版本（§4.1） |
| `jdeprscan --for-removal` | **2 条命中**：`java/lang/System::getSecurityManager()Ljava/lang/SecurityManager;` 与 `java/lang/System::setSecurityManager(Ljava/lang/SecurityManager;)V`（**日志原文带完整方法签名**），均标 `(forRemoval=true)` | 任何一个命中，都意味着**升级 JDK 时这一处会直接编译不过**；在安全语境里还多一层含义——**它可能正是你还在依赖的旧防线** |
| `jdeps --jdk-internals` | **输出是空的**（一条都没有） | **见下的坑** |
| `jdeps -summary` | 依赖面可见：`SecAgent.class -> java.instrument`、`SecDangerApis.class -> java.xml`、`-> java.desktop`，其余多为 `java.base` | 出现**你没想到的模块依赖**就值得问一句「我要它干什么」 |
| `javap -p` | `SecDeserBasic$WithHook` 有 `private void readObject(java.io.ObjectInputStream) throws java.io.IOException, java.lang.ClassNotFoundException;`（日志原文）；`$Cmp` 有 `compareTo`；`$Key` 有 `hashCode`/`equals` | **可序列化类里出现 `readObject`/`readResolve`/`readExternal`，就必须问「它做了什么、输入是谁给的」**；`compareTo`/`hashCode`/`equals` 出现，说明这些类**参与容器重建时的回调**（§4.1 的 0/1/3 与 1/4/16 就是这么来的） |

**必须说的一个坑**：`SecBoundary` 明明在用 `sun.misc.Unsafe`、`SecJndi` 明明在碰 `com.sun.jndi.rmi.registry.*`，**`jdeps --jdk-internals` 却什么都没报**——因为这些引用是**运行期用字符串反射**拼出来的（`Class.forName(...)` 之类），**没进常量池，静态分析看不见**。**纪律：静态扫描干净 ≠ 没有内部依赖。** 要覆盖这一类，得靠 §5.2 的运行期观测或代码审查。**这是「工具能替你做的事有边界」的实例。**

> 09 篇/05 篇的纪律同样适用：`-Xlint` 可用的 key 用 `javac -X` 自己查，**别背**。

### 5.2 运行期：jcmd 与 jfr

```bash
jcmd -l                              # 列出本机可见的 JVM
jcmd <pid> VM.command_line           # 启动参数（有没有 -javaagent？有没有 -Djdk.serialFilter？）
jcmd <pid> VM.system_properties      # 系统属性（安全开关设没设？）
jcmd <pid> GC.class_histogram        # 类直方图（哪类对象特别多？）
```

**本机读数（体检 6）**：

```text
-- VM.command_line --
jvm_args: -Dstdout.encoding=UTF-8 -Djdk.serialFilter=java.base/*;!*
java_command: Sleeper
-- VM.system_properties | grep 安全开关 --
jdk.serialFilter=java.base/*;\!*
-- GC.class_histogram（前 6 行）--
 num     #instances         #bytes  class name (module)
   1:         12012         636008  [B (java.base@25.0.4.1)
   2:         11585         278040  java.lang.String (java.base@25.0.4.1)
   3:          1781         235160  java.lang.Class (java.base@25.0.4.1)
```

**看什么**：**`VM.command_line` 是体检的第一站**——① 有没有**不认识的 `-javaagent`**（§4.12 的持久化入口）；② 有没有 `--add-opens`（§4.11 的封装松动）；③ **`jdk.serialFilter` 到底设没设**。这三个都该出现在**有理由的清单**里。`GC.class_histogram` 看「不该多的东西是不是太多了」：本机 top3 是 `byte[]`/`String`/`Class`，属正常；若出现**大量同名的陌生类**（尤其自定义加载器造出来的），就去看下面的 JFR 事件与 §4.12 的 `CodeSource` 手法。

**本文最有价值的一节：`jfr`**。Java 的攻击面里，「反序列化」「运行期改类」「挂 agent」「改安全属性」「松动模块封装」这几件事，**JFR 都有事件类型可以观测**。本机核对了本 JDK 里存在的相关类型：

```text
jdk.Deserialization      jdk.JavaAgent           jdk.NativeAgent
jdk.ClassDefine          jdk.ClassLoad           jdk.ClassRedefinition
jdk.RedefineClasses      jdk.RetransformClasses  jdk.InitialSecurityProperty
jdk.SecurityPropertyModification   jdk.ModuleExport   jdk.ModuleRequire
jdk.InitialEnvironmentVariable
```

**本机实测（体检 7，对 `javaagent` 那次运行做录制）**：

```text
-- jfr print --events jdk.JavaAgent --
jdk.JavaAgent { startTime = 20:26:19.016 (2026-10-01)
  name = "<用户目录>\AppData\Local/Temp/javasec-checkup-agent/agent.jar"
  dynamic = false ; initializationDuration = 17.8 ms }
-- 相关事件计数 --
 jdk.ClassRedefinition 1    jdk.JavaAgent 1    jdk.RedefineClasses 1
 jdk.ClassDefine 0          jdk.ModuleExport 0
```

**这组读数为什么重要**：§4.12 那个「磁盘上一个文件都没改、实现却被换掉了」的动作，**在 JFR 里是有痕迹的**——`jdk.JavaAgent` 记下了 agent 的来源路径与加载时刻，`jdk.RedefineClasses`/`jdk.ClassRedefinition` 记下了「有人改过已加载的类」。**「内存马查不到文件」是对的，但它不完整：查不到文件 ≠ 查不到事件。**

**一个如实的局限**：`jdk.Deserialization` 在**默认设置下没有记录到事件**（那次录制计数为 0）。要让它出数据需要改录制设置/装相应的事件配置——**本机没做到这一步，本节不宣称「默认就记录反序列化」**；能确定的只是：**这个事件类型在本 JDK 里存在**。

**看什么 / 发现什么算问题**：

| 事件 | 发现什么算问题 |
|---|---|
| `jdk.JavaAgent` / `jdk.NativeAgent` | 出现**不认识的 agent 路径**（生产里 agent 应当是白名单里的那几个） |
| `jdk.RedefineClasses` / `jdk.RetransformClasses` / `jdk.ClassRedefinition` | 出现**没人预期的改类动作**（内存马/热部署的共同痕迹） |
| `jdk.ClassDefine` | 出现**来源为 null、绕委派定义出来的类**（配合 §4.12 的 `CodeSource` 检查） |
| `jdk.InitialSecurityProperty` / `jdk.SecurityPropertyModification` | 有谁在**改安全属性**（比如把信任开关打开） |
| `jdk.ModuleExport` / `jdk.ModuleRequire` | 有谁在**松动模块封装**（与启动参数 `--add-opens` 对照） |

### 5.3 最小化：`jlink` 的「少一个模块 = 少一块攻击面」

```bash
jlink --add-modules java.base --output rt-min
```

**本机读数（体检 8）**：模块总数 **70**；`--add-modules java.base` 的最小镜像 **50M**（05 篇 §3.3 有同一口径的体积对照：完整 JDK 291 MB）。**看什么**：① **镜像里少了哪些模块**——少一个模块就少一批类、少一批 API、少一批反序列化可达的类型（这对 §4.1 是**结构性**的减小，比任何过滤器都硬）；② **别把诊断工具一起裁掉**——05 篇已实测：`jcmd`/`jfr`/`jconsole` 都在 `jdk.*` 模块里，裁完线上出问题你手里就没有现场工具了。**「为了安全裁掉可观测性」是本末倒置。**

### 5.4 一张体检总表（照着自己项目跑一遍）

| # | 工具 | 命令要点 | 发现什么算问题 |
|---|---|---|---|
| 1 | `javac -Xlint:all` | 编译时加上 | `[removal]`、`[serial]`、`[rawtypes]`/`[unchecked]` |
| 2 | `jdeprscan --for-removal` | 对产物目录跑 | 任何 `(forRemoval=true)` 命中 |
| 3 | `jdeps --jdk-internals` | 对每个 class 跑 | 有命中 → 依赖内部 API（**反射拼出来的它看不见**） |
| 4 | `jdeps -summary` | 对产物跑 | 出现你没想到的模块依赖（如 `java.desktop`、`java.xml`） |
| 5 | `javap -p` | 对可序列化类跑 | 有 `readObject`/`readResolve`；缺 `serialVersionUID` |
| 6 | `jcmd <pid> VM.command_line` | 对**运行中**的进程跑 | 不认识的 `-javaagent`、`--add-opens`；`jdk.serialFilter` 没设 |
| 7 | `jfr` | 录制后 `jfr print`/`jfr summary` | `jdk.JavaAgent`、`jdk.RedefineClasses` 等出现非预期记录 |
| 8 | `jlink` | 裁最小运行时 | 该裁的没裁（攻击面偏大）；把诊断模块一起裁掉（自断手脚） |

> **本节定位**：**体检不等于渗透测试**。它回答的是「我的暴露面有多大、我的防线设没设、出事时我能不能看见」——全是防守方的问题，**不含任何攻击动作**。

## 6. 依赖供应链：一行依赖背后的风险面

**先说本机的口径**：**本机没有 Maven、没有 Gradle，也没有任何第三方 Java 库**（05 篇 §4 与索引页都有这条实测）。所以**本节没有任何一项是本机验证过的**——凡属 05 篇已有口径的地方我明说，其余全部标为**文档口径**。

**为什么「一行依赖」是攻击面**：**T1195.001** 的官方正文（2026-10-01 核对）说：依赖外部软件才能运行的项目，**「pip 与 NPM 包」这类被广泛依赖的开源项目可能被当作目标**；而它的**检测栏**进一步点名了一串包管理器——**其中就有 `maven/gradle`**。（**一句必须说的公平话**：官方**正文里举的显式例子是 npm/pip**；**maven/gradle 出现在该页的检测/数据源描述里**。引用时别把两处混成一句。）

| 风险环节 | 机制 | Java 生态里的样子 |
|---|---|---|
| **坐标与解析** | 同名/近名包被优先拉到 | 仓库解析顺序、私有仓库与公开仓库的配置（文档口径） |
| **传递依赖** | 你只写了一行，实际进来几十个 | A→B→C 自动带入（05 篇 §4.2 已讲「依赖传递」与「离根最近先赢」） |
| **版本收敛** | 同一条链上多个版本，实际生效的可能不是你以为的那个 | `dependencyManagement` / 版本对齐（05 篇 §4.2） |
| **维护状态** | 依赖还在，但已无人维护 | **CWE-1104** 正是这一类 |
| **构建期执行** | 构建脚本本身能执行任意代码 | 插件与构建脚本（05 篇 §4.3 讲过「Gradle 脚本是代码」） |
| **构件被替换** | 发布出去的产物与源码不一致 | **T1195.002** 覆盖的对象 |

**能做的六件事，以及「本机能不能验」**：

| # | 动作 | 本机能不能验 |
|---|---|---|
| 1 | 建 **SBOM**（出事时你得能回答「我用了哪个版本」） | **做不了**；工具类别见 OWASP Dependency-Track |
| 2 | 跑 **SCA**（把「已知漏洞的组件」找出来） | **做不了**；见 OWASP Dependency-Check |
| 3 | **锁版本**、提交 lockfile | **做不了**；05 篇 §4.6 讲的是同一件事（wrapper 进版本库） |
| 4 | **看依赖树，而不是看一行** | **`jdeps` 能做「模块级」的相邻替代**：§5.1 实测能看到 `SecAgent.class -> java.instrument`、`SecDangerApis.class -> java.xml` 这种**你没显式要求过**的依赖——**这是本机唯一能落地的「依赖面清点」** |
| 5 | 新增依赖前先审（传递依赖多少、是否还在维护、有没有构建期钩子） | 文档口径 |
| 6 | 对「不需要的能力」做减法（`jlink` 裁模块；不需要的库直接不引） | **本机验过**（模块 70 → 最小镜像 50M） |

> **一句话**：供应链这一族，**本机能做的只有「减法」（裁模块、清点模块依赖）与「思维」（把依赖当代码看）**；扫描、SBOM、锁定需要工具链——**本文如实标注，不假装跑过。**

## 7. 六个真实 Java 案例（每个都要核对官方条目）

**核对方式**：每个案例的**编号、受影响组件、发布年份、CVSS、CWE** 都取自 **NVD 条目**（`nvd.nist.gov/vuln/detail/...` 与其官方 REST API `services.nvd.nist.gov/rest/json/cves/2.0`），核对日 **2026-10-01**。**不写利用步骤。**

| # | 案例 | CVE | 根因（NVD 所载） | CVSS 3.1 | 发布 |
|---|---|---|---|---|---|
| 1 | **Log4Shell**（Apache Log4j2） | CVE-2021-44228 | JNDI 特性：配置、日志消息、参数里的 JNDI 查找**不防护攻击者可控的 LDAP 等端点** | **10.0 CRITICAL** | 2021-12-10 |
| 2 | **Spring4Shell**（Spring Framework） | CVE-2022-22965 | **数据绑定**（data binding）导致 RCE | **9.8 CRITICAL** | 2022-04-01 |
| 3 | **Spring Cloud Gateway（Actuator）** | CVE-2022-22947 | Actuator 端点**被启用、暴露且不安全**时的**代码注入** | **10.0 CRITICAL** | 2022-03-03 |
| 4 | **fastjson 反序列化**（com.alibaba:fastjson） | CVE-2022-25845 | **绕过默认的 autoType 关闭限制**，不可信数据反序列化 | **8.1 HIGH** | 2022-06-10 |
| 5 | **Apache Shiro RememberMe** | CVE-2016-4437 | 未为 rememberMe 配置密钥时，可被用于执行任意代码或绕过访问限制 | **9.8 CRITICAL** | 2016-06-07 |
| 6 | **WebLogic 未授权远程代码执行** | CVE-2019-2725 | **Web Services 子组件**（受影响 10.3.6.0.0 / 12.1.3.0.0） | **9.8 CRITICAL** | 2019-04-26 |

同族条目也在 NVD 上核对过：**CVE-2015-4852**（WebLogic WLS Security 组件，NVD 描述明确写「**T3 协议流量里构造的序列化 Java 对象**」、**CWE-502**、9.8、2015-11-18）；**CVE-2020-14882**（WebLogic **Console** 组件，Oracle 给的 CVSS 3.1 = **9.8**，2020-10-21，CWE 为 `NVD-CWE-Other`）；**CVE-2017-18349**（Fastjson <1.2.25，2018-10-23，CWE-20）。

### 7.1 Log4Shell（CVE-2021-44228）

- **时间线**：NVD 发布日 **2021-12-10**。受影响范围（NVD 原文）：**Log4j2 2.0-beta9 到 2.15.0**（不含安全版本 2.12.2、2.12.3、2.3.1）。修复演进（NVD 描述）：**自 2.15.0 起该行为默认被禁用**；**自 2.16.0（以及 2.12.2、2.12.3、2.3.1）起该功能被彻底移除**。
- **根因（机制层面）**：日志的**消息/参数**里若含 `${...}` 形式的**查找（lookup）**语法，而该语法允许把 **JNDI** 当查找目标，那么「**让程序记一行日志**」就等价于「**让程序按我给的地址找一个对象**」——§4.2 那条链被搬进了最不起眼的地方：**日志**。
- **CWE 口径要注意**：**NVD 主 CWE 给的是 CWE-917（Expression Language Injection）**，而 Apache（条目里的另一来源）给的是 **CWE-20 / CWE-400 / CWE-502**。**同一件事被归了不同族**——这不是错误，是「框架是观察工具」的实例：它既像表达式注入（`${}` 求值），也像反序列化（取回对象并实例化）。
- **影响面**：日志是几乎所有 Java 应用都会有的东西，加上 `log4j-core` 引用面极广，使它成为**「一个库的默认行为改变一整个行业运维节奏」**的样本。
- **修复手段（NVD 所载）**：升级到**已移除该功能**的版本（2.16.0 / 2.12.2 / 2.12.3 / 2.3.1），或用 **2.15.0**（默认禁用；NVD 也记载该版本后续仍有相关问题需继续升级——**「打了补丁」不等于「补完了」**）。
- **给初学者的启示**：① **危险的不是「日志库」，是「日志库允许求值」这个能力**；② 同一个漏洞能被归进多个 CWE，**归族是判断，不是查表**；③ 第三方库的**默认开关**就是你的默认风险。

### 7.2 Spring4Shell（CVE-2022-22965）

- **时间线**：NVD 发布日 **2022-04-01**。
- **根因（机制，照 NVD 描述）**：**数据绑定**（把请求参数绑定到对象属性）本身是正当功能，但在特定条件下可被用来**影响不该被影响的属性**，进而 RCE。NVD 还写明**前提条件**：应用运行在 **JDK 9+**，且需特定部署形态（**Tomcat 上以 WAR 部署**）；**以 Spring Boot 可执行 jar（默认形态）部署时不可被该利用方式拿到**，但也补了一句「**该漏洞的性质更一般，可能还有其它利用方式**」——**这句话本身就值得抄进笔记**：厂商通报里的「不适用于这种部署」往往只是当时已知的那条路径。
- **CWE 口径**：**NVD 与 VMware 都标 CWE-94（Code Injection）**。
- **影响面**：Spring 在 Java Web 里的引用面，与 Log4j 在日志里的引用面是同一个量级。
- **修复手段**：升级到修复版本（**具体版本号以 Spring 官方公告为准**；NVD 的 CVE 描述正文里没有逐字给出，本文不转抄）。
- **给初学者的启示**：**「框架的便利功能」=「攻击者的输入口」**。数据绑定、模板渲染、参数自动转换，这些「省事」的设计都要问一句：**用户能碰到哪些字段？**

### 7.3 Spring Cloud Gateway Actuator（CVE-2022-22947）

- **时间线**：NVD 发布日 **2022-03-03**。
- **根因（机制，照 NVD 描述）**：当 **Gateway 的 Actuator 端点被启用、暴露并且不安全（unsecured）**时，应用**可被代码注入攻击**，远程攻击者可构造请求达成任意远程执行。**三个前提缺一不可**——这正是本案例的教学价值：**它讲的是「功能暴露」造成的风险，不是「代码写错」**。
- **CWE 口径**：**NVD 标 CWE-917，VMware 标 CWE-94**（又一次「同一件事两族」）。
- **影响面**：网关处在流量入口，一旦被利用位置极佳（对应 ATT&CK 的 **T1190**）。
- **修复手段**：升级（NVD 描述写的是受影响范围在 **3.1.1+ 与 3.0.7+ 之前**）；**并且**——这条同样重要——**关掉/保护 Actuator 端点**（管理端点不该对公网暴露）。
- **给初学者的启示**：**「管理端点」是攻击面的重灾区**。任何「给运维用的口子」（actuator、调试接口、健康检查、各类 console）都该问三个问题：**开没开？谁能到？有没有鉴权？**

### 7.4 fastjson 反序列化（CVE-2022-25845）

- **时间线**：NVD 发布日 **2022-06-10**（`vulnStatus` 为 Modified）。
- **根因（机制，照 NVD 描述）**：**`com.alibaba:fastjson` 1.2.83 之前**的版本，在**某些条件下可以绕过默认的 autoType 关闭限制**，构成**不可信数据反序列化**。NVD 还给了官方缓解办法：无法升级时可启用 **safeMode**。
- **CWE 口径**：**CWE-502**（这一条没有分歧）。
- **影响面**：JSON 是 Java 服务的通用数据格式；「按 JSON 里的类名去构造对象」这一特性，把 §4.1 的问题**原样搬进了 JSON 时代**。
- **修复手段**：**升级到 1.2.83**（NVD 写 "before 1.2.83" 为受影响范围）；或按 NVD 引用的官方说明启用 safeMode。
- **一条必须区分的话**：本条（**CVE-2022-25845**，2022）与本族更早的那条（**CVE-2017-18349**，NVD 记 Fastjson **1.2.25 之前**的 `parseObject`，2018-10-23，CWE-20）**不是同一件事**——**同一个组件在同一个缺陷族上出现过多次**，这正是「依赖要持续跟踪」的最直观理由。
- **给初学者的启示**：**「反序列化漏洞」没有随着 JDK 加固而消失，它换了载体**——原生序列化 → XML → JSON，入口一直在变（§4.1、§4.5 是同一个病根）。

### 7.5 Apache Shiro RememberMe（CVE-2016-4437）

- **时间线**：NVD 发布日 **2016-06-07**。
- **根因（机制，照 NVD 描述）**：**Apache Shiro 1.2.5 之前**，当 **rememberMe 功能没有配置加密密钥**时，可被远程攻击者用于**执行任意代码或绕过访问限制**。机制上是两件事叠在一起：**「记住我」这个 cookie 是客户端可改的**，而它的**默认密钥是公开的**——于是「客户端提交的密文」变成了「客户端可控、服务端会解密并处理的数据」。
- **CWE 口径**：**NVD 主 CWE 为 `NVD-CWE-noinfo`**（未给出），另一来源标 **CWE-321（Use of Hard-coded Cryptographic Key）**。**「权威库没给出缺陷类型」这件事本身**，与 C 语言那篇里 EternalBlue 的情形一样：**真实漏洞的根因归类本身就是一项工作。**
- **修复手段**：**升级到 1.2.5 及以上**（NVD 写 "before 1.2.5"）；**并且**为 rememberMe **配置自己的密钥**——NVD 把「未配置密钥」写成了前提条件，**这说明即使版本升级了，配置没改也仍然是问题**。
- **给初学者的启示**：① **默认密钥 = 公开密钥**（CWE-321 这一族在 §4.10 也出现过）；② **客户端提交的任何东西都要当成不可信输入**——包括看起来「是服务端发下去的」cookie；③ 「版本升了就没事了」是错的，**配置也是防线**。

### 7.6 WebLogic 未授权远程代码执行（CVE-2019-2725 / 同族 CVE-2015-4852）

- **时间线**：CVE-2019-2725 发布 **2019-04-26**；同族 CVE-2015-4852 发布 **2015-11-18**；同族 CVE-2020-14882 发布 **2020-10-21**。
- **根因（机制，分两条说清）**：
  - **CVE-2015-4852**：NVD 描述**直接写明机制**——「**T3 协议流量里构造的序列化 Java 对象**」，关联到 WebLogic 自带的那份 `commons-collections` jar；**NVD 的 CWE 也给的是 CWE-502**。**这一条就是 §4.1 的教科书式落地**。受影响版本（NVD）：10.3.6.0 / 12.1.2.0 / 12.1.3.0 / 12.2.1.0。
  - **CVE-2019-2725**：NVD 描述为 **Web Services 子组件**上**未授权且网络可达**的问题（受影响 10.3.6.0.0 / 12.1.3.0.0），CVSS 3.1 **9.8**。**注意口径**：NVD 给它的 CWE 是 **CWE-74（Injection）**，不是 CWE-502——**所以本文只说它「属同一批 WebLogic 未授权远程代码执行问题」，不硬把它写成「反序列化」**。真正被 NVD 明确写成 T3 序列化的，是上面那条 CVE-2015-4852。
  - **CVE-2020-14882**：**Console 组件**，Oracle 给的 CVSS 3.1 = **9.8**，CWE 为 `NVD-CWE-Other`。它的位置（**管理控制台**）与 §7.3 的 Actuator 是同一种病：**给运维用的界面/端点，成了给攻击者用的入口**。
- **影响面**：WebLogic 是企业里长期存在的大型中间件，**同族问题跨越 2015→2020 五年**——这条时间跨度本身就是教训。
- **修复手段**：按 Oracle 的季度安全补丁（CPU）升级；**同时**按官方指引限制不必要的协议/组件暴露（T3/IIOP 这类管理协议不该对外可达）。
- **给初学者的启示**：① **「管理面」与「数据面」要分开**（T3/IIOP、Console、Actuator 属管理面）；② **同一个产品会在同一族缺陷上反复出事**，「这个漏洞修了」不等于「这个组件安全了」；③ **CWE 归类可能有分歧**（CVE-2019-2725 = CWE-74 vs CVE-2015-4852 = CWE-502），**这提醒我们「归族」是判断，要把依据（NVD 原文）一起写下来。**

> **六个案例的共同点**：**没有一个的根因是「内存被写坏了」**——全部落在 §3 索引表的那几行里（反序列化/表达式注入/默认密钥/管理端点暴露），缓解手段也基本落在 §8 的表里。**框架的价值，就是让「看一个陌生的漏洞通报」变成「归到哪一族、对应哪条缓解」的填空题。**

## 8. 防守矩阵：按 ATT&CK 战术组织的 Java 侧动作

左列是 ATT&CK 战术（编号见 §2.2，核对过），右列是**你在 Java 侧能做的具体动作**。**这是动作清单，不是产品清单**；每行尽量给一个**本机可验证**的落点。

| 战术 | Java 侧能做什么 | 本机落点 / 依据 |
|---|---|---|
| **TA0001 Initial Access** | ① 反序列化入口走**白名单过滤器**（`-Djdk.serialFilter` / `ObjectInputFilter`，JEP 290、415）；② 不给不可信数据当类名/URL/路径/命令的机会（CWE-470/918/22/78）；③ **管理端点**不对外（Actuator/web console/T3）；④ 依赖持续跟踪 | §4.1 本机实测（`InvalidClassException -> filter status: REJECTED`，副作用 0 次）；§5.4 体检 5/6 |
| **TA0002 Execution** | ① 数组形式的 `exec`/`ProcessBuilder` + 程序白名单（CWE-78）；② 表达式与模板只来自开发者（CWE-917/1336）；③ 关掉不需要的动态能力 | §4.4 本机实测（数组形式可用）；§4.3 本机实测（引擎数 0） |
| **TA0003 Persistence** | ① `-javaagent` 白名单；② 监控改类事件；③ 定期比对「内存里的类」与「磁盘上的产物」 | §4.12、§5.2（`jdk.JavaAgent` = 1、`jdk.RedefineClasses` = 1 本机实测） |
| **TA0004 Privilege Escalation** | ① 不要依赖 Security Manager（本机已抛 `UnsupportedOperationException`）；② 用 `jlink` 裁掉不需要的模块；③ 进程与文件权限最小化 | §4.11、§5.3 本机实测（70 模块 → 50M） |
| **TA0005 Stealth** | ① 审计 `--add-opens` 与 `sun.misc.Unsafe`/`jdk.internal.*` 的使用；② 记住「静态扫描干净 ≠ 没有内部依赖」；③ 把 JFR 安全相关事件接进监控 | §4.11 本机实测（前后对照）；§5.1 本机实测（`jdeps --jdk-internals` 空输出） |
| **TA0112 Defense Impairment** | ① 诊断工具要**留在运行时镜像里**；② 日志与录制文件本身要防篡改、要有独立留存 | §5.3（05 篇 §3.3 同口径：`jcmd`/`jfr` 都在 `jdk.*` 模块） |
| **TA0006 Credential Access** | ① 密钥不进代码/产物/日志（CWE-798/532）；② 日志按字段白名单输出；③ 配置走运行期注入 | §4.10（机制 + `SecBoundary` 里「属性被打进日志」的观察角度） |
| **TA0007 Discovery / TA0008 Lateral Movement** | ① 应用出网**白名单**（SSRF 与横向移动共同的第一道墙）；② 内部服务之间也做鉴权（不要靠「在内网」当授权） | §4.6（机制与防守；**本机未复现**） |
| **TA0009 Collection / TA0010 Exfiltration** | ① 限制 `file://` 类访问与路径越界（CWE-22/611）；② 出网白名单 + 出站流量留痕 | §4.5、§4.7 本机实测（XXE 默认展开实体；路径遍历读到 `outside`） |
| **TA0011 Command and Control** | ① 出站流量白名单与异常外连告警；② 依赖与补丁管理（M1051） | §6（供应链口径）；**本机未做流量侧实验** |
| **TA0040 Impact** | ① XML 解析防范放大类攻击；② 资源上限与超时；③ 隔离（M1048） | §4.5（机制）；**本机未复现 DoS** |

**ATT&CK 官方缓解（核对过的编号）与 Java 侧的对应**：

| 缓解编号 | 名称 | Java 侧最实际的落点 |
|---|---|---|
| **M1051** | Update Software | 依赖与 JDK 的补丁管理——**性价比最高的一条**（§7 六个案例里五个的修复手段都是「升级」） |
| **M1050** | Exploit Protection | 序列化过滤器、模块裁剪、`-Xlint`/`jdeprscan` 这类「让危险用法编不过/跑不通」的手段 |
| **M1042** | Disable or Remove Feature or Program | 关掉不用的功能：Actuator 端点、不需要的协议、不必要的模块（§5.3） |
| **M1048** | Application Isolation and Sandboxing | 进程与网络隔离——**Security Manager 退役后（JEP 486），这是唯一还硬的隔离层** |
| **M1026** | Privileged Account Management | 数据库/服务账号最小权限（05 篇 §7 口径） |
| **M1038** | Execution Prevention | 不让不可信数据触发执行路径（过滤器 + 白名单） |
| **M1054** | Software Configuration | 把「配置当防线」：Shiro 的密钥、网关的端点、日志的敏感字段（§7.3、§7.5） |
| **M1017** | User Training | 对「运维把管理端点暴露出去」这类**人为前提**的培训 |

> 每条技术页下方还有 **Detection Strategy** 一栏——**那一栏是防守方最该读的部分**，比技术描述本身有用。

## 9. 漏洞生命周期：从披露到你该做什么

```text
发现 → 验证可重现 → 报告厂商 → 分级（CVSS 维度）→ 修复 → 披露（CVE/CNVD）→ 回归验证
```

**CVSS 别背分数，理解维度**（以 CVSS 3.1 为例）：Attack Vector（要网络/相邻/本地）、Attack Complexity（需要特殊条件吗）、Privileges Required（攻击前得有账号吗）、User Interaction（需要有人点一下吗）、Scope（影响越出原组件边界吗）、Confidentiality / Integrity / Availability（对三性影响各多大）。

**用 §7 的两个案例把这张表用一次**：

- **CVE-2021-44228** 的向量是 `CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H` —— **`S:C`（Scope 变化）** 是它拿到 10.0 的关键：影响**越出了组件自身**。
- **CVE-2022-22965** 的向量是 `CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H` —— 同样是 9.8，但 **Scope 为 `U`（不变）**。

**两条向量的差别，正说明「同分不代表同风险」。** 同一个漏洞在不同环境下分数也可能不同，**看分数先看向量**。

**协调披露**：业界惯例是给厂商一段修复窗口（常见的「90 天」是**惯例而非法律**），到期再公开。中国可报 **CNVD**（国家信息安全漏洞共享平台），国际可走 **CVE**（`cve.org`）。

**作为学习者，你能做的三件事**：① **复现公开 CVE**（在本机隔离环境里）——读官方通报与 CWE/ATT&CK，**自己写一份「它为什么错」的报告**（不是抄利用代码）；② **审计自己的代码**——拿 §3 的索引表当清单，逐行问「我这里有没有这一类输入」；③ **把 §5 的体检做成例行动作**——每次交付前跑一遍 `checkup.sh`，把读数贴进笔记。

**边界（重申）**：以上全部只在**自己的机器、自己的程序、明确授权的靶场**上做。未经授权去「验证」真实系统，触的是《刑法》285/286 条——**「超出授权范围」同样构成侵入**。发现真实漏洞就报厂商/CNVD，**不要自己去打穿**。

## 10. 学习路线与合法练习

| 层 | 内容 | 验收 |
|---|---|---|
| 已有（10、11 篇） | 机制与动手：从字节码看攻击面、反序列化、类加载、本地靶场 | 能在自己的靶子上跑通机制验证，**并且说清每一步「是谁在动」** |
| **本文（12 篇）** | **地图与防守**：攻击面索引表 + ATT&CK + 真实案例 + 体检 | 拿到一个陌生 Java 漏洞通报，能**归族（CWE）→ 定位 ATT&CK 技术 → 说出对应缓解 → 说出本机怎么体检** |
| 下一层 | 真实系统的漏洞研究（**授权环境**） | 独立完成公开靶场的中级题，并写出**只有防守视角**的报告 |

**练习 ATT&CK 用法的具体做法**：在 **ATT&CK Navigator** 里新开一层，把 §7 六个案例涉及的技术标上色（T1190、T1195.001/.002、T1505.003、T1078、T1552.001……）。标完你会立刻看出**哪些战术它们没碰到、哪些检测点本该有信号**——这就是防守方做**覆盖度评估**的方式。

**合法练习资源（只给类别，不保证具体站点可达）**：

| 类别 | 说明 | 可核对的官方入口 |
|---|---|---|
| 框架与分类 | 查技术编号、CWE 定义、CVE 细节 | `attack.mitre.org`、`cwe.mitre.org`、`nvd.nist.gov`、`cve.org` |
| 故意不安全的 Web 靶场 | 在自己的机器上练「发现与修复」（**不是**拿别人的站练） | **OWASP Juice Shop**（`owasp.org` 上有项目页） |
| 组件漏洞扫描工具 | 对**自己的**项目跑 SCA | **OWASP Dependency-Check / Dependency-Track**（`owasp.org`） |
| ATT&CK 可视化 | 覆盖度着色 | ATT&CK Navigator（`attack.mitre.org` 上的入口） |
| JDK 自带工具 | §5 的全部内容 | 你本机 `bin/` 下就有（05 篇已实测这些工具都在） |

> **不给具体站点名单的理由**：可达性随时间和网络环境变化，**我这次没有逐个核对过**，写上去反而会变成「过期链接」。**类别 + 官方入口域名是更负责任的给法。**

**法律与伦理边界（必读）**：

| 依据 | 条文号 / 编号 | 要点（只引编号，不展开） |
|---|---|---|
| 《中华人民共和国刑法》 | **第二百八十五条** | 非法侵入特定领域系统；**非法获取数据 / 非法控制**他人系统；**提供**侵入、非法控制程序工具 |
| 《中华人民共和国刑法》 | **第二百八十六条** | 对系统功能或数据**删除、修改、增加、干扰**；制作、传播破坏性程序 |
| 两高《关于办理危害计算机信息系统安全刑事案件应用法律若干问题的解释》 | **法释〔2011〕19 号** | 2011 年 8 月 1 日公布、**2011 年 9 月 1 日起施行**；其**第一条**给了「情节严重」的数量/数额标准（身份认证信息、非法控制台数、违法所得与损失额等），**第二条**界定「专门用于侵入、非法控制计算机信息系统的程序、工具」 |

**四句话记住边界**：① **「我只看了看」不是免责理由**——未授权访问本身就是「非法侵入」的起点；扫描、爆破、遍历接口都属于**测试行为**，需要授权。② **授权必须是明确的、有范围的**（谁的资产、什么时间、哪些动作）——靶场与 CTF 之所以可以练，就是因为**授权是显式的**。③ **漏洞发现后的正确姿势是「报送，不是传播」**：联系资产方或平台、给最小必要的复现步骤、给修复时间；**不要在公开渠道贴可利用细节**。④ **写代码的人也有责任**：处理个人信息要有依据、要最小化；出了安全事件要按规定报告。

> **声明：这一节是边界说明，不是法律意见。** 引条文号只为了让你知道「线在这里」；**具体案件的认定请查官方发布页或咨询专业人士**；本文不提供任何针对真实系统的测试方法。

## 练习

**全部只给任务 + 验收标准 + 建议时长，不给答案代码。全部能用本机 JDK 完成。**

| # | 任务 | 验收标准 | 建议时长 |
|---|---|---|---|
| 1 | 【索引表】把 §3 的索引表抄成自己的版本，**只留你已经亲手验过机制的那几行**，其余标注「待验」 | 能说出「哪一行是我做过的实验、哪一行只是读来的」；每个 CWE/ATT&CK 编号都能说出**你是在哪个官方域名核对的** | 2 h |
| 2 | 【过滤器】给自己写的一个反序列化端**加上 JEP 290 过滤器**，然后证明它拦得住 | 加之前副作用发生、加之后**抛 `InvalidClassException` 且 `filter status: REJECTED`、副作用次数为 0**；再写出你那一条过滤规则的**读法**（`!` / 通配 / `java.base/*` / 结尾 `!*`） | 3 h |
| 3 | 【体检 · 静态】用 `javap -p` + `jdeps -summary` 审计一个你自己写的多类项目 | 列出**至少 3 个工作点**：① 哪些类可序列化且带反序列化钩子；② 哪些类的 `serialVersionUID` 缺失；③ 依赖面里有没有你**没打算要**的模块 | 2 h |
| 4 | 【体检 · 工具链】用 `jdeprscan --for-removal` 扫你的项目，再自己写一个**反射调用内部 API** 的类，重跑 `jdeps --jdk-internals` | 能**亲眼看到「静态扫描干净 ≠ 没有内部依赖」**这个现象，并写出原因（提示：常量池 vs 字符串反射） | 1.5 h |
| 5 | 【体检 · 运行期】对你自己的一个进程跑 `jcmd <pid> VM.command_line` 与 `jcmd <pid> VM.system_properties \| grep` | 能回答三问：**有没有 `-javaagent`？有没有 `--add-opens`？`jdk.serialFilter` 设了没有？** 并能说清「为什么这三个是体检第一站」 | 1.5 h |
| 6 | 【检测】用 JFR 记录一次「运行期改一个已加载的类」的动作，再从录制文件里把证据捞出来 | 能打印出 `jdk.JavaAgent` 事件（含 **agent 路径**）与 `jdk.RedefineClasses`/`jdk.ClassRedefinition` 的**计数**；再用 §4.12 的 `CodeSource` 手法说明「为什么磁盘上查不到文件，但事件里查得到」 | 3 h |
| 7 | 【最小化】给你的项目裁一个 `jlink` 运行时镜像，并说明裁掉了什么 | 镜像里的 `bin/java` 能跑起你的应用；能说出**裁掉诊断模块后会出现什么后果**（05 篇 §3.3 已给口径） | 3 h |
| 8 | 【案例摘要】从 §7 挑一个 CVE，写一份**三行摘要**：根因（机制）+ 影响面 + 修复手段 | 三行里每一行都**标注来源**（NVD 条目 / JEP / 厂商公告）；**不含任何利用步骤**；能说出它的 CWE 与对应 ATT&CK 技术 | 2 h |
| 9 | 【归族】把 §7 六个案例全部映射到 §3 的索引表上 | 能指出**哪些案例的 CWE 归类在不同来源之间不一致**（提示：至少有一例 NVD 与厂商给了不同编号），并解释「为什么归族是判断而不是查表」 | 2 h |
| 10 | 【矩阵】用 ATT&CK Navigator 把 §7 六个案例的技术标上色 | 能读出**哪些战术它们没碰**，并列出「本该有信号却没有」的检测点 | 2 h |
| 11 | 【供应链】给你现在这个项目写一份**依赖清单思路**（不必真的跑扫描工具） | 能回答：传递依赖怎么来的、同一条链上多版本怎么调解、SBOM 解决的是哪个问题；并**明确标注**哪几条你本机做不到 | 2 h |

## 自我检查清单

- [ ] 我能说清 CWE / CVE / ATT&CK 三者的分工，并各举一个 Java 的例子
- [ ] 我知道 ATT&CK 是**防守框架**，用法是统一语言与评估覆盖，**不是攻击教程目录**
- [ ] 我知道 **ATT&CK 里找不到「Java 反序列化」**，并能说出**为什么**（T1190 页面原文把 OWASP 与 CWE 指出来了）
- [ ] 我能说出企业战术大致有哪 15 个，并知道**它一直在改**（Defense Evasion 被拆成 Stealth / Defense Impairment 就是例子）
- [ ] 我能说出本文引用过的 **ATT&CK 编号**，并知道我是**在哪一天、哪个版本（v19.2）**核对的
- [ ] 我能把「反序列化 / JNDI / 表达式注入 / XXE / SSRF / 路径遍历 / 命令注入 / SQL 注入 / 供应链」各自对到 **CWE 编号**上
- [ ] 我能解释**为什么 Java 的攻击链是「两段式」**（入口 + gadget），并说出至少两种入口
- [ ] 我做过 **JEP 290 过滤器**的实验，并能读懂一条过滤规则的写法
- [ ] 我知道 **JNDI 那次本机实测「HTTP 请求数 = 0」当时只能算「未触发」**，并能说出测试局限是什么（13 篇已把它走到解码那一步，结论升级为「走到了、被明确拒绝」）
- [ ] 我知道 `Security Manager` 是**先弃用（JEP 411）后禁用（JEP 486）**，且**本机实测它已经抛异常**
- [ ] 我能说出 `--add-opens` **扩大了**攻击面（本机有前后对照实测）
- [ ] 我知道「内存马查不到文件」这句话的**机制原因**（`CodeSource` 为 null），也知道它**不完整**（JFR 里有事件）
- [ ] 我能用 `javap` / `jdeps` / `jdeprscan` / `jcmd` / `jfr` / `jlink` **各做一件体检**
- [ ] 我知道 **`jdeps --jdk-internals` 看不到反射拼出来的内部依赖**（本机实测空输出就是例子）
- [ ] 我能说出供应链这一族里**哪些是文档口径、哪些本机根本做不到**
- [ ] 我能说出六个真实案例的**编号、组件、CVSS 与发布年份**，并知道**我是怎么核对的**
- [ ] 我知道**同一个 CVE 在不同来源可能被归到不同 CWE**，并能举出至少一例
- [ ] 我知道 CVSS **要看向量，不要只背分数**
- [ ] 我知道漏洞披露的流程，也清楚**只在自己/授权范围**内做
- [ ] 我能说出《刑法》285/286 条与**法释〔2011〕19 号**的编号，并记得「这是边界说明，不是法律意见」

## 出处

**核对口径**：本文所有编号与事实**于 2026-10-01 逐条核对**——CVE 事实来自 **NVD**（条目页 `nvd.nist.gov` 与其官方 REST API `services.nvd.nist.gov/rest/json/cves/2.0`）；ATT&CK 编号来自 `attack.mitre.org`（站点显示内容版本 **v19.2**）；CWE 编号来自 `cwe.mitre.org`（站点显示 **CWE 版本 4.20**）；JEP 编号与所属发行版来自 `openjdk.org/jeps`。

| 内容 | 来源（官方域名） | 链接 |
|---|---|---|
| ATT&CK 版本页（站点显示 **v19.2**） | mitre.org | https://attack.mitre.org/resources/versions/ |
| 企业战术全景（TA0043…TA0040，共 15 条） | mitre.org | https://attack.mitre.org/tactics/enterprise/ |
| T1190 Exploit Public-Facing Application（含 "OWASP top 10 / CWE top 25" 那句原文） | mitre.org | https://attack.mitre.org/techniques/T1190/ |
| T1195 / T1195.001（正文含 pip、NPM；检测栏含 maven/gradle）/ T1195.002 | mitre.org | https://attack.mitre.org/techniques/T1195/ ；…/T1195/001/ ；…/T1195/002/ |
| T1505.003 / T1055 / T1059 / T1078 / T1027 / T1211 / T1574.001 / T1552.001 / T1499 / T1005 / T1567.002 / T1070 | mitre.org | https://attack.mitre.org/techniques/T1505/003/ 等同域名路径 |
| 缓解 M1051 / M1050 / M1048 / M1042 / M1026 / M1038 / M1054 / M1017 | mitre.org | https://attack.mitre.org/mitigations/M1051/ 等同域名 `/mitigations/Mxxxx/` |
| CWE-502 / 611 / 918 / 22 / 78 / 89 / 470 / 1336 / 917 / 1395 / 1104 / 798 / 532 / 321 / 269 | cwe.mitre.org | https://cwe.mitre.org/data/definitions/502.html 等同域名 `/data/definitions/N.html`（站点显示 **CWE 4.20**） |
| CWE Top 25（2024 榜） | cwe.mitre.org | https://cwe.mitre.org/top25/archive/2024/2024_top25_list.html |
| 六个案例的 NVD 条目（另含三条同族） | nvd.nist.gov | https://nvd.nist.gov/vuln/detail/CVE-2021-44228 ；/CVE-2022-22965 ；/CVE-2022-22947 ；/CVE-2022-25845 ；/CVE-2016-4437 ；/CVE-2019-2725 ；/CVE-2015-4852 ；/CVE-2020-14882 |
| NVD 官方 REST API（本文取事实的接口） | nvd.nist.gov | https://services.nvd.nist.gov/rest/json/cves/2.0?cveId=CVE-2021-44228 |
| CVE 项目（编号体系的官方入口） | cve.org | https://www.cve.org/ |
| OWASP Top 10:2021 / Top 10:2025（A03 Software Supply Chain Failures 等） | owasp.org | https://owasp.org/Top10/2021/ ；https://owasp.org/Top10/2025/ |
| 反序列化 / XXE / SSRF / 注入 防护速查 | owasp.org | https://cheatsheetseries.owasp.org/cheatsheets/Deserialization_Cheat_Sheet.html ；…/XML_External_Entity_Prevention_Cheat_Sheet.html ；…/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html ；…/Injection_Prevention_Cheat_Sheet.html |
| 故意不安全的 Web 靶场（合法练习） | owasp.org | https://owasp.org/www-project-juice-shop/ |
| 组件漏洞扫描类工具（本机未装） | owasp.org | https://owasp.org/www-project-dependency-check/ ；https://owasp.org/www-project-dependency-track/ |
| 序列化过滤器（`ObjectInputFilter` / `jdk.serialFilter` 契约） | docs.oracle.com | https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/ObjectInputFilter.html |
| JLS SE25 / JVMS SE25；Java SE 25 开发指南与故障排查 | docs.oracle.com | https://docs.oracle.com/javase/specs/jls/se25/html/index.html ；https://docs.oracle.com/javase/specs/jvms/se25/html/index.html ；https://docs.oracle.com/en/java/javase/25/core/ ；https://docs.oracle.com/en/java/javase/25/troubleshoot/ |
| JEP 索引（JEP 的**状态**与**发行版**） | openjdk.org | https://openjdk.org/jeps/0 |
| JEP 290 / 415（序列化过滤器）；411 / 486（Security Manager）；403（强封装）；261（模块系统）；320（移除 Java EE 与 CORBA）；335 / 372（Nashorn）；282（jlink） | openjdk.org | https://openjdk.org/jeps/290 等同域名 `/jeps/N` |
| JDK 25 的发行内容（**由 JSR 400 规定**） | openjdk.org / jcp.org | https://openjdk.org/projects/jdk/25/ ；https://jcp.org/en/home/index |
| 本机 JDK 的来源（Eclipse Temurin） | adoptium.net | https://adoptium.net/ |
| OpenJDK 源码（想追某个行为的实现） | github.com（官方仓库） | https://github.com/openjdk/jdk |
| 案例涉及的官方仓库（**用于核对修复版本的存在性**） | github.com（官方仓库） | https://github.com/apache/logging-log4j2/releases/tag/rel%2F2.16.0 ；https://github.com/spring-projects/spring-framework/releases/tag/v5.3.18 ；https://github.com/apache/shiro/releases/tag/shiro-root-1.2.5 ；https://github.com/alibaba/fastjson/releases/tag/1.2.83 |
| 编年背景（**二手，只作背景**） | wikipedia.org | https://zh.wikipedia.org/wiki/Java |
| 《刑法》第 285 / 286 条、**法释〔2011〕19 号**（本文**只引条文号**，不附链接——见 §10 的声明） | —— | —— |

## 依据说明

**分四类写，请按需取用。**

**一、有官方依据的（编号可查，核对日 2026-10-01）**

- **ATT&CK**：站点内容版本 **v19.2**；15 个企业战术的 ID 与名称；T1190、T1195、T1195.001、T1195.002、T1505.003、T1055、T1059、T1078、T1027、T1211、T1574.001、T1552.001、T1499、T1005、T1567.002、T1070 的**编号、名称与所属战术**；缓解 M1051/M1050/M1048/M1042/M1026/M1038/M1054/M1017 的编号与名称（**M1013 在核对时属 Mobile 域**，故未作企业侧引用）；T1190 页面那句 "OWASP top 10 and CWE top 25…" 的**原文**；T1195.001 正文的 npm/pip 与**检测栏里的 maven/gradle**。
- **CWE**：站点显示 **CWE 版本 4.20**；CWE-502 / 611 / 918 / 22 / 78 / 89 / 470 / 1336 / 917 / 1395 / 1104 / 798 / 532 / 321 / 269 的**官方英文名**；CWE Top 25 (2024) 的 25 条构成与位次（如 CWE-502 在 2024 榜第 16 位）。
- **CVE（六个案例 + 三条同族）**：编号、发布日、受影响组件与版本范围、CVSS 3.1 分值/向量、CWE（含**来源分歧**的记录），全部取自 NVD 条目与官方 REST API。具体：CVE-2021-44228（2021-12-10，10.0，CWE-917/NVD）；CVE-2022-22965（2022-04-01，9.8，CWE-94）；CVE-2022-22947（2022-03-03，10.0，CWE-917/NVD）；CVE-2022-25845（2022-06-10，8.1，<1.2.83，CWE-502）；CVE-2016-4437（2016-06-07，9.8，<1.2.5，CWE-321/另一来源）；CVE-2019-2725（2019-04-26，9.8，Web Services 子组件，**CWE-74**）；CVE-2015-4852（2015-11-18，9.8，**NVD 描述含「T3 协议」与序列化对象，CWE-502**）；CVE-2020-14882（2020-10-21，Oracle 给 9.8，Console 组件，`NVD-CWE-Other`）；CVE-2017-18349（Fastjson <1.2.25，2018-10-23，CWE-20）。
- **JDK / JEP**：JEP 290（JDK 9）、415（JDK 17）、403（JDK 17）、411（JDK 17）、486（JDK 24）、261（JDK 9）、320（JDK 11）、335（JDK 11）、372（JDK 15）、282（JDK 9）的**标题、Status（均 Closed / Delivered）与 Release**；`java.io.ObjectInputFilter` 的契约（docs.oracle.com）。
- **OWASP**：Top 10:2021 与 Top 10:2025 的十条类别名；Deserialization / XXE / SSRF / Injection 四份防护速查页；Juice Shop / Dependency-Check / Dependency-Track 项目页。
- **法律**：法释〔2011〕19 号的**公布日（2011-08-01）、施行日（2011-09-01）、文号**与第一条/第二条的**内容要点**；《刑法》285/286 条的**条文号**。

**二、本机实测的（全部可在 `code/sec/本机实测输出.txt` 查到，按节名索引）**

- **`SecBoundary（本机 JDK 25 安全画像）` 与其 `--add-opens` 对照组**：Security Manager 为 `null`、`setSecurityManager` 抛 `UnsupportedOperationException`；`jdk.internal.misc.Unsafe` 的 `InaccessibleObjectException` 与松动后的成功对照；反射改 `String.value` 的前后对照；**可用脚本引擎数 = 0**；常见攻击面 API 的存在性清单。
- **`SecDeserBasic（反序列化会执行代码：最小复现）`**：字节数 79；`readObject` 触发 1 次；`PriorityQueue` 1/2/4 → `compareTo` **0/1/3** 次；`HashMap` 1/4/16 → `hashCode`+`equals` **1/4/16** 次。
- **`SecDeserFilter（无进程级过滤器）` / `SecDeserFilter（带 -Djdk.serialFilter 的进程级过滤器）`**：载荷 45 字节；不加过滤器 → 副作用 1 次；流级与进程级过滤器 → **`InvalidClassException -> filter status: REJECTED`、副作用 0 次**；进程级规则字符串与语法说明。
- **`SecClassLoader（内存马原理：从字节数组定义类）`**：双亲委派前后「是不是同一个类」的 true/false/false；绕委派类的 `CodeSource = (null <no signer certificates>)` 与正常类的 `file:...` 对照。
- **`SecDangerApis（常见攻击面 API 的默认行为）`**：`cmd /c echo` 输出与退出码 0、数组形式可用；**XXE 默认展开外部实体**（读到 `SECRET-CONTENT-1234567890`）与加固写法的拒绝原文；`XMLDecoder` 的 setter 被调用 1 次；Nashorn 返回 `null`；路径遍历读到 `outside` 与 `normalize().startsWith()` 判据。
- **`SecJndi（JNDI 注入：默认会不会去下载远程类）`**：三个 `trustURLCodebase` 属性显示「未设置」；路径 A / B / 开关打开的**三次 HTTP 请求数均为 0**；两个内部类的可加载性。**测试局限已写在 §4.2**（路径 B 拿回来的是普通 RMI 代理，没走到 `Reference` 解码）。
- **`javaagent（…）`**：`premain` 执行、已加载类 **904**、可重定义 true；补丁字节数 **1871**；`redefineClasses` 完成且**磁盘无改动**；不带 `-javaagent` 的对照输出。
- **`【体检批次】`（脚本 `code/sec/checkup.sh`，本文写作时新跑的一批，追加进同一份日志）**：`javac -Xlint:all` 的 **3 个警告**（`[removal]`×2、`[serial]`×1）；`jdeprscan --for-removal` 的 **2 条命中**；**`jdeps --jdk-internals` 空输出**；`jdeps -summary` 的模块依赖面（含 `java.instrument`/`java.xml`/`java.desktop`）；`javap -p` 看到 `readObject`/`compareTo`/`hashCode`；`jcmd` 的 `VM.command_line`（`jvm_args: … -Djdk.serialFilter=java.base/*;!*`）、`VM.system_properties`（`jdk.serialFilter=java.base/*;\!*`）与 `GC.class_histogram` 前三行；**JFR：`jdk.JavaAgent` 1 次（含 agent 路径）、`jdk.ClassRedefinition` 1 次、`jdk.RedefineClasses` 1 次，`jdk.Deserialization` 计数为 0**；本 JDK 里与安全相关的 **15 个 `jdk.*` JFR 事件类型名**；模块数 **70**、`jlink` 最小镜像 **50M**。
- **环境口径**：JDK **25.0.4.1**（Eclipse Temurin），Windows 11；与 05 篇、索引页同口径。

**三、属本人编排判断的（官方没有这种规定）**

§1「Java 的攻击链天生是**两段式**（入口 + gadget）」这个讲法；§3 索引表的**列的选取与行序**及「本库对应篇目」列的对应关系；§4 每族的四段骨架与族内小节顺序；§5 的**体检清单与判读标准**；§6 把「本机能做的」收敛为「减法 + 思维」；§7 每个案例的「**给初学者的启示**」与「共同点」归纳；§8 防守矩阵的**战术行划分与动作取舍**，以及把 ATT&CK 缓解编号对到 Java 侧动作的映射；§9 用两条 CVSS 向量做对照的讲法；§10 的路线分层与「只给类别不给站点名单」的取舍；**全部练习题、验收标准与时间预算**。

**四、不确定 / 未核对 / 本机做不到的（明确写出来）**

- **本机没有 Maven / Gradle，也没有任何第三方 Java 库**：§6 整节内容（传递依赖、版本收敛、SBOM、SCA、lockfile）**属文档口径，本机一条也没跑过**；只把「模块级依赖清点」与「jlink 裁剪」标为本机可做。
- **JNDI 本机实测的边界**：路径 B **没有走完** `Reference` 解码（拿回来的是普通 RMI 代理），所以「HTTP = 0」**只等于「未触发」**，**不等于**「本机 JDK 一定不会取远程类」（13 篇用 `--add-exports` 让流程真走到解码，拿到的是**明确拒绝**：`ConfigurationException: Remote object factories are not supported`，且不读 `trustURLCodebase`）；三个 `trustURLCodebase` 的**内置默认值本身没有直接读到**（只读到「未设置」）。
- **JFR 的 `jdk.Deserialization`**：本机核对到**该事件类型存在**，但默认设置下的那次录制里**计数为 0**；**「默认就记录反序列化」这句话我没有验证，本文不这样写**。
- **SSRF（§4.6）与 DoS（§4.5 的放大面）**：**本机未做实验**（会真的发起网络请求，与本套笔记「不对外」的纪律冲突），纯机制与防守说明。
- **CVE-2022-22965 与 CVE-2022-22947 的「修复版本号」**：NVD 的 CVE 描述正文里**没有逐字给出**（CVE-2022-22947 写的是「3.1.1+ 与 3.0.7+ 之前」这种范围式表述），**厂商公告页不在本文允许引用的域名清单里**，所以**只写「升级到修复版本，以厂商公告为准」**，不转抄具体补丁版本。
- **CVE-2019-2725 与「T3/IIOP 反序列化」的关系**：NVD 的 CWE 给的是 **CWE-74**、描述写的是「Web Services 子组件」；**把它等同于 T3 反序列化的说法我没有在 NVD 上核到**，所以 §7.6 只把它写成「同一批 WebLogic 未授权远程代码执行问题」，把「T3 协议 + 序列化对象」这一点归给**核对过的 CVE-2015-4852**。
- **ATT&CK Navigator 的具体操作**、**公开靶场/CTF 站点的当前可达性**：**未逐个核对**，本文只给类别与官方入口域名。
- **法律部分**：本文**只引条文号**（《刑法》285/286、法释〔2011〕19 号），**不解释条文、不做适用判断**——**这是边界说明，不是法律意见**；法释的公布/施行日期与第一条/第二条要点来自公开检索所得的两高公报文本，**具体适用请查官方发布页**。
- **一个容易被当成「违规外链」清掉的东西**：§4.5 那张表里 `http://apache.org/xml/features/disallow-doctype-decl` **不是可访问的链接，是 XML 解析器的 feature 标识字符串**（照本机日志原文引用）；它与 05 篇 §依据说明 里讲过的 `xmlns`/`xsi:schemaLocation` 是同一类东西，**删掉它就等于改写了实测原文**。
- **本文不包含**：任何针对真实系统或第三方软件的利用步骤、任何可用载荷、任何绕过检测/免杀的技巧。**所有实验都在本机自建靶子上做。**

---

上一篇 → [[11-Java漏洞利用实践 反序列化与类加载（本地靶场）]] · [[00-Java索引]] · 深入靶场 → [[13-Java运行期植入与字节码改写（深入靶场）]]
