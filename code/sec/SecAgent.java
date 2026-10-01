import java.lang.instrument.Instrumentation;

/**
 * javaagent：JVM 允许一个 jar 在**主程序之前**拿到 Instrumentation 句柄。
 * 拿到它之后能做什么？本程序只做两件无害的事：
 *   1) 数一数进程里已加载了多少个类（攻击者与 APM 都关心这个）
 *   2) 把**已经加载**的 Victim 类换成另一份实现（redefineClasses）
 * 这就是「运行时植入、磁盘上不留痕迹」的原语；APM / 热部署工具用的是同一套 API。
 */
public class SecAgent {

    static Instrumentation inst;

    /** JVM 启动时（main 之前）被调用：agent 的入口 */
    public static void premain(String args, Instrumentation instrumentation) {
        inst = instrumentation;
        System.out.println("    [agent] premain 已执行，拿到 Instrumentation（主程序还没开始）");
        System.out.println("    [agent] 进程内已加载类数量 = " + instrumentation.getAllLoadedClasses().length);
        System.out.println("    [agent] 能改动已加载的类吗？" + instrumentation.isRetransformClassesSupported()
                + " / 能重新定义吗？" + instrumentation.isRedefineClassesSupported());
    }

    /** 也支持运行期附加（本机演示用不到，列出来是因为它是同一条路） */
    public static void agentmain(String args, Instrumentation instrumentation) {
        inst = instrumentation;
        System.out.println("    [agent] agentmain 被调用（运行期附加路径）");
    }

    /** 把 Victim 换成补丁版本：字节码来自另一份编译产物 */
    static void applyPatch(String patchedClassFile) {
        if (inst == null) { System.out.println("    [agent] 没有 Instrumentation，跳过"); return; }
        try {
            byte[] patched = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(patchedClassFile));
            Class<?> victim = Class.forName("Victim");
            System.out.println("    [agent] 补丁字节数 = " + patched.length + "，目标类 = " + victim);
            System.out.println("    [agent] 该类可被重新定义吗？" + inst.isModifiableClass(victim));
            inst.redefineClasses(new java.lang.instrument.ClassDefinition(victim, patched));
            System.out.println("    [agent] redefineClasses 完成 —— 磁盘上没有任何文件被改动");
        } catch (Throwable t) {
            System.out.println("    [agent] 换实现失败: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }
}
