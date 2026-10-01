import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

/**
 * 附加方（另一个进程）：找到目标 JVM → 连上去 → 把自己的 agent 塞进去。
 *
 * 用的是 JDK 自带的 `jdk.attach` 模块（`com.sun.tools.attach`），
 * 也就是 `jcmd` / `jstack` / `jmap` 背后同一个机制 ——
 * **运维工具与攻击者共用同一扇门**，区别只在谁有权限按这个门铃。
 *
 * 参数：<目标 pid> <agent jar 路径> <补丁 class 文件路径>
 */
public class SecAttach {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("用法: SecAttach <pid> <agent.jar> <patched.class>");
            System.exit(2);
        }
        String pid = args[0], jar = args[1], patched = args[2];

        System.out.println("    [附加方] 当前能看到的 Java 进程：");
        for (VirtualMachineDescriptor d : VirtualMachine.list()) {
            System.out.println("        pid=" + d.id() + "  " + d.displayName());
        }

        System.out.println("    [附加方] 试着连上 pid=" + pid + " …");
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            String ver = vm.getSystemProperties().getProperty("java.version");
            System.out.println("    [附加方] 已连上（读到了目标的 java.version = " + ver + "）");
            System.out.println("    [附加方] 把 agent 塞进目标：loadAgent(jar, options)");
            vm.loadAgent(jar, patched);
            System.out.println("    [附加方] loadAgent 返回 —— 目标那边已经执行完 agentmain");
        } finally {
            vm.detach();
            System.out.println("    [附加方] 已断开（detach）；目标进程照旧在跑");
        }
    }
}
