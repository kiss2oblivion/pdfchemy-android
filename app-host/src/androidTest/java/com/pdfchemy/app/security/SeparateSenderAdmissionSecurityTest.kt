package com.pdfchemy.app.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.MainActivity
import com.pdfchemy.app.security.ExternalUriAdmission.AdmissionResult
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException

/**
 * Android instrumentation security regression tests for FOLLOW-04.
 * Exercises separate-sender and test-provider URI admission, scheme policy,
 * grant/failure handling, private-path defense, and activity lifecycle admission.
 */
@RunWith(AndroidJUnit4::class)
class SeparateSenderAdmissionSecurityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        DocumentStager.releaseAll()
    }

    @After
    fun tearDown() {
        DocumentStager.releaseAll()
    }

    private fun samplePdfBytes(marker: String = "separate_sender_test"): ByteArray {
        val doc = PDDocument()
        doc.addPage(PDPage())
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        val base = out.toByteArray()
        return (base.toString(Charsets.ISO_8859_1) + "\n%$marker\n").toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun separateSender_fileUriInSharedPrefs_isRejectedAndNeverStaged() {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        val prefsFile = File(sharedPrefsDir, "confidential_prefs_${System.nanoTime()}.xml").apply {
            writeText("<map><string name=\"token\">secret_token_12345</string></map>")
        }
        val fileUri = Uri.fromFile(prefsFile)

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            data = fileUri
        }
        val result = ExternalUriAdmission.admit(context, viewIntent)
        assertTrue("External file URI in shared_prefs must be rejected", result is AdmissionResult.Rejected)
        val reason = (result as AdmissionResult.Rejected).reason
        assertTrue("Rejection reason must mention private application storage", reason.contains("private application storage"))

        // Direct staging attempt must fail defensive check
        try {
            DocumentStager.stageDocument(context, fileUri)
            fail("DocumentStager must refuse to stage private shared_prefs file")
        } catch (expected: SecurityException) {
            assertTrue(expected.message!!.contains("private application storage is forbidden"))
        } finally {
            prefsFile.delete()
        }
    }

    @Test
    fun separateSender_fileUriInDatabases_isRejectedAndNeverStaged() {
        val dbDir = File(context.applicationInfo.dataDir, "databases").apply { mkdirs() }
        val dbFile = File(dbDir, "app_secrets_${System.nanoTime()}.db").apply {
            writeBytes("SQLITE_HEADER_TEST".toByteArray())
        }
        val fileUri = Uri.fromFile(dbFile)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, fileUri)
        }
        val result = ExternalUriAdmission.admit(context, sendIntent)
        assertTrue("External file URI in databases must be rejected", result is AdmissionResult.Rejected)
        assertTrue((result as AdmissionResult.Rejected).reason.contains("private application storage"))

        try {
            DocumentStager.stageDocument(context, fileUri)
            fail("DocumentStager must refuse to stage private databases file")
        } catch (expected: SecurityException) {
            assertTrue(expected.message!!.contains("private application storage is forbidden"))
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun separateSender_fileUriInFilesDir_isRejected() {
        val privateFile = File(context.filesDir, "private_document_${System.nanoTime()}.pdf").apply {
            writeBytes(samplePdfBytes("private_files_dir"))
        }
        val fileUri = Uri.fromFile(privateFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = fileUri
        }
        val result = ExternalUriAdmission.admit(context, intent)
        assertTrue("External file URI in filesDir must be rejected", result is AdmissionResult.Rejected)
        assertTrue((result as AdmissionResult.Rejected).reason.contains("private application storage"))
        privateFile.delete()
    }

    @Test
    fun separateSender_fileUriInCacheDir_isRejected() {
        val cacheFile = File(context.cacheDir, "cached_private_${System.nanoTime()}.pdf").apply {
            writeBytes(samplePdfBytes("cache_private"))
        }
        val fileUri = Uri.fromFile(cacheFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = fileUri
        }
        val result = ExternalUriAdmission.admit(context, intent)
        assertTrue("External file URI in cacheDir must be rejected", result is AdmissionResult.Rejected)
        assertTrue((result as AdmissionResult.Rejected).reason.contains("private application storage"))
        cacheFile.delete()
    }

    @Test
    fun separateSender_fileUriAttemptingToClaimStagedSnapshot_isRejected() {
        val stagedDir = File(context.cacheDir, "staged").apply { mkdirs() }
        val snapshotFile = File(stagedDir, "snapshot_hostile_impersonation.bin").apply {
            writeBytes(samplePdfBytes("staged_claim"))
        }
        val snapshotUri = Uri.fromFile(snapshotFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = snapshotUri
        }
        val result = ExternalUriAdmission.admit(context, intent)
        assertTrue("External intent cannot claim internal staged directory", result is AdmissionResult.Rejected)
        val reason = (result as AdmissionResult.Rejected).reason
        assertTrue("Reason must mention staged cache directory or capability",
            reason.contains("staged cache directory") || reason.contains("internal staged capability"))
        snapshotFile.delete()
    }

    @Test
    fun separateSender_activeStagedCapability_isNotAdmissibleFromExternalSender() {
        val leased = samplePdfBytes("active_staged").inputStream().use {
            DocumentStager.stageStreamWithLease(context, it, "pdf")
        }
        try {
            val stagedUri = leased.document.uri
            assertTrue(DocumentStager.isTrustedStagedCapability(context, stagedUri))

            val externalIntent = Intent(Intent.ACTION_VIEW).apply {
                data = stagedUri
            }
            val result = ExternalUriAdmission.admit(context, externalIntent)
            assertTrue("Active internal staged capability must NOT be admitted from external intent",
                result is AdmissionResult.Rejected)
            assertTrue((result as AdmissionResult.Rejected).reason.contains("cannot claim internal staged capability"))
        } finally {
            leased.lease.close()
            DocumentStager.release(leased.document)
        }
    }

    @Test
    fun separateSender_pathTraversalEscapesToPrivateStorage_isRejected() {
        val traversalUris = listOf(
            Uri.parse("file:///sdcard/Download/../../data/data/${context.packageName}/shared_prefs/app_prefs.xml"),
            Uri.parse("file:///sdcard/Download/%2e%2e/%2e%2e/data/data/${context.packageName}/databases/app.db"),
            Uri.parse("file://${context.cacheDir}/../shared_prefs/app_prefs.xml")
        )
        for (traversalUri in traversalUris) {
            val intent = Intent(Intent.ACTION_VIEW).apply { data = traversalUri }
            val result = ExternalUriAdmission.admit(context, intent)
            assertTrue("Path traversal URI must be rejected: $traversalUri", result is AdmissionResult.Rejected)
        }
    }

    @Test
    fun separateSender_forbiddenSchemes_areStrictlyRejected() {
        val forbiddenUris = listOf(
            "http://malicious.org/exploit.pdf",
            "https://malicious.org/exploit.pdf",
            "javascript:alert(document.domain)",
            "ftp://malicious.org/exploit.pdf",
            "data:application/pdf;base64,JVBERi0xLjQK",
            "android.resource://${context.packageName}/raw/secret",
            "about:blank",
            "ws://evil.com/stream",
            "wss://evil.com/stream",
            "smb://server/share/file.pdf"
        )
        for (rawUri in forbiddenUris) {
            val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(rawUri) }
            val result = ExternalUriAdmission.admit(context, intent)
            assertTrue("Forbidden scheme must be rejected: $rawUri", result is AdmissionResult.Rejected)
            val reason = (result as AdmissionResult.Rejected).reason
            assertTrue("Reason must indicate forbidden or unsupported scheme: $reason",
                reason.contains("forbidden") || reason.contains("Unsupported"))
        }
    }

    @Test
    fun separateSender_maliciousContentUriAuthorityAndPath_areRejected() {
        val maliciousContentUris = listOf(
            Uri.parse("content://../evil/1"),
            Uri.parse("content://evil/../traversal"),
            Uri.parse("content://evil\\escaped/file"),
            Uri.parse("content://com.pdfchemy.app.security.mutablefixture/../secret"),
            Uri.parse("content://com.pdfchemy.app.security.mutablefixture/%2e%2e/secret"),
            Uri.parse("content://com.pdfchemy.app.security.mutablefixture/path\\escape")
        )
        for (uri in maliciousContentUris) {
            val intent = Intent(Intent.ACTION_VIEW).apply { data = uri }
            val result = ExternalUriAdmission.admit(context, intent)
            assertTrue("Malicious content URI syntax must be rejected: $uri", result is AdmissionResult.Rejected)
        }
    }

    @Test
    fun separateProvider_validContentUriFromSeparateProvider_isAdmittedAndStaged() {
        val pdfBytes = samplePdfBytes("valid_provider_content")
        val fixtureUri = Uri.parse("content://com.pdfchemy.app.security.mutablefixture/input")

        // Configure test provider with valid bytes
        context.contentResolver.call(fixtureUri, "configure", null, Bundle().apply {
            putByteArray("first", pdfBytes)
            putByteArray("subsequent", pdfBytes)
        })

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = fixtureUri
        }
        val admission = ExternalUriAdmission.admit(context, intent)
        assertTrue("Legitimate provider content URI must be admitted", admission is AdmissionResult.Admitted)
        val admittedUri = (admission as AdmissionResult.Admitted).uri
        assertEquals(fixtureUri, admittedUri)

        // Stage document from provider
        val snapshot = DocumentStager.stageDocument(context, admittedUri)
        try {
            assertTrue("Snapshot must exist on disk", File(snapshot.uri.path!!).exists())
            assertEquals("Staged byte size must match source bytes", pdfBytes.size.toLong(), snapshot.size)
            assertTrue("Snapshot must be verified as trusted capability",
                DocumentStager.isTrustedStagedCapability(context, snapshot.uri))
        } finally {
            DocumentStager.release(snapshot)
        }
    }

    @Test
    fun separateProvider_failingOrMissingProvider_failsGracefullyWithoutOrphans() {
        val missingUri = Uri.parse("content://com.pdfchemy.app.security.outputfixture/missing_doc_${System.nanoTime()}")
        val beforeSnapshots = File(context.cacheDir, "staged").listFiles().orEmpty().map { it.name }.toSet()

        val intent = Intent(Intent.ACTION_VIEW).apply { data = missingUri }
        val admission = ExternalUriAdmission.admit(context, intent)
        assertTrue("URI syntax passes admission", admission is AdmissionResult.Admitted)

        // Staging attempt must catch FileNotFoundException and leave no orphaned files
        try {
            DocumentStager.stageDocument(context, (admission as AdmissionResult.Admitted).uri)
            fail("Nonexistent provider URI must fail staging")
        } catch (expected: FileNotFoundException) {
            // Expected
        }

        val afterSnapshots = File(context.cacheDir, "staged").listFiles().orEmpty().map { it.name }.toSet()
        assertEquals("Failed staging must clean up and leave zero orphaned snapshots", beforeSnapshots, afterSnapshots)
    }

    @Test
    fun separateSender_activityLifecycleIntentAdmission_acceptsValidProviderAndDropsHostileFile() {
        val launcher = Intent(context, MainActivity::class.java).apply {
            putExtra("isScreenshotRun", true) // Bypasses ads/consent for headless testing
        }

        ActivityScenario.launch<MainActivity>(launcher).use { scenario ->
            scenario.onActivity { activity ->
                // 1. Initial state: no incoming PDF
                assertNull("Initial state must have null incoming URI", activity.incomingPdfUriForTesting.value)

                // 2. Separate sender sends hostile private file URI
                val hostileFile = File(context.applicationInfo.dataDir, "shared_prefs/exploit_test.xml")
                val hostileIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.fromFile(hostileFile)
                }
                activity.handleIncomingIntentForTesting(hostileIntent)
                assertNull("Hostile private file intent must be dropped without updating incoming URI state",
                    activity.incomingPdfUriForTesting.value)

                // 3. Separate sender sends forbidden scheme intent
                val forbiddenIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("javascript:alert(1)")
                }
                activity.handleIncomingIntentForTesting(forbiddenIntent)
                assertNull("Forbidden scheme intent must be dropped", activity.incomingPdfUriForTesting.value)

                // 4. Separate sender sends valid provider content URI
                val validProviderUri = Uri.parse("content://com.pdfchemy.app.security.mutablefixture/input")
                val validIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = validProviderUri
                }
                activity.handleIncomingIntentForTesting(validIntent)
                assertEquals("Valid external provider URI must be admitted into incoming state",
                    validProviderUri, activity.incomingPdfUriForTesting.value)
            }
        }
    }
}
