import java.beans.XMLDecoder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import org.w3c.dom.Document;

/**
 * 常见攻击面 API 的默认行为（只读/自建靶子，全部本机实测）。
 * 关心的不是「能不能打」，而是「默认配置下是什么行为、改哪个开关能关掉」。
 */
public class SecDangerApis {

    static class SideEffect {
        static int count = 0;
        static void fire(String s) { count++; System.out.println("    >> [副作用触发 #" + count + "] " + s); }
    }

    /** 给 XMLDecoder 当靶子：一个普通的 JavaBean（有 setter 就会被调用） */
    public static class Bean {
        private String value;
        public void setValue(String v) { this.value = v; SideEffect.fire("Bean.setValue(" + v + ")"); }
        public String getValue() { return value; }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1) 命令执行原语：ProcessBuilder / Runtime.exec ===");
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "echo hello-from-java");
        Process pr = pb.start();
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        System.out.println("    执行 cmd /c echo … → 输出: " + out + "，退出码 " + pr.waitFor());
        System.out.println("    注意：Java 不做任何「命令安全」判断，参数怎么拼就怎么执行；");
        System.out.println("    要防的是**把不可信输入拼进这里**（拼接式命令注入），不是这个 API 本身。");
        try {
            Runtime.getRuntime().exec(new String[]{"cmd", "/c", "echo", "arg-form"});
            System.out.println("    Runtime.exec(String[]) 形式可用（数组形式不经 shell，分隔符不会被当元字符）");
        } catch (Exception e) {
            System.out.println("    Runtime.exec(String[]) 抛 " + e.getClass().getSimpleName());
        }
        Path probe = Files.createTempFile("sec", ".txt");
        System.out.println("    ProcessBuilder 重定向到文件也是同一个原语：" + probe.getFileName());

        System.out.println();
        System.out.println("=== 2) XXE：默认配置下外部实体解不解析？（本机实测）===");
        Path xxe = Files.createTempFile("xxe", ".xml");
        Path secretFile = Files.createTempFile("secret", ".txt");
        Files.writeString(secretFile, "SECRET-CONTENT-1234567890", StandardCharsets.UTF_8);
        String secretUri = secretFile.toUri().toString();      // 正确写法：file:///C:/...
        Files.writeString(xxe, "<?xml version=\"1.0\"?>\n<!DOCTYPE r [ <!ENTITY xxe SYSTEM \"" + secretUri + "\"> ]>\n<r>&xxe;</r>\n", StandardCharsets.UTF_8);
        System.out.println("    实体指向的本机文件: " + secretFile);
        System.out.println("    XML 里用的 URI:    " + secretUri);
        System.out.println("    -- 默认 newInstance() --");
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            Document d = f.newDocumentBuilder().parse(xxe.toFile());
            String text = d.getDocumentElement().getTextContent().trim();
            if (text.equals("SECRET-CONTENT-1234567890")) {
                System.out.println("    解析成功，实体内容 = \"" + text + "\"");
                System.out.println("    ↑ 默认配置下外部实体**被展开了**：这就是 XXE 能读到本机文件的原因");
            } else {
                System.out.println("    解析成功，但实体内容为空（长度 " + text.length() + "）→ 默认挡住了");
            }
        } catch (Exception e) {
            System.out.println("    解析抛 " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }
        System.out.println("    -- 加固写法（禁用 DTD 与外部实体）--");
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.newDocumentBuilder().parse(xxe.toFile());
            System.out.println("    解析成功（不该发生）");
        } catch (Exception e) {
            System.out.println("    被拒：" + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }

        System.out.println();
        System.out.println("=== 3) XMLDecoder：把 XML 直接「还原成对象」的另一个反序列化入口 ===");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<java><object class=\"SecDangerApis$Bean\"><void property=\"value\"><string>来自XML的调用</string></void></object></java>\n";
        System.out.println("    输入 XML 里写了类名与方法名，看它会不会照做：");
        SideEffect.count = 0;
        try (XMLDecoder dec = new XMLDecoder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))) {
            Object o = dec.readObject();
            System.out.println("    还原出的对象 = " + o.getClass().getName() + "，value = " + ((Bean) o).getValue());
            System.out.println("    setter 被调用次数 = " + SideEffect.count + " ← 又一处「数据变代码」");
        } catch (Exception e) {
            System.out.println("    抛 " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }
        System.out.println("    加固：不要用 XMLDecoder 读外部数据；必须读时用 ObjectInputFilter 或干脆换成 JSON + 显式校验。");

        System.out.println();
        System.out.println("=== 4) 脚本引擎：Nashorn 已移除，这条老路本机走不通 ===");
        try {
            Object mgr = Class.forName("javax.script.ScriptEngineManager").getDeclaredConstructor().newInstance();
            Object eng = mgr.getClass().getMethod("getEngineByName", String.class).invoke(mgr, "nashorn");
            System.out.println("    getEngineByName(\"nashorn\") = " + eng + "（null 表示本 JDK 没有 JS 引擎）");
        } catch (Throwable t) {
            System.out.println("    查询脚本引擎抛 " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        System.out.println();
        System.out.println("=== 5) 路径遍历：API 层不管「..」，要自己规范化 ===");
        Path base = Files.createTempDirectory("secbase");
        Path inside = base.resolve("a.txt");
        Files.writeString(inside, "inside", StandardCharsets.UTF_8);
        Path evil = base.resolve("../" + base.getFileName() + "-escape.txt");
        Files.writeString(base.getParent().resolve(base.getFileName() + "-escape.txt"), "outside", StandardCharsets.UTF_8);
        System.out.println("    直接用拼出来的路径读: " + evil.getFileName());
        System.out.println("    → 读到内容: " + Files.readString(evil).trim() + "（跨出了 base 目录）");
        System.out.println("    规范化后: " + base.resolve("../" + base.getFileName() + "-escape.txt").normalize().getParent().equals(base.getParent()));
        System.out.println("    纪律：base.resolve(userInput).normalize().startsWith(base) 才允许读。");

        Files.deleteIfExists(xxe); Files.deleteIfExists(secretFile); Files.deleteIfExists(inside);
        Files.deleteIfExists(base.getParent().resolve(base.getFileName() + "-escape.txt"));
        Files.deleteIfExists(base); Files.deleteIfExists(probe);
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
