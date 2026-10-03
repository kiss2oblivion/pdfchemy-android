package com.pdfchemy.app.jail

import com.pdfchemy.app.jail.engines.ActiveContentScrubber
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.*
import org.junit.Test

class ActiveContentScrubberTest {
    private fun name(value: String) = COSName.getPDFName(value)
    private fun carrier(key: String, value: COSBase) {
        PDDocument().use { document ->
            document.documentCatalog.cosObject.setItem(name(key), value)
            assertTrue(ActiveContentScrubber.inspect(document).total > 0)
            ActiveContentScrubber.inspect(document, scrub = true)
            assertEquals(0, ActiveContentScrubber.inspect(document).total)
            val bytes = java.io.ByteArrayOutputStream()
            document.save(bytes)
            PDDocument.load(bytes.toByteArray()).use { assertEquals(0, ActiveContentScrubber.inspect(it).total) }
        }
    }
    @Test fun bookmarkActionsCannotSurvive() {
        for (action in listOf("URI", "Launch", "JavaScript", "GoToR", "GoToE", "Rendition", "SubmitForm", "ImportData")) {
            val outline = COSDictionary()
            val bookmark = COSDictionary()
            val dictionary = COSDictionary().apply { setName(COSName.S, action) }
            bookmark.setItem(COSName.A, dictionary)
            outline.setItem(name("First"), bookmark)
            carrier("Outlines", outline)
        }
    }
    @Test fun associatedFilesAreScrubbedAtEveryDictionaryDepth() {
        val nested = COSDictionary().apply { setItem(name("AF"), COSArray().apply { add(COSDictionary().apply { setItem(name("EF"), COSDictionary()) }) }) }
        carrier("CustomObject", nested)
    }
    @Test fun attachmentAnnotationsAndFileSpecificationsAreScrubbed() {
        carrier("Annots", COSArray().apply { add(COSDictionary().apply { setName(COSName.SUBTYPE, "FileAttachment"); setItem(name("FS"), COSDictionary()) }) })
    }
    @Test fun xfaAndRichMediaAreScrubbed() {
        carrier("AcroForm", COSDictionary().apply { setItem(name("XFA"), COSString("hostile XML")) })
        carrier("Annots", COSArray().apply { add(COSDictionary().apply { setName(COSName.SUBTYPE, "RichMedia") }) })
    }
    @Test fun ordinaryWebLinksAreCountedWithoutBeingExecutableTriggers() {
        PDDocument().use { document ->
            val action = COSDictionary().apply { setName(COSName.S, "URI"); setString(name("URI"), "https://example.com") }
            document.documentCatalog.cosObject.setItem(name("Annots"), COSArray().apply { add(COSDictionary().apply { setItem(COSName.A, action) }) })
            val page = com.tom_roush.pdfbox.pdmodel.PDPage()
            document.addPage(page)
            document.documentCatalog.openAction = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination().apply { this.page = page }
            val findings = ActiveContentScrubber.inspect(document)
            assertEquals(1, findings.uris)
            assertEquals(0, findings.actions)
        }
    }
    @Test fun autoRunUriIsDangerousEvenWhenAlsoReferencedByAnOrdinaryLink() {
        PDDocument().use { document ->
            val action = COSDictionary().apply { setName(COSName.S, "URI"); setString(name("URI"), "https://example.com/beacon") }
            document.documentCatalog.cosObject.setItem(name("Annots"), COSArray().apply { add(COSDictionary().apply { setItem(COSName.A, action) }) })
            document.documentCatalog.cosObject.setItem(name("OpenAction"), action)
            assertTrue(ActiveContentScrubber.inspect(document).actions > 0)
            ActiveContentScrubber.inspect(document, scrub = true)
            assertEquals(0, ActiveContentScrubber.inspect(document).total)
        }
    }
    @Test fun traversalFailsClosedInsteadOfSilentlyIgnoringDeepCarriers() {
        PDDocument().use { document ->
            var node = document.documentCatalog.cosObject
            repeat(140) { val child = COSDictionary(); node.setItem(name("Next"), child); node = child }
            node.setName(COSName.S, "JavaScript")
            try { ActiveContentScrubber.inspect(document); fail("Traversal overflow accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test(timeout = 5000) fun resolvedJsoupArtifactHandlesAdversarialNamespaces() {
        val xml = buildString {
            repeat(10_000) { append("<x xmlns:n").append(it).append("='urn:").append(it).append("'>") }
            repeat(10_000) { append("</x>") }
        }
        val document = org.jsoup.Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
        assertNotNull(document)
        // NamespaceScope was introduced by upstream 862ba2f; this checks the resolved JAR too.
        Class.forName("org.jsoup.internal.NamespaceBindings")
    }
}
