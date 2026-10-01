import javax.naming.Context;
import javax.naming.Name;
import javax.naming.spi.ObjectFactory;
import java.util.Hashtable;

/**
 * 攻击面演示里「被远程取走的那个工厂类」——**本地无害靶子**。
 *
 * 它只做一件事：打印自己被实例化了、被调用了。
 * 真实世界里这个位置是落地动作发生的地方（构造里或 getObjectInstance 里执行命令/写文件），
 * 这里刻意不含任何这类代码 —— 材料要讲的是「类被从远端取来并执行」这个机制本身。
 *
 * 注意：这个类**不放进主 classpath**，只放在本地 HTTP 服务要托管的目录里，
 * 这样「它到底是从本地 classpath 找的还是从远端取来的」才分得清。
 */
public class EvilFactory implements ObjectFactory {

    public EvilFactory() {
        System.out.println("    [远程工厂类] EvilFactory 的构造函数被执行了（说明字节码已经从 HTTP 取来并加载）");
    }

    @Override
    public Object getObjectInstance(Object obj, Name name, Context nameCtx, Hashtable<?, ?> environment) {
        System.out.println("    [远程工厂类] getObjectInstance() 被调用 —— 真实世界里的落地动作就在这个位置");
        return "来自远程工厂类的对象（本地无害靶子的返回值）";
    }
}
