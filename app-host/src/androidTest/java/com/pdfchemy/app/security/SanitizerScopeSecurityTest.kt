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
            assertEquals(0, selectedAudit.jsCount)
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
}
