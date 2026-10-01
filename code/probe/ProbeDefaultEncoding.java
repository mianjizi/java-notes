import java.nio.charset.Charset;

/**
 * 平台默认编码探针：**故意不带任何 -D 编码开关**跑，用来记录「不加开关时机器是什么样」。
 * 输出必须全是 ASCII（键名与值都是 ASCII），否则这段输出本身就会变成乱码、没法读。
 */
public class ProbeDefaultEncoding {
    public static void main(String[] args) {
        p("file.encoding", System.getProperty("file.encoding"));
        p("native.encoding", System.getProperty("native.encoding"));
        p("stdout.encoding", System.getProperty("stdout.encoding"));
        p("stderr.encoding", System.getProperty("stderr.encoding"));
        p("stdin.encoding", System.getProperty("stdin.encoding"));
        p("sun.jnu.encoding", System.getProperty("sun.jnu.encoding"));
        p("Charset.defaultCharset()", String.valueOf(Charset.defaultCharset()));
        p("console redirected (System.console() == null)", String.valueOf(System.console() == null));
    }

    static void p(String k, String v) {
        System.out.printf("%-24s = %s%n", k, v);
    }
}
