/**
 * 被 agent 盯上的类（**版本 A：原始实现**）。
 * 同名的「版本 B」在 patch/Victim.java，编译到另一个目录，用来做运行期替换的对照。
 */
public class Victim {
    public static String status() {
        return "原始实现：我是磁盘上编译出来的那个版本";
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
