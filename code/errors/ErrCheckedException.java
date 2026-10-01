import java.io.FileReader;

public class ErrCheckedException {
    public static void main(String[] args) {
        FileReader r = new FileReader("nope.txt");   // 受检异常：不处理就编译不过
    }
}
