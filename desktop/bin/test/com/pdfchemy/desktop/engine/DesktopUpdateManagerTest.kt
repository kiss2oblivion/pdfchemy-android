package com.pdfchemy.desktop.engine

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class DesktopUpdateManagerTest {

    private lateinit var testPubKeyBase64: String
    private lateinit var privateKey: java.security.PrivateKey
    private lateinit var tempDir: File
    private val createdTempFiles = CopyOnWriteArrayList<File>()
    
    private val expectedAsset = ReleaseAsset(name = "PDFchemy-Windows.msi", downloadUrl = "https://example.com/dl", size = 100)
    private val expectedRelease = ReleaseInfo(
        tagName = "v1.1.0",
        name = "Release v1.1.0",
        htmlUrl = "https://example.com",
        body = "",
        publishedAt = "",
        isNewer = true,
        assets = listOf(expectedAsset),
        sha256SumsUrl = "https://example.com/SHA256SUMS.txt"
    )

    @Before
    fun setup() {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        privateKey = kp.private
        testPubKeyBase64 = Base64.getEncoder().encodeToString(kp.public.encoded)
        tempDir = File(System.getProperty("java.io.tmpdir"), "pdfchemy_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        createdTempFiles.clear()
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
        // Also cleanup possible left-over pdfchemy-update-verified-PDFchemy-Windows.msi
        val updateDir = com.pdfchemy.desktop.engine.DesktopUpdateManager.updateDir
        File(updateDir, "pdfchemy-update-verified-PDFchemy-Windows.msi").delete()
    }

    private fun sign(payload: String): String {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(privateKey)
        sig.update(payload.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun hash(fileBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val calculated = digest.digest(fileBytes).joinToString("") { "%02x".format(it) }
        return calculated
    }

    private fun createHttpClient(
        manifestText: String,
        downloadAction: (File) -> Result<File>
    ): UpdateHttpClient {
        return object : UpdateHttpClient {
            override fun getText(url: String, timeoutMs: Int): String {
                if (url.endsWith(".sig")) return sign(manifestText)
                return manifestText
            }

            override fun download(
                url: String,
                destination: File,
                maxBytes: Long,
                onProgress: (Long, Long) -> Unit,
                isCancelled: () -> Boolean
            ): Result<File> {
                createdTempFiles.add(destination)
                return downloadAction(destination)
            }
        }
    }

    private fun getValidManifestText(fileHash: String, version: String = "v1.1.0", platform: String = "Desktop", arch: String = "Universal", filename: String = expectedAsset.name): String {
        return """
            # VERSION=$version
            # PLATFORM=$platform
            # ARCH=$arch
            $fileHash  $filename
        """.trimIndent()
    }

    @Test
    fun testValidUpdateFlow() = runBlocking {
        val fileBytes = "valid executable content".toByteArray()
        val fileHash = hash(fileBytes)
        val manifestText = getValidManifestText(fileHash)

        val httpClient = createHttpClient(manifestText) { dest ->
            dest.writeBytes(fileBytes)
            Result.success(dest)
        }

        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = expectedRelease,
            asset = expectedAsset,
            onProgress = { _, _ -> },
            isCancelled = { false },
            httpClient = httpClient,
            publicKeyBase64 = testPubKeyBase64
        )

        assertTrue("Update should succeed", result.isSuccess)
        val finalFile = result.getOrNull()!!
        assertTrue("Final file should exist", finalFile.exists())
        assertEquals("pdfchemy-update-verified-PDFchemy-Windows.msi", finalFile.name)
    }

    @Test
    fun testMissingSignature() = runBlocking {
        val fileBytes = "content".toByteArray()
        val manifestText = getValidManifestText(hash(fileBytes))
        
        val httpClient = object : UpdateHttpClient {
            override fun getText(url: String, timeoutMs: Int): String {
                if (url.endsWith(".sig")) throw IllegalStateException("404 Not Found")
                return manifestText
            }
            override fun download(u: String, d: File, m: Long, p: (Long, Long)->Unit, c: ()->Boolean): Result<File> {
                d.writeBytes(fileBytes)
                return Result.success(d)
            }
        }

        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = expectedRelease, asset = expectedAsset, onProgress = {_,_->}, isCancelled = {false},
            httpClient = httpClient, publicKeyBase64 = testPubKeyBase64
        )
        
        assertTrue("Should fail due to missing signature", result.isFailure)
        assertCleanup()
    }

    @Test
    fun testInvalidSignature() = runBlocking {
        val fileBytes = "content".toByteArray()
        val manifestText = getValidManifestText(hash(fileBytes))
        
        val httpClient = object : UpdateHttpClient {
            override fun getText(url: String, timeoutMs: Int): String {
                if (url.endsWith(".sig")) return "invalid_base64_or_wrong_sig"
                return manifestText
            }
            override fun download(u: String, d: File, m: Long, p: (Long, Long)->Unit, c: ()->Boolean): Result<File> {
                d.writeBytes(fileBytes)
                return Result.success(d)
            }
        }

        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = expectedRelease, asset = expectedAsset, onProgress = {_,_->}, isCancelled = {false},
            httpClient = httpClient, publicKeyBase64 = testPubKeyBase64
        )
        
        assertTrue("Should fail due to invalid signature", result.isFailure)
        assertCleanup()
    }

    @Test
    fun testModifiedManifest() = runBlocking {
        val fileBytes = "content".toByteArray()
        val originalManifestText = getValidManifestText(hash(fileBytes))
        val forgedManifestText = getValidManifestText("forgedhash")
        
        val httpClient = object : UpdateHttpClient {
            override fun getText(url: String, timeoutMs: Int): String {
                // Return signature for original, but return forged text
                if (url.endsWith(".sig")) return sign(originalManifestText)
                return forgedManifestText
            }
            override fun download(u: String, d: File, m: Long, p: (Long, Long)->Unit, c: ()->Boolean): Result<File> {
                d.writeBytes(fileBytes)
                return Result.success(d)
            }
        }

        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = expectedRelease, asset = expectedAsset, onProgress = {_,_->}, isCancelled = {false},
            httpClient = httpClient, publicKeyBase64 = testPubKeyBase64
        )
        
        assertTrue("Should fail due to signature mismatch on modified manifest", result.isFailure)
        assertCleanup()
    }

    @Test
    fun testWrongVersionPlatformArch() = runBlocking {
        val scenarios = listOf(
            getValidManifestText(hash("c".toByteArray()), version = "v9.9.9"), // Wrong version
            getValidManifestText(hash("c".toByteArray()), platform = "Mac"), // Wrong platform
            getValidManifestText(hash("c".toByteArray()), arch = "arm64") // Wrong arch
        )

        for (manifestText in scenarios) {
            val httpClient = createHttpClient(manifestText) { dest ->
                dest.writeBytes("c".toByteArray())
                Result.success(dest)
            }

            val result = DesktopUpdateManager.secureDownloadAndVerify(
                release = expectedRelease, asset = expectedAsset, onProgress = {_,_->}, isCancelled = {false},
                httpClient = httpClient, publicKeyBase64 = testPubKeyBase64
            )
            assertTrue("Should fail on metadata mismatch", result.isFailure)
            assertCleanup()
        }
    }

    @Test
    fun testHashMismatch() = runBlocking {
        val fileBytes = "content".toByteArray()
        // Manifest expects hash of "other_content"
        val manifestText = getValidManifestText(hash("other_content".toByteArray()))

        val httpClient = createHttpClient(manifestText) { dest ->
            dest.writeBytes(fileBytes)
            Result.success(dest)
        }

        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = expectedRelease, asset = expectedAsset, onProgress = {_,_->}, isCancelled = {false},
            httpClient = httpClient, publicKeyBase64 = testPubKeyBase64
        )
        
        assertTrue("Should fail due to hash mismatch", result.isFailure)
        assertCleanup()
    }

    @Test
    fun testDownloadExceeding250MBWithContentLength() = runBlocking {
        val maxBytes = 250L * 1024 * 1024
        
        val httpClient = object : UpdateHttpClient {
            override fun getText(url: String, timeoutMs: Int): String = ""
            override fun download(u: String, d: File, m: Long, p: (Long, Long)->Unit, c: ()->Boolean): Result<File> {
                createdTempFiles.add(d)
                // Simulate exception being thrown by internal downloader
                return Result.failure(SecurityException("Download exceeded maximum allowed size of 250 MB"))
            }
        }

        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = expectedRelease, asset = expectedAsset.copy(size = maxBytes + 1), onProgress = {_,_->}, isCancelled = {false},
            httpClient = httpClient, publicKeyBase64 = testPubKeyBase64
        )
        
        assertTrue("Should abort download exceeding 250 MB", result.isFailure)
        assertEquals("Download exceeded maximum allowed size of 250 MB", result.exceptionOrNull()?.message)
        assertCleanup()
    }

    @Test
    fun testDownloadExactlyAt250MBBoundary() = runBlocking {
        val maxBytes = 250L * 1024 * 1024
        val fileBytes = "valid executable content".toByteArray()
        val fileHash = hash(fileBytes)
        val manifestText = getValidManifestText(fileHash)

        val httpClient = createHttpClient(manifestText) { dest ->
            dest.writeBytes(fileBytes)
            Result.success(dest)
        }

        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = expectedRelease, 
            asset = expectedAsset.copy(size = maxBytes), 
            onProgress = { _, _ -> }, 
            isCancelled = { false },
            httpClient = httpClient, 
            publicKeyBase64 = testPubKeyBase64
        )

        assertTrue("Update should succeed exactly at 250MB", result.isSuccess)
    }

    @Test
    fun testAtomicMoveUnsupported() = runBlocking {
        val fileBytes = "valid executable content".toByteArray()
        val fileHash = hash(fileBytes)
        val manifestText = getValidManifestText(fileHash)

        val httpClient = createHttpClient(manifestText) { dest ->
            dest.writeBytes(fileBytes)
            Result.success(dest)
        }

        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = expectedRelease,
            asset = expectedAsset,
            onProgress = { _, _ -> },
            isCancelled = { false },
            httpClient = httpClient,
            publicKeyBase64 = testPubKeyBase64,
            fileMover = { _, _ -> throw java.nio.file.AtomicMoveNotSupportedException("src", "dest", "Not supported in test") }
        )

        assertTrue("Should fail due to atomic move unsupported", result.isFailure)
        assertEquals("Critical Error: Atomic file move is not supported on this filesystem.", result.exceptionOrNull()?.message)
        assertCleanup()
    }

    @Test
    fun testOlderRelease() = runBlocking {
        val oldRelease = expectedRelease.copy(isNewer = false)
        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = oldRelease, asset = expectedAsset, onProgress = {_,_->}, isCancelled = {false},
            httpClient = createHttpClient("") { Result.failure(Exception()) }, publicKeyBase64 = testPubKeyBase64
        )
        assertTrue(result.isFailure)
        assertEquals("Downgrade attempt blocked by security policy.", result.exceptionOrNull()?.message)
    }

    @Test
    fun testMalformedJsonInUpdate() = runBlocking {
        // DesktopUpdateManager parseAssets is tested via its usage, but we test secureDownloadAndVerify.
        // If manifest doesn't match format, it won't find the hash.
        val manifestText = "some garbage data without hashes"
        val httpClient = createHttpClient(manifestText) { dest ->
            dest.writeBytes("content".toByteArray())
            Result.success(dest)
        }

        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = expectedRelease, asset = expectedAsset, onProgress = {_,_->}, isCancelled = {false},
            httpClient = httpClient, publicKeyBase64 = testPubKeyBase64
        )
        assertTrue("Should fail due to missing hash (malformed manifest)", result.isFailure)
        assertCleanup()
    }

    @Test
    fun testMissingAssetHash() = runBlocking {
        // Hash present but for a different file
        val manifestText = getValidManifestText(hash("c".toByteArray()), filename = "different-file.msi")
        val httpClient = createHttpClient(manifestText) { dest ->
            dest.writeBytes("c".toByteArray())
            Result.success(dest)
        }

        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = expectedRelease, asset = expectedAsset, onProgress = {_,_->}, isCancelled = {false},
            httpClient = httpClient, publicKeyBase64 = testPubKeyBase64
        )
        assertTrue("Should fail due to missing asset hash", result.isFailure)
        assertCleanup()
    }

    private fun assertCleanup() {
        for (tempFile in createdTempFiles) {
            assertFalse("Cleanup failed: temporary download file ${tempFile.name} exists", tempFile.exists())
        }
        
        val finalFile = File(com.pdfchemy.desktop.engine.DesktopUpdateManager.updateDir, "pdfchemy-update-verified-PDFchemy-Windows.msi")
        assertFalse("Cleanup failed: verified installer staged despite failure", finalFile.exists())
    }
}
