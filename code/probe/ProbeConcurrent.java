import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 并发探针：竞态的真实表现、原子类、虚拟线程规模 */
public class ProbeConcurrent {
    static int unsafe = 0;
    static final AtomicInteger safe = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        System.out.println("--- 竞态：同一个计数器，加锁与不加锁 ---");
        int n = 200_000;
        runTwice(() -> unsafe++, "i++ (无同步)", () -> unsafe = 0, () -> unsafe);
        runTwice(() -> safe.incrementAndGet(), "AtomicInteger", () -> safe.set(0), () -> safe.get());
        System.out.println("两者本轮观测：" + n + " 次递增，无同步版几乎总是小于 " + n);

        System.out.println("--- 虚拟线程：平台线程做不到的规模 ---");
        int count = 50_000;
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            long t0 = System.nanoTime();
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < count; i++) futures.add(exec.submit(() -> 1));
            int sum = 0;
            for (var f : futures) sum += f.get();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("虚拟线程 %d 个任务全部 join：sum=%d，耗时 %d ms%n", count, sum, ms);
        }
        System.out.println("虚拟线程实现类 = " + Thread.ofVirtual().unstarted(() -> {}).getClass().getName());

        System.out.println("--- CompletableFuture：回调式组合 ---");
        var cf = CompletableFuture.supplyAsync(() -> 21)
                .thenApply(x -> x * 2)
                .thenApply(x -> "结果 " + x);
        System.out.println(cf.get());

        System.out.println("--- 线程安全的集合选择 ---");
        var m = new ConcurrentHashMap<String, Integer>();
        m.merge("a", 1, Integer::sum);
        m.merge("a", 1, Integer::sum);
        System.out.println("ConcurrentHashMap.merge 两次 = " + m + "（普通 HashMap 并发 put 会丢更新）");
    }

    interface Action { void run() throws Exception; }

    static void runTwice(Action a, String label, Runnable reset, java.util.function.Supplier<Integer> read) throws Exception {
        for (int round = 1; round <= 2; round++) {
            reset.run();
            int threads = 4, per = 50_000;
            var pool = Executors.newFixedThreadPool(threads);
            var start = new CountDownLatch(1);
            var fs = new ArrayList<Future<?>>();
            for (int t = 0; t < threads; t++)
                fs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < per; i++) a.run();
                    return null;
                }));
            start.countDown();
            for (var f : fs) f.get();
            pool.shutdown();
            System.out.printf("%-16s 第%d轮 期望 %d，实测 %d%n", label, round, threads * per, read.get());
        }
    }
}
