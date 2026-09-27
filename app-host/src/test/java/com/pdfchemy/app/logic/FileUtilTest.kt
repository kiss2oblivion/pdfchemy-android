package com.pdfchemy.app.logic

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FileUtilTest {

    @Test
    fun generateSuggestedName_withNullUri_usesTimestamp() {
        val result = FileUtil.generateSuggestedName(null, "created")
        assertTrue("Should contain action", result.contains("created_"))
        assertTrue("Should contain pdf extension", result.endsWith(".pdf"))
    }

    @Test
    fun generateSuggestedName_withValidUri_appendsAction() {
        val uri = Uri.parse("content://dummy/my_document.pdf")
        val result = FileUtil.generateSuggestedName(uri, "compressed")
        assertEquals("my_document_compressed.pdf", result)
    }

    @Test
    fun generateSuggestedName_cleansPreviousActions() {
        val uri = Uri.parse("content://dummy/my_document_compressed.pdf")
        val result = FileUtil.generateSuggestedName(uri, "merged")
        
        // It should drop '_compressed' and add '_merged'
        assertEquals("my_document_merged.pdf", result)
    }

    @Test
    fun generateSuggestedName_preservesCustomExtension() {
        val uri = Uri.parse("content://dummy/report.csv")
        val result = FileUtil.generateSuggestedName(uri, "converted", extension = "md")
        assertEquals("report_converted.md", result)
    }
}
