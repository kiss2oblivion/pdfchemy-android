package com.pdfchemy.app.logic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

object TextFormatConverter {

    private val jsonMapper = ObjectMapper()
    private val csvMapper = CsvMapper()
    private val yamlMapper = YAMLMapper()
    private val xmlMapper = XmlMapper()

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
            Format.TXT -> "<p>" + input.replace("\n", "<br>") + "</p>"
            else -> input
        }

        return when (to) {
            Format.HTML -> html
            Format.MD -> htmlToMdConverter.convert(html)
            Format.TXT -> Jsoup.parse(html).text()
            else -> input
        }
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
        val map = mapOf("content" to input)
        return when (to) {
            Format.JSON -> jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map)
            Format.YAML -> yamlMapper.writeValueAsString(map)
            Format.XML -> xmlMapper.writer().withRootName("document").writeValueAsString(map)
            Format.CSV -> {
                val schema = com.fasterxml.jackson.dataformat.csv.CsvSchema.builder().addColumn("content").build().withHeader().withColumnSeparator(',')
                csvMapper.writer(schema).writeValueAsString(listOf(map))
            }
            Format.TSV -> {
                val schema = com.fasterxml.jackson.dataformat.csv.CsvSchema.builder().addColumn("content").build().withHeader().withColumnSeparator('\t')
                csvMapper.writer(schema).writeValueAsString(listOf(map))
            }
            else -> throw IllegalArgumentException("Not a structured format: $to")
        }
    }

    private fun parseToTree(input: String, format: Format): JsonNode {
        return when (format) {
            Format.JSON -> jsonMapper.readTree(input)
            Format.YAML -> yamlMapper.readTree(input)
            Format.XML -> xmlMapper.readTree(input)
            Format.CSV -> {
                val schema = com.fasterxml.jackson.dataformat.csv.CsvSchema.emptySchema().withHeader().withColumnSeparator(',')
                val list = csvMapper.readerFor(Map::class.java).with(schema).readValues<Map<String, String>>(input).readAll()
                jsonMapper.valueToTree(list)
            }
            Format.TSV -> {
                val schema = com.fasterxml.jackson.dataformat.csv.CsvSchema.emptySchema().withHeader().withColumnSeparator('\t')
                val list = csvMapper.readerFor(Map::class.java).with(schema).readValues<Map<String, String>>(input).readAll()
                jsonMapper.valueToTree(list)
            }
            else -> throw IllegalArgumentException("Not a structured format: $format")
        }
    }

    private fun writeFromTree(tree: JsonNode, format: Format): String {
        return when (format) {
            Format.JSON -> jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree)
            Format.YAML -> yamlMapper.writeValueAsString(tree)
            Format.XML -> xmlMapper.writeValueAsString(tree)
            Format.CSV -> {
                if (!tree.isArray || tree.isEmpty) throw IllegalArgumentException("Source must be a non-empty array of objects for CSV conversion.")
                val firstObject = tree.get(0)
                val schemaBuilder = com.fasterxml.jackson.dataformat.csv.CsvSchema.builder()
                firstObject.fieldNames().forEach { schemaBuilder.addColumn(it) }
                val schema = schemaBuilder.build().withHeader().withColumnSeparator(',')
                val list = jsonMapper.convertValue(tree, List::class.java)
                csvMapper.writer(schema).writeValueAsString(list)
            }
            Format.TSV -> {
                if (!tree.isArray || tree.isEmpty) throw IllegalArgumentException("Source must be a non-empty array of objects for TSV conversion.")
                val firstObject = tree.get(0)
                val schemaBuilder = com.fasterxml.jackson.dataformat.csv.CsvSchema.builder()
                firstObject.fieldNames().forEach { schemaBuilder.addColumn(it) }
                val schema = schemaBuilder.build().withHeader().withColumnSeparator('\t')
                val list = jsonMapper.convertValue(tree, List::class.java)
                csvMapper.writer(schema).writeValueAsString(list)
            }
            else -> throw IllegalArgumentException("Not a structured format: $format")
        }
    }
    
    private fun jsonToMarkdownTable(tree: JsonNode): String {
        if (!tree.isArray || tree.isEmpty) return ""
        val firstObject = tree.get(0)
        val headers = firstObject.fieldNames().asSequence().toList()
        
        val sb = java.lang.StringBuilder()
        sb.append(headers.joinToString(" | ", "| ", " |")).append("\n")
        sb.append(headers.joinToString("|", "|", "|") { "---" }).append("\n")
        
        for (node in tree) {
            val row = headers.map { header -> node.get(header)?.asText() ?: "" }
            sb.append(row.joinToString(" | ", "| ", " |")).append("\n")
        }
        return sb.toString()
    }
}
