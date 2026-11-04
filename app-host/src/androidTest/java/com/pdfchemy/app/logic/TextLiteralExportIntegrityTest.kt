package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.text.Html
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real isolated converter -> host publication -> independently reopened exported HTML. */
@RunWith(AndroidJUnit4::class)
class TextLiteralExportIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private suspend fun export(text: String, throughMarkdown: Boolean) {
        val file = File.createTempFile("literal-export-", ".html", context.cacheDir)
        val uri = Uri.fromFile(file); OutputPolicy.registerFreshCopy(uri)
        try {
            TextOutputPublisher.publish(context, uri) {
                if (throughMarkdown) {
                    val markdown = TextFormatConverter.convert(context, text, TextFormatConverter.Format.TXT, TextFormatConverter.Format.MD).getOrThrow()
                    TextFormatConverter.convert(context, markdown, TextFormatConverter.Format.MD, TextFormatConverter.Format.HTML).getOrThrow()
                } else TextFormatConverter.convert(context, text, TextFormatConverter.Format.TXT, TextFormatConverter.Format.HTML).getOrThrow()
            }
            val saved = file.readText(Charsets.UTF_8)
            assertFalse(saved.contains("<script>")); assertFalse(saved.contains("<keep>"))
            assertEquals(text, Html.fromHtml(saved, Html.FROM_HTML_MODE_LEGACY).toString().trim())
            assertFalse(OutputPolicy.isFreshCopy(uri))
            assertFalse(OutputPolicy.deleteIfFresh(context, uri))
            assertTrue(file.exists())
        } finally {
            if (OutputPolicy.isFreshCopy(uri)) OutputPolicy.unregisterFreshCopy(uri)
            file.delete()
        }
    }
    @Test fun plainTextHtmlExportPreservesLiteralTagsEntitiesAndUnicode() = runBlocking {
        export("Public <script>window.fixture=1</script> &amp; <keep>Ω</keep>", throughMarkdown = false)
    }
    @Test fun plainTextMarkdownExportRendersAsLiteralContent() = runBlocking {
        export("Public <keep>value</keep> &copy; **literal** [link](https://example.invalid) Ω", throughMarkdown = true)
    }
    @Test fun xmlAndJsonConvertOnTheActualAndroidWorker() = runBlocking {
        val json = TextFormatConverter.convert(context, "<document><value>PUBLIC</value></document>", TextFormatConverter.Format.XML, TextFormatConverter.Format.JSON).getOrThrow()
        assertEquals("PUBLIC", org.json.JSONObject(json).getString("value"))
        val xml = TextFormatConverter.convert(context, "{\"value\":\"PUBLIC\"}", TextFormatConverter.Format.JSON, TextFormatConverter.Format.XML).getOrThrow()
        val parser = android.util.Xml.newPullParser().apply { setInput(java.io.StringReader(xml)) }
        var found: String? = null
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "value") found = parser.nextText()
        }
        assertEquals("PUBLIC", found)
    }
    @Test fun workerRejectsInternalDocumentTypeEntities() = runBlocking {
        val xml = "<!DOCTYPE root [<!ENTITY fixture 'PRIVATE_ENTITY_MARKER'>]><root>&fixture;</root>"
        val result = TextFormatConverter.convert(context, xml, TextFormatConverter.Format.XML, TextFormatConverter.Format.JSON)
        assertTrue("The parser must reject the entity, rather than lose its worker", result.exceptionOrNull() is PdfGateway.WorkerFailure)
    }
    @Test fun workerRejectsExternalFileEntitiesAndPreservesPrivateFile() = runBlocking {
        val file = File.createTempFile("xml-private-", ".txt", context.cacheDir)
        val secret = "PRIVATE_ENTITY_PAYLOAD"
        file.writeText(secret)
        try {
            val xml = "<!DOCTYPE root [<!ENTITY fixture SYSTEM '${file.toURI()}'>]><root>&fixture;</root>"
            val result = TextFormatConverter.convert(context, xml, TextFormatConverter.Format.XML, TextFormatConverter.Format.JSON)
            assertTrue("The parser must reject the entity, rather than lose its worker", result.exceptionOrNull() is PdfGateway.WorkerFailure)
            assertEquals(secret, file.readText())
        } finally { file.delete() }
    }
    @Test fun allStructuredFormatsPreserveLiteralMultilineContent() = runBlocking {
        val literal = "PUBLIC & <literal> \"quoted\"\nUnicode Ω"
        for (format in listOf(TextFormatConverter.Format.JSON, TextFormatConverter.Format.YAML, TextFormatConverter.Format.XML, TextFormatConverter.Format.CSV, TextFormatConverter.Format.TSV)) {
            val encoded = TextFormatConverter.convert(context, literal, TextFormatConverter.Format.TXT, format).getOrThrow()
            val decoded = TextFormatConverter.convert(context, encoded, format, TextFormatConverter.Format.JSON).getOrThrow()
            val value = if (format == TextFormatConverter.Format.CSV || format == TextFormatConverter.Format.TSV)
                org.json.JSONArray(decoded).getJSONObject(0) else org.json.JSONObject(decoded)
            assertEquals(format.name, literal, value.getString("content"))
        }
    }
}
