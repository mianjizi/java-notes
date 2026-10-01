import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectInputFilter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 实验 3：JEP 290 过滤器的「进阶面」—— 11 篇只测了「放行/拒绝某个类」，
 * 这里测四种更实际的东西：
 *   1) maxdepth / maxrefs / maxarray 这类**限值**（拦的是「形状」，不是类名）
 *   2) 数组与基本类型的模式怎么写
 *   3) Config.createFilter 解析字符串（不装到进程上也能先验语法）
 *   4) Config.setSerialFilter 只能设一次 —— 第二次会抛什么
 * 全部本地自建对象，不涉及任何外部输入。
 */
public class SecFilterAdv {

    /** 用来堆深度的链式对象 */
    static class Node implements Serializable {
        private static final long serialVersionUID = 1L;
        Node next;
        int n;
        Node(int n) { this.n = n; }
    }

    static class Pair implements Serializable {
        private static final long serialVersionUID = 1L;
        String a, b;
        Pair(String a, String b) { this.a = a; this.b = b; }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1) 先看几个字符串模式解析成什么（不装到进程上）===");
        String[] patterns = {
                "java.base/*;!*",
                "!SecFilterAdv$Node;java.base/*;!*",
                "maxdepth=3;*",
                "maxrefs=2;*",
                "maxarray=4;*",
                "[Ljava.lang.String;;java.base/*;!*",
                "[I;java.base/*;!*",
        };
        for (String p : patterns) {
            ObjectInputFilter f = ObjectInputFilter.Config.createFilter(p);
            System.out.println("    模式 " + p + "\n        → 解析出的过滤器 = " + f);
        }

        System.out.println();
        System.out.println("=== 2) maxdepth：拦的是「嵌套多深」，不是「哪个类」===");
        for (int depth : new int[] { 2, 6 }) {
            Node head = null;
            for (int i = 0; i < depth; i++) { Node n = new Node(i); n.next = head; head = n; }
            byte[] payload = dump(head);
            tryRead("链长 " + depth + "（字节数 " + payload.length + "）",
                    payload, "maxdepth=4;*");
        }

        System.out.println();
        System.out.println("=== 3) maxrefs：拦的是「一次读里有多少个引用」===");
        List<Pair> big = new ArrayList<>();
        for (int i = 0; i < 30; i++) big.add(new Pair("k" + i, "v" + i));
        byte[] bigPayload = dump(big);
        tryRead("List 里 30 个 Pair（字节数 " + bigPayload.length + "）", bigPayload, "maxrefs=8;java.base/*;*");

        System.out.println();
        System.out.println("=== 4) 数组模式与 maxarray ===");
        tryRead("String[20] 的数组", dump(new String[20]), "maxarray=8;java.base/*;*");
        tryRead("String[20] 的数组（显式拒绝数组模式）", dump(new String[20]),
                "![Ljava.lang.String;;java.base/*;!*");

        System.out.println();
        System.out.println("=== 5) 进程级过滤器只能设一次 ===");
        ObjectInputFilter mine = ObjectInputFilter.Config.createFilter("java.base/*;!*");
        try {
            ObjectInputFilter.Config.setSerialFilter(mine);
            System.out.println("    第一次 setSerialFilter：成功");
        } catch (Exception e) {
            System.out.println("    第一次 setSerialFilter 就失败：" + e);
        }
        try {
            ObjectInputFilter.Config.setSerialFilter(mine);
            System.out.println("    第二次 setSerialFilter：居然又成功了（与文档描述不同，如实记录）");
        } catch (Exception e) {
            System.out.println("    第二次 setSerialFilter 抛：" + e.getClass().getName() + ": " + e.getMessage());
        }
        System.out.println("    现在进程级过滤器 = " + ObjectInputFilter.Config.getSerialFilter());

        System.out.println();
        System.out.println("=== 6) 进程级过滤器设上之后，流级还能不能覆盖它 ===");
        try {
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(dump(new Pair("a", "b"))));
            in.setObjectInputFilter(info -> ObjectInputFilter.Status.ALLOWED);   // 流级：全放
            Object o = in.readObject();
            System.out.println("    流级全放行 → 读成功：" + o.getClass().getSimpleName());
        } catch (Exception e) {
            System.out.println("    流级全放行 → 仍被拦：" + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }
        try {
            ObjectInputStream in2 = new ObjectInputStream(new ByteArrayInputStream(dump(new Pair("a", "b"))));
            Object o = in2.readObject();       // 不设流级 → 走进程级
            System.out.println("    不设流级 → 读成功：" + o.getClass().getSimpleName());
        } catch (Exception e) {
            System.out.println("    不设流级 → 被进程级拦下：" + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }
    }

    static void tryRead(String what, byte[] payload, String pattern) {
        ObjectInputFilter f = ObjectInputFilter.Config.createFilter(pattern);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            in.setObjectInputFilter(f);
            Object o = in.readObject();
            System.out.println("    " + what + " —— 过滤器 [" + pattern + "] → 读成功（" + o.getClass().getSimpleName() + "）");
        } catch (Exception e) {
            System.out.println("    " + what + " —— 过滤器 [" + pattern + "] → 拦下："
                    + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }
    }

    static byte[] dump(Object o) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) { out.writeObject(o); }
        return bos.toByteArray();
    }

    static String firstLine(String s) {
        if (s == null) return "(无消息)";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
