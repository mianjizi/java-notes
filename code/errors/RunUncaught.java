/** 完全不捕获：看 JVM 默认怎么打未捕获异常（进程退出码 1） */
public class RunUncaught {
    public static void main(String[] args) {
        System.out.println("main 开始");
        helper();
        System.out.println("这一行不会执行");
    }

    static void helper() {
        String s = null;
        System.out.println(s.length());     // 未捕获的 NPE：栈信息会一路打到 main
    }
}
