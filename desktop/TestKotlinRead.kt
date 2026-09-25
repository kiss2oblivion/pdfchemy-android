import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Arrays

fun main() {
    var url = "https://github.com/kiss2oblivion/pdfchemy/releases/download/v1.0.7/SHA256SUMS.txt"
    var currentUrl = url
    var redirects = 0
    while (redirects < 5) {
        val uri = URI(currentUrl)
        val conn = uri.toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        val code = conn.responseCode
        if (code in 300..399) {
            currentUrl = conn.getHeaderField("Location")
            redirects++
            continue
        }
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val rawBytes = Files.readAllBytes(Paths.get("desktop/raw.txt"))
        println("Bytes match: " + Arrays.equals(bytes, rawBytes))
        println("Text size: " + bytes.size)
        println("Raw size: " + rawBytes.size)
        return
    }
}
