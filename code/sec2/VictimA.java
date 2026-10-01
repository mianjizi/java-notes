/**
 * 深度靶场的目标类（版本 A：**磁盘上那份，全程不许改动**）。
 *
 * main 里每 0.5 秒打印一次 status()，共 40 次（约 20 秒窗口），
 * 目的：让「外部进程在它跑着的时候动手」有时间发生 —— 返回值中途变化，
 * 就是「JVM 没重启、实现被换了」的直接证据。
 */
public class VictimA {

    public static String status() {
        return "磁盘上的原始实现（字节码未被改动）";
    }

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        System.out.println("    [目标] pid = " + ProcessHandle.current().pid()
                + "（每 0.5 秒打印一次 status()，共 " + n + " 次）");
        long t0 = System.currentTimeMillis();
        for (int i = 1; i <= n; i++) {
            System.out.println("    [目标] 第" + String.format("%02d", i) + "次 status() = " + status());
            Thread.sleep(500);
        }
        System.out.println("    [目标] 循环结束，用时 " + (System.currentTimeMillis() - t0) + " ms");
    }
}
