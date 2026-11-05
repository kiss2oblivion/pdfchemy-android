package com.pdfchemy.app.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.ui.createTempImageUri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShareProviderModeIntegrityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val marker = "original recipient bytes".toByteArray()
    private val recipient get() = InstrumentationRegistry.getInstrumentation().context.packageName
    private val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private fun probe(uri: Uri, mode: String, bytes: ByteArray? = null): Bundle {
        val extras = Bundle().apply { putString("mode", mode); putByteArray("bytes", bytes) }
        return context.contentResolver.call(Uri.parse("content://com.pdfchemy.app.test.sharerecipient"), "probeMode", uri.toString(), extras)!!
    }
    private fun fixture(root: File, block: (File, Uri) -> Unit) {
        root.mkdirs()
        val prefix = if (root.name == "shared_copies") "shared_mode_" else "mode_"
        val file = File.createTempFile(prefix, ".pdf", root).apply { writeBytes(marker) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        try { block(file, uri) } finally { context.revokeUriPermission(uri, grant); file.delete() }
    }

    @Test fun everySharedRootRejectsAllWriteModesWithoutChangingBytes() {
        for (root in listOf(File(context.cacheDir,"scans"), File(context.cacheDir,"exports"),
                            File(context.filesDir,"scans"), File(context.filesDir,"shared_copies"))) {
            fixture(root) { file, uri ->
                for (mode in listOf("w","wt","wa","rw","rwt")) {
                    val error = runCatching { context.contentResolver.openFileDescriptor(uri, mode)?.close() }.exceptionOrNull()
                    assertTrue("Read-only sharing must reject $mode", error is SecurityException)
                    assertArrayEquals(marker, file.readBytes())
                }
                assertArrayEquals(marker, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            }
        }
    }

    @Test fun foreignWriteGrantCannotTruncateOrDeleteAReadOnlySharedDocument() {
        fixture(File(context.cacheDir,"exports")) { file, uri ->
            context.grantUriPermission(recipient, uri, grant)
            for (mode in listOf("w","wt","wa","rw","rwt","delete")) {
                val result = probe(uri, mode)
                assertNotEquals(context.applicationInfo.uid, result.getInt("uid"))
                assertEquals("SecurityException", result.getString("error"))
                assertTrue(file.exists()); assertArrayEquals(marker, file.readBytes())
            }
            val read = probe(uri,"r")
            assertFalse(read.containsKey("error")); assertTrue(read.getBoolean("opened"))
        }
    }

    @Test fun cameraCaptureRetainsWriteAccessOnlyToItsSeparateScanRoot() {
        val uri = createTempImageUri(context)
        try {
            assertEquals("${context.packageName}.scanprovider", uri.authority)
            context.grantUriPermission(recipient, uri, grant)
            val result = probe(uri, "w", marker)
            assertNotEquals(context.applicationInfo.uid, result.getInt("uid"))
            assertFalse(result.getString("error"), result.containsKey("error"))
            assertArrayEquals(marker, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            fixture(File(context.cacheDir,"exports")) { file, _ ->
                assertTrue(runCatching { FileProvider.getUriForFile(context, "${context.packageName}.scanprovider", file) }.exceptionOrNull() is IllegalArgumentException)
            }
        } finally {
            context.revokeUriPermission(uri, grant)
            context.contentResolver.delete(uri, null, null)
        }
    }
}
