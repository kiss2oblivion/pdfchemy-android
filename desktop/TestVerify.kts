import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale

fun getText(url: String): String {
    var currentUrl = url
    var redirects = 0
    while (redirects < 5) {
        val uri = URI(currentUrl)
        val conn = uri.toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "PDFchemy-Desktop/1.0.5")
        
        val code = conn.responseCode
        if (code in 300..399) {
            val location = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirect without Location header")
            currentUrl = location
            redirects++
            continue
        }
        if (code !in 200..299) {
            throw IllegalStateException("Failed to download from $url: HTTP $code")
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    throw IllegalStateException("Too many redirects for $url")
}

val sha256Url = "https://github.com/kiss2oblivion/pdfchemy/releases/download/v1.0.7/SHA256SUMS.txt"
val publicKeyBase64 = "MCowBQYDK2VwAyEAgezj3JQ6QKJgmIcxfb4Xyl5RrXdQjHIkmDuKDWd1w3I="

try {
    val text = getText(sha256Url)
    val sigText = getText("$sha256Url.sig").trim()

    val pubKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
    val keySpec = X509EncodedKeySpec(pubKeyBytes)
    val keyFactory = KeyFactory.getInstance("Ed25519")
    val publicKey = keyFactory.generatePublic(keySpec)

    val sigBytes = Base64.getDecoder().decode(sigText)
    val signature = Signature.getInstance("Ed25519")
    signature.initVerify(publicKey)
    signature.update(text.toByteArray(Charsets.UTF_8))

    val verified = signature.verify(sigBytes)
    println("Verified: $verified")
} catch (e: Exception) {
    e.printStackTrace()
}
