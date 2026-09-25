import java.net.HttpURLConnection;
import java.net.URI;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;

public class TestVerifyJava {
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
                String location = conn.getHeaderField("Location");
                if (location == null) throw new IllegalStateException("Redirect without Location header");
                currentUrl = location;
                redirects++;
                continue;
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Failed to download from " + url + ": HTTP " + code);
            }
            
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        }
        throw new IllegalStateException("Too many redirects for " + url);
    }

    public static void main(String[] args) {
        try {
            String sha256Url = "https://github.com/kiss2oblivion/pdfchemy/releases/download/v1.0.7/SHA256SUMS.txt";
            String publicKeyBase64 = "MCowBQYDK2VwAyEAgezj3JQ6QKJgmIcxfb4Xyl5RrXdQjHIkmDuKDWd1w3I=";

            String text = getText(sha256Url);
            // Replicate exactly:
            if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
            
            String sigText = getText(sha256Url + ".sig").trim();

            byte[] pubKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(pubKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            java.security.PublicKey publicKey = keyFactory.generatePublic(keySpec);

            byte[] sigBytes = Base64.getDecoder().decode(sigText);
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(text.getBytes(StandardCharsets.UTF_8));

            boolean verified = signature.verify(sigBytes);
            System.out.println("Verified: " + verified);
            if (!verified) {
                System.out.println("Text used for verify:\n--- START ---\n" + text + "\n--- END ---");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
