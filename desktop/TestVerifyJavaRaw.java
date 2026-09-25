import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.net.HttpURLConnection;
import java.net.URI;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;

public class TestVerifyJavaRaw {
    public static String getText(String url) throws Exception {
        String currentUrl = url;
        int redirects = 0;
        while (redirects < 5) {
            URI uri = new URI(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "PDFchemy-Desktop/1.0.5");
            
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                currentUrl = conn.getHeaderField("Location");
                redirects++;
                continue;
            }
            if (code < 200 || code >= 300) throw new IllegalStateException();
            
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        }
        throw new IllegalStateException();
    }

    public static void main(String[] args) throws Exception {
        byte[] rawBytes = Files.readAllBytes(Paths.get("desktop/raw.txt"));
        String publicKeyBase64 = "MCowBQYDK2VwAyEAgezj3JQ6QKJgmIcxfb4Xyl5RrXdQjHIkmDuKDWd1w3I=";
        String sigText = getText("https://github.com/kiss2oblivion/pdfchemy/releases/download/v1.0.7/SHA256SUMS.txt.sig").trim();

        byte[] pubKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(pubKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        java.security.PublicKey publicKey = keyFactory.generatePublic(keySpec);

        byte[] sigBytes = Base64.getDecoder().decode(sigText);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(publicKey);
        signature.update(rawBytes);

        boolean verified = signature.verify(sigBytes);
        System.out.println("Verified RAW: " + verified);
    }
}
