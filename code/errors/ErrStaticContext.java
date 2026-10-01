public class ErrStaticContext {
    int x = 1;                              // 实例字段
    public static void main(String[] args) {
        System.out.println(x);              // 静态方法里直接摸实例字段
    }
}
