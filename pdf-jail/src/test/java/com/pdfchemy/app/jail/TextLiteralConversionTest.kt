package com.pdfchemy.app.jail

import com.pdfchemy.app.jail.engines.TextFormatConverter
import com.pdfchemy.app.jail.engines.TextFormatConverter.Format
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class TextLiteralConversionTest {
    private suspend fun convert(text: String, from: Format, to: Format) = TextFormatConverter.convert(text, from, to).getOrThrow()

    @Test fun textExportHasLiteralScriptTextAndNoExecutableElement() = runBlocking<Unit> {
        val text = "literal <script>window.fixture=1</script> & <keep>value</keep>"
        val html = convert(text, Format.TXT, Format.HTML)
        val document = Jsoup.parse(html)
        assertTrue(document.select("script, keep").isEmpty())
        assertEquals(text, document.body().text())
        assertTrue(html.contains("&lt;script&gt;"))
    }
    @Test fun markdownExportRetainsLiteralAngleBracketsWhenRendered() = runBlocking<Unit> {
        val text = "alpha <keep>beta</keep> omega"
        val markdown = convert(text, Format.TXT, Format.MD)
        val document = Jsoup.parse(convert(markdown, Format.MD, Format.HTML))
        assertTrue(document.select("keep").isEmpty())
        assertEquals(text, document.body().text())
    }
    @Test fun literalEntitiesQuotesAndUnicodeSurviveHtmlRoundTrip() = runBlocking<Unit> {
        val text = "A &amp; B <x> \"quoted\" 'single' Ω 漢字"
        assertEquals(text, Jsoup.parse(convert(text, Format.TXT, Format.HTML)).body().text())
    }
    @Test fun literalEntitiesAndMarkdownSyntaxSurviveMarkdownRoundTrip() = runBlocking<Unit> {
        val text = "literal &copy; <x> [link](https://example.invalid) **bold** `code` Ω"
        val markdown = convert(text, Format.TXT, Format.MD)
        val document = Jsoup.parse(convert(markdown, Format.MD, Format.HTML))
        assertEquals(text, document.body().text())
        assertTrue(document.select("a, strong, code, x").isEmpty())
    }
    @Test fun explicitHtmlInputRetainsItsExistingContract() = runBlocking<Unit> {
        val html = "<p><strong>Styled</strong></p>"
        assertEquals(html, convert(html, Format.HTML, Format.HTML))
        assertTrue(convert(html, Format.HTML, Format.MD).contains("**Styled**"))
    }
    @Test fun lineBreaksRemainLineBreaksInHtml() = runBlocking<Unit> {
        val document = Jsoup.parse(convert("first\nsecond", Format.TXT, Format.HTML))
        assertEquals(1, document.select("br").size)
        assertEquals("first second", document.body().text())
    }
}
