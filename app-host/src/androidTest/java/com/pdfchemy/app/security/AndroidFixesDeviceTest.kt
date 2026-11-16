package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.*
import com.pdfchemy.app.logic.audio.AudioOutputPublisher
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.common.filespecification.*
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidFixesDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun initialize() { PDFBoxResourceLoader.init(context) }
    @Test fun fiveDocumentMergeWithVanguardOff() = verifyMerge(false)
    @Test fun fiveDocumentMergeWithVanguardOn() = verifyMerge(true)
    private fun verifyMerge(enabled: Boolean) = runBlocking {
        val originals = (1..5).map { index ->
            File.createTempFile("batch_source_$index", ".pdf", context.cacheDir).also { file ->
                PDDocument().use { document ->
                    val page = PDPage().also(document::addPage)
                    PDPageContentStream(document, page).use {
                        it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
                        it.newLineAtOffset(20f, 40f); it.showText("page-$index"); it.endText()
                    }
                    document.save(file)
                }
            }
        }
        val output = File.createTempFile("batch_merge", ".pdf", context.cacheDir)
        val operation = StagedSelectionOwner()
        var outcome: PickerOutcome.Selected? = null
        try {
            outcome = VanguardPickerProcessor.processMultiple(context, originals.map(Uri::fromFile), enabled) as PickerOutcome.Selected
            val selected = outcome.stagedUris
            operation.retainOnly(selected)
            outcome.close()
            repeat(10) { DocumentStager.stageStream(context, "eviction pressure $it".byteInputStream()) }
            selected.forEach { assertTrue(File(it.path!!).exists()) }
            PdfManipulator.mergePdfs(context, selected, Uri.fromFile(output))
            PDDocument.load(output).use { merged ->
                assertEquals(5, merged.numberOfPages)
                val text = PDFTextStripper().getText(merged)
                val positions = (1..5).map { text.indexOf("page-$it") }
                assertTrue(positions.all { it >= 0 })
                assertEquals(positions.sorted(), positions)
            }
            operation.close()
            selected.forEach { assertFalse(File(it.path!!).exists()) }
        } finally {
            outcome?.close(); operation.close(); DocumentStager.releaseAll()
            originals.forEach { it.delete() }; output.delete()
        }
    }
    @Test fun attachmentIdentitySurvivesWorkerProtocolAndRemoval() = runBlocking {
        val source = File.createTempFile("attachment_collision", ".pdf", context.cacheDir)
        val output = File.createTempFile("attachment_output", ".bin", context.cacheDir)
        val removed = File.createTempFile("attachment_removed", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document ->
                document.addPage(PDPage())
                fun spec(payload: String) = PDComplexFileSpecification().apply {
                    file = "collision.txt"
                    embeddedFile = PDEmbeddedFile(document, payload.byteInputStream())
                }
                document.documentCatalog.names = PDDocumentNameDictionary(document.documentCatalog).apply {
                    embeddedFiles = PDEmbeddedFilesNameTreeNode().apply {
                        names = linkedMapOf("collision.txt" to spec("first payload"), "z-target" to spec("selected payload"))
                    }
                }
                document.save(source)
            }
            val items = PdfGateway.executeEngineTyped<AttachmentListContract>(context, "ATTACHMENT_LIST", Uri.fromFile(source), null, "{}").attachments
            assertEquals(2, items.size)
            assertEquals(2, items.map { it.attachmentId }.toSet().size)
            for (item in items) {
                PdfGateway.executeEngine(context, "ATTACHMENT_EXTRACT", Uri.fromFile(source), Uri.fromFile(output),
                    JSONObject().put("attachmentId", item.attachmentId).toString())
                assertEquals(if (item.attachmentId == "z-target") "selected payload" else "first payload", output.readText())
            }
            PdfGateway.executeEngine(context, "ATTACHMENT_REMOVE", Uri.fromFile(source), Uri.fromFile(removed),
                JSONObject().put("attachmentId", "z-target").toString())
            PDDocument.load(removed).use { document ->
                assertEquals(setOf("collision.txt"), document.documentCatalog.names.embeddedFiles.names.keys)
            }
        } finally { source.delete(); output.delete(); removed.delete(); DocumentStager.releaseAll() }
    }
    @Test fun cancelledServiceCleanupPreservesPublishedAudio() = runBlocking {
        val resolver = context.contentResolver
        val authority = "com.pdfchemy.app.security.batchfixture"
        val control = Uri.parse("content://$authority")
        resolver.call(control, "configure", null, Bundle())
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val destination = DocumentsContract.createDocument(resolver, parent, "audio/wav", "audio.wav")!!
        val wav = File.createTempFile("audio_publish", ".wav", context.cacheDir).apply { writeBytes(ByteArray(65539) { (it % 251).toByte() }) }
        OutputPolicy.registerFreshCopy(destination)
        val published = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            try {
                AudioOutputPublisher.publish(context, wav, destination, true)
                published.complete(Unit)
                awaitCancellation()
            } finally { AudioOutputPublisher.deletePartial(context, destination, true) }
        }
        try {
            withTimeout(20_000) { published.await() }
            job.cancelAndJoin()
            assertArrayEquals(wav.readBytes(), resolver.openInputStream(destination)!!.use { it.readBytes() })
            assertFalse(OutputPolicy.isFreshCopy(destination))
            assertEquals(0, resolver.call(control, "state", null, null)!!.getInt("deletedCount"))
        } finally {
            job.cancelAndJoin()
            if (OutputPolicy.isFreshCopy(destination)) OutputPolicy.unregisterFreshCopy(destination)
            DocumentsContract.deleteDocument(resolver, destination); wav.delete()
        }
    }
}
