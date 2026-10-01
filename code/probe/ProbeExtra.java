import java.util.concurrent.ForkJoinPool;

/** 补测探针：几条"看结构"的读数（类加载器、公共池并行度、堆上限） */
public class ProbeExtra {
    public static void main(String[] args) {
        p("String.class.getClassLoader()", String.valueOf(String.class.getClassLoader()));
        p("本探针的类加载器", String.valueOf(ProbeExtra.class.getClassLoader()));
        p("系统类加载器", String.valueOf(ClassLoader.getSystemClassLoader()));
        p("平台类加载器（旧名扩展）", String.valueOf(ClassLoader.getPlatformClassLoader()));
        p("ForkJoinPool.commonPool().getParallelism()", String.valueOf(ForkJoinPool.commonPool().getParallelism()));
        p("availableProcessors", String.valueOf(Runtime.getRuntime().availableProcessors()));
        p("maxMemory", (Runtime.getRuntime().maxMemory() >> 20) + " MiB");
        p("Object 是不是所有类的父类", String.valueOf(Object.class.isAssignableFrom(String.class)));
        p("一个普通对象的类", String.valueOf(new Object().getClass()));
        p("系统模块总数（ModuleFinder，等于 java --list-modules 的行数）",
          String.valueOf(java.lang.module.ModuleFinder.ofSystem().findAll().size()));
        p("引导层已加载模块数（ModuleLayer.boot）", String.valueOf(ModuleLayer.boot().modules().size()));
        p("java.class.version", System.getProperty("java.class.version"));
        p("Runtime.version()", String.valueOf(Runtime.version()));
    }

    static void p(String k, String v) {
        System.out.printf("%-40s = %s%n", k, v);
    }
}
