public class ErrMissingReturn {
    static int f(boolean b) {
        if (b) { return 1; }
        // 少了一条 return 路径
    }
    public static void main(String[] a) { System.out.println(f(true)); }
}
