public class ErrIncompatibleTypes {
    public static void main(String[] args) {
        String s = 123;                 // int 不会自动变 String（C 里也不会，但 Java 连警告都不给）
        System.out.println(s);
    }
}
