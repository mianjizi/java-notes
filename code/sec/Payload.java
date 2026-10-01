/** 给 SecClassLoader 当靶子的无害类：被从内存里加载 */
public class Payload {
    public String say() {
        return "我是被从内存字节里定义出来的类（磁盘上没有我的定义）";
    }
}
