import java.util.*;
import java.util.stream.*;

/** 面向对象探针：记录类、密封接口、模式匹配、equals/hashCode 契约 */
public class ProbeOop {

    record Point(int x, int y) {}                       // 记录类（Java 16+）：不可变数据载体

    sealed interface Shape permits Circle, Square {}     // 密封接口（Java 17+）
    record Circle(double r) implements Shape {}
    record Square(double a) implements Shape {}

    enum Level { LOW, MID, HIGH }                        // 枚举：本身就是类

    static double area(Shape s) {
        return switch (s) {                              // switch 模式匹配（Java 21 正式）
            case Circle c -> Math.PI * c.r() * c.r();
            case Square q -> q.a() * q.a();
        };
    }

    static class BadEquals {                             // 反面教材：equals 改了 hashCode 没改
        final int id;
        BadEquals(int id) { this.id = id; }
        @Override public boolean equals(Object o) {
            return o instanceof BadEquals b && b.id == id;
        }
    }

    public static void main(String[] args) {
        System.out.println("--- 记录类：自动得到 equals/hashCode/toString/访问器 ---");
        var p1 = new Point(3, 4);
        var p2 = new Point(3, 4);
        System.out.println("p1 = " + p1);
        System.out.println("p1.equals(p2) = " + p1.equals(p2) + "（值语义，不用自己写）");
        System.out.println("p1.x() = " + p1.x() + "（访问器不是 getX()）");
        System.out.println("是 final 类吗 = " + java.lang.reflect.Modifier.isFinal(Point.class.getModifiers()));

        System.out.println("--- 密封接口 + switch 模式匹配：穷尽性由编译器检查 ---");
        List<Shape> shapes = List.of(new Circle(1), new Square(2));
        for (Shape s : shapes) System.out.printf("%-22s 面积 = %.4f%n", s, area(s));

        System.out.println("--- equals/hashCode 契约：只改一半会出什么事 ---");
        var bad = new BadEquals(7);
        var set = new HashSet<BadEquals>();
        set.add(bad);
        System.out.println("contains(等值新对象) = " + set.contains(new BadEquals(7))
                + "（equals 说相等，但 HashSet 按 hashCode 找，找不到）");

        System.out.println("--- 枚举：可以带字段与方法，switch 也是穷尽的 ---");
        for (Level l : Level.values())
            System.out.printf("%-5s ordinal=%d name()=%s%n", l, l.ordinal(), l.name());
        System.out.println("valueOf(\"MID\") = " + Level.valueOf("MID"));

        System.out.println("--- 初始化顺序：静态 -> 实例 -> 构造体 ---");
        new Tracked();

        System.out.println("--- 接口默认方法：不破坏已有实现地加方法 ---");
        System.out.println(new Impl().greet());

        System.out.println("--- Stream：声明式流水线，本身不改变原集合 ---");
        var nums = List.of(5, 1, 4, 2, 3);
        System.out.println("原集合        = " + nums);
        System.out.println("sorted+map    = " + nums.stream().sorted().map(n -> n * 10).toList());
        System.out.println("原集合仍然    = " + nums + "（不可变）");
        System.out.println("过滤求和      = " + nums.stream().filter(n -> n % 2 == 1).mapToInt(Integer::intValue).sum());
        System.out.println("分组          = " + nums.stream().collect(Collectors.groupingBy(n -> n % 2 == 0 ? "偶" : "奇")));
    }

    interface Greeter {
        default String greet() { return "hello from default method"; }
    }
    static class Impl implements Greeter {}

    static class Tracked {
        static { System.out.println("1 静态初始化块"); }
        { System.out.println("  2 实例初始化块"); }
        Tracked() { System.out.println("  3 构造体"); }
    }
}
