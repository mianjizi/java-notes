import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectInputFilter;

/**
 * 实验 3b：把「数组类名在过滤器里怎么写」测清楚。
 *
 * 起因：在 SecFilterAdv 里写了一条自认为对的模式 `![Ljava.lang.String;;java.base/*;!*`，
 * 结果读 String[20] **居然放行了**，与预期相反。所以这里把候选写法一条条试：
 * 数组描述符本身带 `;`，而 `;` 又是过滤器的**模式分隔符** —— 到底哪种写法能精确拒绝一个数组？
 */
public class SecFilterArray {

    public static void main(String[] args) throws Exception {
        byte[] strArr = dump(new String[20]);
        byte[] intArr = dump(new int[20]);

        String[] patterns = {
                "java.base/*;!*",                       // 基线：放行 java.base 下的一切
                "maxarray=8;*",                         // 限值（不点名类）
                "!java.lang.String;java.base/*;!*",      // 拒绝元素类（不写数组描述符）
                "[;java.base/*;!*",                     // 文档里的「任意数组」写法？
                "![;java.base/*;!*",                    // 拒绝任意数组？
                "[Ljava.lang.String;;java.base/*;!*",   // 直接写数组描述符（裸）
                "![Ljava.lang.String;;java.base/*;!*",  // 直接写数组描述符（带 ! 前缀）
                "[I;java.base/*;!*",                    // 基本类型数组描述符
                "![I;java.base/*;!*",
        };

        System.out.println("    === 7) 数组类名在过滤器里怎么写（String[20] 与 int[20] 各试一遍）===");
        for (String p : patterns) {
            System.out.println("        模式 [" + p + "]");
            System.out.println("            → String[20]：" + tryRead(strArr, p));
            System.out.println("            → int[20]   ：" + tryRead(intArr, p));
        }

        System.out.println();
        System.out.println("    === 8) 解析器把这些串拆成了什么（看出分隔规则）===");
        for (String p : patterns) {
            ObjectInputFilter f = ObjectInputFilter.Config.createFilter(p);
            System.out.println("        模式 [" + p + "] → 解析出的过滤器 = " + f);
        }

        System.out.println();
        System.out.println("    === 9) 单个类名模式的「第一个命中生效」验证 ===");
        // 前一条明确拒绝、后面又写放行：应当以「先命中的」为准
        System.out.println("        [!java.lang.String;java.base/*] 读 String[20] → "
                + tryRead(strArr, "!java.lang.String;java.base/*"));
        System.out.println("        [java.base/*;!java.lang.String] 读 String[20] → "
                + tryRead(strArr, "java.base/*;!java.lang.String"));
    }

    static String tryRead(byte[] payload, String pattern) {
        ObjectInputFilter f = ObjectInputFilter.Config.createFilter(pattern);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            in.setObjectInputFilter(f);
            Object o = in.readObject();
            return "读成功（" + o.getClass().getName() + "）";
        } catch (Exception e) {
            return "拦下：" + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
        }
    }

    static byte[] dump(Object o) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) { out.writeObject(o); }
        return bos.toByteArray();
    }

    static String firstLine(String s) {
        if (s == null) return "(无消息)";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
