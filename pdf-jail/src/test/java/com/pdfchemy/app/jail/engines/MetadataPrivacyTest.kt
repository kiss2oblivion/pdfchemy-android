package com.pdfchemy.app.jail.engines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.common.filespecification.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MetadataPrivacyTest {
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>()) }
    private fun name(value: String) = COSName.getPDFName(value)
    private val marker = "PRIVATE_METADATA_883729"
    private fun saved(doc: PDDocument, check: (PDDocument, ByteArray) -> Unit) {
        val bytes = ByteArrayOutputStream().also(doc::save).toByteArray()
        PDDocument.load(bytes).use { check(it, bytes) }
    }
    private fun assertRemoved(doc: PDDocument) = saved(doc) { output, bytes ->
        assertFalse(PdfPrivacyCleanup.hasMetadata(output))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(marker))
        for (obj in output.document.objects) (obj.`object` as? COSStream)?.let {
            assertFalse(it.createInputStream().use { input -> String(input.readBytes()) }.contains(marker))
        }
    }
    @Test fun pageAndFormMetadataAndAliasesAreRemovedWithoutRasterizing() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            page.rotation = 90
            PDPageContentStream(doc, page).use { it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f); it.newLineAtOffset(40f, 50f); it.showText("KEEP_VECTOR_TEXT"); it.endText() }
            val metadata = PDMetadata(doc).apply { importXMPMetadata(marker.toByteArray()) }
            page.metadata = metadata
            val nested = COSDictionary().apply { setItem(name("Metadata"), metadata) }
            page.resources.cosObject.setItem(name("Nested"), nested)
            doc.documentCatalog.cosObject.setItem(name("AlternatePayload"), metadata)
            assertTrue(PdfPrivacyCleanup.hasMetadata(doc))
            val content = page.contents.use { it.readBytes() }
            assertTrue(String(content).contains("KEEP_VECTOR_TEXT"))
            PdfPrivacyCleanup.removeMetadata(doc)
            assertRemoved(doc)
            saved(doc) { output, _ ->
                assertEquals(90, output.getPage(0).rotation)
                assertArrayEquals(content, output.getPage(0).contents.use { it.readBytes() })
                assertEquals(1, output.getPage(0).resources.fontNames.count())
            }
        }
    }
    @Test fun infoDescendantAndDirectPieceInfoAliasesAreRemoved() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val secret = COSString(marker)
            doc.documentInformation.cosObject.setItem(name("Private"), COSDictionary().apply { setItem(name("Nested"), COSArray().apply { add(secret) }) })
            val catalog = doc.documentCatalog.cosObject
            catalog.setItem(name("AlternatePayload"), secret)
            catalog.setItem(name("Custom"), COSDictionary().apply { setItem(name("PieceInfo"), COSDictionary().apply { setItem(name("Private"), secret) }) })
            PdfPrivacyCleanup.removeMetadata(doc)
            assertRemoved(doc)
            assertNull(catalog.getItem(name("AlternatePayload")))
        }
    }
    @Test fun metadataOnlyAndNonAuthorInfoAreDetected() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.documentInformation.subject = marker
            assertTrue(PdfPrivacyCleanup.hasMetadata(doc))
            PdfPrivacyCleanup.removeMetadata(doc)
            doc.getPage(0).metadata = PDMetadata(doc).apply { importXMPMetadata(marker.toByteArray()) }
            assertTrue(PdfPrivacyCleanup.hasMetadata(doc))
        }
    }
    @Test fun sourceIdentifierIsReplacedRatherThanRetained() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.document.trailer.setItem(COSName.ID, COSArray().apply { add(COSString(marker)); add(COSString(marker)) })
            PdfPrivacyCleanup.removeMetadata(doc)
            assertNull(doc.document.trailer.getItem(COSName.ID))
            assertRemoved(doc)
        }
    }
    @Test fun cyclicPrivateDataTerminatesAndIsDetached() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val private = COSDictionary().apply { setString(name("Private"), marker); setItem(name("Self"), this) }
            doc.documentCatalog.cosObject.setItem(name("PieceInfo"), private)
            PdfPrivacyCleanup.removeMetadata(doc)
            assertRemoved(doc)
        }
    }
    @Test fun attachmentAndNavigationArePreservedWhenRemovingMetadata() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val spec = PDComplexFileSpecification().apply { file = "keep.txt"; embeddedFile = PDEmbeddedFile(doc, "KEEP_ATTACHMENT".byteInputStream()) }
            doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply {
                embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("keep.txt" to spec) }
            }
            doc.documentInformation.author = marker
            PdfPrivacyCleanup.removeMetadata(doc)
            saved(doc) { output, _ -> assertEquals("KEEP_ATTACHMENT", output.documentCatalog.names.embeddedFiles.getValue("keep.txt").embeddedFile.createInputStream().use { String(it.readBytes()) }) }
        }
    }
    @Test fun liveContentAliasIsRejectedBeforeMutation() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            PDPageContentStream(doc, page).use { it.addRect(1f, 1f, 10f, 10f); it.fill() }
            val content = page.cosObject.getItem(COSName.CONTENTS)
            page.cosObject.setItem(COSName.METADATA, content)
            assertTrue(runCatching { PdfPrivacyCleanup.removeMetadata(doc) }.isFailure)
            assertSame(content, page.cosObject.getItem(COSName.CONTENTS))
            assertSame(content, page.cosObject.getItem(COSName.METADATA))
        }
    }
    @Test fun privateHierarchyAliasAndExcessiveDepthFailClosed() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            doc.documentCatalog.cosObject.setItem(name("PieceInfo"), page.cosObject)
            assertTrue(runCatching { PdfPrivacyCleanup.removeMetadata(doc) }.isFailure)
            doc.documentCatalog.cosObject.removeItem(name("PieceInfo"))
            var node = COSDictionary().apply { setString(name("Private"), marker) }
            repeat(140) { node = COSDictionary().apply { setItem(name("Nested"), node) } }
            doc.documentCatalog.cosObject.setItem(name("PieceInfo"), node)
            assertTrue(runCatching { PdfPrivacyCleanup.removeMetadata(doc) }.isFailure)
        }
    }
    @Test fun handSerializedDirectMetadataIsRemovedWithoutChangingDrawingCommands() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/privacy-direct-metadata.pdf")).use { it.readBytes() }
        PDDocument.load(fixture).use { doc ->
            assertTrue(doc.documentCatalog.cosObject.getItem(name("CustomWrapper")) is COSDictionary)
            val content = doc.getPage(0).contents.use { it.readBytes() }
            PdfPrivacyCleanup.removeMetadata(doc)
            saved(doc) { output, bytes ->
                assertFalse(String(bytes).contains("PRIVATE_DIRECT_METADATA_55321"))
                val wrapper = output.documentCatalog.cosObject.getDictionaryObject(name("CustomWrapper")) as COSDictionary
                assertNull(wrapper.getItem(name("PieceInfo")))
                assertArrayEquals(content, output.getPage(0).contents.use { it.readBytes() })
                assertFalse(PdfPrivacyCleanup.hasMetadata(output))
            }
        }
    }
}
