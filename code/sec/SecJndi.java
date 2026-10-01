import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicInteger;
import javax.naming.*;
import javax.naming.spi.NamingManager;

/**
 * JNDI / Reference 的关键开关：**JVM 会不会去下载并加载远程类**。
 * 全部在 127.0.0.1 上自建：一个 RMI registry + 一个只统计请求数的 HTTP 服务。
 * 目的是量出「默认信任策略」到底是什么，而不是复现一次可用的攻击。
 */
public class SecJndi {

    static final AtomicInteger httpHits = new AtomicInteger();

    /** 自定义 RMI 对象：被 lookup 时返回一个 Reference（方法名不能叫 getRef，与 RemoteObject 的 final 方法冲突） */
    public interface RefService extends Remote {
        Object fetch() throws RemoteException;
    }

    public static class RefServiceImpl extends UnicastRemoteObject implements RefService {
        private final Reference ref;
        public RefServiceImpl(Reference ref) throws RemoteException { this.ref = ref; }
        @Override public Object fetch() { return ref; }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 0) 先看默认属性 ===");
        for (String k : new String[]{"com.sun.jndi.rmi.object.trustURLCodebase",
                "com.sun.jndi.ldap.object.trustURLCodebase",
                "com.sun.jndi.cosnaming.object.trustURLCodebase"}) {
            System.out.println("    " + k + " = " + System.getProperty(k, "(未设置 → 用 JVM 内置默认)"));
        }

        // 本地 HTTP：只统计「有没有人来取 class」
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            System.out.println("      >> [HTTP 收到请求 #" + httpHits.incrementAndGet() + "] " + ex.getRequestURI());
            byte[] body = new byte[0];
            ex.sendResponseHeaders(404, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        http.start();
        int httpPort = http.getAddress().getPort();
        String codebase = "http://127.0.0.1:" + httpPort + "/";
        System.out.println();
        System.out.println("=== 1) 本地起一个 HTTP 服务（充当攻击者的类托管点）===");
        System.out.println("    监听 127.0.0.1:" + httpPort + "，只统计请求数，不返回任何可加载的类");
        System.out.println("    codebase = " + codebase);

        // 路径 A：通用 NamingManager —— 库代码处理 Reference 时最常见的那条路
        System.out.println();
        System.out.println("=== 2) 路径 A：NamingManager.getObjectInstance(Reference) ===");
        System.out.println("    （任何把不可信数据当 Reference 处理的库，走的都是这一条）");
        Reference refA = new Reference("Evil", "EvilFactory", codebase);
        httpHits.set(0);
        try {
            Object o = NamingManager.getObjectInstance(refA, null, null, new Hashtable<String, Object>());
            System.out.println("    返回: " + o);
        } catch (Throwable t) {
            System.out.println("    抛 " + t.getClass().getName() + ": " + firstLine(t.getMessage()));
        }
        System.out.println("    HTTP 收到的请求数 = " + httpHits.get()
                + (httpHits.get() == 0 ? "   ← 0：没去下载远程工厂类" : "   ← 非 0：**它去下载了**"));

        // 路径 B：JNDI + RMI registry
        System.out.println();
        System.out.println("=== 3) 路径 B：JNDI lookup 一个 RMI 上绑定的 Reference ===");
        System.out.println("    先问一个关键问题：那条经典链的构造工具（内部类）在本 JDK 还够得着吗？");
        for (String cn : new String[]{"com.sun.jndi.rmi.registry.ReferenceWrapper",
                "com.sun.jndi.rmi.registry.RemoteReference"}) {
            try {
                Class<?> k = Class.forName(cn);
                System.out.println("      " + cn + " → 可加载：" + k);
            } catch (Throwable t) {
                System.out.println("      " + cn + " → " + t.getClass().getSimpleName()
                        + "（模块 java.naming 未把 com.sun.jndi.rmi.registry 导出给本模块）");
            }
        }
        int rmiPort = 2099 + (int) (Math.random() * 500);
        Registry registry = LocateRegistry.createRegistry(rmiPort);
        Reference refB = new Reference("Evil", "EvilFactory", codebase);
        registry.rebind("evil", new RefServiceImpl(refB));
        System.out.println("    地址: rmi://127.0.0.1:" + rmiPort + "/evil");
        httpHits.set(0);
        lookup(rmiPort);
        System.out.println("    HTTP 收到的请求数 = " + httpHits.get()
                + (httpHits.get() == 0 ? "   ← 0：没去下载远程类" : "   ← 非 0：它去下载了"));

        // 路径 B 对照：显式打开信任开关
        System.out.println();
        System.out.println("=== 4) 对照：显式把 com.sun.jndi.rmi.object.trustURLCodebase 打开再 lookup 一次 ===");
        System.setProperty("com.sun.jndi.rmi.object.trustURLCodebase", "true");
        httpHits.set(0);
        lookup(rmiPort);
        System.out.println("    HTTP 收到的请求数 = " + httpHits.get()
                + (httpHits.get() == 0 ? "   ← 开关开了仍没去取（还有别的拦截）" : "   ← 开关一开就去了"));

        System.out.println();
        System.out.println("=== 5) 结论（本机实测）===");
        System.out.println("    JNDI 只是「按名字找东西」的 API；危险的是「把远程 URL 上的类当工厂加载」这个能力。");
        System.out.println("    现代 JDK 在 provider 层默认不信任远程 codebase，所以这条老路默认打不动；");
        System.out.println("    而库自己拿不可信数据去调 NamingManager 时，局面取决于库怎么做 —— 见上面路径 A 的读数。");

        http.stop(0);
        try { UnicastRemoteObject.unexportObject(registry, true); } catch (Exception ignored) { }
        // RMI registry 与 HTTP 服务都起了非守护线程，不显式退出的话程序会一直挂着
        System.out.println("（本程序显式 System.exit(0)：RMI/HTTP 的线程是非守护线程，不会自己结束）");
        System.exit(0);
    }

    static void lookup(int rmiPort) {
        try {
            Context ctx = new InitialContext();
            Object o = ctx.lookup("rmi://127.0.0.1:" + rmiPort + "/evil");
            System.out.println("    lookup 返回: " + o + "（类型 " + (o == null ? "null" : o.getClass().getName()) + "）");
        } catch (Throwable t) {
            System.out.println("    抛 " + t.getClass().getName() + ": " + firstLine(t.getMessage()));
            Throwable c = t.getCause();
            int depth = 0;
            while (c != null && depth++ < 4) {
                System.out.println("      ← 由 " + c.getClass().getName() + ": " + firstLine(c.getMessage()) + " 引起");
                c = c.getCause();
            }
        }
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
