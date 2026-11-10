package com.pdfchemy.app.jail.engines

import com.ctc.wstx.stax.WstxInputFactory
import com.ctc.wstx.stax.WstxOutputFactory
import com.ctc.wstx.api.WstxOutputProperties
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.pdfchemy.app.security.SecurityLimits
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.composer.Composer
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.emitter.Emitter
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader
import org.yaml.snakeyaml.resolver.Resolver
import org.yaml.snakeyaml.serializer.Serializer
import java.io.StringReader
import java.io.Writer
import java.math.BigDecimal
import java.math.BigInteger
import java.util.IdentityHashMap
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLResolver
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader
import javax.xml.stream.XMLStreamWriter

/** Generic text values only. Jackson databind requires API26; this worker supports API24. */
internal object StructuredTextCodec {
    private const val MAX_DEPTH = 64
    private const val MAX_NODES = SecurityLimits.MAX_REQUEST_NODES
    private val json = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
    private class Budget {
        var nodes = 0
        fun take(depth: Int) { require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "Structured text exceeds resource limits" } }
    }
    private class BoundedWriter : Writer() {
        private val value = StringBuilder()
        override fun write(chars: CharArray, offset: Int, length: Int) {
            require(value.length <= SecurityLimits.MAX_TEXT_BYTES - length) { "Converted text exceeds size limit" }
            value.append(chars, offset, length)
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString() = value.toString()
    }

    fun parse(input: String, format: TextFormatConverter.Format): JsonElement {
        SecurityLimits.enforceStringLength(input)
        return when (format) {
            TextFormatConverter.Format.JSON -> JsonReader(StringReader(input)).use { reader ->
                reader.isLenient = false
                readJson(reader, Budget(), 0).also { require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON content" } }
            }
            TextFormatConverter.Format.YAML -> fromValue(readYaml(input), Budget(), 0, IdentityHashMap())
            TextFormatConverter.Format.XML -> readXml(input)
            TextFormatConverter.Format.CSV -> readCsv(input, ',')
            TextFormatConverter.Format.TSV -> readCsv(input, '\t')
            else -> error("Not a structured format: $format")
        }
    }

    fun write(tree: JsonElement, format: TextFormatConverter.Format, root: String? = null): String {
        checkTree(tree, Budget(), 0)
        val output = when (format) {
            TextFormatConverter.Format.JSON -> BoundedWriter().also { json.toJson(tree, it) }.toString()
            TextFormatConverter.Format.YAML -> writeYaml(tree)
            TextFormatConverter.Format.XML -> writeXml(tree, root ?: if (tree.isJsonArray) "ArrayNode" else "ObjectNode")
            TextFormatConverter.Format.CSV -> writeCsv(tree, ',')
            TextFormatConverter.Format.TSV -> writeCsv(tree, '\t')
            else -> error("Not a structured format: $format")
        }
        SecurityLimits.enforceStringLength(output)
        return output
    }

    private fun checkTree(node: JsonElement, budget: Budget, depth: Int) {
        budget.take(depth)
        if (node.isJsonObject) node.asJsonObject.entrySet().forEach { checkTree(it.value, budget, depth + 1) }
        else if (node.isJsonArray) node.asJsonArray.forEach { checkTree(it, budget, depth + 1) }
    }

    private fun readJson(reader: JsonReader, budget: Budget, depth: Int): JsonElement {
        budget.take(depth)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) { val key = reader.nextName(); add(key, readJson(reader, budget, depth + 1)) }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray(); while (reader.hasNext()) add(readJson(reader, budget, depth + 1)); reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            JsonToken.NUMBER -> {
                val number = reader.nextString(); require(number.length <= 1000) { "JSON number exceeds size limit" }
                JsonPrimitive(if (number.any { it == '.' || it == 'e' || it == 'E' }) BigDecimal(number) else BigInteger(number))
            }
            else -> error("Invalid JSON value")
        }
    }

    private fun readYaml(input: String): Any? {
        val loader = LoaderOptions().apply {
            nestingDepthLimit = MAX_DEPTH; maxAliasesForCollections = 50
            codePointLimit = SecurityLimits.MAX_TEXT_BYTES; isAllowDuplicateKeys = false
        }
        // Jackson's YAML tree preserves date-like scalars as strings, rather than Java Date objects.
        val resolver = object : Resolver() {
            override fun resolve(kind: NodeId, value: String?, implicit: Boolean): Tag {
                val tag = super.resolve(kind, value, implicit)
                return if (tag == Tag.TIMESTAMP) Tag.STR else tag
            }
        }
        return SafeConstructor(loader).apply {
            setComposer(Composer(ParserImpl(StreamReader(input), loader), resolver, loader))
        }.getSingleData(Any::class.java)
    }

    /** Emit only JSON-compatible values; the JavaBean Representer is unavailable on Android. */
    private fun writeYaml(tree: JsonElement): String {
        val output = BoundedWriter()
        val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        val serializer = Serializer(Emitter(output, options), Resolver(), options, null)
        serializer.open()
        try { serializer.serialize(yamlNode(tree)) } finally { serializer.close() }
        return output.toString()
    }

    private fun yamlNode(node: JsonElement): Node = when {
        node.isJsonObject -> MappingNode(Tag.MAP, node.asJsonObject.entrySet().map { (key, value) ->
            NodeTuple(yamlScalar(Tag.STR, key), yamlNode(value))
        }, DumperOptions.FlowStyle.BLOCK)
        node.isJsonArray -> SequenceNode(Tag.SEQ, node.asJsonArray.map { yamlNode(it) }, DumperOptions.FlowStyle.BLOCK)
        node.isJsonNull -> yamlScalar(Tag.NULL, "null")
        node.asJsonPrimitive.isBoolean -> yamlScalar(Tag.BOOL, node.asString)
        node.asJsonPrimitive.isNumber -> yamlScalar(if (node.asString.any { it == '.' || it == 'e' || it == 'E' }) Tag.FLOAT else Tag.INT, node.asString)
        else -> yamlScalar(Tag.STR, node.asString)
    }

    private fun yamlScalar(tag: Tag, value: String) = ScalarNode(tag, value, null, null, DumperOptions.ScalarStyle.PLAIN)

    private fun fromValue(value: Any?, budget: Budget, depth: Int, active: IdentityHashMap<Any, Boolean>): JsonElement {
        budget.take(depth)
        return when (value) {
            null -> JsonNull.INSTANCE
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> {
                require(value !is Double || value.isFinite()) { "Non-finite number" }
                require(value !is Float || value.isFinite()) { "Non-finite number" }
                JsonPrimitive(value)
            }
            is Map<*, *> -> {
                require(active.put(value, true) == null) { "Recursive YAML reference" }
                try { JsonObject().apply { value.forEach { (key, item) ->
                    require(key is String) { "Object keys must be strings" }
                    add(key, fromValue(item, budget, depth + 1, active))
                } } } finally { active.remove(value) }
            }
            is List<*> -> {
                require(active.put(value, true) == null) { "Recursive YAML reference" }
                try { JsonArray().apply { value.forEach { add(fromValue(it, budget, depth + 1, active)) } } }
                finally { active.remove(value) }
            }
            else -> error("Unsupported YAML value")
        }
    }

    private fun readXml(input: String): JsonElement {
        val factory = WstxInputFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            xmlResolver = XMLResolver { _, _, _, _ -> throw XMLStreamException("External XML references are not supported") }
        }
        val reader = factory.createXMLStreamReader(StringReader(input))
        try {
            var result: JsonElement? = null
            val budget = Budget()
            while (reader.hasNext()) when (reader.next()) {
                XMLStreamConstants.DTD -> error("Document types are not supported")
                XMLStreamConstants.START_ELEMENT -> {
                    require(result == null) { "Multiple XML roots" }
                    result = xmlElement(reader, budget, 0)
                }
            }
            return requireNotNull(result) { "Empty XML document" }
        } finally { reader.close() }
    }

    private fun xmlElement(reader: XMLStreamReader, budget: Budget, depth: Int): JsonElement {
        budget.take(depth)
        val fields = JsonObject(); val text = StringBuilder()
        for (i in 0 until reader.attributeCount) {
            budget.take(depth + 1); fields.addProperty(reader.getAttributeLocalName(i), reader.getAttributeValue(i))
        }
        val repeated = mutableSetOf<String>()
        while (reader.hasNext()) when (reader.next()) {
            XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> error("XML entities are not supported")
            XMLStreamConstants.START_ELEMENT -> {
                val name = reader.localName; val child = xmlElement(reader, budget, depth + 1)
                if (!fields.has(name)) fields.add(name, child)
                else if (name in repeated) fields.getAsJsonArray(name).add(child)
                else { fields.add(name, JsonArray().apply { add(fields.get(name)); add(child) }); repeated.add(name) }
            }
            XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                require(text.length <= SecurityLimits.MAX_TEXT_BYTES - reader.textLength) { "XML text exceeds size limit" }
                text.append(reader.text)
            }
            XMLStreamConstants.END_ELEMENT -> return if (fields.size() == 0) JsonPrimitive(text.toString())
                else fields.apply { if (text.isNotBlank()) addProperty("", text.toString()) }
        }
        error("Unterminated XML element")
    }

    private fun writeXml(tree: JsonElement, root: String): String {
        val output = BoundedWriter()
        val factory = WstxOutputFactory().apply {
            setProperty(WstxOutputProperties.P_OUTPUT_VALIDATE_NAMES, true)
            setProperty(WstxOutputProperties.P_OUTPUT_VALIDATE_STRUCTURE, true)
            setProperty(WstxOutputProperties.P_OUTPUT_ESCAPE_CR, true)
        }
        val writer = factory.createXMLStreamWriter(output)
        try {
            if (tree.isJsonArray) {
                writer.writeStartElement(root)
                tree.asJsonArray.forEach { xmlValue(writer, "item", it) }
                writer.writeEndElement()
            } else xmlValue(writer, root, tree)
            writer.flush()
        } finally { writer.close() }
        return output.toString()
    }

    private fun xmlValue(writer: XMLStreamWriter, name: String, node: JsonElement) {
        require(name.isNotEmpty() && ':' !in name) { "XML field names need an unqualified name" }
        if (node.isJsonArray) {
            for (item in node.asJsonArray) xmlValue(writer, name, item)
            return
        }
        writer.writeStartElement(name)
        if (node.isJsonObject) node.asJsonObject.entrySet().forEach { (key, value) ->
            if (key.isEmpty()) { require(value.isJsonPrimitive); writer.writeCharacters(value.asString) }
            else xmlValue(writer, key, value)
        }
        else if (!node.isJsonNull) writer.writeCharacters(node.asString)
        writer.writeEndElement()
    }

    private fun readCsv(input: String, separator: Char): JsonElement {
        val records = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var closed = false; var index = 0; var cells = 0
        fun finishCell() {
            require(++cells <= MAX_NODES && row.size < 128) { "CSV exceeds cell limits" }
            row.add(cell.toString()); cell.setLength(0); closed = false
        }
        fun finishRow() { finishCell(); require(records.size < 5001) { "CSV exceeds row limits" }; records.add(row); row = mutableListOf() }
        while (index < input.length) {
            val c = input[index++]
            if (quoted) {
                if (c == '"') {
                    if (index < input.length && input[index] == '"') { cell.append('"'); index++ }
                    else { quoted = false; closed = true }
                } else cell.append(c)
            } else when (c) {
                '"' -> { require(cell.isEmpty() && !closed) { "Malformed CSV quotation" }; quoted = true }
                separator -> finishCell()
                '\r', '\n' -> { if (c == '\r' && index < input.length && input[index] == '\n') index++; finishRow() }
                else -> { require(!closed) { "Characters after CSV closing quote" }; cell.append(c) }
            }
        }
        require(!quoted) { "Unterminated CSV quotation" }
        if (cell.isNotEmpty() || row.isNotEmpty() || closed) finishRow()
        if (records.isEmpty()) return JsonArray()
        val headers = records.first(); require(headers.distinct().size == headers.size) { "Duplicate CSV headers" }
        return JsonArray().apply { for (values in records.drop(1)) {
            require(values.size <= headers.size) { "CSV row has extra columns" }
            add(JsonObject().apply { values.forEachIndexed { i, value -> addProperty(headers[i], value) } })
        } }
    }

    private fun writeCsv(tree: JsonElement, separator: Char): String {
        require(tree.isJsonArray && tree.asJsonArray.size() > 0 && tree.asJsonArray[0].isJsonObject) { "Source must be a non-empty array of objects for CSV conversion." }
        val headers = tree.asJsonArray[0].asJsonObject.keySet().toList()
        require(headers.size in 1..128 && tree.asJsonArray.size() <= 5000) { "CSV exceeds row or column limits" }
        fun sanitize(value: String): String {
            if (value.isEmpty()) return value
            if (value.toDoubleOrNull() != null) return value
            val trimmed = value.trimStart(' ', '\t', '\r', '\n')
            val first = value[0]
            val isTrigger = first == '=' || first == '+' || first == '-' || first == '@' ||
                    first == '\t' || first == '\r' || first == '\u0000' ||
                    (trimmed.isNotEmpty() && (trimmed[0] == '=' || trimmed[0] == '+' || trimmed[0] == '-' || trimmed[0] == '@'))
            return if (isTrigger) "'$value" else value
        }
        fun quote(value: String): String {
            val sanitized = sanitize(value)
            return if (sanitized.any { it == separator || it == '"' || it == '\r' || it == '\n' }) "\"${sanitized.replace("\"", "\"\"")}\"" else sanitized
        }
        return BoundedWriter().apply {
            append(headers.joinToString(separator.toString()) { quote(it) }).append('\n')
            for (row in tree.asJsonArray) {
                require(row.isJsonObject && row.asJsonObject.keySet().all { it in headers }) { "CSV rows must use the same columns" }
                append(headers.joinToString(separator.toString()) { key ->
                    val value = row.asJsonObject.get(key)
                    require(value == null || value.isJsonNull || value.isJsonPrimitive) { "CSV cells must be scalar values" }
                    quote(if (value == null || value.isJsonNull) "" else value.asString)
                }).append('\n')
            }
        }.toString()
    }
}
