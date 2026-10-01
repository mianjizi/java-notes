import java.nio.charset.Charset;
import java.util.Arrays;
import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;

/** 环境探针：本机 JDK 的真实版本、编码、GC、内存口径 */
public class ProbeEnv {
    public static void main(String[] args) {
        Runtime rt = Runtime.getRuntime();
        p("java.version", System.getProperty("java.version"));
        p("java.version.date", System.getProperty("java.version.date"));
        p("java.vendor", System.getProperty("java.vendor"));
        p("java.vendor.version", System.getProperty("java.vendor.version"));
        p("java.runtime.name", System.getProperty("java.runtime.name"));
        p("java.vm.name", System.getProperty("java.vm.name"));
        p("java.vm.version", System.getProperty("java.vm.version"));
        p("java.home", System.getProperty("java.home"));
        p("java.class.version", System.getProperty("java.class.version"));
        p("os.name", System.getProperty("os.name"));
        p("os.version", System.getProperty("os.version"));
        p("os.arch", System.getProperty("os.arch"));
        p("Runtime.version()", String.valueOf(Runtime.version()));
        p("file.encoding", System.getProperty("file.encoding"));
        p("native.encoding", System.getProperty("native.encoding"));
        p("stdout.encoding", System.getProperty("stdout.encoding"));
        p("sun.jnu.encoding", System.getProperty("sun.jnu.encoding"));
        p("Charset.defaultCharset()", String.valueOf(Charset.defaultCharset()));
        p("line.separator", esc(System.lineSeparator()));
        p("availableProcessors", String.valueOf(rt.availableProcessors()));
        p("maxMemory", (rt.maxMemory() >> 20) + " MiB");
        p("totalMemory(启动初)", (rt.totalMemory() >> 20) + " MiB");
        String gcs = String.join(", ",
                ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .map(GarbageCollectorMXBean::getName).toList());
        p("GC collectors", gcs);
        p("虚拟线程可用", String.valueOf(probeVirtualThread()));
        p("已加载模块数", String.valueOf(ModuleLayer.boot().modules().size()));
    }

    static boolean probeVirtualThread() {
        try { Thread.ofVirtual().name("probe").start(() -> {}).join(); return true; }
        catch (Throwable t) { return false; }
    }

    static void p(String k, String v) {
        System.out.printf("%-24s = %s%n", k, v);
    }

    static String esc(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }
}
