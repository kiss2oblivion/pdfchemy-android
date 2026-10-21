package com.pdfchemy.app.jail.engines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.pdfchemy.app.security.SecurityLimits
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivatePayloadAliasTest {
    @Before fun initialize() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>()) }

    private fun sanitize(document: PDDocument): ByteArray {
        RedactionResources.prepareForRedaction(document)
        val plan = RedactionResources.rasterizationPlan(document, setOf(0), emptySet())
        RedactionResources.replacePageContent(document, document.getPage(0)) {
            it.addRect(0f, 0f, 50f, 50f); it.fill()
        }
        RedactionResources.pruneUnused(document)
        plan.requireOriginalsDetached(document)
        return ByteArrayOutputStream().also(document::save).toByteArray()
    }

    private fun assertAbsent(bytes: ByteArray, marker: String) {
        assertFalse("Private bytes in serialized PDF", String(bytes, Charsets.ISO_8859_1).contains(marker))
        PDDocument.load(bytes).use { saved ->
            val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
            fun visit(raw: COSBase?) {
                val node = if (raw is COSObject) raw.`object` else raw
                if (node == null || !seen.add(node)) return
                when (node) {
                    is COSString -> assertFalse("Private COS string", node.string.contains(marker))
                    is COSStream -> {
                        assertFalse("Private decoded stream", node.createInputStream().use {
                            String(it.readBytes(), Charsets.ISO_8859_1).contains(marker)
                        })
                        node.keySet().forEach { visit(node.getItem(it)) }
                    }
                    is COSDictionary -> node.keySet().forEach { visit(node.getItem(it)) }
                    is COSArray -> for (index in 0 until node.size()) visit(node.get(index))
                }
            }
            visit(saved.document.trailer)
            saved.document.objects.forEach(::visit)
            assertNull(saved.documentCatalog.cosObject.getItem(COSName.getPDFName("AlternatePayload")))
        }
    }

    private fun retainedFixture(name: String) {
        val input = requireNotNull(javaClass.getResourceAsStream("/redaction-private-alias/$name.pdf"))
            .use { it.readBytes() }
        val output = PDDocument.load(input).use(::sanitize)
        assertAbsent(output, "REANALYSIS_NESTED_PRIVATE_PAYLOAD_529814")
    }

    @Test fun retainedPieceInfoFixtureRemovesDescendantAlias() = retainedFixture("piece-info")
    @Test fun retainedUntypedAttachmentFixtureRemovesDescendantAlias() = retainedFixture("attachment")

    @Test fun nestedStringsArraysAndCyclesAreSanitized() {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val marker = "PRIVATE_NESTED_STRING_82641"
            val value = COSString(marker)
            val application = COSDictionary()
            val nested = COSArray().apply { add(value); add(application) }
            application.setItem(COSName.getPDFName("Private"), nested)
            val catalog = document.documentCatalog.cosObject
            catalog.setItem(COSName.getPDFName("PieceInfo"), COSDictionary().apply {
                setItem(COSName.getPDFName("Application"), application)
            })
            catalog.setItem(COSName.getPDFName("AlternatePayload"), value)
            val output = sanitize(document)
            assertAbsent(output, marker)
            PDDocument.load(output).use { saved -> assertEquals(1, saved.numberOfPages) }
        }
    }

    private fun pieceInfo(document: PDDocument, payload: COSBase) {
        document.documentCatalog.cosObject.setItem(COSName.getPDFName("PieceInfo"), COSDictionary().apply {
            setItem(COSName.getPDFName("Application"), COSDictionary().apply {
                setItem(COSName.getPDFName("Private"), payload)
            })
        })
    }

    @Test fun serializedIndirectStringAndContainerAliasesAreRemoved() {
        val marker = "PRIVATE_INDIRECT_STRING_86241"
        // PDFBox's writer inlines COSString wrappers. Serialize a genuine shared
        // indirect string so this tests loaded object identity rather than copies.
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /PieceInfo << /App << /Private 4 0 R >> >> /AlternatePayload 5 0 R /AlternateContainer 4 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>",
            "<< /Type /Page /Nested [5 0 R] >>",
            "($marker)"
        )
        val input = ByteArrayOutputStream().apply {
            fun text(value: String) { write(value.toByteArray(Charsets.ISO_8859_1)) }
            text("%PDF-1.4\n")
            val offsets = objects.mapIndexed { index, body ->
                val offset = size()
                text("${index + 1} 0 obj\n$body\nendobj\n")
                offset
            }
            val xref = size()
            text("xref\n0 6\n0000000000 65535 f \n")
            offsets.forEach { text("%010d 00000 n \n".format(java.util.Locale.ROOT, it)) }
            text("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }.toByteArray()
        PDDocument.load(input).use { document ->
            val catalog = document.documentCatalog.cosObject
            val container = catalog.getDictionaryObject(COSName.getPDFName("AlternateContainer")) as COSDictionary
            val nested = container.getDictionaryObject(COSName.getPDFName("Nested")) as COSArray
            assertSame("Input control must retain indirect string identity", nested.getObject(0),
                catalog.getDictionaryObject(COSName.getPDFName("AlternatePayload")))
            val output = sanitize(document)
            assertAbsent(output, marker)
            PDDocument.load(output).use { saved ->
                assertNull(saved.documentCatalog.cosObject.getItem(COSName.getPDFName("AlternateContainer")))
            }
        }
    }

    @Test fun nestedObjectWrappersAndPrivateCyclesTerminate() {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val payload = COSDictionary()
            val stream = document.document.createCOSStream().apply {
                createOutputStream().use { it.write("PRIVATE_WRAPPED_STREAM_86241".toByteArray()) }
            }
            payload.setItem(COSName.getPDFName("Cycle"), COSObject(COSObject(payload)))
            payload.setItem(COSName.getPDFName("Payload"), COSObject(COSObject(stream)))
            pieceInfo(document, payload)
            document.documentCatalog.cosObject.setItem(COSName.getPDFName("AlternatePayload"), COSObject(stream))
            assertAbsent(sanitize(document), "PRIVATE_WRAPPED_STREAM_86241")
        }
    }

    private fun assertRejectedBeforeMutation(document: PDDocument) {
        document.documentInformation.subject = "Keep before rejection"
        val privateRoot = document.documentCatalog.cosObject.getItem(COSName.getPDFName("PieceInfo"))
        assertThrows(IllegalArgumentException::class.java) { RedactionResources.prepareForRedaction(document) }
        assertSame(privateRoot, document.documentCatalog.cosObject.getItem(COSName.getPDFName("PieceInfo")))
        assertEquals("Keep before rejection", document.documentInformation.subject)
        assertEquals(1, document.numberOfPages)
    }

    @Test fun privateAliasToUsedPageResourceRejectsBeforeMutation() {
        PDDocument().use { document ->
            val page = PDPage().also(document::addPage)
            PDPageContentStream(document, page).use {
                it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f); it.showText("Keep"); it.endText()
            }
            pieceInfo(document, page.resources.cosObject)
            assertRejectedBeforeMutation(document)
        }
    }

    @Test fun privateAliasToNavigationRejectsBeforeMutation() {
        PDDocument().use { document ->
            val page = PDPage().also(document::addPage)
            val destination = COSArray().apply { add(page); add(COSName.getPDFName("Fit")) }
            document.documentCatalog.cosObject.setItem(COSName.DESTS, COSDictionary().apply {
                setItem(COSName.getPDFName("Keep"), destination)
            })
            pieceInfo(document, destination)
            assertRejectedBeforeMutation(document)
        }
    }

    @Test fun actualDocumentAnchorsCannotBePrivateCarrierRoots() {
        PDDocument().use { document ->
            val page = PDPage().also(document::addPage)
            val catalog = document.documentCatalog.cosObject
            for (anchor in listOf(page.cosObject, catalog, document.document.trailer,
                    catalog.getDictionaryObject(COSName.PAGES))) {
                catalog.setItem(COSName.getPDFName("PieceInfo"), anchor)
                assertRejectedBeforeMutation(document)
            }
        }
    }

    @Test fun arbitraryPrivateDescendantLinksToDocumentAnchorsReject() {
        PDDocument().use { document ->
            val page = PDPage().also(document::addPage)
            val catalog = document.documentCatalog.cosObject
            val marker = COSString("PRIVATE_PAGE_DESCENDANT_86241")
            page.cosObject.setItem(COSName.getPDFName("Secret"), marker)
            catalog.setItem(COSName.getPDFName("AlternatePayload"), marker)
            for (anchor in listOf(page.cosObject, catalog, document.document.trailer)) {
                pieceInfo(document, COSArray().apply { add(anchor) })
                assertRejectedBeforeMutation(document)
            }
            val spoofedStructure = COSDictionary().apply {
                setName(COSName.TYPE, "StructElem")
                setItem(COSName.getPDFName("Pg"), page)
            }
            // Strict validation must win even when the same object is a structure seed.
            catalog.setItem(COSName.getPDFName("SE"), spoofedStructure)
            pieceInfo(document, spoofedStructure)
            assertRejectedBeforeMutation(document)
        }
    }

    @Test fun excessivePrivateDepthRejectsBeforeMutation() {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val root = COSDictionary()
            var next = root
            repeat(140) {
                val child = COSDictionary()
                next.setItem(COSName.getPDFName("Next"), child)
                next = child
            }
            pieceInfo(document, root)
            assertRejectedBeforeMutation(document)
        }
    }

    @Test fun excessivePrivateDescendantCountRejectsBeforeMutation() {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val payload = COSArray()
            repeat(SecurityLimits.MAX_GRAPH_NODES / 1000 + 1) {
                payload.add(COSArray().apply { repeat(1000) { add(COSString("private")) } })
            }
            pieceInfo(document, payload)
            assertRejectedBeforeMutation(document)
        }
    }

    @Test fun structureObjectReferenceToLiveAnnotationRejectsConservatively() {
        PDDocument().use { document ->
            val page = PDPage().also(document::addPage)
            val annotation = PDAnnotationLink().also { page.annotations.add(it) }
            document.documentCatalog.cosObject.setItem(COSName.getPDFName("StructTreeRoot"), COSDictionary().apply {
                setName(COSName.TYPE, "StructTreeRoot")
                setItem(COSName.K, COSDictionary().apply {
                    setName(COSName.TYPE, "OBJR")
                    setItem(COSName.getPDFName("Pg"), page)
                    setItem(COSName.getPDFName("Obj"), annotation)
                })
            })
            assertRejectedBeforeMutation(document)
            assertSame(annotation.cosObject, page.annotations.single().cosObject)
        }
    }
}
