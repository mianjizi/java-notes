---
tags: [Java, 学习路线, 索引]
created: 2026-10-01
环境: Windows 11 + Eclipse Temurin JDK 25.0.4.1 LTS（OpenJDK 64-Bit Server VM / HotSpot，class 文件主版本 69.0）—— 2026-10-01 本机实测
---

# Java · 学习索引

> 一句话：Java 拿"**保守的语法**"换了"**极度成熟的运行时**"——类型宽度、溢出行为、越界检查、并发可见性全写进规范里，所以**没有未定义行为**；跨平台由虚拟机保证；出问题时有异常栈和一整套自带诊断工具接手。本套笔记按"**先用起来 → 建对象模型 → 管住数据与并发 → 上工程**"组织：五篇主课 + 四篇查证，另配**安全专题四卷**（10 机制 / 11 本地靶场 / 12 攻击面全景 / 13 深入靶场：运行期附加与字节码改写），把「会写 Java」拉到「看得懂攻击面」。
> **本套笔记的定位**：学习用，不是可以粘贴交差的成品。每篇的「练习」只给**任务 + 验收标准**，代码由你自己写；示例代码只示范语法形态，不构成答案。
> **主要参考**：JLS SE25（语言规范）、JVMS SE25（虚拟机规范）、JDK 25 API 文档与开发指南、OpenJDK JEP 索引；逐篇「出处」列出，链接均为 2026-10-01 实测可达。

## 一、学习地图（五篇主课 + 四篇查证 + 安全专题四卷）

| 序 | 笔记 | 建议时间 | 学完能做到 | 自检（做不到就先别往下走） |
|---|---|---|---|---|
| 1 | [[Java/01-阶段一 环境与语言基础]] | 2 周 | 从 `.java` 一路跑到运行，写得出带方法、数组、输入输出的完整小程序 | 能说清 `.java` → `.class` → JVM 各是什么；能论证「Java 只有值传递」；能把中文乱码按环节修好 |
| 2 | [[02-阶段二 面向对象与核心类库]] | 3 周 | 用类和接口描述问题，会写异常处理，看懂 `equals`/`hashCode` 契约 | 能解释为什么改了 `equals` 必须改 `hashCode`；能说清受检异常在要求你做什么 |
| 3 | [[03-阶段三 集合框架与泛型]] | 3 周 | 按复杂度挑容器，用 Stream 表达意图，看懂泛型的边界 | 能说出 `ArrayList` 随机访问与 `HashMap` 查找的均摊复杂度；能举出类型擦除带来的三个限制 |
| 4 | [[04-阶段四 并发与JVM]] | 3~4 周 | 正确共享状态，看懂对象住在哪、GC 在回收什么 | 能说出 happens-before 是「判断能不能看到」的尺子；能用 `jcmd`/`jstack` 看一个进程的堆与线程 |
| 5 | [[05-阶段五 工程实践与生态]] | 2~3 周 | 打 jar、写测试、理解构建与依赖，并用 JDK 自带工具完成一次交付 | 能解释 Maven/Gradle 各自解决什么问题；能在没有第三方库的机器上完成「编译→测试→打包→运行」 |
| — | [[06-Java的前世今生与生态脉络]] | 2 小时，可随时读 | 知道这门语言从哪来、今天的地盘在哪 | 能说出 Java 最初是为哪类设备设计的；能解释「语言 / 虚拟机 / 平台」不是一回事 |
| — | [[07-关键字与版本差异速查]] | 随查随看 | 51 个保留关键字、17 个上下文关键字、特性与 JEP 的对应 | 能说出保留关键字与上下文关键字的**判断依据**；能给出任意一个特性的 JEP 编号 |
| — | [[Java/08-常见报错速查]] | 随查随看（**报错时第一个去的地方**） | 认出编译期与运行期两类故障的类型，会读栈 | 看到 `错误: 找不到符号` 会先读 javac 补的 `符号:` / `位置:` 两行 |
| — | [[09-标准类库全景速查]] | 随查随看 | 70 个模块与 58 个 `java.base` 导出包的全景、按需求反查包 | 能立刻判断「JSON 解析 / 数据库驱动 / 测试框架」在不在标准库里 |
| — | [[10-Java安全研究视角 从会写到看懂字节码与攻击面]] | 3~4 小时 | 看懂 Java 的攻击面在哪：字节码、类加载、反序列化、JNDI、agent | 能用自己的话讲清「反序列化为什么等于执行代码」；能说出本机哪些安全能力已经被禁用 |
| — | [[11-Java漏洞利用实践 反序列化与类加载（本地靶场）]] | 4~6 小时（含动手） | 在本机自建无害靶场上把反序列化 / 类加载 / agent 三条原理跑通 | 能自己做出「只在反序列化阶段触发」的对照实验；能给载荷装上 JEP 290 过滤器并证明拦住了 |
| — | [[12-Java安全视角 ATT&CK与Java漏洞族]] | 3~4 小时 | 把 Java 缺陷类别对上 CWE / ATT&CK / 真实案例，形成防守矩阵 | 能讲清一条 CVE 的根因、影响面与修复手段（而不是背 payload） |
| — | [[13-Java运行期植入与字节码改写（深入靶场）]] | 5~7 小时（含动手） | 把「运行期植入」真做出来：不重启换实现、进程内改字节码，并学会从 JFR 找痕迹 | 能说出 `premain` 与 `agentmain` 的四个区别；能解释为什么「字符串过滤器里写不出数组描述符」 |

**顺序不能跳的两处理由**：① **不弄懂「引用语义 + `equals`/`hashCode` 契约」就上集合，等于抄代码**——那时每个「找不到元素」「去重失效」的 bug 都会变成玄学；② **不弄懂「共享可变状态」就上线程池，只会把偶发故障归因于玄学**——竞态按概率发作（见 04 篇四批实测读数），"上次跑对了"从来不是证据。

## 二、本机环境口径（2026-10-01 实测，不是网上抄的）

| 项目 | 实测值 |
|---|---|
| 发行版 | **Eclipse Temurin 25.0.4.1+1 LTS**（`java.version` = `25.0.4.1`，`java.version.date` = 2026-08-18；OpenJDK 64-Bit Server VM，mixed mode, sharing） |
| 安装方式与位置 | `winget install EclipseAdoptium.Temurin.25.JDK` → `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot`（**整个目录 291 MB**） |
| 环境变量 | 安装器把 `JAVA_HOME` 与 `<JDK>\bin` 写进 **HKLM（机器级）**；**已经开着的终端不会刷新**，要新开一个才有 `java` |
| class 文件版本 | `java.class.version` = **69.0**（主版本 69；每经过一个大版本加一） |
| 模块 | `java --list-modules` 共 **70 个**；`java.base` 导出 **58 个包**；运行时引导层 `ModuleLayer.boot().modules().size()` = 62 |
| **编码三件套** | `file.encoding` = **UTF-8**、`Charset.defaultCharset()` = UTF-8；但 `native.encoding` = **GBK**、`stdout.encoding` = **GBK**、`stderr.encoding` = **GBK**、`stdin.encoding` = ms936（探针日志里 **`ProbeDefaultEncoding` 一节**是不带任何编码开关跑的，那几行就是这些默认值） |
| 默认垃圾回收器 | **G1**（三个 collector 名：`G1 Young Generation` / `G1 Concurrent GC` / `G1 Old Generation`） |
| CPU 与内存 | `availableProcessors` = 8；`Runtime.maxMemory()` ≈ **8120 MiB**；启动初 `totalMemory()` = 512 MiB；`ForkJoinPool.commonPool().getParallelism()` = 7 |
| 虚拟线程 | 可用；实例类名 `java.lang.VirtualThread`；**50000 个任务全部 join，四批实测 89 / 122 / 185 / 103 ms** |
| 自带工具 | `javac` `java` `javap` `jar` `jshell` `javadoc` `jlink` `jpackage` `jcmd` `jconsole` `jdb` `jmap` `jstack` `jfr` 全在 `bin\` 下 |
| `jlink` 的实测意外 | 本机 JDK **没有 `jmods\` 目录**，但 `jlink` 可用：`jlink --add-modules java.base --output <目录>` 退出码 0，产出**最小镜像 50 MB**（对照完整 JDK 291 MB）。原因是 **JEP 493（无需 JMOD 链接运行时镜像）** |
| 第三方依赖 | **本机没有 Maven、Gradle，也没有任何第三方 Java 库** → 05 篇里凡需要引依赖的示例都标了「本机无法直接运行」 |
| 安全画像（10 篇用） | `System.getSecurityManager()` = **null**；`setSecurityManager` 抛 `UnsupportedOperationException: Setting a Security Manager is not supported`（**Security Manager 已永久禁用**）；`jdk.serialFilter` **未设置**（没有进程级反序列化过滤器）；可用脚本引擎数 = **0** |
| 安全画像：模块封装 | 默认 `jdk.internal.misc.Unsafe`、`java.lang.String.value` 反射都抛 `InaccessibleObjectException`；加 `--add-opens` 后**可以访问**（而 `sun.misc.Unsafe` 反射**两次运行都能拿到**） |
| 安全靶场 | `Java/code/sec2/`（**深度靶场**）：运行期附加（`jdk.attach`）/ 进程内改字节码（Class-File API）/ 过滤器进阶 / JNDI 解码路径四组实验 —— 关键读数：JFR `jdk.JavaAgent.dynamic=true`（运行期）vs `false`（启动期）、1865→1879 字节的原地改写、本机 RMI provider **无条件**拒绝远程对象工厂 |
| 安全靶场（基础） | `Java/code/sec/`：机制演示 **9 段**（`run.sh`）+ 只读体检 **8 段**（`checkup.sh`），同一份日志**按批次追加** —— 反序列化钩子与容器回调计数、JEP 290 过滤器拦截、`defineClass` 的 `CodeSource=null`、`javaagent` 运行期 `redefineClasses`、XXE 默认展开实体、路径遍历、JNDI 两条路径均未下载远程类（测试局限见 11 篇第 8 节）；体检段用 `javac -Xlint` / `jdeprscan` / `jdeps` / `javap` / `jcmd` / `jfr` / `jlink` 做静态与运行期清点 |
| IDE | **本机没装任何 Java IDE**（JetBrains / Eclipse / VS Code 均无）。本套材料的口径是**命令行 + 自带工具**，不走 IDE 路径 |
| 单文件源码直跑 | 可用（JEP 330）：`java Hello.java` → `你好，Java 25` |
| 字节码观察 | `javap -c` 可见字符串拼接编译成 `invokedynamic ... makeConcatWithConstants`（JEP 280） |

**四条实测踩坑（都已验证，别再踩）**：

1. **`javac` 的报错默认是 GBK 字节**，而源码是 UTF-8 → **报错行回显的源码里的中文变乱码**。修法：`javac -encoding UTF-8 -J-Dstderr.encoding=UTF-8 …`（本机 git-bash 里连 `iconv` 都没有，只能让 JVM 自己换编码）。
2. **`stdout` 与 `stderr` 是两个独立编码设置**：只写 `-Dstdout.encoding=UTF-8` 时，`System.out` 的中文正常了，但**未捕获异常栈（走 stderr）里的中文仍然是乱码**。两个都要加。
3. **NPE 提示里的变量名靠 `-g`**：默认编译只给出 `because "<local0>" is null`，加 `-g` 才变成 `because "s" is null`（变量名来自 `LocalVariableTable`）。
4. **PATH 刷新的老坑**：装完 JDK 后**当前终端敲不出 `java`**，这不代表装失败——新开终端即可（与 C 工具链那边是同一类问题，成因不同）。

**探针的读数会变，别当常量引用**：`Java/code/probe/本机实测输出.txt` 是**追加**写入的，同一脚本连续四批的无同步 `i++` 读数分别是 `52200 / 60365`、`60198 / 74237`、`81554 / 177331`、`54980 / 59719`（期望 200000；哈希全冲突那项四批是 1069 / 1118 / 1136 / 1427 ms），而 `AtomicInteger` 每批都是 200000。这组差异本身就是 04 篇的教学素材。（引用的这四批在日志里都能查到；`run.sh` 每重跑一次就再追加一批，日志里的批数会多于这里引用的——条数不同属正常，数值对得上才算数。）

## 三、资料总表（按可信度排序，本页为准）

| 级别 | 资料 | 适合 | 链接 |
|---|---|---|---|
| ★★★ **判据** | Java 语言规范 JLS SE25 | 有争议时以此为准；查语法、异常、并发可见性（第 17 章） | https://docs.oracle.com/javase/specs/jls/se25/html/index.html |
| ★★★ **判据** | Java 虚拟机规范 JVMS SE25 | 字节码、类文件格式、加载与初始化 | https://docs.oracle.com/javase/specs/jvms/se25/html/index.html |
| ★★★ **权威解释** | JDK 25 API 文档 | 全程查类与方法语义 | https://docs.oracle.com/en/java/javase/25/docs/api/ |
| ★★★ **权威解释** | `java.base` 模块摘要（导出包一览） | 想知道某个类属于哪个包 | https://docs.oracle.com/en/java/javase/25/docs/api/java.base/module-summary.html |
| ★★★ **权威解释** | Java SE 25 开发指南 | 编译、执行、类路径、打包的操作口径 | https://docs.oracle.com/en/java/javase/25/core/ |
| ★★★ **权威解释** | 执行与类路径 | `-cp`、`-d`、jar、模块路径怎么用 | https://docs.oracle.com/en/java/javase/25/core/execution.html |
| ★★★ **权威解释** | 语言变更与迁移指南 | 跨版本时看「哪些变了、哪些被移除」 | https://docs.oracle.com/en/java/javase/25/language/java-language-changes.html · https://docs.oracle.com/en/java/javase/25/migrate/ |
| ★★★ **权威解释** | JVM 指南 / GC 调优 / 故障排查 | 内存结构与回收、`-Xlog`、诊断工具 | https://docs.oracle.com/en/java/javase/25/vm/ · https://docs.oracle.com/en/java/javase/25/gctuning/ · https://docs.oracle.com/en/java/javase/25/troubleshoot/ |
| ★★★ **权威解释** | OpenJDK JEP 索引 | **判断某个特性是「正式 / 预览 / 孵化」** | https://openjdk.org/jeps/0 |
| ★★★ **安全判据** | JEP 290 反序列化过滤器 | 过滤器的语法与三种作用范围 | https://openjdk.org/jeps/290 |
| ★★★ **安全判据** | JEP 403 / 411 / 486 | 强封装、Security Manager 弃用与永久禁用 | https://openjdk.org/jeps/403 · https://openjdk.org/jeps/411 · https://openjdk.org/jeps/486 |
| ★★★ **安全判据** | JEP 335（移除 Nashorn） | 「用脚本引擎执行代码」这条老路为什么走不通 | https://openjdk.org/jeps/335 |
| ★★★ **安全参考** | MITRE ATT&CK / CWE / NVD | 战术技术编号、缺陷类别、漏洞条目（12 篇用） | https://attack.mitre.org/ · https://cwe.mitre.org/ · https://nvd.nist.gov/ |
| ★★★ **安全参考** | OWASP | Java 侧常见缺陷类别与防护清单 | https://owasp.org/www-project-top-ten/ |
| ★★★ **权威解释** | OpenJDK JDK 25 项目页 | 本机这一版包含什么、何时 GA | https://openjdk.org/projects/jdk/25/ |
| ★★ 教程 | dev.java 学习站 | 官方出品的入门到进阶读物 | https://dev.java/learn/ · https://dev.java/learn/language-basics/ |
| ★★ 教程 | 官方旧教程（Trail） | 过时但结构清楚的经典读物 | https://docs.oracle.com/javase/tutorial/ |
| ★★ 参考 | HotSpot 组页面 | 想跟进运行时与 GC 的动向 | https://openjdk.org/groups/hotspot/ |
| ★★ 参考 | JCP（Java 社区进程） | JSR 与规范流程 | https://jcp.org/en/home/index |
| ★★ 参考 | Eclipse Adoptium | 免费 JDK 发行版（本机装的就是它） | https://adoptium.net/ |
| ★★ 参考 | Oracle Java 下载页 | 官方发行版入口 | https://www.oracle.com/java/technologies/downloads/ |
| ★★ 参考 | OpenJDK 源码仓库 | 想读实现 | https://github.com/openjdk/jdk |
| ★ 工具 | Maven 指南 / POM 参考 | 构建工具（本机未装） | https://maven.apache.org/guides/ · https://maven.apache.org/pom.html |
| ★ 工具 | Gradle 用户手册 | 构建工具（本机未装） | https://docs.gradle.org/current/userguide/userguide.html |
| ★ 工具 | JUnit / Mockito | 测试（本机未装） | https://junit.org/ · https://site.mockito.org/ |
| ★ 工具 | Log4j2 / H2 / Jakarta EE / Spring Boot | 日志、嵌入式数据库、企业规范与框架 | https://logging.apache.org/log4j/2.x/ · https://www.h2database.com/html/main.html · https://jakarta.ee/ · https://spring.io/projects/spring-boot |
| ★ 工具 | javadoc.io | 第三方库文档聚合（按坐标跳） | https://javadoc.io/ |
| ☆ 参考（**不得当依据**） | 菜鸟教程 Java | 快速找线索；结论必须回到上面一级/二级核对 | https://www.runoob.com/java/java-tutorial.html |
| ☆ 背景（二手） | 中文维基 Java 条目 | 只作背景；编年要交叉核对 | https://zh.wikipedia.org/wiki/Java |

**本机实测不可达、已排除**（`curl -A <真实浏览器 UA>` 实测的状态码）：`inside.java`（000 超时）、`wiki.openjdk.org`（403）、`zh.cppreference.com/w/java`（403）、`liaoxuefeng.com`（302 跳转）——**别当参考链接用**。
**说明**：`https://openjdk.org/projects/jdk/25/` 首次请求曾超时（000），重试返回 200，已按可达收录。

## 四、学习纪律（自己给自己定的规矩）

1. **手敲，不粘贴**。Java 的报错密度比 C 低，这意味着"看着会"与"写得出"之间的差距**更不容易被发现**。
2. **编译命令固定带上编码与 lint**：

   ```bash
   javac -encoding UTF-8 -J-Dstderr.encoding=UTF-8 -Xlint:all -d <输出目录> *.java
   java  -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp <输出目录> <主类>
   ```

   等到觉得"能跑就行"的时候，再加 `-Werror` 逼自己清零警告；调试期另外加 `-g`（NPE 才说得清变量名）。
3. **每阶段必须有能跑的程序**。产物写进第五节的表。读完整本书却写不出 200 行程序，是学 Java 最常见的失败方式。
4. **报错先分类再动手**：有没有 `.class` 产出？是 `错误:` 还是异常类名？有没有栈？（01 篇与 08 篇提供判据）
5. **编码永远显式指定**。`Files.readString(path, StandardCharsets.UTF_8)`、`new InputStreamReader(in, UTF_8)`——不依赖平台默认，哪怕本机的默认已经是 UTF-8。
6. **并发问题不做"多试几次就好了"的调试**。竞态按概率发作；本机四批实测读数从 52200 到 177331 就是证据。先问"这份状态能不能不共享"，再谈加锁。
7. **`catch` 不是消音器**。能处理就处理，不能处理就让它带着上下文往上抛（`throw new XxxException("说明", e)`），不要 `catch (Exception e) {}`。
8. **AI 辅助的边界**：可以让 AI 解释报错、解释概念、审阅你写的代码；**不要让 AI 写你要学的那一段**——那等于跳过练习。这条对本人同样适用。

## 五、我的进度与产物

| 序 | 阶段 | 开始日期 | 状态 | 产物位置 |
|---|---|---|---|---|
| 1 | 环境与语言基础 | | 未开始 | |
| 2 | 面向对象与核心类库 | | 未开始 | |
| 3 | 集合框架与泛型 | | 未开始 | |
| 4 | 并发与JVM | | 未开始 | |
| 5 | 工程实践与生态 | | 未开始 | |

## 六、本套材料的文件结构

```
Java/
  ├─ 00-Java索引.md                    本页：学习地图 / 环境口径 / 资料总表 / 纪律 / 进度
  ├─ 01-阶段一 环境与语言基础.md       编译运行三路径、包与类路径、类型、方法、字符串、I/O 与编码
  ├─ 02-阶段二 面向对象与核心类库.md   类与对象、继承与接口、Object 契约、lambda、异常、record/sealed
  ├─ 03-阶段三 集合框架与泛型.md       复杂度总表、List/Set/Map/Queue、equals 契约、泛型擦除、Stream
  ├─ 04-阶段四 并发与JVM.md            线程与虚拟线程、竞态与 happens-before、并发工具箱、GC、诊断工具
  ├─ 05-阶段五 工程实践与生态.md       模块、打包与分发、构建工具、测试、日志、JDBC、代码质量、版本升级
  ├─ 06-Java的前世今生与生态脉络.md    史料篇：1991→2026、语言与平台的分层、生态脉络（三张插图）
  ├─ 07-关键字与版本差异速查.md        速查篇：51 个保留关键字 + 17 个上下文关键字、特性 → JEP 对照
  ├─ 08-常见报错速查.md                速查篇：编译期七类错误的中英原文、警告、编码乱码、栈怎么读
  ├─ 09-标准类库全景速查.md            速查篇：70 个模块、java.base 的 58 个导出包、按需求反查
  ├─ 10-Java安全研究视角 从会写到看懂字节码与攻击面.md  安全专题（一）：字节码 / 攻击面 / 边界
  ├─ 11-Java漏洞利用实践 反序列化与类加载（本地靶场）.md  安全专题（二）：本地靶场动手
  ├─ 12-Java安全视角 ATT&CK与Java漏洞族.md            安全专题（三）：攻击面地图 × 真实案例 × 防守
  ├─ 13-Java运行期植入与字节码改写（深入靶场）.md       安全专题（四）：运行期附加 / 进程内改字节码 / 过滤器进阶 / JNDI 解码路径
  ├─ figs/                             插图（06 篇使用）与生成脚本 make_figs.py
  └─ code/
       ├─ hello/Hello.java             最小示例（单文件源码直跑）
       ├─ probe/                       环境与语言行为探针（7 个）+ run.sh + 本机实测输出.txt
       ├─ errors/                      编译期/运行期报错样例（10 个）+ run.sh + 本机实测输出.txt
       ├─ sec/                         安全靶场（9 个类：安全画像 / 反序列化 / 过滤器 / 类加载 / agent / 危险 API / JNDI）
       │                                 + run.sh（9 段机制演示）+ checkup.sh（8 段体检）+ 本机实测输出.txt
       ├─ sec2/                        深度靶场（8 个类：目标 / 附加方 / 两个 agent / 过滤器 ×2 / JNDI ×2）+ run.sh + 本机实测输出.txt
       └─ audit.py                     本目录自检：内链 / 外链白名单 / 禁用写法 / 结构四件套
```

每篇结构统一：心智模型 → 要点分节 → **练习（只给任务与验收标准）** → 自我检查清单 → 出处 → 依据说明（分清"有官方依据""属本人编排判断""二手需谨慎"）。
版本与工具链数字取自 **2026-10-01 本机实测**（JDK 25.0.4.1、class 版本 69、模块数、编码三件套、GC、虚拟线程与并发读数），不是网络文章转述；语言与平台相关内容逐条对应 JLS/JVMS 条款或 JEP 编号。
`code/probe/run.sh`、`code/errors/run.sh`、`code/sec/run.sh`（机制演示）与 `code/sec/checkup.sh`（JDK 自带工具体检）都可原样重跑，重跑会往同一份 `本机实测输出.txt` **追加**一批新读数（并发数字、进程号、JFR 时间戳逐批不同，属正常——**只要笔记引用的那几批在日志里查得到，读数对得上就算数**）。改完笔记跑一次 `py -3.11 Java/code/audit.py`：内链解析、外链白名单、禁用写法、结构四件套一次查完。

## 七、与库内其它内容区的关系

| | 本目录 | [[00-C语言索引]]（C语言/） | [[00-前端索引]]（Web前端/） |
|---|---|---|---|
| 读者 | 自己学 | 自己学 | 自己学 |
| 组织 | 五篇主课 + 四篇查证（史料 / 关键字 / 报错 / 类库） | 五篇主课 + 四篇查证 | 四阶段六篇 |
| 工具链口径 | JDK 25（Temurin）+ 命令行，无 IDE | Code::Blocks 自带 MinGW | Node 与浏览器 |
| 与本目录的互补 | —— | 讲**机器语义与内存**；本套讲**类型系统与运行时** | 讲**平台与工程**；本套的 05 篇与之对读很有用 |
| 安全专题的平行先例 | **四卷**（10 机制 / 11 靶场 / 12 攻击面全景 / 13 深入靶场），只用本机 JDK 与自建无害靶子 | **三卷**（11 安全研究视角 / 12 漏洞利用实践 / 13 ATT&CK×二进制漏洞） | 前端安全专章（22 篇） |

本目录是**自学区**，与库内其它科目目录互不相干，配套脚本也不会往这里写东西。

---

相关：[[Java/01-阶段一 环境与语言基础]] · [[06-Java的前世今生与生态脉络]] · [[07-关键字与版本差异速查]] · [[Java/08-常见报错速查]] · [[09-标准类库全景速查]] · 安全专题：[[10-Java安全研究视角 从会写到看懂字节码与攻击面]] · [[13-Java运行期植入与字节码改写（深入靶场）]] · 平行学习区：[[00-C语言索引]]、[[00-前端索引]]
