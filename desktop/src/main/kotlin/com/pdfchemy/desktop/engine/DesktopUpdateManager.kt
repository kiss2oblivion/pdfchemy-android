package com.pdfchemy.desktop.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.prefs.Preferences
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import com.google.gson.JsonParser
import com.google.gson.JsonObject

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long
)

data class SignedManifest(
    val version: String?,
    val platform: String?,
    val architecture: String?,
    val hashes: Map<String, String>
)

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val htmlUrl: String,
    val body: String,
    val publishedAt: String,
    val isNewer: Boolean,
    val assets: List<ReleaseAsset> = emptyList(),
    val sha256SumsUrl: String? = null
)

object DesktopUpdateManager {
    const val CURRENT_VERSION = "1.0.10"
    const val GITHUB_LATEST_RELEASE_API = "https://api.github.com/repos/kiss2oblivion/pdfchemy/releases/latest"
    const val GITHUB_RELEASES_WEB = "https://github.com/kiss2oblivion/pdfchemy/releases"

    private const val PREF_KEY_LAST_CHECK = "last_update_check_time"
    private const val PREF_KEY_DISMISSED_TAG = "dismissed_update_tag"

    // PDFchemy Ed25519 Public Key for Release Verification
    private const val UPDATE_PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEAgezj3JQ6QKJgmIcxfb4Xyl5RrXdQjHIkmDuKDWd1w3I="

    private val prefs: Preferences by lazy {
        Preferences.userNodeForPackage(DesktopUpdateManager::class.java)
    }

    val updateDir: File by lazy {
        val path = Paths.get(System.getProperty("user.home"), ".pdfchemy", "updates")
        
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw SecurityException("Security violation: ~/.pdfchemy/updates is a symbolic link.")
        }
        
        val isWindows = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
        if (isWindows) {
            Files.createDirectories(path)
        } else {
            val base = path.parent
            if (base != null && !Files.exists(base)) {
                Files.createDirectories(base)
            }
            if (!Files.exists(path)) {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            }
        }
        path.toFile()
    }

    fun sweepStaleInstallers(maxAgeMs: Long = 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        updateDir.listFiles()
            ?.filter { file -> file.isFile && file.name.startsWith("pdfchemy-update-") && now - file.lastModified() > maxAgeMs }
            ?.forEach { file -> runCatching { file.delete() } }
    }

    var dismissedTag: String?
        get() = prefs.get(PREF_KEY_DISMISSED_TAG, null)
        set(value) {
            try {
                if (value != null) {
                    prefs.put(PREF_KEY_DISMISSED_TAG, value)
                } else {
                    prefs.remove(PREF_KEY_DISMISSED_TAG)
                }
                prefs.flush()
            } catch (_: Exception) {}
        }

    fun isVersionNewer(remoteTag: String, current: String = CURRENT_VERSION): Boolean {
        val cleanRemote = remoteTag.trim().removePrefix("v").removePrefix("V").split("-")[0]
        val cleanCurrent = current.trim().removePrefix("v").removePrefix("V").split("-")[0]

        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * Strictly verifies that the URL uses HTTPS and points exclusively to GitHub official hosts.
     * Prevents SSRF, MitM, and malicious redirect attacks.
     */
    fun validateSecureGitHubUri(uri: URI) {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https") {
            throw SecurityException("Security violation: Only secure HTTPS connections are permitted ($uri)")
        }
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw SecurityException("Security violation: Invalid host in URL ($uri)")

        val isAllowed = host == "github.com" || host.endsWith(".github.com") ||
                        host == "githubusercontent.com" || host.endsWith(".githubusercontent.com")
        if (!isAllowed) {
            throw SecurityException("Security violation: Untrusted update host '$host'. Only GitHub official domains are permitted.")
        }
    }

    suspend fun checkForUpdates(
        currentVersion: String = CURRENT_VERSION,
        timeoutMs: Int = 8000
    ): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val uri = URI(GITHUB_LATEST_RELEASE_API)
            validateSecureGitHubUri(uri)

            val conn = uri.toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "PDFchemy-Desktop/$CURRENT_VERSION")

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("GitHub API returned HTTP $responseCode")
                )
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val jsonStr = reader.use { it.readText() }
            val json = JsonParser.parseString(jsonStr).asJsonObject

            val tagName = if (json.has("tag_name") && !json.get("tag_name").isJsonNull) json.get("tag_name").asString else "v$currentVersion"
            val name = if (json.has("name") && !json.get("name").isJsonNull) json.get("name").asString else "PDFchemy $tagName"
            val htmlUrl = if (json.has("html_url") && !json.get("html_url").isJsonNull) json.get("html_url").asString else "$GITHUB_RELEASES_WEB/tag/$tagName"
            val publishedAt = if (json.has("published_at") && !json.get("published_at").isJsonNull) json.get("published_at").asString.take(10) else ""
            val body = if (json.has("body") && !json.get("body").isJsonNull) json.get("body").asString else ""

            val parsedAssets = parseAssets(json)
            val sha256Url = parsedAssets.firstOrNull { it.name.equals("SHA256SUMS.txt", ignoreCase = true) }?.downloadUrl

            val newer = isVersionNewer(tagName, currentVersion)

            // Record check timestamp
            try {
                prefs.putLong(PREF_KEY_LAST_CHECK, System.currentTimeMillis())
                prefs.flush()
            } catch (_: Exception) {}

            Result.success(
                ReleaseInfo(
                    tagName = tagName,
                    name = name,
                    htmlUrl = htmlUrl,
                    body = body,
                    publishedAt = publishedAt,
                    isNewer = newer,
                    assets = parsedAssets,
                    sha256SumsUrl = sha256Url
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Finds the best installer asset corresponding to the current running OS architecture.
     */
    fun findBestAssetForCurrentPlatform(assets: List<ReleaseAsset>): ReleaseAsset? {
        val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            osName.contains("win") -> {
                // Windows: Prefer MSI installer, fallback to EXE installer, then portable JAR
                assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.contains("windows", ignoreCase = true) && it.name.endsWith(".jar", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.endsWith(".jar", ignoreCase = true) }
            }
            osName.contains("linux") -> {
                // Linux: Prefer DEB (Ubuntu/Debian/Mint), then RPM (Fedora/RHEL), then shell/jar
                assets.firstOrNull { it.name.endsWith(".deb", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.endsWith(".rpm", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.contains("linux", ignoreCase = true) && it.name.endsWith(".jar", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.endsWith(".jar", ignoreCase = true) }
            }
            else -> {
                // Fallback to standalone JAR
                assets.firstOrNull { it.name.endsWith(".jar", ignoreCase = true) }
            }
        }
    }

    // Replaced by UpdateHttpClient

    suspend fun fetchSha256Checksums(
        sha256Url: String,
        timeoutMs: Int = 10000,
        httpClient: UpdateHttpClient = DefaultUpdateHttpClient,
        publicKeyBase64: String = UPDATE_PUBLIC_KEY_BASE64
    ): Result<SignedManifest> = withContext(Dispatchers.IO) {
        try {
            val text = httpClient.getText(sha256Url, timeoutMs)
            val sigText = httpClient.getText("$sha256Url.sig", timeoutMs).trim()

            // Verify Ed25519 signature
            val pubKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
            val keySpec = X509EncodedKeySpec(pubKeyBytes)
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val publicKey = keyFactory.generatePublic(keySpec)

            val sigBytes = Base64.getDecoder().decode(sigText)
            val signature = Signature.getInstance("Ed25519")
            signature.initVerify(publicKey)
            signature.update(text.toByteArray(Charsets.UTF_8))

            if (!signature.verify(sigBytes)) {
                return@withContext Result.failure(SecurityException("CRITICAL: Invalid Ed25519 signature for SHA256SUMS.txt"))
            }

            var parsedVersion: String? = null
            var parsedPlatform: String? = null
            var parsedArch: String? = null
            val map = mutableMapOf<String, String>()

            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) {
                    if (trimmed.startsWith("#")) {
                        // Attempt to parse metadata from comments
                        val commentContent = trimmed.removePrefix("#").trim()
                        if (commentContent.startsWith("VERSION=", ignoreCase = true)) {
                            parsedVersion = commentContent.substringAfter("=").trim()
                        } else if (commentContent.startsWith("PLATFORM=", ignoreCase = true)) {
                            parsedPlatform = commentContent.substringAfter("=").trim()
                        } else if (commentContent.startsWith("ARCH=", ignoreCase = true)) {
                            parsedArch = commentContent.substringAfter("=").trim()
                        }
                    } else {
                        // Standard format: <hash>  <filename> or <hash> *<filename>
                        val parts = trimmed.split(Regex("\\s+"), limit = 2)
                        if (parts.size == 2) {
                            val hash = parts[0].trim().lowercase(Locale.ROOT)
                            val fname = parts[1].trim().removePrefix("*").trim()
                            map[fname] = hash
                        }
                    }
                }
            }
            Result.success(SignedManifest(parsedVersion, parsedPlatform, parsedArch, map))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams the release asset to a secure temporary file with progress tracking and cancel checking.
     */
    suspend fun downloadAssetFile(
        asset: ReleaseAsset,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean,
        httpClient: UpdateHttpClient = DefaultUpdateHttpClient
    ): Result<File> = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val extension = when {
                asset.name.endsWith(".msi", ignoreCase = true) -> ".msi"
                asset.name.endsWith(".exe", ignoreCase = true) -> ".exe"
                asset.name.endsWith(".deb", ignoreCase = true) -> ".deb"
                asset.name.endsWith(".rpm", ignoreCase = true) -> ".rpm"
                asset.name.endsWith(".jar", ignoreCase = true) -> ".jar"
                else -> ".tmp"
            }

            tempFile = File.createTempFile("pdfchemy-update-", extension, updateDir)
            tempFile.deleteOnExit()

            val maxBytes = 250L * 1024 * 1024
            val result = httpClient.download(
                url = asset.downloadUrl,
                destination = tempFile,
                maxBytes = maxBytes,
                onProgress = onProgress,
                isCancelled = isCancelled
            )

            if (result.isFailure) {
                try { tempFile.delete() } catch (_: Exception) {}
                return@withContext Result.failure(result.exceptionOrNull() ?: Exception("Download failed"))
            }

            Result.success(tempFile)
        } catch (e: Exception) {
            try {
                tempFile?.delete()
            } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    /**
     * Orchestrates the secure download, manifest verification, hash checking, and atomic staging.
     * Returns the finalized secure File ready for launchInstaller, or fails with an Exception.
     */
    suspend fun secureDownloadAndVerify(
        release: ReleaseInfo,
        asset: ReleaseAsset,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean,
        httpClient: UpdateHttpClient = DefaultUpdateHttpClient,
        publicKeyBase64: String = UPDATE_PUBLIC_KEY_BASE64,
        fileMover: (File, File) -> Unit = { src, dest ->
            java.nio.file.Files.move(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!release.isNewer) {
            return@withContext Result.failure(SecurityException("Downgrade attempt blocked by security policy."))
        }

        val downloadResult = downloadAssetFile(asset, onProgress, isCancelled, httpClient)
        val downloadedFile = downloadResult.getOrElse {
            return@withContext Result.failure(it)
        }

        var verified = false
        if (!release.sha256SumsUrl.isNullOrBlank()) {
            val manifestRes = fetchSha256Checksums(release.sha256SumsUrl, httpClient = httpClient, publicKeyBase64 = publicKeyBase64)
            if (manifestRes.isSuccess) {
                val manifest = manifestRes.getOrNull()

                val expectedPlatform = "Desktop"
                val expectedArch = "Universal"

                if (manifest == null || manifest.version != release.tagName || manifest.platform != expectedPlatform || manifest.architecture != expectedArch) {
                    try { downloadedFile.delete() } catch (_: Exception) {}
                    val err = SecurityException("Manifest mismatch. Expected ${release.tagName} $expectedPlatform $expectedArch, got ${manifest?.version} ${manifest?.platform} ${manifest?.architecture}")
                    File(updateDir, "pdfchemy_debug.txt").writeText(err.message ?: "")
                    return@withContext Result.failure(err)
                } else {
                    val expectedHash = manifest.hashes[asset.name]
                    if (expectedHash != null) {
                        verified = verifyFileSha256(downloadedFile, expectedHash)
                        if (!verified) {
                            try { downloadedFile.delete() } catch (_: Exception) {}
                            val err = SecurityException("SHA256 mismatch for ${asset.name}. Expected $expectedHash")
                            File(updateDir, "pdfchemy_debug.txt").writeText(err.message ?: "")
                            return@withContext Result.failure(err)
                        }
                    } else {
                        try { downloadedFile.delete() } catch (_: Exception) {}
                        val err = SecurityException("Asset ${asset.name} not found in SHA256SUMS.txt")
                        File(updateDir, "pdfchemy_debug.txt").writeText(err.message ?: "")
                        return@withContext Result.failure(err)
                    }
                }
            } else {
                try { downloadedFile.delete() } catch (_: Exception) {}
                val err = SecurityException("Checksum verification failed: ${manifestRes.exceptionOrNull()?.message}")
                File(updateDir, "pdfchemy_debug.txt").writeText(err.message ?: "")
                return@withContext Result.failure(err)
            }
        } else {
            try { downloadedFile.delete() } catch (_: Exception) {}
            val err = SecurityException("Release is missing SHA256SUMS.txt")
            File(updateDir, "pdfchemy_debug.txt").writeText(err.message ?: "")
            return@withContext Result.failure(err)
        }

        if (!verified) {
            try { downloadedFile.delete() } catch (_: Exception) {}
            val err = SecurityException("Update verification failed internally (should not happen).")
            File(updateDir, "pdfchemy_debug.txt").writeText(err.message ?: "")
            return@withContext Result.failure(err)
        }

        // Atomically move to final secure execution path
        val finalFile = File(updateDir, "pdfchemy-update-verified-${asset.name}")
        try {
            if (finalFile.exists()) finalFile.delete()
            fileMover(downloadedFile, finalFile)
            
            // Best-effort hardening of verified artifact
            val isWindows = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
            if (isWindows) {
                finalFile.setReadOnly()
            } else {
                Files.setPosixFilePermissions(finalFile.toPath(), PosixFilePermissions.fromString("r-x------"))
            }
            
            Result.success(finalFile)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            System.err.println("CRITICAL: Atomic move unsupported on this filesystem. Aborting update for security.")
            try { downloadedFile.delete() } catch (_: Exception) {}
            val err = SecurityException("Critical Error: Atomic file move is not supported on this filesystem.")
            File(updateDir, "pdfchemy_debug.txt").writeText(err.message ?: "")
            Result.failure(err)
        } catch (e: Exception) {
            try { downloadedFile.delete() } catch (_: Exception) {}
            File(updateDir, "pdfchemy_debug.txt").writeText(e.stackTraceToString())
            Result.failure(e)
        }
    }

    /**
     * Calculates the SHA-256 hash of a file on disk and verifies it against the expected hash.
     */
    suspend fun verifyFileSha256(file: File, expectedSha256: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val calculated = digest.digest().joinToString("") { "%02x".format(it) }
            val cleanExpected = expectedSha256.trim().lowercase(Locale.ROOT)
            val match = calculated.equals(cleanExpected, ignoreCase = true)
            if (!match) {
                // Immediate security deletion of untrusted binary
                try { file.delete() } catch (_: Exception) {}
            }
            match
        } catch (_: Exception) {
            try { file.delete() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Safely launches the installer using strict parameterized ProcessBuilder without shell concatenation.
     */
    fun launchInstaller(installerFile: File): Result<Unit> {
        return try {
            val canonicalPath = installerFile.canonicalPath
            val name = installerFile.name.lowercase(Locale.ROOT)
            val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)

            val processBuilder = when {
                name.endsWith(".msi") -> {
                    // Windows MSI installer via absolute msiexec
                    val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
                    ProcessBuilder("$systemRoot\\System32\\msiexec.exe", "/i", canonicalPath)
                }
                name.endsWith(".exe") -> {
                    // Windows executable installer
                    ProcessBuilder(canonicalPath)
                }
                name.endsWith(".deb") || name.endsWith(".rpm") -> {
                    // Linux packages: pass to xdg-open for UI delegation to package manager
                    ProcessBuilder("xdg-open", canonicalPath)
                }
                name.endsWith(".jar") -> {
                    // Standalone executable JAR
                    val javaHome = System.getProperty("java.home")
                    val javaBin = if (javaHome != null) "$javaHome${File.separator}bin${File.separator}java" else "java"
                    ProcessBuilder(javaBin, "-jar", canonicalPath)
                }
                else -> {
                    if (osName.contains("win")) {
                        ProcessBuilder(canonicalPath)
                    } else {
                        ProcessBuilder("xdg-open", canonicalPath)
                    }
                }
            }

            processBuilder.start()
            kotlin.system.exitProcess(0)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseAssets(jsonObj: JsonObject): List<ReleaseAsset> {
        val assets = mutableListOf<ReleaseAsset>()
        if (jsonObj.has("assets") && jsonObj.get("assets").isJsonArray) {
            val arr = jsonObj.getAsJsonArray("assets")
            for (elem in arr) {
                if (elem.isJsonObject) {
                    val obj = elem.asJsonObject
                    val name = if (obj.has("name") && !obj.get("name").isJsonNull) obj.get("name").asString else ""
                    val downloadUrl = if (obj.has("browser_download_url") && !obj.get("browser_download_url").isJsonNull) obj.get("browser_download_url").asString else ""
                    val size = if (obj.has("size") && !obj.get("size").isJsonNull) obj.get("size").asLong else 0L
                    if (name.isNotBlank() && downloadUrl.isNotBlank()) {
                        assets.add(ReleaseAsset(name = name, downloadUrl = downloadUrl, size = size))
                    }
                }
            }
        }
        return assets
    }
}
