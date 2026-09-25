import java.nio.file.Files
import java.nio.file.Paths

fun main() {
    val rawBytes = Files.readAllBytes(Paths.get("desktop/raw.txt"))
    val text = String(rawBytes, Charsets.UTF_8)
    val bytes = text.toByteArray(Charsets.UTF_8)
    println("Match: ${java.util.Arrays.equals(rawBytes, bytes)}")
}
