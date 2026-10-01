import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 实验 2：**不写第二份源码**，直接在进程内改字节码 —— 真 gadget 链、
 * 真实内存马、真实 APM 用的都是这一层能力（JDK 24 起标准库自带 Class-File API，JEP 484）。
 *
 * 与实验 1 的差别只有一句「补丁字节从哪来」：
 *   实验 1：补丁字节 = 另一份 `VictimA.java` 编译出来的 `.class`（要先有一份改过的源码）
 *   实验 2：补丁字节 = **读原类的字节 → 用 API 改掉 status() 的方法体 → 原地得到新字节**
 *
 * 改法：
 *   parse 原字节 → 逐个搬 ClassElement → 遇到 status() 就换成新方法体 → build 出新字节
 *   redefineClasses 换上去（要求 schema 不变、只换实现）
 */
public class SecRewriteAgent {

    public static void premain(String args, Instrumentation inst) { work(inst, "premain"); }

    public static void agentmain(String args, Instrumentation inst) { work(inst, "agentmain"); }

    static void work(Instrumentation inst, String how) {
        System.out.println("    [rewrite-agent/" + how + "] 拿到 Instrumentation（重定义支持 = "
                + inst.isRedefineClassesSupported() + "）");
        try {
            String p = System.getProperty("victim.class.file");
            byte[] original = Files.readAllBytes(Path.of(p));
            String newReturn = "被 Class-File API 在进程内改写的实现（磁盘上没有这份字节）";
            byte[] rewritten = rewriteStatus(original, newReturn);

            System.out.println("    [rewrite-agent] 原字节数 = " + original.length
                    + "，改写后字节数 = " + rewritten.length
                    + "，两份不同吗？" + !Arrays.equals(original, rewritten));

            // 反查证据：把改写后的字节再 parse 一遍，看 status() 里现在是什么
            ClassModel cm = ClassFile.of().parse(rewritten);
            cm.methods().stream()
                    .filter(m -> m.methodName().stringValue().equals("status"))
                    .forEach(m -> System.out.println("    [rewrite-agent] 改写后的 status() 代码属性 = "
                            + m.code().map(c -> c.elementList().toString()).orElse("(无)")));

            Class<?> victim = Class.forName("VictimA");
            System.out.println("    [rewrite-agent] 目标类 = " + victim
                    + "，可重定义吗？" + inst.isModifiableClass(victim));
            inst.redefineClasses(new ClassDefinition(victim, rewritten));
            System.out.println("    [rewrite-agent] redefineClasses 完成 —— 磁盘上的 VictimA.class 一个字节没动");
        } catch (Throwable t) {
            System.out.println("    [rewrite-agent] 失败：" + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
        }
    }

    /** 把 status() 的方法体换成 「ldc <新串>; areturn」——其余元素原样搬运。 */
    static byte[] rewriteStatus(byte[] classBytes, String newReturn) {
        ClassFile cf = ClassFile.of();
        ClassModel cm = cf.parse(classBytes);
        return cf.build(cm.thisClass().asSymbol(), cb -> {
            for (ClassElement ce : cm) {
                if (ce instanceof MethodModel mm && mm.methodName().stringValue().equals("status")) {
                    replaceMethod(cb, mm, newReturn);
                } else {
                    cb.with(ce);
                }
            }
        });
    }

    private static void replaceMethod(ClassBuilder cb, MethodModel mm, String newReturn) {
        cb.withMethodBody(mm.methodName().stringValue(), mm.methodTypeSymbol(),
                mm.flags().flagsMask(), cob -> {
                    cob.ldc(newReturn);   // 把新字符串压入常量池并 ldc
                    cob.areturn();        // 返回它
                });
    }
}
