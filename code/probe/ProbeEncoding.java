import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.charset.Charset;

/** I/O 与编码探针：Windows 中文控制台最容易踩的一类坑 */
public class ProbeEncoding {
    public static void main(String[] args) throws Exception {
        System.out.println("file.encoding      = " + System.getProperty("file.encoding"));
        System.out.println("native.encoding    = " + System.getProperty("native.encoding"));
        System.out.println("stdout.encoding    = " + System.getProperty("stdout.encoding"));
        System.out.println("默认 Charset        = " + Charset.defaultCharset());
        System.out.println("直接打印中文测试：你好，Java");
        String s = "你好，Java";
        System.out.println("s.length()（char 数）= " + s.length()
                + "（2 个汉字 + 1 个全角逗号 + 4 个 ASCII 字母 = 7 个 char）");
        System.out.println("s 的 UTF-8 字节数 = " + s.getBytes(StandardCharsets.UTF_8).length
                + "（3+3+3+4 = 13 字节；char 数与字节数不是一个数）");

        // 同一段内容，两种编码各写一次 —— 只有内容相同，字节数才可比
        String content = "第一行：中文\nsecond line\n";
        Path dir = Files.createTempDirectory("probe-io-");
        Path f = dir.resolve("utf8.txt");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        System.out.println("--- 同一段内容（含 1 行中文 + 1 行 ASCII），显式 UTF-8 读写 ---");
        System.out.println("读回：" + Files.readString(f, StandardCharsets.UTF_8).replace("\n", "\\n"));
        System.out.println("UTF-8 字节数 = " + Files.size(f) + "（中文 12 字节 + 全角冒号 3 字节 + ASCII 与换行 16 字节）");

        Path g = dir.resolve("gbk.txt");
        Files.writeString(g, content, Charset.forName("GBK"));
        System.out.println("同一段内容用 GBK 写，字节数 = " + Files.size(g)
                + "（中文 8 字节 + 全角冒号 2 字节 + ASCII 与换行 16 字节；ASCII 部分两种编码完全一样）");
        try {
            System.out.println("拿 UTF-8 去读 GBK 文件 -> " + Files.readString(g, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            System.out.println("读失败：" + e.getClass().getSimpleName());
        }
        Files.deleteIfExists(f); Files.deleteIfExists(g); Files.deleteIfExists(dir);
    }
}
