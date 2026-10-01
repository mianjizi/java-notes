/**
 * 版本 B：与 Victim.java **结构完全一致**（同样有 status() 与 main(String[])），
 * 只有 status() 的实现不同 —— redefineClasses 要求 schema 不变，只换实现。
 */
public class Victim {
    public static String status() {
        return "补丁实现：我是被 agent 在运行期塞进去的那份代码";
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== javaagent 运行期替换已加载的类 ===");
        System.out.println("    [主程序] 加载器 = " + Victim.class.getClassLoader());
        System.out.println("    [主程序] 替换前 status() = " + status());
        String patched = System.getProperty("patched.class.file");
        System.out.println();
        System.out.println("    -- 现在让 agent 用另一份字节码替换这个类 --");
        SecAgent.applyPatch(patched);
        System.out.println();
        System.out.println("    [主程序] 替换后 status() = " + status());
        System.out.println("    ↑ 同一个 Class 对象、同一份已加载代码，实现被换掉了；");
        System.out.println("      磁盘上的 Victim.class 从头到尾没变（可核对修改时间）。");
    }
}
