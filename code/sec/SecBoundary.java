import java.lang.reflect.Field;

/**
 * 本机 JDK 25 安全画像探针：把「能做什么、默认挡住了什么」逐条量出来。
 * 全部是只读/无害探测，不改系统状态。
 */
public class SecBoundary {
    public static void main(String[] args) {
        p("java.version", System.getProperty("java.version"));
        p("java.vendor", System.getProperty("java.vendor"));

        // 1) Security Manager：JEP 411 弃用 → JEP 486 永久禁用（JDK 24 起）
        System.out.println("--- Security Manager（沙箱时代的遗产）---");
        try {
            Object sm = System.getSecurityManager();
            p("System.getSecurityManager()", String.valueOf(sm));
        } catch (Throwable t) {
            p("System.getSecurityManager() 抛", t.getClass().getName() + ": " + t.getMessage());
        }
        try {
            System.setSecurityManager(null);
            p("System.setSecurityManager(null)", "成功（说明还允许设置）");
        } catch (Throwable t) {
            p("System.setSecurityManager(null) 抛", t.getClass().getName() + ": " + t.getMessage());
        }

        // 2) JNDI 远程加载类的开关：这两个属性的默认值是 Log4Shell 之后最常被问的
        System.out.println("--- JNDI 远程 codebase（Log4Shell 的关键开关）---");
        p("com.sun.jndi.ldap.object.trustURLCodebase", def("com.sun.jndi.ldap.object.trustURLCodebase"));
        p("com.sun.jndi.rmi.object.trustURLCodebase", def("com.sun.jndi.rmi.object.trustURLCodebase"));
        p("com.sun.jndi.ldap.object.trustSerialData", def("com.sun.jndi.ldap.object.trustSerialData"));

        // 3) 反序列化全局过滤器
        System.out.println("--- 反序列化（JEP 290 过滤器）---");
        p("jdk.serialFilter", def("jdk.serialFilter"));

        // 4) 脚本引擎：Nashorn 在 JDK 15 移除
        System.out.println("--- 脚本引擎（JEP 335 移除 Nashorn 之后）---");
        try {
            Object mgr = Class.forName("javax.script.ScriptEngineManager").getDeclaredConstructor().newInstance();
            Object engines = mgr.getClass().getMethod("getEngineFactories").invoke(mgr);
            p("可用脚本引擎数", String.valueOf(((java.util.List<?>) engines).size()));
        } catch (Throwable t) {
            p("脚本引擎查询", t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // 5) 模块强封装：反射能不能摸到 JDK 内部
        System.out.println("--- 模块强封装（JEP 403：JDK 17 起不再允许松动）---");
        probe("sun.misc.Unsafe", "sun.misc.Unsafe", "theUnsafe");
        probe("jdk.internal.misc.Unsafe", "jdk.internal.misc.Unsafe", "theUnsafe");
        probe("jdk.internal.access.SharedSecrets", "jdk.internal.access.SharedSecrets", null);
        try {
            Field f = String.class.getDeclaredField("value");
            f.setAccessible(true);
            p("反射改 String.value", "可访问（未强封装）");
        } catch (Throwable t) {
            p("反射改 String.value", t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // 6) 攻击面相关的常用 API 是否存在于本 JDK
        System.out.println("--- 常见攻击面 API 的存在性 ---");
        for (String cn : new String[]{"java.io.ObjectInputStream", "javax.naming.InitialContext",
                "java.lang.instrument.Instrumentation", "java.lang.reflect.Proxy",
                "javax.xml.parsers.DocumentBuilderFactory", "jdk.jfr.Recording"}) {
            try { Class.forName(cn); p(cn, "存在"); }
            catch (Throwable t) { p(cn, "不存在（" + t.getClass().getSimpleName() + "）"); }
        }
    }

    static String def(String key) {
        String v = System.getProperty(key);
        return v == null ? "(未设置 → 用 JVM 内置默认)" : v;
    }

    static void probe(String label, String cls, String field) {
        try {
            Class<?> c = Class.forName(cls);
            if (field == null) { p(label, "类可加载：" + c); return; }
            Field f = c.getDeclaredField(field);
            f.setAccessible(true);
            p(label, "反射拿到实例：" + f.get(null));
        } catch (Throwable t) {
            p(label, t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).split("\n")[0]);
        }
    }

    static void p(String k, String v) {
        System.out.printf("%-46s = %s%n", k, v);
    }
}
