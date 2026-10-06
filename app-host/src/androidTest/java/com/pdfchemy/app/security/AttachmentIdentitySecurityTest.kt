package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.PdfSanitizerEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

@RunWith(AndroidJUnit4::class)
class AttachmentIdentitySecurityTest {
    private fun name(value: String) = COSName.getPDFName(value)

    private suspend fun withFixture(count: Int, sharedReferences: Boolean, check: suspend (Context, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("attachment_identity_", ".pdf", context.cacheDir)
        try {
            PDFBoxResourceLoader.init(context)
            PDDocument().use { document ->
                val page = PDPage(); document.addPage(page)
                val files = (1..count).associate { index ->
                    val fileSpec = PDComplexFileSpecification().apply {
                        file = "attachment-$index.txt"
                        embeddedFile = PDEmbeddedFile(document, ByteArrayInputStream("benign $index".toByteArray())).apply {
                            subtype = "text/plain"
                        }
                    }
                    "attachment-$index" to fileSpec
                }
                document.documentCatalog.names = PDDocumentNameDictionary(document.documentCatalog).apply {
                    embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { names = files }
                }
                if (sharedReferences) {
                    val fileSpec = files.values.first()
                    document.documentCatalog.cosObject.setItem(name("AF"), COSArray().apply { add(fileSpec.cosObject) })
                    page.annotations.add(PDAnnotationFileAttachment().apply {
                        rectangle = PDRectangle(20f, 20f)
                        file = fileSpec
                    })
                }
                document.save(source)
            }
            check(context, source)
        } finally { source.delete() }
    }

    private suspend fun assertBenignCount(context: Context, source: File, count: Int) {
        val uri = Uri.fromFile(source)
        val audit = PdfSanitizerEngine.auditDocumentThreats(context, uri)
        assertEquals(count, audit.attachmentCount)
        assertEquals(0, audit.threatsFound)
        assertTrue(audit.isClean)
        assertFalse(PdfSanitizerEngine.hasExecutableThreats(context, uri))
    }

    @Test fun oneEmbeddedFileCountsOnce() = runBlocking<Unit> {
        withFixture(1, false) { context, source -> assertBenignCount(context, source, 1) }
    }

    @Test fun nameTreeAssociatedFileAndAnnotationShareOneIdentity() = runBlocking<Unit> {
        withFixture(1, true) { context, source -> assertBenignCount(context, source, 1) }
    }

    @Test fun twoIndependentFileSpecificationsCountTwice() = runBlocking<Unit> {
        withFixture(2, true) { context, source -> assertBenignCount(context, source, 2) }
    }

    @Test fun purgingAttachmentsSeversAllCarriersAndReauditsToZero() = runBlocking<Unit> {
        withFixture(2, true) { context, source ->
            val output = File.createTempFile("attachment_purged_", ".pdf", context.cacheDir)
            try {
                val result = PdfSanitizerEngine.sanitizeDocument(context, Uri.fromFile(source), Uri.fromFile(output),
                    purgeJs = false, purgeActions = false, purgeMetadata = false, purgeAttachments = true)
                assertTrue(result.isSuccess)
                assertEquals(2, result.attachmentsRemoved)
                assertBenignCount(context, output, 0)
                PDDocument.load(output).use { document -> assertNoAttachmentCarriers(document) }
            } finally { output.delete() }
        }
    }

    private fun assertNoAttachmentCarriers(document: PDDocument) {
        val queue = ArrayDeque<COSBase>()
        val visited = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        queue.add(document.documentCatalog.cosObject)
        document.document.objects.forEach { queue.add(it) }
        while (queue.isNotEmpty()) {
            val base = queue.removeFirst()
            if (!visited.add(base)) continue
            when (base) {
                is COSObject -> base.`object`?.let { queue.add(it) }
                is COSArray -> for (index in 0 until base.size()) queue.add(base.get(index))
                is COSDictionary -> {
                    for (key in listOf("EmbeddedFiles", "AF", "EF", "FS")) {
                        assertFalse("Surviving attachment carrier /$key", base.containsKey(name(key)))
                    }
                    assertNotEquals("FileAttachment", base.getNameAsString(COSName.SUBTYPE))
                    base.keySet().forEach { key -> base.getItem(key)?.let { queue.add(it) } }
                }
            }
        }
    }
}
