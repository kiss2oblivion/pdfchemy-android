package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.logic.PdfGateway
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeepMetadataRemovalDeviceTest {
    @Test fun metadataWipeRemovesDirectPrivateDictionaryAndKeepsDrawing() = exercise("METADATA_WRITE", true)
    @Test fun compressionStripRemovesDirectPrivateDictionaryAndKeepsDrawing() = exercise("COMPRESS", true)
    @Test fun compressionKeepPreservesPrivateDictionaryAndDrawing() = exercise("COMPRESS", false)
    @Test fun metadataEditPreservesUnselectedPrivateData() = exercise("METADATA_WRITE", false)

    private fun exercise(operation: String, purge: Boolean) = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = InstrumentationRegistry.getInstrumentation().context.assets.open("privacy-direct-metadata.pdf").use { it.readBytes() }
        val source = File.createTempFile("direct_metadata_in_", ".pdf", context.cacheDir).apply { writeBytes(input) }
        val output = File.createTempFile("direct_metadata_out_", ".pdf", context.cacheDir)
        try {
            val originalDrawing = PDDocument.load(input).use { document ->
                assertTrue(document.documentCatalog.cosObject.getItem(COSName.getPDFName("CustomWrapper")) is COSDictionary)
                document.getPage(0).contents.use { it.readBytes() }
            }
            val params = if (operation == "COMPRESS") JSONObject().put("stripMetadata", purge)
                else JSONObject().put("wipeAllMetadata", purge).put("newMetadata", JSONObject().put("author", "EDITED_AUTHOR"))
            PdfGateway.executeEngine(context, operation, Uri.fromFile(source), Uri.fromFile(output), params.toString())
            PDDocument.load(output).use { document ->
                assertEquals(1, document.numberOfPages)
                assertArrayEquals(originalDrawing, document.getPage(0).contents.use { it.readBytes() })
                val wrapper = document.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("CustomWrapper")) as COSDictionary
                if (purge) {
                    assertNull(wrapper.getItem(COSName.getPDFName("PieceInfo")))
                    assertFalse(String(output.readBytes(), Charsets.ISO_8859_1).contains("PRIVATE_DIRECT_METADATA_55321"))
                } else {
                    val info = wrapper.getDictionaryObject(COSName.getPDFName("PieceInfo")) as COSDictionary
                    assertEquals("PRIVATE_DIRECT_METADATA_55321", info.getString(COSName.getPDFName("Private")))
                    if (operation == "METADATA_WRITE") assertEquals("EDITED_AUTHOR", document.documentInformation.author)
                }
            }
            assertArrayEquals(input, source.readBytes())
        } finally { source.delete(); output.delete() }
    }
}
