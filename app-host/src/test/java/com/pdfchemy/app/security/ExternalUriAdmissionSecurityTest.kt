package com.pdfchemy.app.security

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.utils.DocumentStager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalUriAdmissionSecurityTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        DocumentStager.releaseAll()
    }

    @After
    fun tearDown() {
        DocumentStager.releaseAll()
    }

    @Test
    fun testAdmit_nullIntent_returnsRejected() {
        val result = ExternalUriAdmission.admit(app, null)
        assertTrue(result is ExternalUriAdmission.AdmissionResult.Rejected)
        assertEquals("Intent is null", (result as ExternalUriAdmission.AdmissionResult.Rejected).reason)
    }

    @Test
    fun testAdmit_unsupportedAction_returnsRejected() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            data = Uri.parse("content://media/external/file/1")
        }
        val result = ExternalUriAdmission.admit(app, intent)
        assertTrue(result is ExternalUriAdmission.AdmissionResult.Rejected)
        assertTrue((result as ExternalUriAdmission.AdmissionResult.Rejected).reason.contains("Unsupported external intent action"))
    }

    @Test
    fun testAdmit_viewAction_withValidContentUri_returnsAdmitted() {
        val target = Uri.parse("content://com.android.providers.media.documents/document/123")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = target
        }
        val result = ExternalUriAdmission.admit(app, intent)
        assertTrue(result is ExternalUriAdmission.AdmissionResult.Admitted)
        assertEquals(target, (result as ExternalUriAdmission.AdmissionResult.Admitted).uri)
    }

    @Test
    fun testAdmit_sendAction_withValidContentUri_returnsAdmitted() {
        val target = Uri.parse("content://com.google.android.apps.docs.storage/doc/456")
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, target)
        }
        val result = ExternalUriAdmission.admit(app, intent)
        assertTrue(result is ExternalUriAdmission.AdmissionResult.Admitted)
        assertEquals(target, (result as ExternalUriAdmission.AdmissionResult.Admitted).uri)
    }

    @Test
    fun testAdmit_sendMultipleAction_withValidContentUri_returnsAdmitted() {
        val target1 = Uri.parse("content://provider/doc/1")
        val target2 = Uri.parse("content://provider/doc/2")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(target1, target2))
        }
        val result = ExternalUriAdmission.admit(app, intent)
        assertTrue(result is ExternalUriAdmission.AdmissionResult.Admitted)
        assertEquals(target1, (result as ExternalUriAdmission.AdmissionResult.Admitted).uri)

        val allResults = ExternalUriAdmission.admitAll(app, intent)
        assertEquals(2, allResults.size)
        assertTrue(allResults[0] is ExternalUriAdmission.AdmissionResult.Admitted)
        assertTrue(allResults[1] is ExternalUriAdmission.AdmissionResult.Admitted)
    }

    @Test
    fun testAdmit_clipDataUri_returnsAdmitted() {
        val target = Uri.parse("content://com.example.provider/pdf/789")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            clipData = ClipData.newUri(app.contentResolver, "label", target)
        }
        val result = ExternalUriAdmission.admit(app, intent)
        assertTrue(result is ExternalUriAdmission.AdmissionResult.Admitted)
        assertEquals(target, (result as ExternalUriAdmission.AdmissionResult.Admitted).uri)
    }

    @Test
    fun testAdmit_forbiddenSchemes_rejected() {
        val forbidden = listOf(
            "http://attacker.com/exploit.pdf",
            "https://attacker.com/exploit.pdf",
            "ftp://files.example.com/doc.pdf",
            "javascript:alert(1)",
            "data:application/pdf;base64,JVBERi0xLjQK",
            "android.resource://com.pdfchemy.app/raw/sample",
            "about:blank",
            "ws://evil.com/stream"
        )
        for (uriString in forbidden) {
            val result = ExternalUriAdmission.admitUri(app, Uri.parse(uriString))
            assertTrue("Expected rejection for $uriString", result is ExternalUriAdmission.AdmissionResult.Rejected)
            val rejected = result as ExternalUriAdmission.AdmissionResult.Rejected
            assertTrue(
                "Reason must mention forbidden scheme: ${rejected.reason}",
                rejected.reason.contains("forbidden") || rejected.reason.contains("Unsupported")
            )
        }
    }

    @Test
    fun testAdmit_nullOrEmptyUri_rejected() {
        val nullResult = ExternalUriAdmission.admitUri(app, null)
        assertTrue(nullResult is ExternalUriAdmission.AdmissionResult.Rejected)

        val emptyResult = ExternalUriAdmission.admitUri(app, Uri.parse(""))
        assertTrue(emptyResult is ExternalUriAdmission.AdmissionResult.Rejected)

        val blankResult = ExternalUriAdmission.admitUri(app, Uri.parse("   "))
        assertTrue(blankResult is ExternalUriAdmission.AdmissionResult.Rejected)
    }

    @Test
    fun testAdmit_fileUri_inSharedPrefs_strictlyRejected() {
        val sharedPrefsFile = File(app.applicationInfo.dataDir, "shared_prefs/app_prefs.xml")
        val uri = Uri.fromFile(sharedPrefsFile)

        val result = ExternalUriAdmission.admitUri(app, uri)
        assertTrue(result is ExternalUriAdmission.AdmissionResult.Rejected)
        assertTrue(
            (result as ExternalUriAdmission.AdmissionResult.Rejected).reason.contains("private application storage")
        )
    }

    @Test
    fun testAdmit_fileUri_inDatabases_strictlyRejected() {
        val dbFile = File(app.applicationInfo.dataDir, "databases/app_secrets.db")
        val uri = Uri.fromFile(dbFile)

        val result = ExternalUriAdmission.admitUri(app, uri)
        assertTrue(result is ExternalUriAdmission.AdmissionResult.Rejected)
        assertTrue(
            (result as ExternalUriAdmission.AdmissionResult.Rejected).reason.contains("private application storage")
        )
    }

    @Test
    fun testAdmit_fileUri_inFilesDir_strictlyRejected() {
        val privateFile = File(app.filesDir, "internal_document.pdf")
        val uri = Uri.fromFile(privateFile)

        val result = ExternalUriAdmission.admitUri(app, uri)
        assertTrue(result is ExternalUriAdmission.AdmissionResult.Rejected)
        assertTrue(
            (result as ExternalUriAdmission.AdmissionResult.Rejected).reason.contains("private application storage")
        )
    }

    @Test
    fun testAdmit_fileUri_inCacheDir_strictlyRejected() {
        val privateCache = File(app.cacheDir, "private_render.tmp")
        val uri = Uri.fromFile(privateCache)

        val result = ExternalUriAdmission.admitUri(app, uri)
        assertTrue(result is ExternalUriAdmission.AdmissionResult.Rejected)
        assertTrue(
            (result as ExternalUriAdmission.AdmissionResult.Rejected).reason.contains("private application storage")
        )
    }

    @Test
    fun testAdmit_fileUri_attemptingToClaimStagedSnapshot_strictlyRejected() {
        val dummyPdf = "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF".toByteArray()
        val stagedLease = DocumentStager.stageStreamWithLease(app, ByteArrayInputStream(dummyPdf), "pdf")

        try {
            // An external intent attempting to pass the internal staged capability URI
            val result = ExternalUriAdmission.admitUri(app, stagedLease.document.uri)
            assertTrue(
                "External intent must be strictly rejected when claiming internal staged capability",
                result is ExternalUriAdmission.AdmissionResult.Rejected
            )
            val reason = (result as ExternalUriAdmission.AdmissionResult.Rejected).reason
            assertTrue(
                "Reason must mention staged capability or private storage: $reason",
                reason.contains("internal staged capability") || reason.contains("private application storage")
            )
        } finally {
            stagedLease.close()
        }
    }

    @Test
    fun testAdmit_fileUri_withTraversalToPrivateStorage_strictlyRejected() {
        val traversalUri = Uri.parse("file:///sdcard/Download/../../data/data/${app.packageName}/shared_prefs/app_prefs.xml")
        val result = ExternalUriAdmission.admitUri(app, traversalUri)
        assertTrue(result is ExternalUriAdmission.AdmissionResult.Rejected)
        assertTrue(
            (result as ExternalUriAdmission.AdmissionResult.Rejected).reason.contains("Path traversal pattern rejected") ||
            (result as ExternalUriAdmission.AdmissionResult.Rejected).reason.contains("private application storage")
        )
    }

    @Test
    fun testAdmit_fileUri_onExternalStorage_returnsAdmitted() {
        val externalFile = File("/storage/emulated/0/Download/legitimate_document.pdf")
        val uri = Uri.fromFile(externalFile)

        val result = ExternalUriAdmission.admitUri(app, uri)
        assertTrue("Legitimate external document should be admitted", result is ExternalUriAdmission.AdmissionResult.Admitted)
        assertEquals(uri, (result as ExternalUriAdmission.AdmissionResult.Admitted).uri)
    }

    @Test
    fun testAdmit_contentUri_maliciousAuthority_rejected() {
        val malicious = listOf(
            "content://../traversal/path",
            "content://evil/../something",
            "content://evil\\backslash/path"
        )
        for (m in malicious) {
            val result = ExternalUriAdmission.admitUri(app, Uri.parse(m))
            assertTrue(result is ExternalUriAdmission.AdmissionResult.Rejected)
        }
    }

    @Test
    fun testTrustedStagedCapability_validStagedSnapshot_returnsTrue() {
        val dummyPdf = "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF".toByteArray()
        val stagedLease = DocumentStager.stageStreamWithLease(app, ByteArrayInputStream(dummyPdf), "pdf")
        try {
            assertTrue(
                "Valid staged snapshot must be recognized as trusted staged capability",
                DocumentStager.isTrustedStagedCapability(app, stagedLease.document.uri)
            )
        } finally {
            stagedLease.close()
        }
    }

    @Test
    fun testTrustedStagedCapability_unregisteredFile_returnsFalse() {
        val dummy = File(app.cacheDir, "staged/snapshot_999999.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        try {
            assertFalse(
                "Unregistered file in staged directory must NOT be trusted capability",
                DocumentStager.isTrustedStagedCapability(app, Uri.fromFile(dummy))
            )
        } finally {
            dummy.delete()
        }
    }

    @Test
    fun testTrustedStagedCapability_tamperedSize_returnsFalse() {
        val dummyPdf = "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF".toByteArray()
        val stagedLease = DocumentStager.stageStreamWithLease(app, ByteArrayInputStream(dummyPdf), "pdf")
        val stagedFile = File(stagedLease.document.uri.path!!)
        try {
            // Tamper file size
            stagedFile.setWritable(true)
            stagedFile.appendBytes(byteArrayOf(0, 0, 0))

            assertFalse(
                "Tampered file size must invalidate trusted capability",
                DocumentStager.isTrustedStagedCapability(app, stagedLease.document.uri)
            )
        } finally {
            stagedLease.close()
        }
    }

    @Test
    fun testDocumentStager_refusesToStageSharedPrefs() {
        val sharedPrefsFile = File(app.applicationInfo.dataDir, "shared_prefs/app_prefs.xml").apply {
            parentFile?.mkdirs()
            writeText("<map><string name=\"token\">secret_123</string></map>")
        }
        try {
            assertThrows(SecurityException::class.java) {
                DocumentStager.stageDocument(app, Uri.fromFile(sharedPrefsFile))
            }
        } finally {
            sharedPrefsFile.delete()
        }
    }
}
