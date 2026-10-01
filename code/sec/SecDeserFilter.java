import java.io.*;
import java.util.*;

/**
 * 反序列化的第一道防线：JEP 290 的 ObjectInputFilter。
 * 同一段攻击字节流，分别在「无过滤器 / 流级过滤器 / 进程级过滤器」下过一遍。
 */
public class SecDeserFilter {

    static class Hit {
        static int count = 0;
        static void fire(String where) { count++; System.out.println("    >> [副作用触发] " + where); }
    }

    /** 模拟「框架里的危险类」：它的 readObject 会调用一个危险动作 */
    static class Dangerous implements Serializable {
        private static final long serialVersionUID = 1L;
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            Hit.fire("Dangerous.readObject（真实世界这一段会调 Runtime.exec / JNDI lookup）");
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] payload = ser(new Dangerous());
        System.out.println("载荷字节数 = " + payload.length);

        System.out.println();
        System.out.println("=== 1) 不加任何过滤器：载荷自由执行 ===");
        Hit.count = 0;
        try {
            deserNoFilter(payload);
            System.out.println("    结果：反序列化成功，副作用触发 " + Hit.count + " 次 ← 这就是被利用了");
        } catch (Exception e) {
            System.out.println("    结果：抛 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== 2) 流级过滤器：只放行自己认识的类（白名单思路）===");
        Hit.count = 0;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            in.setObjectInputFilter(info -> {
                Class<?> c = info.serialClass();
                if (c == null) return ObjectInputFilter.Status.UNDECIDED;
                boolean allow = c.getName().startsWith("java.lang.") || c.getName().startsWith("java.util.");
                System.out.println("    过滤器看到类 " + c.getName() + " → " + (allow ? "放行" : "拒绝"));
                return allow ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
            });
            in.readObject();
            System.out.println("    结果：读成功（不该发生）");
        } catch (Exception e) {
            System.out.println("    结果：抛 " + e.getClass().getSimpleName() + " -> " + firstLine(e.getMessage()));
            System.out.println("    副作用触发次数 = " + Hit.count + "（0 就是拦住了）");
        }

        System.out.println();
        System.out.println("=== 3) 进程级过滤器（-Djdk.serialFilter）：启动参数就能兜底 ===");
        ObjectInputFilter processWide = ObjectInputFilter.Config.getSerialFilter();
        System.out.println("    本进程生效的全局过滤器 = " + processWide);
        if (processWide == null) {
            System.out.println("    （本次运行没设 jdk.serialFilter —— run.sh 里另有一次带参数的运行，那里会看到拒绝）");
        } else {
            Hit.count = 0;
            try { deserNoFilter(payload); System.out.println("    结果：读成功（不该发生），副作用 " + Hit.count + " 次"); }
            catch (Exception e) {
                System.out.println("    结果：全局过滤器拦下 → " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
                System.out.println("    副作用触发次数 = " + Hit.count + "（0 就是拦住了）");
            }
        }

        System.out.println();
        System.out.println("=== 4) 过滤器语法速记（jdk.serialFilter 的写法）===");
        System.out.println("    类名或包名，用 ! 表示拒绝、不带前缀表示允许、* 通配");
        System.out.println("    例：-Djdk.serialFilter=\"!SecDeserFilter$Dangerous;java.base/*;!*\"");
        System.out.println("    含义：先明确拒绝危险类，再放行 java.base 下的类，最后拒绝其余一切。");
    }

    /** 不带任何过滤器的对照组 */
    static void deserNoFilter(byte[] b) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(b))) {
            in.readObject();
        }
    }

    static byte[] ser(Object o) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) { out.writeObject(o); }
        return bos.toByteArray();
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
