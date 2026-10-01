import java.util.*;

/** 运行期异常：每种都抓下来打印类名、消息、以及栈顶三帧（本机真实输出） */
public class RunErrors {
    public static void main(String[] args) {
        probe("NullPointerException（有用提示，JDK 15+ 默认开）", () -> {
            String s = null;
            s.length();
        });
        probe("ArrayIndexOutOfBoundsException", () -> {
            int[] a = {1, 2, 3};
            System.out.println(a[5]);
        });
        probe("ClassCastException", () -> {
            Object o = "我是字符串";
            Integer i = (Integer) o;
        });
        probe("NumberFormatException", () -> {
            Integer.parseInt("12x");
        });
        probe("StringIndexOutOfBoundsException", () -> {
            "abc".charAt(9);
        });
        probe("ArithmeticException（整数除零）", () -> {
            int z = 10 / args.length;       // 用非常量除数，编译期折不掉
        });
        probe("UnsupportedOperationException", () -> {
            List.of("a").add("b");
        });
        probe("NoSuchElementException（Scanner 读完了还在读）", () -> {
            Scanner sc = new Scanner("one");
            sc.next();
            sc.next();
        });
        probe("StackOverflowError（错误，不是异常）", () -> {
            recurse(0);
        });
        probe("异常链（Caused by）", () -> {
            try {
                throw new java.io.IOException("底层原因：文件不存在");
            } catch (java.io.IOException e) {
                throw new IllegalStateException("上层包装：读取配置失败", e);
            }
        });
        probe("NullPointerException（对象数组里放了 null）", () -> {
            String[] arr = new String[2];
            arr[1].toUpperCase();
        });
        System.out.println("\n--- 共同点：消息里有\"是什么\"和\"哪里\"，先读第一行，再看第一帧属于你自己的类还是 JDK ---");
    }

    static int recurse(int n) { return recurse(n + 1); }

    interface Block { void run() throws Throwable; }

    static void probe(String label, Block b) {
        try {
            b.run();
            System.out.println("[" + label + "] 没抛异常");
        } catch (Throwable t) {
            System.out.println("\n[" + label + "]");
            System.out.println("  " + t.getClass().getName() + ": " + t.getMessage());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(3, st.length); i++)
                System.out.println("    at " + st[i]);
            if (st.length > 3) System.out.println("    ... " + (st.length - 3) + " more");
        }
    }
}
