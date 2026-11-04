package com.pdfchemy.app.jail.engines

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

object TextFormatConverter {

    // Markdown tools
    private val mdOptions = MutableDataSet()
    private val mdParser = Parser.builder(mdOptions).build()
    private val mdHtmlRenderer = HtmlRenderer.builder(mdOptions).build()
    private val htmlToMdConverter = FlexmarkHtmlConverter.builder(mdOptions).build()

    enum class Format {
        TXT, MD, HTML, CSV, TSV, JSON, YAML, XML
    }

    suspend fun convert(inputContent: String, from: Format, to: Format): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (from == to) {
                return@withContext Result.success(inputContent)
            }

            val isFromStructured = from in listOf(Format.JSON, Format.YAML, Format.XML, Format.CSV, Format.TSV)
            val isToStructured = to in listOf(Format.JSON, Format.YAML, Format.XML, Format.CSV, Format.TSV)

            val result = when {
                isFromStructured && isToStructured -> convertStructuredToStructured(inputContent, from, to)
                !isFromStructured && !isToStructured -> convertUnstructuredToUnstructured(inputContent, from, to)
                isFromStructured && !isToStructured -> convertStructuredToUnstructured(inputContent, from, to)
                !isFromStructured && isToStructured -> convertUnstructuredToStructured(inputContent, from, to)
                else -> throw IllegalArgumentException("Conversion from $from to $to is not supported.")
            }
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun convertStructuredToStructured(input: String, from: Format, to: Format): String {
        val tree = parseToTree(input, from)
        return writeFromTree(tree, to)
    }

    private fun convertUnstructuredToUnstructured(input: String, from: Format, to: Format): String {
        val html = when (from) {
            Format.HTML -> input
            Format.MD -> mdHtmlRenderer.render(mdParser.parse(input))
            Format.TXT -> "<p>" + escapePlainText(input).replace("\n", "<br>") + "</p>"
            else -> input
        }

        return when (to) {
            Format.HTML -> html
            Format.MD -> htmlToMdConverter.convert(html)
            Format.TXT -> Jsoup.parse(html).text()
            else -> input
        }
    }

    /** TXT is literal content, even when it happens to contain HTML syntax or entities. */
    private fun escapePlainText(input: String): String = buildString {
        for (character in input) append(when (character) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> character.toString()
        })
    }

    private fun convertStructuredToUnstructured(input: String, from: Format, to: Format): String {
        if (to == Format.TXT) {
            return if (from == Format.JSON || from == Format.YAML || from == Format.XML) {
                input // already readable enough
            } else {
                 val tree = parseToTree(input, from)
                 writeFromTree(tree, Format.JSON)
            }
        }
        
        if (to == Format.MD) {
            if (from == Format.CSV || from == Format.TSV) {
                val tree = parseToTree(input, from)
                return jsonToMarkdownTable(tree)
            }
            return "```${from.name.lowercase()}\n$input\n```"
        }
        
        if (to == Format.HTML) {
            return "<pre><code class=\"language-${from.name.lowercase()}\">\n${input.replace("<", "&lt;").replace(">", "&gt;")}\n</code></pre>"
        }
        return input
    }

    private fun convertUnstructuredToStructured(input: String, from: Format, to: Format): String {
        val value = JsonObject().apply { addProperty("content", input) }
        val tree = if (to == Format.CSV || to == Format.TSV) JsonArray().apply { add(value) } else value
        return StructuredTextCodec.write(tree, to, root = "document")
    }

    private fun parseToTree(input: String, format: Format): JsonElement = StructuredTextCodec.parse(input, format)

    private fun writeFromTree(tree: JsonElement, format: Format): String = StructuredTextCodec.write(tree, format)

    private fun jsonToMarkdownTable(tree: JsonElement): String {
        if (!tree.isJsonArray || tree.asJsonArray.size() == 0) return ""
        val firstObject = tree.asJsonArray[0].asJsonObject
        val headers = firstObject.keySet().toList()
        
        val sb = java.lang.StringBuilder()
        sb.append(headers.joinToString(" | ", "| ", " |")).append("\n")
        sb.append(headers.joinToString("|", "|", "|") { "---" }).append("\n")
        
        for (node in tree.asJsonArray) {
            val row = headers.map { header -> node.asJsonObject.get(header)?.takeIf { it.isJsonPrimitive }?.asString ?: "" }
            sb.append(row.joinToString(" | ", "| ", " |")).append("\n")
        }
        return sb.toString()
    }
}
