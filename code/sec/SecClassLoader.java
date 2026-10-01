import java.io.IOException;
import java.nio.file.*;

/**
 * 「内存马」的原理内核：**字节码不必来自磁盘文件**。
 * 看懂这一段，就明白了为什么「查 webshell 文件」查不到内存里的东西。
 * 本程序只加载自己编译出来的无害 Payload，不下载、不落地。
 */
public class SecClassLoader {

    /** 只负责把字节数组变成类 —— 这就是内存马用的那一步 */
    static class ByteLoader extends ClassLoader {
        private final byte[] bytes;
        private final String name;
        private final boolean breakDelegation;
        ByteLoader(byte[] bytes, String name, boolean breakDelegation) {
            this.bytes = bytes; this.name = name; this.breakDelegation = breakDelegation;
        }

        @Override
        protected Class<?> loadClass(String cn, boolean resolve) throws ClassNotFoundException {
            if (breakDelegation && cn.equals(name)) {
                // 关键：先自己定义，不去问父加载器 —— 这一步就是「绕过双亲委派」
                Class<?> c = findLoadedClass(cn);
                if (c == null) c = defineClass(cn, bytes, 0, bytes.length);
                if (resolve) resolveClass(c);
                return c;
            }
            return super.loadClass(cn, resolve);
        }

        @Override
        protected Class<?> findClass(String cn) throws ClassNotFoundException {
            if (!cn.equals(name)) throw new ClassNotFoundException(cn);
            return defineClass(cn, bytes, 0, bytes.length);
        }
    }

    public static void main(String[] args) throws Exception {
        Path payloadClass = Path.of(args.length > 0 ? args[0] : "out/Payload.class");
        if (!Files.exists(payloadClass)) {
            System.out.println("找不到 " + payloadClass.toAbsolutePath() + "（先编译 Payload.java）");
            return;
        }
        byte[] bytes = Files.readAllBytes(payloadClass);
        System.out.println("读到 Payload.class 字节数 = " + bytes.length + "（从这里开始就不再需要那个文件了）");

        System.out.println();
        System.out.println("=== 1) 先看「不绕委派」会怎样：父加载器赢，字节白读了 ===");
        Class<?> cDelegate = new ByteLoader(bytes, "Payload", false).loadClass("Payload");
        Class<?> cByApp = Class.forName("Payload");
        System.out.println("    委派型加载器拿到的类 = " + cDelegate + "，加载器 = " + cDelegate.getClassLoader());
        System.out.println("    直接用 Class.forName 拿到的类 = " + cByApp + "，加载器 = " + cByApp.getClassLoader());
        System.out.println("    两者是同一个类吗？" + (cDelegate == cByApp)
                + "  ← true 说明父加载器先命中，findClass 根本没被调用（双亲委派在起作用）");

        System.out.println();
        System.out.println("=== 2) 绕过委派：字节数组变成真正独立的一个类 ===");
        Class<?> c = new ByteLoader(bytes, "Payload", true).loadClass("Payload");
        Object obj = c.getDeclaredConstructor().newInstance();
        System.out.println("    调用结果：" + c.getMethod("say").invoke(obj));
        System.out.println("    这个类的加载器 = " + c.getClassLoader());
        System.out.println("    和上一步那个类相同吗？" + (c == cByApp) + "  ← false：类身份 = 类名 + 定义它的加载器");
        System.out.println("    再 new 一个同样的加载器定义一次：结果还等于上面这个吗？"
                + (new ByteLoader(bytes, "Payload", true).loadClass("Payload") == c) + "  ← 每个加载器实例都是一个新类");

        System.out.println();
        System.out.println("=== 3) 检测视角：内存里定义的类和磁盘上的类，哪里不一样 ===");
        System.out.println("    绕过委派那个类的 CodeSource = " + c.getProtectionDomain().getCodeSource()
                + "   ← null 表示「没有任何磁盘来源」");
        System.out.println("    正常加载（走委派）那个类的 CodeSource = " + cByApp.getProtectionDomain().getCodeSource());
        System.out.println("    ↑ 正常部署的类会指向 jar 或 classes 目录；内存里 defineClass 出来的没有来源可查。");

        System.out.println();
        System.out.println("=== 4) 双亲委派：默认是防篡改的，绕过它才叫内存马 ===");
        System.out.println("    默认（loadClass → 先问父加载器）：同名类以父加载器找到的为准，");
        System.out.println("    所以把恶意 class 塞进 classpath 的**顺序**改不掉结果，改了磁盘文件也拦不住篡改。");
        System.out.println("    绕过后：defineClass 出来的类与父加载器那个同名类**可以并存**，来源为 null、不落盘——");
        System.out.println("    这就是「内存马」查不到文件的机制层面原因（另一边要靠 dump 堆/查已加载类来找）。");
    }
}
