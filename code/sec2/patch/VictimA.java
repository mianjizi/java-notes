/**
 * 版本 B：与 VictimA.java **结构完全一致**（同样的 status() 与 main(String[])），
 * 只有 status() 的返回串不同 —— redefineClasses 要求 schema 不变。
 *
 * 用途：给「动态附加」那个实验当补丁字节。注意这是**两份源码各自编译**的老做法，
 * 实验 2（SecRewriteAgent）会展示**不写第二份源码、直接在进程内改字节码**的做法。
 */
public class VictimA {

    public static String status() {
        return "补丁实现（来自另一份编译产物，经外部进程 loadAgent 换上去）";
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
