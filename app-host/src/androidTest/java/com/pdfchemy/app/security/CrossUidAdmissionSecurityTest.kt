package com.pdfchemy.app.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.MainActivity
import com.pdfchemy.app.logic.DocumentActions
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Foreign launches use the installed test APK's distinct UID; internal Open uses the real app action. */
@RunWith(AndroidJUnit4::class)
class CrossUidAdmissionSecurityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val receiptUri = Uri.parse("content://com.pdfchemy.app.test.crossuid.receipt")

    private fun fixture(): ByteArray {
        val content = "BT /F1 12 Tf 20 100 Td (CROSS_UID_PRIVATE_PDF_20261023) Tj ET\n"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n${content}endstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        )
        val output = ByteArrayOutputStream()
        fun emit(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
        emit("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body -> output.size().also { emit("${index + 1} 0 obj\n$body\nendobj\n") } }
        val xref = output.size()
        emit("xref\n0 6\n0000000000 65535 f \n")
        offsets.forEach { emit(it.toString().padStart(10, '0') + " 00000 n \n") }
        emit("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }

    private fun canRead(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false
    } catch (_: Exception) { false }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out: $description" }
            SystemClock.sleep(50)
        }
    }

    private fun verify(kind: String, action: String, grant: Boolean = false, expectedAdmission: Boolean) {
        DocumentStager.releaseAll()
        val token = UUID.randomUUID().toString().replace("-", "")
        val bytes = fixture()
        val run = InstrumentationRegistry.getArguments().getString("crossUidEvidenceRun", "probe")!!
        require(run.matches(Regex("[a-zA-Z0-9_-]+")))
        val archive = File(context.getExternalFilesDir(null), "cross-uid/$run/$kind-${action.substringAfterLast('.')}-$token").apply { mkdirs() }
        File(archive, "source.pdf").writeBytes(bytes)
        var ownFile: File? = null
        var observer: Job? = null
        var prepared = false
        val evidence = JSONObject().put("kind", kind).put("action", action).put("grant", grant)
            .put("host_uid", Process.myUid()).put("source_sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        try {
            val sourceUri = if (kind.startsWith("own")) {
                ownFile = File(File(context.filesDir, "scans").apply { mkdirs() }, "cross_uid_$token.pdf").apply { writeBytes(bytes) }
                if (kind == "ownFile") Uri.fromFile(ownFile)
                else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ownFile!!).let { uri ->
                    // CI/owned AVD use the primary user; the owner-read control below
                    // refuses to treat an unreadable cross-user URI as a bypass.
                    if (kind == "ownQualified") uri.buildUpon().authority("0@${uri.authority}").build() else uri
                }
            } else null
            val extras = Bundle().apply {
                putString("action", action); putBoolean("grant", grant)
                putBoolean("private_alias", kind == "ownAlias")
                if (sourceUri != null) putString("uri", sourceUri.toString()) else putByteArray("bytes", bytes)
            }
            val initial = context.contentResolver.call(receiptUri, "prepare", token, extras)!!
            prepared = true
            val uri = Uri.parse(initial.getString("uri"))
            evidence.put("uri", uri.toString()).put("sender_uid", initial.getInt("sender_uid"))
                .put("prepare_caller_uid", initial.getInt("prepare_caller_uid"))
            assertNotEquals("Fixture must run under a genuinely different UID", Process.myUid(), initial.getInt("sender_uid"))
            assertEquals("Fixture must observe this host as its Binder caller", Process.myUid(), initial.getInt("prepare_caller_uid"))
            val before = canRead(uri)
            evidence.put("host_can_read_before", before)
            if (sourceUri == null) assertFalse("Protected provider must require a real grant", before)
            else assertTrue("Owner control must be able to read its own synthetic private input", before)
            val accepted = AtomicBoolean()
            val delivered = AtomicBoolean()
            val existing = File(context.cacheDir, "staged").listFiles().orEmpty().map { it.name }.toSet()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val launcher = Intent(context, MainActivity::class.java).putExtra("isScreenshotRun", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            // ActivityScenario filters later lifecycle events against its original
            // launch Intent. Real VIEW/SEND delivery changes Activity.intent, so use
            // the actual Activity identity and require its destruction directly.
            val activity = instrumentation.startActivitySync(launcher) as MainActivity
            try {
                instrumentation.runOnMainSync {
                    observer = CoroutineScope(Dispatchers.Main.immediate).launch(start = CoroutineStart.UNDISPATCHED) {
                        activity.incomingPdfUriForTesting.collect { if (it == uri) accepted.set(true) }
                    }
                }
                if (kind == "ownInternal") {
                    evidence.put("launch_origin", "internal_document_action").put("launch_uid", Process.myUid())
                    instrumentation.runOnMainSync { DocumentActions.view(activity, uri) }
                } else {
                evidence.put("launch_origin", "foreign_activity")
                context.startActivity(Intent().setComponent(ComponentName("com.pdfchemy.app.test", "com.pdfchemy.app.security.CrossUidSenderFixture\$SenderActivity"))
                    .putExtra("token", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                var sent: Bundle? = null
                waitUntil("foreign sender launch") {
                    sent = context.contentResolver.call(receiptUri, "get", token, null)
                    sent!!.getBoolean("sent") || sent!!.containsKey("launch_error")
                }
                val result = sent!!
                evidence.put("launch_uid", result.getInt("launch_uid")).put("sender_can_read", result.getBoolean("sender_can_read"))
                    .put("launch_error", result.getString("launch_error") ?: JSONObject.NULL)
                    .put("launch_error_message", result.getString("launch_error_message") ?: JSONObject.NULL)
                if (kind == "ownAlias") {
                    assertFalse("Private alias must never launch for a foreign UID", result.getBoolean("sent"))
                    assertEquals("SecurityException", result.getString("launch_error"))
                    assertTrue("Android must enforce the non-exported component boundary",
                        result.getString("launch_error_message").orEmpty().contains("not exported"))
                } else assertNull("Foreign launch must succeed", result.getString("launch_error"))
                assertEquals(initial.getInt("sender_uid"), result.getInt("launch_uid"))
                if (sourceUri != null) assertFalse("Foreign sender must not be able to read the app's private input", result.getBoolean("sender_can_read"))
                }
                if (kind != "ownAlias") {
                waitUntil("real onNewIntent delivery") {
                    instrumentation.runOnMainSync {
                        delivered.set(if (kind == "ownInternal")
                            activity.intent.component?.className == InternalDocumentIntents.ALIAS_CLASS_NAME && activity.intent.data == uri
                        else activity.intent.getStringExtra("crossUidProbeToken") == token)
                    }
                    delivered.get()
                }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                evidence.put("delivered", delivered.get()).put("admitted", accepted.get())
                evidence.put("host_can_read_after", canRead(uri))
                if (accepted.get()) {
                    val deadline = SystemClock.uptimeMillis() + 3_000
                    var snapshot: File? = null
                    do {
                        snapshot = File(context.cacheDir, "staged").listFiles().orEmpty().firstOrNull {
                            it.name !in existing && it.isFile && it.length() == bytes.size.toLong() && runCatching { it.readBytes().contentEquals(bytes) }.getOrDefault(false)
                        }
                        if (snapshot == null) SystemClock.sleep(50)
                    } while (snapshot == null && SystemClock.uptimeMillis() < deadline)
                    evidence.put("automatic_staging_observed", snapshot != null)
                    snapshot?.copyTo(File(archive, "automatically-staged.pdf"))
                    if (kind == "ownInternal") assertNotNull("Internal Open must stage the exact private document", snapshot)
                }
                assertEquals("Foreign intent admission must respect private capability boundaries", expectedAdmission, accepted.get())
                if (kind == "provider") {
                    if (grant) {
                        assertTrue("Actual sender URI grant must authorize reading", canRead(uri))
                        val staged = DocumentStager.stageDocument(context, uri)
                        try { assertArrayEquals(bytes, File(staged.uri.path!!).readBytes()) } finally { DocumentStager.release(staged) }
                        evidence.put("granted_stage_bytes_match", true)
                    } else {
                        assertFalse("Launch without a grant must not create read authority", canRead(uri))
                        val beforeFailedCopy = File(context.cacheDir, "staged").listFiles().orEmpty().map { it.name }.toSet()
                        assertThrows(SecurityException::class.java) { DocumentStager.stageDocument(context, uri) }
                        assertEquals(beforeFailedCopy, File(context.cacheDir, "staged").listFiles().orEmpty().map { it.name }.toSet())
                        evidence.put("ungranted_stage_rejected", true)
                    }
                }
            } finally {
                observer?.cancel()
                instrumentation.runOnMainSync { activity.finish() }
                waitUntil("MainActivity destruction after foreign intent") { activity.isDestroyed }
            }
        } finally {
            observer?.cancel()
            File(archive, "receipt.json").writeText(evidence.toString(2))
            if (prepared) context.contentResolver.call(receiptUri, "clear", token, null)
            ownFile?.delete()
            DocumentStager.releaseAll()
        }
    }

    @Test fun foreignFileViewCannotClaimPrivateAppFile() = verify("ownFile", Intent.ACTION_VIEW, expectedAdmission = false)
    @Test fun foreignSendCannotClaimAppOwnedProvider() = verify("ownProvider", Intent.ACTION_SEND, expectedAdmission = false)
    @Test fun foreignSendMultipleCannotClaimAppOwnedProvider() = verify("ownProvider", Intent.ACTION_SEND_MULTIPLE, expectedAdmission = false)
    @Test fun foreignViewCannotClaimUserQualifiedAppOwnedProvider() = verify("ownQualified", Intent.ACTION_VIEW, expectedAdmission = false)
    @Test fun foreignProviderReadGrantIsTransferredAndStaged() = verify("provider", Intent.ACTION_VIEW, grant = true, expectedAdmission = true)
    @Test fun foreignProviderWithoutGrantCannotBeReadOrStaged() = verify("provider", Intent.ACTION_SEND, expectedAdmission = true)
    @Test fun foreignUidCannotLaunchPrivateInternalAlias() = verify("ownAlias", Intent.ACTION_VIEW, expectedAdmission = false)
    @Test fun internalOpenStillAdmitsAndStagesOwnDocument() = verify("ownInternal", Intent.ACTION_VIEW, expectedAdmission = true)
}
