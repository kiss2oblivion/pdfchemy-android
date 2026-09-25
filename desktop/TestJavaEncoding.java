import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

public class TestJavaEncoding {
    public static void main(String[] args) throws Exception {
        byte[] rawBytes = Files.readAllBytes(Paths.get("desktop/raw.txt"));
        String text = new String(rawBytes, StandardCharsets.UTF_8);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        System.out.println("Match: " + java.util.Arrays.equals(rawBytes, bytes));
    }
}
