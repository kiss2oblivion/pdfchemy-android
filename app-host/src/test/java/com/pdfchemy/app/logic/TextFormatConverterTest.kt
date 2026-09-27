package com.pdfchemy.app.logic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFormatConverterTest {

    @Test
    fun convert_JsonToCsv_worksCorrectly() = runTest {
        val jsonInput = """
            [
              { "name": "Alice", "age": 30 },
              { "name": "Bob", "age": 25 }
            ]
        """.trimIndent()

        val result = TextFormatConverter.convert(jsonInput, TextFormatConverter.Format.JSON, TextFormatConverter.Format.CSV)
        assertTrue(result.isSuccess)
        val csvOutput = result.getOrThrow().trim()
        
        // CsvMapper output has headers and comma separated values
        assertTrue(csvOutput.contains("name,age"))
        assertTrue(csvOutput.contains("Alice,30"))
        assertTrue(csvOutput.contains("Bob,25"))
    }

    @Test
    fun convert_HtmlToMarkdown_worksCorrectly() = runTest {
        val htmlInput = "<h1>Title</h1><p>Some <b>bold</b> text.</p>"

        val result = TextFormatConverter.convert(htmlInput, TextFormatConverter.Format.HTML, TextFormatConverter.Format.MD)
        assertTrue(result.isSuccess)
        val mdOutput = result.getOrThrow().trim()

        assertTrue(mdOutput.contains("Title"))
        assertTrue(mdOutput.contains("bold"))
    }

    @Test
    fun convert_CsvToMarkdownTable_worksCorrectly() = runTest {
        val csvInput = """
            id,value
            1,apple
            2,banana
        """.trimIndent()

        val result = TextFormatConverter.convert(csvInput, TextFormatConverter.Format.CSV, TextFormatConverter.Format.MD)
        assertTrue(result.isSuccess)
        val mdOutput = result.getOrThrow().trim()

        // It should generate a markdown table
        assertTrue(mdOutput.contains("| id | value |"))
        assertTrue(mdOutput.contains("|---|---|"))
        assertTrue(mdOutput.contains("| 1 | apple |"))
    }

    @Test
    fun convert_TextToJson_wrapsInContentKey() = runTest {
        val textInput = "Hello world"
        
        val result = TextFormatConverter.convert(textInput, TextFormatConverter.Format.TXT, TextFormatConverter.Format.JSON)
        assertTrue(result.isSuccess)
        val jsonOutput = result.getOrThrow()

        assertTrue(jsonOutput.contains("\"content\""))
        assertTrue(jsonOutput.contains("\"Hello world\""))
    }
}
