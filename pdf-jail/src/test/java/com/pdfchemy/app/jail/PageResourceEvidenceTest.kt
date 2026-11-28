package com.pdfchemy.app.jail

import com.pdfchemy.app.jail.engines.PageContentEvidence
import com.pdfchemy.app.jail.engines.PageResourceEvidence
import com.pdfchemy.app.logic.PageStreamEvidence
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import org.junit.Assert.*
import org.junit.Test

class PageResourceEvidenceTest {
    private fun name(s: String) = COSName.getPDFName(s)
    private fun resources(page: PDPage, dictionary: COSDictionary) {
        page.cosObject.setItem(COSName.RESOURCES, dictionary)
    }
    private fun compare(doc: PDDocument, before: List<ByteArray?>) =
        PageContentEvidence.compare(before, PageResourceEvidence.capture(doc))

    @Test fun dictionaryOrderAndIndirectIdentityDoNotAffectFingerprint() {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            val first = COSDictionary().apply {
                setName(name("A"), "One")
                setInt(name("B"), 3)
            }
            resources(page, first)
            val before = PageResourceEvidence.capture(doc)
            resources(page, COSDictionary().apply {
                setItem(name("B"), COSObject(COSInteger.get(3L)))
                setName(name("A"), "One")
            })
            assertEquals(PageStreamEvidence(1, 0, 0), compare(doc, before))
        }
    }

    @Test fun fontAndNestedImageChangesAreDetected() {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            val font = COSDictionary().apply { setName(name("BaseFont"), "Helvetica") }
            val image = PDStream(doc).cosObject
            image.setName(COSName.SUBTYPE, "Image")
            image.createRawOutputStream().use { it.write(byteArrayOf(1, 2, 3)) }
            val form = PDStream(doc).cosObject
            form.setName(COSName.SUBTYPE, "Form")
            form.createRawOutputStream().use { it.write(byteArrayOf(4)) }
            form.setItem(COSName.RESOURCES, COSDictionary().apply {
                setItem(COSName.XOBJECT, COSDictionary().apply { setItem(name("Im1"), image) })
            })
            resources(page, COSDictionary().apply {
                setItem(COSName.FONT, COSDictionary().apply { setItem(name("F1"), font) })
                setItem(COSName.XOBJECT, COSDictionary().apply { setItem(name("Fm1"), form) })
            })
            val first = PageResourceEvidence.capture(doc)
            assertNotNull(first.single())
            font.setName(name("BaseFont"), "Courier")
            assertEquals(PageStreamEvidence(0, 1, 0), compare(doc, first))
            val second = PageResourceEvidence.capture(doc)
            image.createRawOutputStream().use { it.write(byteArrayOf(1, 2, 4)) }
            assertEquals(PageStreamEvidence(0, 1, 0), compare(doc, second))
        }
    }

    @Test fun inheritedSharedResourcesAreComparedPerPage() {
        PDDocument().use { doc ->
            repeat(2) { doc.addPage(PDPage()) }
            val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.PAGES) as COSDictionary
            val shared = COSDictionary().apply { setName(name("Marker"), "Before") }
            root.setItem(COSName.RESOURCES, shared)
            val before = PageResourceEvidence.capture(doc)
            shared.setName(name("Marker"), "After")
            assertEquals(PageStreamEvidence(0, 2, 0), compare(doc, before))
        }
    }

    @Test fun cyclesAndExhaustedBudgetRemainUnmeasured() {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            val cyclic = COSDictionary()
            cyclic.setItem(name("Cycle"), cyclic)
            resources(page, cyclic)
            assertNull(PageResourceEvidence.capture(doc).single())
            resources(page, COSDictionary().apply { setString(name("Data"), "too large") })
            assertNull(PageResourceEvidence.capture(doc, byteBudget = 1L).single())
        }
    }
}
