import java.io.IOException;

/** 不捕获的嵌套异常：看 JVM 默认怎么打印异常链（Caused by） */
public class RunChained {
    public static void main(String[] args) throws Exception {
        try {
            throw new IOException("底层原因：文件不存在");
        } catch (IOException e) {
            throw new IllegalStateException("上层包装：读取配置失败", e);
        }
    }
}
