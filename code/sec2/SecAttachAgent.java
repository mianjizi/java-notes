import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 动态附加用的 agent —— 它和实验 1 里 `-javaagent:` 启动的那个**不是一回事**：
 *
 *   premain （启动期）：JVM 启动时由 `-javaagent:` 调起，必须在命令行里
 *   agentmain（运行期）：目标 JVM **已经在跑**，由外部进程 attach 进来后调起
 *
 * 关键差别：agentmain 时目标进程**没重启、没改启动参数、磁盘上的字节码也没动**。
 * 这里的补丁字节通过 loadAgent 的 options 传进来（不是启动参数，目标进程看不到它）。
 */
public class SecAttachAgent {

    public static void agentmain(String options, Instrumentation inst) {
        System.out.println("    [attach-agent] agentmain 已执行 —— 目标 JVM 没有重启");
        System.out.println("    [attach-agent] options（由 attach 方传入）= " + options);
        System.out.println("    [attach-agent] 能重定义已加载的类吗？" + inst.isRedefineClassesSupported());
        try {
            byte[] patched = Files.readAllBytes(Path.of(options));
            Class<?> victim = Class.forName("VictimA");
            System.out.println("    [attach-agent] 补丁字节数 = " + patched.length + "，目标类 = " + victim);
            System.out.println("    [attach-agent] 该类可被重定义吗？" + inst.isModifiableClass(victim));
            inst.redefineClasses(new ClassDefinition(victim, patched));
            System.out.println("    [attach-agent] redefineClasses 完成 —— 磁盘上的 VictimA.class 没被碰过");
        } catch (Throwable t) {
            System.out.println("    [attach-agent] 失败：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }
}
