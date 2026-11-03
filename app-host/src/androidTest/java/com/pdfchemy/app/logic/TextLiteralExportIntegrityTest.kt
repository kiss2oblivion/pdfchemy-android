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
}
