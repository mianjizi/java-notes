---
tags: [Java, 并发, JVM, 阶段四]
created: 2026-10-01
阶段: 4 并发与JVM
主要参考: Java 语言规范 JLS SE25 第 17 章 · Java 虚拟机规范 JVMS SE25 第 2/5 章 · JDK 文档（工具链、JVM 指南、GC 调优指南）
---

# 阶段四 并发与JVM

> 一句话：并发要建的是「**一次写、另一次读，到底能不能看到**」这个判断力——尺子只有一把，叫 happens-before；JVM 要建的是「**对象住在哪、什么时候被回收**」这个空间模型。两者合起来决定你遇到的是 `OutOfMemoryError`、`StackOverflowError`，还是「日志一条报错都没有，但算出来的数字就是不对」。
> 主要参考：JLS SE25 第 17 章（Threads and Locks）、JVMS SE25 第 2 章（运行时数据区）与第 5 章（加载/链接/初始化）、JDK 25 自带的 `jcmd`/`jstack`/`jmap`/`jfr`/`javap`，以及官方 JVM 指南、GC 调优指南、故障排查指南。
> 时间预算：4 周（每天 1.5~2 小时）。判定标准不是「看完了」，是**能用只靠 JDK 自带工具的四个实验，把竞态、死锁、GC 停顿、引用泄漏各复现一次，并说清每次的判据**。

## 1. 心智模型

```text
一个 JVM 进程
├─ 堆 Heap（线程共享）                new 出来的对象；-Xmx / -Xms；GC 的主战场
├─ 方法区 = 元空间 Metaspace（共享）    类元数据、运行时常量池；-XX:MaxMetaspaceSize
├─ 代码缓存 Code Cache（共享）         JIT 编译后的机器码；满了 JIT 停手，程序变慢但不报错
├─ 直接内存（堆外、共享本地内存）        ByteBuffer.allocateDirect；-Xmx 管不到
└─ 每个线程各有一套私有区
   ├─ 虚拟机栈：栈帧 = 局部变量表 + 操作数栈 + 返回地址；深度由 -Xss 影响
   ├─ 本地方法栈
   └─ PC 寄存器
```

1. **并发的所有麻烦都来自「共享可变状态」**。不共享、或共享但不可变，就没有并发 bug。所以遇到问题的第一反应永远不是「加锁」，而是「这一份状态能不能不让两个线程同时碰」。
2. **判断「能不能看到」只有一把尺子：happens-before（JLS 17.4.5）**。它不规定谁先执行，只保证「前面那次写的结果，后面那次读必须看到」。两个操作之间没有 hb 关系就是 data race，此时读到的值**规范上允许是任意值**。
3. **「我刚跑了一次是对的」不是证据**。竞态按概率发作：本机实测同一段无同步自增，连续四批运行的两轮读数分别是 `52200` / `60365`、`60198` / `74237`、`81554` / `177331`、`54980` / `59719`（期望都是 200000）——最好的一批也只到 177331。这和 C 里的 UB 是同一种性质——不能因为这次结果漂亮就认为代码对。
4. **报错名就是排查入口**：堆爆是 `OutOfMemoryError: Java heap space`，栈太深是 `StackOverflowError`，类太多是 `OutOfMemoryError: Metaspace`，堆外是 `OutOfMemoryError: Direct buffer memory`。名字不同，去查的东西完全不同。
5. **GC 只回收「不可达」的对象**。只要还有任何一条活引用链牵着你想要的那块内存，它就不会被回收——Java 里的「内存泄漏」精确地说就是「你还在持有引用」。

## 2. 线程与任务：Thread / Runnable / Callable / Future / ExecutorService

C 里你 `pthread_create` 拿到的是操作系统线程句柄，线程既是「要干的事」也是「调度单位」。Java 把这两件事拆开了：**任务**（`Runnable` / `Callable`）与**执行者**（`Thread` / `ExecutorService`）。这个拆分的直接好处是任务可以被不同的策略执行——同一段逻辑今天跑在单线程里，明天跑在线程池或虚拟线程上，代码不用改。

| 形态 | 它是什么 | 关键点 |
|---|---|---|
| `Thread` | 一条平台线程，默认与 OS 线程 1:1 | 直接 `new Thread` 意味着「每来一个任务开一条 OS 线程」，规模一上来就顶不住 |
| `Runnable` | `void run()`，无返回值、不能抛受检异常 | 想带返回值必须换 `Callable` |
| `Callable<V>` | `V call() throws Exception` | 有返回值、能抛受检异常，配合 `Future` |
| `Future<V>` | 一张「稍后取货」的凭据 | `get()` 阻塞；`cancel` 只是尝试中断；**异常被包在 `ExecutionException` 里** |
| `ExecutorService` | 执行者：线程池、单线程、虚拟线程执行器都是它的实现 | 用它而不是 `new Thread`，你才能控制并发度、队列、拒绝行为 |

```java
import java.util.concurrent.*;

public class TaskShape {                 // 只示范「提交—同时起跑—取结果」的形状
    public static void main(String[] args) throws Exception {
        var start = new CountDownLatch(1);           // 让所有线程尽量同时开始
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 4; i++)
                futures.add(pool.submit(() -> { start.await(); return 1; }));   // Callable<Integer>
            start.countDown();
            int sum = 0;
            for (var f : futures) sum += f.get();    // get() 会阻塞到该任务结束
            System.out.println(sum);
        }                                            // try-with-resources：退出时关闭并等待结束
    }
}
```

### 2.1 提交任务的几个入口

| 入口 | 语义 | 什么时候用 |
|---|---|---|
| `execute(Runnable)` | 只管跑，异常交给线程的 `UncaughtExceptionHandler` | 不关心结果，但要「出错就报」 |
| `submit(...)` | 返回 `Future`，异常装在里面 | 需要结果或需要捕捉异常——**必须把返回的 `Future` 用掉** |
| `invokeAll(tasks)` | 等**所有**任务结束，返回 `List<Future>` | 一批任务要全部完成才能继续 |
| `invokeAny(tasks)` | 任意一个成功就返回，并取消其余 | 冗余查询、抢答式超时 |
| `ScheduledExecutorService` | 延迟执行一次 / 周期性执行 | 定时任务；周期任务抛异常会**静默取消**后续调度 |

示例里那个「先 `await` 再 `countDown`」的技巧值得单独说一句：它的价值不是表演同时开始，而是**把竞态窗口拉到最大**。不加这道闸门，线程起跑的时间差会掩盖掉相当一部分丢更新，你测出的数字会变好看，甚至偶然一次完全不丢——于是你会误以为代码是对的。

### 2.2 虚拟线程：它便宜的到底是什么

虚拟线程是**由 JVM 调度、跑在少量「载体线程」上的线程**，栈按需分配在堆上，所以单个虚拟线程的启动与管理成本远低于平台线程。本机实测：用 `Executors.newVirtualThreadPerTaskExecutor()` 提交 50000 个任务并全部取回结果，连续四批耗时 **89 ms / 122 ms / 185 ms / 103 ms**，实现类是 `java.lang.VirtualThread`（本机实测；四批读数都记在 `Java/code/probe/本机实测输出.txt` 里）。

| 现象 | 不弄懂会翻车在哪 |
|---|---|
| 平台线程要真实 OS 栈 | 起几万个平台线程会撞上 `OutOfMemoryError: unable to create native thread`（本机未实测具体阈值，`-Xss` 与系统限制都会影响，自己加数试） |
| 虚拟线程解决的是**等待**，不是计算 | 拿它跑 CPU 密集任务，吞吐不会变好——总核数就那么几个（本机 8） |
| 虚拟线程是廉价的，所以「一任务一线程」是推荐用法 | 别把虚拟线程塞进池里复用，那等于把它变回平台线程，白拿成本 |
| 「每个线程一份」的东西会乘以任务数 | 如果每个虚拟线程各持有一份 `ThreadLocal`，任务数上去后这块内存会被放大 |
| 阻塞在什么上会决定扩展性 | 载体线程被卡住时会限制并行度；本机 JDK 25 上的具体表现**以本机实测为准**，换 JDK 要自己验 |

**可复现的验证方式**：同一批任务写两个版本——`newFixedThreadPool(8)` 与 `newVirtualThreadPerTaskExecutor()`。第一轮任务体写 `Thread.sleep(200)`（等待型），第二轮把任务体改成纯计算（比如累加）。对比两轮的耗时排序：等待型会拉开很大差距，计算型几乎持平。这个实验比背结论有用，因为它一次说清「虚拟线程是什么场景的工具」。

## 3. 竞态与可见性：`i++` 为什么不可靠

`i++` 不是一条指令，它是三步：**读 i → 加 1 → 写回 i**。三步之间随时可以被切走，两个线程就可能都读到旧值、各自加 1、各自写回，于是一次自增凭空消失。这就是「丢更新」。把交错画出来是这样：

```text
线程 A: 读 i(=0) ─────────────────┐
线程 B: 读 i(=0) ───────────────┐ │
线程 A: 写回 i=1                │ │   两次自增，堆里只多了 1
线程 B: 写回 i=1  <─────────────┘ │
期望 2，实得 1  <─────────────────┘
```

本机实测（2026-10-01）：4 个线程各做 50000 次 `i++`（共享 `static int`，无同步），期望 200000，连续四批的两轮读数分别是 **52200 / 60365**、**60198 / 74237**、**81554 / 177331**、**54980 / 59719**；换成 `AtomicInteger` 后每一批的两轮都恰好是 **200000**。

读这两个数字的方式比数字本身重要：

- **「少了」不是必然，是概率**。数量取决于调度时机与缓存行争用，换台机器、换个循环次数就变。
- **某轮恰好等于 200000 也不代表代码对**。无同步版本完全可能碰巧跑满，这正是并发 bug 难查的原因。
- 数字的**抖动本身**就是判据：同一段代码多轮读数不一致 = 有竞态；`AtomicInteger` 版本多轮恒等于期望值 = 那个变量被正确保护了。

| 手段 | 保证什么 | 不保证什么 | 代价 |
|---|---|---|---|
| 无同步 | 什么都不保证 | 原子性、可见性、顺序 | 零成本，也是唯一的「零正确性」 |
| `volatile` | 单个变量的**可见性**，并禁止相关重排序 | **不保证复合操作原子性**：`volatile int i; i++` 照样丢更新 | 便宜，但别指望它加锁 |
| `synchronized` | 互斥 + 内存语义（解锁 happens-before 后续加锁） | 一次只保护你**用同一把锁**包住的那段；跨多把锁的复合不变式照样破 | 可能阻塞、不可中断、不可超时 |
| `AtomicInteger`（CAS） | 单个变量的「读—改—写」原子 | 多个变量之间的不变式；高竞争下 CAS 自旋会空转 | 单变量场景通常优于锁 |
| `LongAdder` | 高竞争下的**计数**性能更好 | 瞬时值不是精确快照，别拿它做控制流 | 只适合「累加」这类语义 |
| `ReentrantLock` | 同 `synchronized`，另有可中断/超时/公平/多条件队列 | 必须自己 `finally` 解锁，漏了解锁 = 永久卡死 | 代码更长，但诊断信息更好 |

两个很容易踩的补充点：

- **可见性可以单独翻车**。经典形态是：一个线程 `while (running) {}` 空转，另一个线程把 `running = false`。如果 `running` 不是 `volatile`，**规范允许**第一个线程永远看不到这次修改（JIT 把读取提到循环外是合法的）。这个例子的价值在于认识形态，不在于复现——能不能复现取决于优化到什么程度，本机未实测，别拿「我跑了一次它退出了」当反证。
- **非 volatile 的 `long` / `double` 允许被拆成两次 32 位读写**（JLS 17.7，word tearing 是规范明确允许的）。绝大多数 64 位实现上你不会看到撕裂值，但规范没答应你，**以本机实测为准，换平台上写要自己验**。

**可复现的验证方式**：① 用 `javap -c -p` 看你自己写的字段自增——形状是 `getstatic` → `iadd` → `putstatic` 这类「多条指令」，而不是一条原子指令（**以你自己的 javap 输出为准**）；② 把练习 1 的对照表跑出来，多轮读数不一致就是竞态的直接证据。

## 4. `java.util.concurrent` 工具箱

这个包的定位是：把「加锁 + `wait`/`notify` 手写同步」这类容易写错的模式，换成**有名字、有语义保证的组件**。判断该用哪个的标准不是「哪个高级」，而是「我要的顺序与原子性，哪一层能刚好给我」。

| 组件 | 什么时候用 | 翻车点 |
|---|---|---|
| `ReentrantLock` + `Condition` | 需要可中断等待、超时、公平性，或同一把锁上多个等待队列 | 忘记 `finally` 里 `unlock()`；用错 `Condition` 对象的 `await`/`signal` |
| `ReadWriteLock` | 读多写少 | 读锁不是「免费」的；写锁饥饿在默认非公平模式下完全可能 |
| `ConcurrentHashMap` | 多线程共享的映射 | **不允许 null 键/值**（和 `HashMap` 不同）；`size()` 不是并发快照，别拿它做控制流；复合操作要用 `computeIfAbsent`/`merge` 这类原子方法，别 `get` 完再 `put` |
| `Collections.synchronizedMap` | 老代码兼容 | 每个方法一把粗粒度锁，**复合操作不原子**，遍历还得自己同步 |
| `CopyOnWriteArrayList` | 读极多写极少（监听器列表） | 每次写都复制整个数组，写多就是灾难 |
| `BlockingQueue` 家族 | 生产者—消费者 | **容量必须想清楚**：无界队列 = 没有背压，堆会替你把问题攒起来 |
| `CountDownLatch` | 「等 N 件事都做完」 | **一次性**，归零后不能重置（要重置用 `CyclicBarrier`/`Phaser`） |
| `Semaphore` | 限流、限制同时访问资源的数量 | 忘记 `release()` 会永久少一个许可 |
| `CompletableFuture` | 异步编排、组合多步依赖 | 见下面三条 |
| `VarHandle` | 需要 JVM 级别的内存语义操作 | 属于给库作者用的层，日常不要碰 |

### 4.1 `BlockingQueue`：有界才是背压

| 方法 | 队列满/空时 |
|---|---|
| `put` / `take` | **阻塞**，直到有位置/有元素 |
| `offer(e, timeout, unit)` / `poll(timeout, unit)` | 等一段时间后放弃，返回 `false` / `null` |
| `offer` / `poll` | 立刻返回失败 |
| `add` / `remove` / `element` | 立刻抛异常（`IllegalStateException` / `NoSuchElementException`） |

验证方式（本机可做，无需第三方库）：生产者比消费者快，队列容量取 1，程序跑起来后用 `jcmd <pid> Thread.print` 抓快照，你能看到生产者线程停在 `ArrayBlockingQueue.put` 上——这就是「背压」的可视化。把容量改成无界队列再跑，线程不再停在 `put` 上，而堆占用一路涨：**问题没有被解决，只是被搬到了内存里**。

### 4.2 四个协调组件的分工

| 组件 | 语义 | 能不能重用 | 典型错用 |
|---|---|---|---|
| `CountDownLatch` | 一个线程等另外 N 个线程各自报到 | 不能（一次性） | 想「等完再来一轮」却复用同一个 latch |
| `CyclicBarrier` | N 个线程互相等齐，到齐后继续 / 或跑一个汇总动作 | 能 | 参与线程数没配齐 → 永远等不到 |
| `Semaphore` | 限制同时能通过的线程数（许可证） | 能 | 忘了 `release()`，可用许可越来越少 |
| `Phaser` | 分阶段推进、参与方可动态增减 | 能 | 用 `CountDownLatch`/`CyclicBarrier` 硬凑多阶段流程 |

### 4.3 `CompletableFuture`：三条容易静默出错的规则

```java
var cf = CompletableFuture.supplyAsync(() -> 21)
        .thenApply(x -> x * 2)               // 变换：像 map
        .thenApply(x -> "结果 " + x);
System.out.println(cf.join());               // 结果 42（本机实测输出）
```

1. **`thenApply` 对应 map，`thenCompose` 对应 flatMap**：如果下一步的返回本身就是个 `CompletableFuture`，用 `thenApply` 会得到 `CompletableFuture<CompletableFuture<T>>`，套一层。
2. **异常会变成「completed exceptionally」，不会抛到调用点**。你在回调里不写 `exceptionally` / `handle` / `whenComplete`，异常就静静躺在 future 里——不 `join`/`get` 就永远不知道。这和线程池 `submit` 吞异常是同一个坑的两个面。
3. **`allOf(...)` 的返回类型是 `CompletableFuture<Void>`**：它只告诉你「全做完了」，把所有中间结果都丢了，要拿结果得自己收集（或者用 `thenCombine` 之类逐个组合）。另外 `join()` 抛的是非受检的 `CompletionException`，`get()` 抛受检的 `ExecutionException`，捕获代码写法不同。

还有个默认行为要知道：不带执行器的 `*Async` 方法默认用 `ForkJoinPool.commonPool()`，它的并行度由核数推出——**本机实测（2026-10-01）：`ForkJoinPool.commonPool().getParallelism()` = 7（本机 `availableProcessors` = 8）**。把阻塞型任务丢进 common pool，等于拿全进程共享的一个小池子去扛阻塞，会拖累所有用它的代码；阻塞任务请显式传自己的执行器。

## 5. 线程池：参数之间的关系比参数本身重要

`ThreadPoolExecutor` 的七个参数里，最容易搞错的是**「先排队还是先扩容」**。实际顺序是：

```text
submit(task)
 ├─ 线程数 < corePoolSize ?          → 直接新建核心线程执行
 ├─ 工作队列还没满 ?                  → 任务入队，等核心线程空闲后来取
 ├─ 线程数 < maximumPoolSize ?        → 新建「非核心」线程执行
 └─ 否则                             → 交给拒绝策略 handler
```

记住这条顺序，就能解释一个很常见的怪现象：**配了 `maximumPoolSize` 却永远用不到**。原因是队列没满就轮不到扩容——而 `Executors` 的几个工厂方法用的正是无界队列。

| 工厂方法 | 队列 | 直接后果 |
|---|---|---|
| `newFixedThreadPool(n)` | 无界 `LinkedBlockingQueue` | `maximumPoolSize` 形同虚设，任务堆积直到堆爆 |
| `newSingleThreadExecutor()` | 无界队列 | 同上，只是并发度固定为 1 |
| `newCachedThreadPool()` | `SynchronousQueue`（不存任务） | 线程数可被无限放大，任务来得多就开得多 |
| `newScheduledThreadPool(n)` | 延迟工作队列 | 无界；注意周期任务抛异常会静默取消后续调度 |
| `newVirtualThreadPerTaskExecutor()` | 不排队，一任务一虚拟线程 | 无需配容量；但下游（数据库、HTTP 连接）会被打爆 |

| 拒绝策略 | 行为 | 适用判断 |
|---|---|---|
| `AbortPolicy`（默认） | 抛 `RejectedExecutionException` | 默认就好：**丢任务必须能被调用方看见** |
| `CallerRunsPolicy` | 提交任务的线程自己执行 | 天然背压：提交方被拖慢，就不会再猛灌 |
| `DiscardPolicy` | 静默丢弃 | 基本等于把故障藏起来 |
| `DiscardOldestPolicy` | 丢队列里最老的，再试一次 | 同上，还额外破坏了顺序语义 |

七个参数逐条对一遍，重点是「谁决定谁」：

| 参数 | 作用 | 判断要点 |
|---|---|---|
| `corePoolSize` | 常驻线程数，前 core 个任务直接开线程 | 小于它不算「排队」，所以小池子的前几个任务不会受队列影响 |
| `maximumPoolSize` | 线程数上限 | **只有队列满了才会往这边扩**；配了无界队列它就作废 |
| `keepAliveTime` + 单位 | 非核心线程空闲多久回收 | 影响「突发流量过去后线程能不能缩回来」 |
| `workQueue` | 排队的地方 | 有界 → 有背压；无界 → 压力全进堆 |
| `threadFactory` | 建线程的方式 | **在这里起名字、装异常处理器、设优先级**，成本最低收益最高 |
| `handler` | 队列满且线程满时的行为 | 默认抛异常；选 `CallerRunsPolicy` 会拖慢提交方，形成背压 |
| `allowCoreThreadTimeOut` | 核心线程是否也能超时回收 | 低频场景可开，避免长期挂着一批闲着的最小工作集 |

### 5.1 `submit` 吞异常：本阶段最值得记的一个坑

```java
try (var pool = Executors.newFixedThreadPool(1)) {
    pool.execute(() -> { throw new RuntimeException("execute 直抛"); });   // 打到 stderr，线程结束
    Future<?> f = pool.submit(() -> { throw new RuntimeException("submit 被吞"); });
    try {
        f.get();                                       // 只有 get() 才会把异常带回来
    } catch (ExecutionException e) {
        System.out.println("通过 get() 才看到: " + e.getCause());
    }
}
```

`submit` 的返回是 `Future`，异常被装进 `Future` 里；你**不 `get()` 就永远看不到它**，日志上一条记录都没有，任务就这么「消失了」。`execute` 则会把异常交给线程的 `UncaughtExceptionHandler`——本机实测（2026-10-01）的未捕获异常形态是 `Exception in thread "main" ...` 打到栈底、**退出码 1**。所以要么把返回值用起来，要么给池子配一个线程工厂，在工厂里统一装上「记录异常」的处理器。

**配置纪律**（先量后调，别背公式）：

- CPU 密集 ≈ 核数（本机 8，用 `Runtime.getRuntime().availableProcessors()` 拿）；I/O 密集要大些，但**大多少必须靠测**。
- **队列要有界**，并且要在测试里真的把队列灌满，看看拒绝策略发生时你的程序是什么表现——没测过拒绝路径，等于没有拒绝策略。
- **给线程起名**：默认名是 `pool-N-thread-M`，`Thread.print` 里根本分不清谁是谁；自定义 `ThreadFactory` 是性价比最高的一个改动。
- **关闭要有计划**：`shutdown()` 不再收新任务、跑完手上的；`shutdownNow()` 尝试中断并返回还没跑的任务队列；`awaitTermination` 等待真正结束。`ExecutorService` 也是 `AutoCloseable`，`try`-with-resources 就能收紧生命周期（`close()` 的等待语义看 API 文档，别凭印象）。

## 6. JMM 与 happens-before 规则

Java 内存模型（JMM）回答的是：**在什么条件下，一个线程里的写对另一个线程的读可见**。JLS 第 17 章给出了全部规则（见 https://docs.oracle.com/javase/specs/jls/se25/html/index.html ，第 17 章）。这里列出日常会用到的那几条：

| 规则 | 说的是 | 典型用途 |
|---|---|---|
| 程序次序（program order） | 同一线程内，前面的操作 hb 后面的操作 | 单线程里写的东西，后来自己读得到 |
| 监视器锁（monitor lock） | 对同一把锁的 `unlock` hb 之后对它的 `lock` | `synchronized` 块里放出的修改，下一个拿锁的人看得见 |
| volatile | 对同一 `volatile` 变量的写 hb 之后的读 | 状态标志、一次性发布 |
| 线程启动（start） | `t.start()` hb 新线程里的任何操作 | 起线程前填好的数据，新线程不必再同步就能读到 |
| 线程终止（join） | 线程里的所有操作 hb 其它线程从 `join` 返回 | 子线程的中间结果，`join` 之后直接读 |
| 中断（interrupt） | 中断一个线程 hb 该线程观察到中断 | 中断前后传递状态 |
| 对象终结 | 构造器结束 hb 该对象的 `finalize` 开始 | 已被标记待移除（JEP 421），了解即可 |
| 传递性（transitivity） | A hb B、B hb C ⇒ A hb C | 把多条规则串起来用 |

三条要点的读法：

- **hb 不是「时间先后」**。它是「可见性与排序的约束」。两个操作没有 hb 关系 = data race，此时读到的值规范上允许是任意值（不是「旧值」这么客气）。
- **`final` 字段有「初始化安全性」保证**（JLS 17.5）：正确构造（构造期不外泄 `this`）的对象，其它线程拿到引用后能看到 `final` 字段被正确赋值。这也是 `record` 那种全 `final` 类型天然适合跨线程传递的原因。
- **不需要背下所有规则**，需要的是遇到「一个线程写的，另一个线程要读」时，能问出那句：**这两个操作之间，有什么东西建立了 hb 关系？** 答不出来，就是 bug。

**典型翻车形态**（都属于「没有 hb 关系」）：

- 双重检查锁定的单例，实例字段不是 `volatile`：另一个线程可能拿到**构造未完成**的对象（引用可见了，字段还没写完）。
- 构造器里把 `this` 交给另一个线程（注册监听器、丢进静态集合）——对象在没构造完的时候逸出。
- 把非线程安全对象当不可变对象共享：`HashMap` 并发 `put` 会丢更新（本机实测里 `ConcurrentHashMap.merge` 两次得 `{a=2}`，对照的就是这个场景），`SimpleDateFormat` 这类有可变内部状态的工具类同理。

**验证方式**：这类 bug 的判据是「不可复现」本身——所以验证方式是**多轮压力跑 + 记录每轮读数**，而不是「跑一次看对不对」。把每轮数字记成表格，抖动就是证据。

## 7. JVM 内存结构：对象住在哪

| 区域 | 是否共享 | 相关参数 | 涨爆时报什么 |
|---|---|---|---|
| 堆 Heap | 共享 | `-Xms` / `-Xmx` | `OutOfMemoryError: Java heap space` |
| 方法区（HotSpot 实现为元空间 Metaspace） | 共享 | `-XX:MaxMetaspaceSize` | `OutOfMemoryError: Metaspace` |
| 运行时常量池（在元空间里） | 共享 | 同上 | 同上 |
| 虚拟机栈 | 每线程私有 | `-Xss` | `StackOverflowError` |
| 本地方法栈 | 每线程私有 | 一般跟随 `-Xss` | 同上 |
| PC 寄存器 | 每线程私有 | 无 | 无 |
| 直接内存（堆外） | 共享，占本地内存 | `-XX:MaxDirectMemorySize` | `OutOfMemoryError: Direct buffer memory` |
| 代码缓存 | 共享 | `-XX:ReservedCodeCacheSize` | 通常是警告「CodeCache is full」，表现为变慢而不是报错 |

本机实测（2026-10-01）一句话：`Runtime.maxMemory()` ≈ **8120 MiB**（未显式设 `-Xmx` 时的默认上限），启动初 `totalMemory()` = 512 MiB，`availableProcessors` = 8。**这三个数是本机事实，换机器换参数都要重新读**，用 `Runtime.getRuntime()` 自己打印最快。

### 7.1 堆内部还分了区（读 GC 日志要用）

| 区域 | 放什么 | 为什么要有它 |
|---|---|---|
| Eden | 新对象（TLAB 分配也在这里） | 新生代回收的主力：这个区几乎全是垃圾，复制出去的很少 |
| Survivor 0 / Survivor 1 | 上一次新生代回收后还活着的对象 | 两块轮流当「目的地」，用复制算法而不是标记清除 |
| 老年代 | 熬过若干次回收、或体积过大的对象 | 回收频率低，为的是摊薄代价 |

- **对象不是「活到某个年龄就进老年代」这么简单**：还有「体积太大直接进老年代」「Survivor 装不下就提前晋升」这类路径，HotSpot 里这些阈值可以调，别背，`-Xlog:gc*` 跑一次看实际落点。
- G1 里这些区在物理上被切成一堆 region，但**逻辑分区语义不变**——所以读 GC 日志时仍然按 Eden/Survivor/Old 三段占用去理解。
- 看当前各分区占用：`jcmd <pid> GC.heap_info`。这个输出是理解「堆到底怎么涨」最快的一手材料。

### 7.2 对象的一生

```text
new → 分配（多在 TLAB 里，指针碰撞） → 使用
   → 可达性分析：从 GC Roots 出发（栈上的局部引用、静态字段、JNI 引用、活动线程…）
   → 不可达 → 可回收（具体何时由 GC 决定，不是立刻）
   → 需要收尾：用 try-with-resources 或 java.lang.ref.Cleaner，别用 finalize
```

- **可达 = 活着**。局部变量出了作用域、字段被置空、对象从集合里移除，才断开引用链。这就是「GC 不替你管泄漏」的准确含义。
- **`finalize` 不要用**：它已被标记为待移除（JEP 421，编号可在 JEP 索引核对 https://openjdk.org/jeps/0 ）。
- **爆栈的观感**：本机实测（2026-10-01）递归爆栈时栈末尾是 `... 1021 more`，也就是默认设置下大约 1024 帧——栈深度受 `-Xss` 影响，想知道你机器上的数，把递归函数改一个版本跑一次就知道。
- **「堆没满，进程却被系统杀掉」是常见困惑**：`-Xmx` 只约束堆，元空间、直接内存、每个线程的栈、JIT 的代码缓存都在堆外。看进程总 RSS 而不是只看堆占用。

**验证方式**：给一句 `static List<Object> hold = new ArrayList<>();` 的循环持续塞对象，运行时用 `jcmd <pid> GC.class_histogram` 看实例数是否只涨不降；把同一个列表换成方法里的局部变量，再看它能否被回收。这两个对照实验能直接把「可达性」讲清楚，而且只需要 JDK 自带工具。`jcmd <pid> GC.heap_info` 则给出堆的当前分区占用——用它验证 `-Xmx` 生效与否（换 `-Xmx64m` 跑同一个程序，数字会变）。

## 8. 垃圾回收：本机默认是 G1，以及停顿从哪来

**分代假设**：绝大多数对象朝生夕死 → 把堆按年龄分区，年轻代频繁小回收、老年代偶尔大回收，整体效率更高。**弱分代假设**：跨代引用很少 → 用一个「记忆集/卡表」记录「老年代指向新生代」的引用，年轻代回收时不用扫整个老年代。

G1 把这套逻辑实现在「region」上：堆被切成很多等大区域，逻辑上仍分年轻代/老年代，物理上不要求连续。

- **本机默认 GC 实测（2026-10-01）**：`G1 Young Generation` / `G1 Concurrent GC` / `G1 Old Generation`——即 G1。G1 成为默认收集器这件事本身有出处：JEP 248（编号可在 https://openjdk.org/jeps/0 核对）。
- 想知道某个收集器开关在你机器上是什么状态，别猜，直接问 JVM：

```bash
java -XX:+PrintFlagsFinal -version | grep -Ei 'UseG1GC|UseZGC|UseSerialGC|MaxHeapSize|MaxMetaspaceSize'
```

| 停顿来源 | 什么时候出现 | 观察点 |
|---|---|---|
| Young GC | 新生代满，复制存活对象 | 单次通常最短，频率随分配速率涨 |
| 并发标记周期的收尾（Remark 一类） | 标记阶段结束 | 周期性的中等停顿，容易在延迟毛刺里被抓到 |
| Mixed GC | 回收部分老年代 region | 单次比 Young 久，取决于选择的 region 数与存活量 |
| **Full GC** | 通常是「退化」信号：晋升失败、元空间/直接内存吃紧、显式 `System.gc()` | 整堆 STW，最疼；出现它就该看你自己的分配行为 |
| Humongous 大对象 | 单个对象超过 region 的一半 | 直接进老年代，容易造成碎片与提前触发回收 |
| 元空间/直接内存不足 | 类加载器泄漏、`allocateDirect` 用太多 | `-Xlog:gc` 里能看到由这些原因触发的回收 |

选哪个收集器是个**在停顿与吞吐之间选边**的决定，不是「越新越好」：吞吐优先（批处理）与延迟优先（交互服务）能选的器不一样。可用开关与默认值**不要背**，用 `java -XX:+PrintFlagsFinal -version` 加上你自己关心的开关名过滤着看——本机默认那一套（G1）在第 8 节开头已经实测过了。

| 开关（常见几个） | 取向 | 注意 |
|---|---|---|
| `-XX:+UseG1GC` | 平衡，默认那一类 | 本机默认就是 G1；关注 Humongous 对象与 Full GC 的出现 |
| `-XX:+UseSerialGC` | 单线程、停顿可能长但开销小 | 小堆、小内存场景；大堆上会很难看 |
| `-XX:+UseParallelGC` | 吞吐优先 | 停顿不可控，交互场景慎用 |
| `-XX:+UseZGC` | 低延迟优先 | 代价是额外的内存与 CPU 开销（ZGC 有 JEP 编号，可在 JEP 索引核对） |

**读 GC 日志的方法**（本笔记**不贴 GC 日志示例**——行格式随 JDK 与参数变化，编一条假原文比不贴更坏）：

```bash
java -Xlog:help                     # 先看这台 JDK 支持哪些 tag 与语法，别背别人的例子
java -Xlog:gc* -Xmx64m 你的主类      # 打开 GC 日志，含阶段明细
java -Xlog:gc*:file=gc.log:time,uptime,level,tags -Xmx64m 你的主类
```

读的顺序：① 看每条 GC 行的**回收前后堆占用**，判断是「正常回落」还是「只涨不降」；② 看**间隔**，间隔越来越短说明分配压力大或上限太小；③ 看**停顿时间**列，找毛刺；④ 出现 Full GC 就先解决它，再谈调参。

**`-Xmx` 的作用与代价**——它是一道「换时间还是换空间」的旋钮，不是越大越好：

| 上限 | 收益 | 代价 |
|---|---|---|
| 调大 | GC 次数减少，吞吐可能变好 | 单次回收要处理更多存活对象，**停顿可能更长**；进程 RSS 更高；容器里容易撞内存配额 |
| 调小 | 内存占用低，回收集中 | GC 频繁、CPU 被回收占用、随时可能 `OutOfMemoryError` |

默认上限不是魔法：HotSpot 会按物理内存与运行环境推一个值（本机换算约 8120 MiB），要精确值就 `Runtime.maxMemory()` 打印。**验证方式**：同一个分配型程序，分别用 `-Xmx64m` 与 `-Xmx512m` 配 `-Xlog:gc*` 跑一遍，数 GC 行的条数与最长停顿，再换 `jcmd GC.heap_info` 看堆分区。这一轮实验做完，你就不需要背「GC 什么时候触发」了。

## 9. 字节码与类加载：源码看不见的两层

### 9.1 `javap` 能看到什么

`javap -c -p 类名`（`-c` 反汇编、`-p` 含私有成员、`-v` 连常量池与标志位一起看）是你和「源码明明这么写、跑起来却不是这样」之间的对照表。本机实测（2026-10-01）的一个例子：**字符串拼接编译成了 `invokedynamic ... makeConcatWithConstants`，而不是显式的 `StringBuilder` 调用**——这就是「编译期改写」的直接证据，也是为什么读字节码比读语法书有用。

同时记住 `javap` 的边界：它给你的是**静态的指令序列**，看不到 JIT 之后的机器码，也看不到运行时的内存布局。所以「javap 里有一条 putstatic」不等于「这行一定是原子的」——是否原子取决于 JVM 的内存语义（回到第 6 节）。

### 9.2 类加载三步与双亲委派

| 阶段 | 做什么 | 相关条款 |
|---|---|---|
| 加载 Loading | 找到字节码（类路径/模块路径/网络），生成 `Class` 对象 | JVMS 第 5 章 |
| 链接 Linking | **验证**（字节码是否合法）→ **准备**（静态字段给默认值）→ **解析**（符号引用变直接引用） | JVMS 第 5 章 |
| 初始化 Initialization | 执行静态初始化块与静态字段赋值（`<clinit>`） | JVMS 第 5 章 |

- **初始化是惰性的**：`new`、访问静态字段/方法、反射、初始化子类等动作才会触发；不碰它就永远不初始化。
- **双亲委派**：加载类时先委派给父加载器，父找不到才自己找。层次是 Bootstrap → Platform → Application（JDK 9 起用平台类加载器取代了扩展类加载器）。

```text
Application（应用类加载器：类路径）
      │  要加载 com.foo.Bar，先问爸爸
      ▼
Platform（平台类加载器：JDK 的模块）
      │  再问爷爷
      ▼
Bootstrap（核心类，getClassLoader() 返回 null）
      │  都没有 → 逐层往下，谁先找到谁加载
      ▲  父加载器能找到的，子加载器一定不会自己加载（这就是「委派」）
```
- **为什么要这样**：保证核心类不被替换（`java.lang.String` 只由 Bootstrap 加载），也让同一份类只被加载一次。
- **类的身份 = 类加载器 + 全限定名**。同一个 `.class` 被两个不同的加载器加载，就是两个互相不可转换的类型——这是理解「热部署/插件隔离后出现的 `ClassCastException`」的钥匙。
- **什么时候会打破双亲委派**：`ServiceLoader` 这类 SPI（接口在核心层、实现在类路径上）、应用服务器隔离、热部署。打破的代价是类加载器可能无法卸载 → 元空间 OOM，这就是「类加载器泄漏」。

**验证方式**：

```java
System.out.println(String.class.getClassLoader());       // null：由 Bootstrap 加载
System.out.println(TaskShape.class.getClassLoader());    // 应用类加载器（类名以你自己跑出来的为准）
```

再用 `jcmd <pid> help` 看这台 JDK 上有哪些和类/类加载器相关的命令（例如对象直方图），配合 `-Xlog:class+load` 之类的统一日志开关观察到底加载了什么——**具体 tag 用 `-Xlog:help` 现查**。

## 10. 诊断工具箱：本机都自带

JDK 25 自带一整套诊断工具（本机没有 Maven/Gradle，也没有任何第三方库，但这些工具一个不缺）。核心纪律只有一条：**先分类现象，再选工具**——「CPU 高」「内存只涨不降」「卡住不动」需要的证据完全不同。

| 工具 | 管什么 | 典型用法 | 什么时候用 |
|---|---|---|---|
| `jcmd` | 万能入口 | `jcmd -l` 列进程；`jcmd <pid> help` 列可用命令；`Thread.print`；`GC.heap_info`；`GC.class_histogram`；`JFR.start` | 不知道该用什么时，先 `jcmd <pid> help` |
| `jstack` | 线程栈快照 | `jstack <pid>` | 「卡住/疑似死锁/CPU 高」时先看每条线程在干什么；与 `jcmd <pid> Thread.print` 等价 |
| `jmap` | 堆 | `jmap -histo:live <pid>`；`jmap -dump:...` | 内存只涨不降；`histo:live` 会触发一次 GC，别在压测峰值随便用 |
| `jfr` | 事件级连续记录 | `jfr print --events jdk.ExecutionSample 文件.jfr` | 想事后分析（CPU 采样、锁、GC、异常），而不是抓一个瞬时快照。Flight Recorder 进入 OpenJDK 有出处：JEP 328（编号见 https://openjdk.org/jeps/0 ） |
| `jconsole` | GUI 面板 | `jconsole <pid>` | 学习期看内存/线程曲线最快 |
| `javap` | 字节码 | `javap -c -p 类名` | 「源码这么写，为什么跑出来那样」 |
| `jdb` | 调试器 | `jdb` | 需要源码级单步时 |
| `jshell` | 交互式验证 | 直接敲表达式 | 快速验证某个 API 的行为，不用建工程 |

**抓快照之前先把这四件事记下来**，否则拿到 dump 也读不出结论：① 现象是什么（卡住 / CPU 高 / 内存涨 / 变慢）；② 这个进程在干什么（刚跑过压测？刚部署完？）；③ 启动参数是什么（`jcmd <pid> VM.command_line` 或 `VM.flags` 能看，`-Xmx` 不同结论完全不同）；④ 快照是几分钟内的第几次（一次快照只能说明「此刻」，趋势要靠对比）。

**按现象查的三条路线**：

| 现象 | 先做什么 | 要看的那一行 |
|---|---|---|
| 卡住不动 | `jcmd <pid> Thread.print` | 各线程状态与栈顶：是 `BLOCKED`（等锁）、还是 `WAITING`（等条件/队列），以及卡在哪一行 |
| CPU 高 | `Thread.print` 抓几次对比，或开 JFR 采样 | 反复出现在栈顶的那个方法就是嫌疑；注意 `jstack` 本身不给 CPU 百分比 |
| 内存只涨不降 | `-Xlog:gc*` + `jcmd GC.heap_info` + `GC.class_histogram` | 是「堆上限太小」还是「真的有引用活着」：直方图里实例数持续增长的那一类就是线索 |

**死锁的最小造法与判据**：两个线程按相反顺序拿两把锁，中间用 `CountDownLatch` 保证交叉，然后在线程 dump 里找 `Found one Java-level deadlock:` 段落，它会列出哪两个线程、各持有什么、在等什么。**本笔记不贴这段 dump 的原文**（本机未实测这一条输出），请你自己造一个死锁把原文抄进自己的笔记——抄一遍的过程就是学会读它的过程。

两个操作层面的提醒：① 抓线程 dump 需要 JVM 走到安全点，会有短暂停顿，练习时随便抓，压测时不建议高频抓；② 先 `jcmd -l` 拿到 pid，别把 pid 记错成别的进程（`jcmd -l` 输出的就是本机 JVM 进程列表）。

## 练习

| 任务 | 验收标准 | 建议时长 |
|---|---|---|
| 1. 竞态对照表：同一目标（累加到 200000）分别用「无同步」「`synchronized`」「`AtomicInteger`」「`LongAdder`」四种方式各跑 **5 轮**，把每轮读数记成表格；再单独测一次 `volatile int` 自增 | 表格里无同步版多轮读数不一致；`AtomicInteger` 每轮恒等；能一句话说出 `volatile` 版本为什么还是错；能解释「某轮无同步版恰好等于 200000」为什么不构成正确性证据 | 2 小时 |
| 2. 线程池判定顺序实验：手工 `new ThreadPoolExecutor(...)`，把核心线程数、最大线程数、队列容量都设成很小的值（例如 1/2/1），提交 5 个任务，每个任务打印自己的线程名；分别用 `AbortPolicy` 与 `CallerRunsPolicy` 各跑一次 | 能从输出实证「核心 → 队列 → 扩容 → 拒绝」的判定顺序；能说出 `newFixedThreadPool` 用无界队列后 `maximumPoolSize` 为什么失效；两种拒绝策略的差异能在输出里指出来 | 2 小时 |
| 3. 死锁现场：造一个双锁反序的死锁程序，用 `jcmd -l` + `Thread.print`（或 `jstack`）抓线程 dump，把与死锁相关的段落**原文**抄进笔记，并标出哪一行对应你自己代码的哪一行 | 不看源码能说出是哪两个线程、各自持有与等待哪把锁；能说出为什么 `CountDownLatch` 让死锁稳定复现；dump 原文抄录完整（不许复述大意） | 2 小时 |
| 4. 内存与 GC 观察：写一个持续分配对象的程序，分别用 `-Xmx64m` 与 `-Xmx512m` 配 `-Xlog:gc*` 跑一遍，数 GC 行数与最长停顿；再用 `jcmd GC.heap_info`、`GC.class_histogram` 各抓一次 | 能说出 `-Xmx` 约束的是哪个区域、哪些区域它管不到；能用 `-Xlog:help` 解释你用到的 tag；能说清直方图里「只涨不降」意味着什么 | 2.5 小时 |
| 5. 生产者—消费者与背压：用容量为 1 的 `ArrayBlockingQueue` 写生产者快、消费者慢的两个线程，跑起来后用 `Thread.print` 抓一次快照；再把容量换成无界队列重跑 | 能从 dump 里指出生产者停在哪个方法上；能用自己的话解释「背压」；能说出无界队列版本里问题被搬到了哪里 | 2 小时 |
| 6. 虚拟线程对照实验：同一批等待型任务（`Thread.sleep`）分别用固定 8 线程的平台线程池与虚拟线程执行器跑，记录耗时与能起的任务规模；再把任务体换成纯计算重跑一次 | 能说出两轮结论为什么相反；能说出虚拟线程适合与不适合的场景；所有耗时都标明是「本机这一次运行」的读数，并说明数字会随机器与负载变化 | 2 小时 |

## 自我检查清单

- [ ] 能说出并发的麻烦从哪来，以及「不共享 / 共享但不可变」为什么能绕开大部分问题
- [ ] 能解释 `i++` 为什么不原子，并说出 `volatile` 在这个场景为什么不管用
- [ ] 能写出会丢更新的最小对照实验，并说明「某轮结果正确」不构成证据的理由
- [ ] 能说出 `submit` 与 `execute` 在异常处理上的差别，并知道不 `get()` 会丢什么
- [ ] 能凭 `ThreadPoolExecutor` 的判定顺序解释「`maximumPoolSize` 明明配了却用不到」
- [ ] 能说出四种拒绝策略里哪两种会让故障隐藏，以及为什么队列要有界
- [ ] 能说出 happens-before 的至少四条规则，并会用「两个操作之间有什么建立了 hb 关系」这句话排查问题
- [ ] 能说出 JMM 里 hb 与「时间先后」的区别，以及 data race 下规范允许读到什么
- [ ] 能说出堆、元空间、虚拟机栈、直接内存各自受哪个参数约束、各自爆掉时报什么错
- [ ] 能解释「堆没满但进程被系统杀」可能由哪些堆外区域造成
- [ ] 能不看资料说出本机默认的 GC 是哪一套，并用一条命令自己复验
- [ ] 能打开 GC 日志并读出一条记录的关键信息（回收前后占用、间隔、停顿）
- [ ] 能说出 `-Xmx` 调大调小各自的代价，而不是「越大越好」
- [ ] 能说出类加载的三步、双亲委派的作用，以及「同一个类被两个加载器加载」的后果
- [ ] 能按「现象 → 工具 → 要看的那一行」说出线程卡住、CPU 高、内存只涨不降三条排查路线
- [ ] 能独立造出一个死锁并在 dump 里指出两个线程与两把锁

## 出处

| 内容 | 来源 | 链接 |
|---|---|---|
| 线程、锁、happens-before 规则、`final` 字段的初始化安全性、`long`/`double` 的非原子读写 | Java 语言规范 JLS SE25 第 17 章（Threads and Locks） | https://docs.oracle.com/javase/specs/jls/se25/html/index.html |
| 运行时数据区、栈帧、类加载（加载/链接/初始化）的过程与时机 | Java 虚拟机规范 JVMS SE25 第 2、5 章 | https://docs.oracle.com/javase/specs/jvms/se25/html/index.html |
| 全部官方规范入口（JLS / JVMS 各版本） | Oracle 规范总入口 | https://docs.oracle.com/javase/specs/ |
| `java.util.concurrent`、`java.lang`、`java.lang.ref` 等 API 的精确语义（`ExecutorService.close`、`ConcurrentHashMap`、`BlockingQueue`、`CompletableFuture`、`Thread.ofVirtual`） | JDK 25 API 文档 | https://docs.oracle.com/en/java/javase/25/docs/api/ |
| `java.base` 里都有哪些包与类 | `java.base` 模块摘要 | https://docs.oracle.com/en/java/javase/25/docs/api/java.base/module-summary.html |
| JVM 概念、内存、类加载、诊断工具总览 | Java SE 25 开发指南：JVM 部分 | https://docs.oracle.com/en/java/javase/25/vm/ |
| 垃圾收集器的选择、`-Xmx`/`-Xms`、停顿与调优思路、统一日志 `-Xlog` | Java SE 25 GC 调优指南 | https://docs.oracle.com/en/java/javase/25/gctuning/ |
| `jcmd`/`jstack`/`jmap`/`jfr`/`jconsole` 的用途与故障处理流程 | Java SE 25 故障排查指南 | https://docs.oracle.com/en/java/javase/25/troubleshoot/ |
| 类路径与启动、`java` 命令的执行模型 | Java SE 25 执行指南 | https://docs.oracle.com/en/java/javase/25/core/execution.html |
| 语言层面的变更清单（含各特性对应的 JEP） | Java 语言变更（SE 25 文档集） | https://docs.oracle.com/en/java/javase/25/language/java-language-changes.html |
| G1 成为默认收集器（JEP 248）、虚拟线程（JEP 425 预览 / JEP 444 正式）、有用的 NPE 提示（JEP 358）、`finalize` 标记待移除（JEP 421）、Flight Recorder 进入 OpenJDK（JEP 328）——**编号请在此索引核对** | OpenJDK JEP 索引 | https://openjdk.org/jeps/0 |
| 本机运行的是哪一个 JDK 发行版与更新版本 | Eclipse Adoptium（本机 `java.vendor` = Eclipse Adoptium） | https://adoptium.net/ |
| HotSpot 虚拟机的实现与工具（`jcmd`/`jfr` 等）的权威入口 | OpenJDK HotSpot 组 | https://openjdk.org/groups/hotspot/ |
| 本机 JDK 对应的 OpenJDK 项目页 | OpenJDK JDK 25 项目页 | https://openjdk.org/projects/jdk/25/ |
| 想读实现源码（GC、类加载、`jcmd` 命令） | OpenJDK 源码仓库 | https://github.com/openjdk/jdk |
| 并发与 JVM 概念的入门讲解顺序 | dev.java 学习站 | https://dev.java/learn/ |
| 官方旧教程（并发一章、JVM 概念） | Oracle Java 教程 | https://docs.oracle.com/javase/tutorial/java/index.html |
| 并发教程的社区读物（**只作背景，不得当依据**） | Jenkov 并发教程 | https://jenkov.com/tutorials/java-concurrency/index.html |
| 常见问题的社区整理（**只作背景，不得当依据**） | Baeldung | https://www.baeldung.com/ |

## 依据说明

- **有官方依据的部分**：第 3 节的 `i++` 非原子性与 `volatile` 的保证范围、word tearing、第 6 节的全部 hb 规则与 `final` 字段初始化安全性、data race 的定义——均出自 JLS SE25 第 17 章（本章链接只给规范首页，具体小节号 17.4.5 / 17.5 / 17.7 请在页内目录里定位）；第 7 节的区域划分、第 9 节的加载/链接/初始化步骤与触发时机，出自 JVMS SE25 第 2、5 章；各 API 的语义细节（`ConcurrentHashMap` 不允许 null、`size()` 非常量时间、`CountDownLatch` 一次性、`allOf` 返回 `Void`、`submit` 把异常装进 `Future`、`jmap -histo:live` 触发 GC）以 JDK 25 API 文档为准；`-Xlog` 语法、GC 选择与调优、诊断工具用法出自 GC 调优指南与故障排查指南；G1 默认（JEP 248）、虚拟线程（JEP 425/444）、`finalize` 待移除（JEP 421）、Flight Recorder（JEP 328）均给 JEP 编号，可在 JEP 索引核对——**本笔记不复核这些编号之外的版本行情，也不断言任何版本「是主流/该用哪个」**。
- **本机实测（2026-10-01）的部分**（原始输出见 `Java/code/probe/本机实测输出.txt`）：并发段四批的无同步读数 52200/60365、60198/74237、81554/177331、54980/59719 与每批 `AtomicInteger` 两轮均 200000；虚拟线程 50000 个任务全部取回耗时 89 / 122 / 185 / 103 ms 与实现类 `java.lang.VirtualThread`；默认 GC 的三个 collector 名；`maxMemory` ≈ 8120 MiB、`totalMemory` 启动初 512 MiB、`availableProcessors` = 8；字符串拼接编译成 `invokedynamic ... makeConcatWithConstants`；递归爆栈栈尾 `... 1021 more`；未捕获异常打到栈底且退出码 1。另外同一份日志里的 `ProbeExtra` 给出：`String.class.getClassLoader()` 为 `null`（引导类加载器）、系统类加载器是 `jdk.internal.loader.ClassLoaders$AppClassLoader`、`ForkJoinPool.commonPool().getParallelism()` = 7。
- **属本人编排判断（取舍，不是权威结论）的部分**：10 个分节的顺序与每节「先讲现象、再给判据」的写法；把「不弄懂会在什么现象上翻车」写进每张表的选择标准；练习的难度、时长与验收口径；第 5 节把「判定顺序」放在参数讲解之前；第 8 节不贴 GC 日志原文、第 10 节不贴死锁 dump 原文这一决定（理由是同机不同参数下行格式不稳定，贴一条编造的原文比不贴更坏）；「先用 `jcmd <pid> help` 确认命令名」这条操作纪律。
- **不确定、请自己实测后再信的部分**：
  1. **同一脚本不同批次的数字确实不一样**：四批的无同步读数从 52200 一路到 177331，虚拟线程耗时从 89 ms 到 185 ms。差异来自调度与机器负载——这恰恰是第 3 节要教的点，所以**任何一次运行的读数都不能当常量引用**；而 `AtomicInteger` 那一列在每批里都稳定在 200000，正好构成对照。（这四批在 `Java/code/probe/本机实测输出.txt` 里都能查到；脚本每重跑一次就再追加一批，日志里的批数会多于四，数值对得上才算数。）
  2. **平台线程数量上限**：本机没有测出确切的失败阈值（未跑到 `unable to create native thread`），第 2 节只给了现象与自测方法。
  3. **可见性「死循环」那个经典例子的复现性**：取决于 JIT 优化程度，本机未实测，笔记里只当形态讲，没有当结论给。
  4. **死锁 dump 的原文措辞**：本笔记没有实测 `Found one Java-level deadlock:` 这一段的本机原文，措辞以你自己抓到的 dump 为准（练习 3 就是干这个的）。
  5. **虚拟线程与 `synchronized` 的「钉住」问题在不同 JDK 上表现不同**，本机没有做对照实验；第 2 节只写了「以本机实测为准」。
  6. **结构化并发等仍标为预览的 API**：是否可用、对应哪个 JEP 编号，请在 JDK 25 API 文档的预览标注与 JEP 索引里现查，本笔记不给结论。

---

上一阶段 → [[03-阶段三 集合框架与泛型]] · [[00-Java索引]] · 下一阶段 → [[05-阶段五 工程实践与生态]]
