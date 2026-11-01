package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchProviderClasspathIntegrityTest {
    @Test fun foreignUidCreationAndDeletionNeedNoHostClasspath() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val authority = "com.pdfchemy.app.security.batchfixture"
        val control = Uri.parse("content://$authority")
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        resolver.call(control, "configure", null, Bundle())
        val state = resolver.call(control, "state", null, null)!!
        assertNotEquals("Provider must run outside the instrumented host UID", Process.myUid(), state.getInt("providerUid"))
        val existing = DocumentsContract.buildDocumentUriUsingTree(tree, "existing")
        fun bytes(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
        val before = bytes(existing)
        val created = DocumentsContract.createDocument(resolver, parent, "application/pdf", "classpath.pdf")!!
        try {
            val payload = "foreign provider roundtrip".toByteArray()
            resolver.openOutputStream(created, "wt")!!.use { it.write(payload) }
            assertArrayEquals(payload, bytes(created))
        } finally {
            assertTrue(DocumentsContract.deleteDocument(resolver, created))
        }
        assertArrayEquals(before, bytes(existing))
        assertEquals(1, resolver.call(control, "state", null, null)!!.getInt("deletedCount"))
    }
}
