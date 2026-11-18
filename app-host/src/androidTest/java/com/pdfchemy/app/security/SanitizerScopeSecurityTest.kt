package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.PdfSanitizerEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.*
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SanitizerScopeSecurityTest {
    @Test fun customPurgesPreserveUnselectedFeaturesAndDefaultPurgesReauditClean() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("sanitizer_scope_", ".pdf", context.cacheDir)
        val selective = File.createTempFile("sanitizer_selected_", ".pdf", context.cacheDir)
        val complete = File.createTempFile("sanitizer_complete_", ".pdf", context.cacheDir)
        try {
            PDFBoxResourceLoader.init(context)
            PDDocument().use { document ->
                val page = PDPage(); document.addPage(page)
                document.documentInformation.author = "Retained when requested"
                document.documentCatalog.openAction = PDActionJavaScript("app.alert('untrusted')")
                page.annotations.add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(100f, 20f)
                    action = PDActionURI().apply { uri = "https://example.com" }
                })
                document.save(source)
            }
            val selected = PdfSanitizerEngine.sanitizeDocument(context, Uri.fromFile(source), Uri.fromFile(selective),
                purgeJs = true, purgeActions = false, purgeMetadata = false, purgeAttachments = false)
            assertTrue(selected.isSuccess)
            assertTrue(selected.jsRemoved > 0)
            assertEquals(0, selected.actionsRemoved)
            assertEquals(0, selected.attachmentsRemoved)
            val selectedAudit = PdfSanitizerEngine.auditDocumentThreats(context, Uri.fromFile(selective))
            assertEquals(0, selectedAudit.threatsFound)
            assertEquals(0, selectedAudit.jsCount)
            assertEquals(0, selectedAudit.launchActionsCount)
            assertEquals(0, selectedAudit.otherActionsCount)
            assertEquals(0, selectedAudit.untrustedUriCount)
            assertEquals(1, selectedAudit.uriCount)
            assertTrue(selectedAudit.hasMetadata)
            assertTrue(selectedAudit.isClean)
            assertTrue(PdfSanitizerEngine.sanitizeDocument(context, Uri.fromFile(source), Uri.fromFile(complete)).isSuccess)
            assertTrue(PdfSanitizerEngine.auditDocumentThreats(context, Uri.fromFile(complete)).isClean)
        } finally { source.delete(); selective.delete(); complete.delete() }
    }

    @Test fun attachmentDeduplicationRegressionTest() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("attachment_dedup_", ".pdf", context.cacheDir)
        try {
            PDFBoxResourceLoader.init(context)
            PDDocument().use { document ->
                val page = PDPage(); document.addPage(page)
                
                // Add embedded file mapping (one logical attachment, but multiple nested dictionaries: Names Tree, FileSpec, EmbeddedFile)
                val efTree = com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary(document.documentCatalog)
                val efMap = com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode()
                val fs = com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification()
                fs.file = "TestFile.txt"
                
                val ef = com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile(document, java.io.ByteArrayInputStream("Dummy".toByteArray()))
                ef.subtype = "text/plain"
                fs.embeddedFile = ef
                
                efMap.names = mapOf("TestFile" to fs)
                efTree.embeddedFiles = efMap
                document.documentCatalog.names = efTree
                
                document.save(source)
            }
            val audit = PdfSanitizerEngine.auditDocumentThreats(context, Uri.fromFile(source))
            // The file contains exactly 1 logical attachment, so we expect exactly 1.
            assertEquals("Logical attachment count should be exactly 1 despite nested dictionaries", 1, audit.attachmentCount)
        } finally { source.delete() }
    }
    @Test fun metadataPurgeRemovesPagePayloadAndAliasesButKeepOptionPreservesThem() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("metadata_source_", ".pdf", context.cacheDir)
        val purged = File.createTempFile("metadata_purged_", ".pdf", context.cacheDir)
        val kept = File.createTempFile("metadata_kept_", ".pdf", context.cacheDir)
        val marker = "PRIVATE_PAGE_XMP_883729"
        try {
            PDDocument().use { document ->
                val page = PDPage().also(document::addPage)
                val metadata = com.tom_roush.pdfbox.pdmodel.common.PDMetadata(document).apply { importXMPMetadata(marker.toByteArray()) }
                page.metadata = metadata
                document.documentCatalog.cosObject.setItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("AlternatePayload"), metadata)
                document.save(source)
            }
            assertTrue(PdfSanitizerEngine.auditDocumentThreats(context, Uri.fromFile(source)).hasMetadata)
            assertTrue(PdfSanitizerEngine.sanitizeDocument(context, Uri.fromFile(source), Uri.fromFile(kept), purgeMetadata = false).isSuccess)
            PDDocument.load(kept).use { document ->
                assertEquals(marker, document.getPage(0).metadata.createInputStream().use { String(it.readBytes()) })
            }
            val result = PdfSanitizerEngine.sanitizeDocument(context, Uri.fromFile(source), Uri.fromFile(purged))
            assertTrue(result.isSuccess)
            assertTrue(result.metadataRemoved)
            assertFalse(PdfSanitizerEngine.auditDocumentThreats(context, Uri.fromFile(purged)).hasMetadata)
            PDDocument.load(purged).use { document ->
                assertNull(document.getPage(0).metadata)
                assertNull(document.documentCatalog.cosObject.getItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("AlternatePayload")))
                for (obj in document.document.objects) (obj.`object` as? com.tom_roush.pdfbox.cos.COSStream)?.let {
                    assertFalse(it.createInputStream().use { input -> String(input.readBytes()) }.contains(marker))
                }
            }
        } finally { source.delete(); purged.delete(); kept.delete() }
    }
    @Test fun attachmentPurgeRemovesAlternatePayloadButKeepOptionPreservesIt() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("alias_source_", ".pdf", context.cacheDir)
        val purged = File.createTempFile("alias_purged_", ".pdf", context.cacheDir)
        val kept = File.createTempFile("alias_kept_", ".pdf", context.cacheDir)
        val marker = "PRIVATE_ALIASED_ATTACHMENT_773289"
        val aliasKey = com.tom_roush.pdfbox.cos.COSName.getPDFName("AlternatePayload")
        try {
            PDDocument().use { document ->
                document.addPage(PDPage())
                document.documentInformation.author = "KEEP_AUTHOR"
                val payload = com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile(document, marker.byteInputStream())
                val spec = com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification().apply { file = "private.txt"; embeddedFile = payload }
                document.documentCatalog.names = PDDocumentNameDictionary(document.documentCatalog).apply {
                    embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("private" to spec) }
                }
                document.documentCatalog.cosObject.setItem(aliasKey, payload)
                document.save(source)
            }
            assertTrue(PdfSanitizerEngine.sanitizeDocument(context, Uri.fromFile(source), Uri.fromFile(kept), purgeMetadata = false, purgeAttachments = false).isSuccess)
            PDDocument.load(kept).use { document ->
                val alias = document.documentCatalog.cosObject.getDictionaryObject(aliasKey) as com.tom_roush.pdfbox.cos.COSStream
                assertEquals(marker, alias.createInputStream().use { String(it.readBytes()) })
            }
            val result = PdfSanitizerEngine.sanitizeDocument(context, Uri.fromFile(source), Uri.fromFile(purged), purgeMetadata = false)
            assertTrue(result.isSuccess)
            assertEquals(1, result.attachmentsRemoved)
            PDDocument.load(purged).use { document ->
                assertEquals("KEEP_AUTHOR", document.documentInformation.author)
                assertNull(document.documentCatalog.cosObject.getItem(aliasKey))
                for (obj in document.document.objects) (obj.`object` as? com.tom_roush.pdfbox.cos.COSStream)?.let {
                    assertFalse(it.createInputStream().use { input -> String(input.readBytes()) }.contains(marker))
                }
            }
        } finally { source.delete(); purged.delete(); kept.delete() }
    }
}
