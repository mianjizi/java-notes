/** 类型探针：Java 与 C 最大的区别之一——基本类型宽度由语言定死，不随平台变 */
public class ProbeTypes {
    public static void main(String[] args) {
        System.out.printf("byte  %d bit%n", Byte.SIZE);
        System.out.printf("short %d bit%n", Short.SIZE);
        System.out.printf("int   %d bit%n", Integer.SIZE);
        System.out.printf("long  %d bit%n", Long.SIZE);
        System.out.printf("char  %d bit（UTF-16 码元，不是字节）%n", Character.SIZE);
        System.out.printf("float %d bit / double %d bit%n", Float.SIZE, Double.SIZE);

        System.out.println("--- 溢出：静默回绕，不抛异常 ---");
        int max = Integer.MAX_VALUE;
        System.out.println("Integer.MAX_VALUE     = " + max);
        System.out.println("MAX_VALUE + 1         = " + (max + 1));
        System.out.println("MAX_VALUE * 2         = " + (max * 2));
        System.out.println("Integer.MIN_VALUE - 1 = " + (Integer.MIN_VALUE - 1));
        System.out.println("Math.addExact 同运算   -> " + tryAddExact(max, 1));

        System.out.println("--- 整数除法截断与除零 ---");
        System.out.println("-7 / 2  = " + (-7 / 2) + "   （向零截断，不是向下取整）");
        System.out.println("-7 % 2  = " + (-7 % 2));
        System.out.println("7 / 0   -> " + tryDiv(7, 0) + "（整数除零抛异常，不是未定义行为）");
        System.out.println("7.0 / 0 = " + (7.0 / 0) + "   （浮点除零得 Infinity）");

        System.out.println("--- char 不是字节：中文与 emoji ---");
        String cn = "中";
        String emoji = "😀";
        System.out.println("'中' 的 char 数（length）= " + cn.length()
                + "，码点数 = " + cn.codePointCount(0, cn.length()));
        System.out.println("'😀' 的 char 数（length）= " + emoji.length()
                + "，码点数 = " + emoji.codePointCount(0, emoji.length())
                + "  （一个 char 装不下 -> 代理对）");
        System.out.println("按字节数 UTF-8 编码：'中' = " + cn.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + " 字节，'😀' = " + emoji.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " 字节");

        System.out.println("--- 自动装箱与缓存（== 比较的经典陷阱）---");
        Integer a = 127, b = 127, c = 128, d = 128;
        System.out.println("Integer 127 == 127 -> " + (a == b) + "（缓存 -128..127）");
        System.out.println("Integer 128 == 128 -> " + (c == d) + "（超出缓存，两个对象）");
        System.out.println("Integer 128 equals 128 -> " + c.equals(d));

        System.out.println("--- 浮点：不要用 == 比小数 ---");
        System.out.println("0.1 + 0.2        = " + (0.1 + 0.2));
        System.out.println("0.1 + 0.2 == 0.3 -> " + (0.1 + 0.2 == 0.3));
        System.out.println("BigDecimal       = " + new java.math.BigDecimal("0.1").add(new java.math.BigDecimal("0.2")));

        System.out.println("--- 局部变量类型推断 var（Java 10+）---");
        var list = java.util.List.of(1, 2, 3);
        System.out.println("var list = List.of(1,2,3) -> " + list.getClass().getName() + " " + list);
    }

    static String tryAddExact(int a, int b) {
        try { return String.valueOf(Math.addExact(a, b)); }
        catch (ArithmeticException e) { return "ArithmeticException: " + e.getMessage(); }
    }

    static String tryDiv(int a, int b) {
        try { return String.valueOf(a / b); }
        catch (ArithmeticException e) { return "ArithmeticException: " + e.getMessage(); }
    }
}
