import java.io.*;
import java.util.*;

/**
 * 反序列化的最小真相：**读对象的过程中，代码会被执行**。
 * 这里不用任何第三方 gadget 链，全部自建，只为把机制摊开：
 *   1) 类自己的 readObject 钩子会被调用
 *   2) 容器类（HashMap / PriorityQueue）在重建结构时会主动调用元素的方法
 *      —— 真实 gadget 链（Commons Collections 那一类）靠的就是这两点
 */
public class SecDeserBasic {

    /** 副作用靶子：真实世界是 Runtime.exec，学习环境里只打印，不执行任何命令 */
    static class Hit {
        static int count = 0;
        /** 只有「反序列化阶段」才记账：构造/序列化阶段也会调 compareTo、hashCode，
         *  不区分就会把两段时间混在一起，读出来的次数没有意义 */
        static boolean armed = false;
        static void fire(String where) {
            if (!armed) return;
            count++;
            System.out.println("      >> [副作用触发 #" + count + "] " + where);
        }
    }

    /** 带 readObject 钩子的类：反序列化时 JVM 会自动调用它 */
    static class WithHook implements Serializable {
        private static final long serialVersionUID = 1L;
        private String tag = "原值";

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();          // 先按默认规则把字段读回来
            Hit.fire("readObject 钩子（类=" + this.getClass().getSimpleName() + ", tag=" + tag + "）");
        }
    }

    /** 放进 PriorityQueue 的类：它是个 Comparable，重建堆时会调 compareTo */
    static class Cmp implements Comparable<Cmp>, Serializable {
        private static final long serialVersionUID = 1L;
        final int pri;
        Cmp(int pri) { this.pri = pri; }
        public int compareTo(Cmp o) {
            Hit.fire("compareTo（PriorityQueue 重建堆时调用）");
            return Integer.compare(this.pri, o.pri);
        }
    }

    /** 当 HashMap 的键：重建哈希表时会调 hashCode 与 equals */
    static class Key implements Serializable {
        private static final long serialVersionUID = 1L;
        final String id;
        Key(String id) { this.id = id; }
        @Override public int hashCode() {
            Hit.fire("hashCode（HashMap 重建哈希表时调用）");
            return id.hashCode();
        }
        @Override public boolean equals(Object o) {
            Hit.fire("equals（HashMap 处理冲突时调用）");
            return o instanceof Key && ((Key) o).id.equals(id);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 一、只用 readObject 钩子：读一个对象就触发了副作用 ===");
        byte[] b1 = ser(new WithHook());
        System.out.println("  序列化后字节数 = " + b1.length + "，现在开始反序列化（下面只记反序列化阶段的调用）：");
        Hit.count = 0; Hit.armed = true;
        Object o1 = deser(b1);
        Hit.armed = false;
        System.out.println("  读回的对象 = " + o1.getClass().getSimpleName() + "，副作用触发次数 = " + Hit.count);

        System.out.println();
        System.out.println("=== 二、容器在重建结构时会主动调用元素的方法（真实 gadget 链的内核）===");
        System.out.println("  (a) PriorityQueue：元素之间需要比较才重建堆，所以 1 个元素时不会调用");
        for (int n : new int[]{1, 2, 4}) {
            List<Comparable<?>> list = new ArrayList<>();
            for (int i = 0; i < n; i++) list.add(new Cmp(n - i));
            byte[] b = ser(new PriorityQueue<>(list));   // 注意：这一步（构造+序列化）也会比较，但不记账
            Hit.count = 0; Hit.armed = true;
            deser(b);
            Hit.armed = false;
            System.out.println("      元素 " + n + " 个 → 反序列化时 compareTo 被调用 " + Hit.count + " 次");
        }
        System.out.println("  (b) HashMap：重建哈希表要算键的位置，冲突还要判等");
        for (int n : new int[]{1, 4, 16}) {
            Map<Object, Object> m = new HashMap<>();
            for (int i = 0; i < n; i++) m.put(new Key("k" + i), "v");
            byte[] b = ser(m);
            Hit.count = 0; Hit.armed = true;
            deser(b);
            Hit.armed = false;
            System.out.println("      键 " + n + " 个 → 反序列化时 hashCode+equals 共被调用 " + Hit.count + " 次");
        }

        System.out.println();
        System.out.println("=== 三、结论 ===");
        System.out.println("  不是「反序列化会执行魔法」，而是：readObject 钩子 + 容器重建结构时的回调，");
        System.out.println("  把「你写的/框架里本来就有的危险方法」串起来执行了。入口只要一个不可信的字节流。");
    }

    static byte[] ser(Object o) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) { out.writeObject(o); }
        return bos.toByteArray();
    }

    static Object deser(byte[] b) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(b))) {
            return in.readObject();
        }
    }

    static void p(String k, String v) { System.out.printf("%-30s = %s%n", k, v); }
}
