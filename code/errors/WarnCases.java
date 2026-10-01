import java.util.ArrayList;
import java.util.List;

/** 警告样例：能编译通过，但 javac 会给出警告（默认大部分不显示，要 -Xlint 才打开） */
public class WarnCases {

    @Deprecated
    static void oldApi() { }

    public static void main(String[] args) {
        List raw = new ArrayList();        // 原始类型：丢掉了泛型检查
        raw.add("a string");
        List<String> typed = new ArrayList<>();
        typed = raw;                        // 未检查的转换

        int x = 1;
        switch (x) {                        // 少写 break：fallthrough 警告
            case 1:
                System.out.println("one");
            case 2:
                System.out.println("two");
                break;
            default:
                break;
        }

        oldApi();                           // 调用已废弃的方法
        System.out.println("raw = " + raw + " typed = " + typed);
    }
}
