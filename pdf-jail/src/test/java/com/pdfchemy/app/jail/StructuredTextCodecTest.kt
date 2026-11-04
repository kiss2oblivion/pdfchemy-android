package com.pdfchemy.app.jail

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.google.gson.JsonParser
import com.pdfchemy.app.jail.engines.TextFormatConverter
import com.pdfchemy.app.jail.engines.TextFormatConverter.Format
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Jackson is a JVM-only parity oracle; production conversion must also work on API24. */
class StructuredTextCodecTest {
    private suspend fun convert(input: String, from: Format, to: Format) = TextFormatConverter.convert(input, from, to).getOrThrow()
    @Test fun allStructuredFormatsRetainLiteralMultilineContent() = runBlocking<Unit> {
        val literal = "literal & <tag> \"quoted\"\r\nUnicode Ω"
        for (format in listOf(Format.JSON, Format.YAML, Format.XML, Format.CSV, Format.TSV)) {
            val output = convert(literal, Format.TXT, format)
            val decoded = JsonParser.parseString(convert(output, format, Format.JSON))
            val objectValue = if (decoded.isJsonArray) decoded.asJsonArray[0].asJsonObject else decoded.asJsonObject
            assertEquals(format.name, literal, objectValue["content"].asString)
        }
    }
    @Test fun yamlScalarAndNestedValuesMatchTheExistingJvmOracle() = runBlocking<Unit> {
        val input = "name: PUBLIC\ncount: 3\nactive: true\nmissing: null\ndate: 2026-11-04\nitems:\n  - one\n  - two\n"
        val expected = YAMLMapper().readTree(input)
        assertEquals(expected, ObjectMapper().readTree(convert(input, Format.YAML, Format.JSON)))
    }
    @Test fun xmlAttributesRepeatedChildrenAndMixedTextMatchTheExistingOracle() = runBlocking<Unit> {
        for (input in listOf("<root a='one'><x>1</x><x>2</x></root>", "<root><item>PUBLIC &amp; literal</item><empty/></root>", "<root a='one'>PUBLIC</root>")) {
            assertEquals(XmlMapper().readTree(input), ObjectMapper().readTree(convert(input, Format.XML, Format.JSON)))
        }
    }
    @Test fun generatedXmlReopensLikeTheExistingObjectAndArrayExports() = runBlocking<Unit> {
        val xml = XmlMapper(); val json = ObjectMapper()
        val input = "{\"value\":\"PUBLIC & literal\",\"count\":3}"
        val expected = xml.readTree(xml.writeValueAsString(json.readTree(input)))
        assertEquals(expected, xml.readTree(convert(input, Format.JSON, Format.XML)))
        // The old writer tries to emit multiple XML roots for an array. The saved document must be valid.
        val array = "[{\"name\":\"one\"},{\"name\":\"two\"}]"
        assertTrue(runCatching { xml.writeValueAsString(json.readTree(array)) }.isFailure)
        assertEquals(json.readTree("{\"item\":$array}"), xml.readTree(convert(array, Format.JSON, Format.XML)))
    }
    @Test fun csvAndTsvPreserveQuotedDelimitersQuotesAndNewlines() = runBlocking<Unit> {
        for ((format, delimiter) in listOf(Format.CSV to ',', Format.TSV to '\t')) {
            val input = "name${delimiter}value\r\nPUBLIC${delimiter}\"first${delimiter}quoted \"\"word\"\"\r\nsecond\"\r\n"
            val json = convert(input, format, Format.JSON)
            val value = JsonParser.parseString(json).asJsonArray[0].asJsonObject
            assertEquals("first${delimiter}quoted \"word\"\r\nsecond", value["value"].asString)
            assertEquals(JsonParser.parseString(json), JsonParser.parseString(convert(convert(json, Format.JSON, format), format, Format.JSON)))
        }
    }
    @Test fun csvBlankLinesAndMissingColumnsMatchTheExistingOracle() = runBlocking<Unit> {
        val mapper = com.fasterxml.jackson.dataformat.csv.CsvMapper()
        val schema = com.fasterxml.jackson.dataformat.csv.CsvSchema.emptySchema().withHeader()
        for (input in listOf("name,value\n\nPUBLIC,value\n", "name,value\nPUBLIC\n", "name\n\"\"\n")) {
            val rows = mapper.readerFor(Map::class.java).with(schema).readValues<Map<String, String>>(input).readAll()
            assertEquals(ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(rows), ObjectMapper().readTree(convert(input, Format.CSV, Format.JSON)))
        }
    }
    @Test fun xmlSupportsUnicodeNamesAndRejectsInvalidNames() = runBlocking<Unit> {
        val xml = convert("{\"文字\":\"PUBLIC\"}", Format.JSON, Format.XML)
        assertEquals("PUBLIC", XmlMapper().readTree(xml).get("文字").asText())
        assertTrue(TextFormatConverter.convert("{\"not a name\":\"PUBLIC\"}", Format.JSON, Format.XML).isFailure)
    }
    @Test fun malformedCsvIsRejectedInsteadOfTruncatedOrReinterpreted() = runBlocking<Unit> {
        for (input in listOf("key\n\"unterminated", "key\n\"closed\"junk", "key\nplain\"quote", "key\nextra,column")) {
            assertTrue(TextFormatConverter.convert(input, Format.CSV, Format.JSON).isFailure)
        }
    }
    @Test fun malformedJsonAndTrailingValuesAreRejected() = runBlocking<Unit> {
        for (input in listOf("{unquoted:1}", "{'quoted':1}", "[NaN]", "{} {}", "[1,]"))
            assertTrue(input, TextFormatConverter.convert(input, Format.JSON, Format.YAML).isFailure)
    }
    @Test fun yamlRoundTripKeepsIntegersDecimalsBooleansAndNull() = runBlocking<Unit> {
        val input = "{\"integer\":3,\"decimal\":3.5,\"boolean\":true,\"missing\":null}"
        val output = convert(convert(input, Format.JSON, Format.YAML), Format.YAML, Format.JSON)
        assertEquals(ObjectMapper().readTree(input), ObjectMapper().readTree(output))
    }
    @Test fun unsafeYamlObjectsAndRecursiveAliasesAreRejected() = runBlocking<Unit> {
        for (input in listOf("!!java.lang.ProcessBuilder [echo, PRIVATE]", "cycle: &cycle [*cycle]"))
            assertTrue(TextFormatConverter.convert(input, Format.YAML, Format.JSON).isFailure)
    }
    @Test fun excessiveNestingAndAliasExpandedOutputAreBounded() = runBlocking<Unit> {
        val nested = "[".repeat(100) + "0" + "]".repeat(100)
        assertTrue(TextFormatConverter.convert(nested, Format.JSON, Format.YAML).isFailure)
        val expanded = "first: &text '" + "x".repeat(100_000) + "'\nitems: [" + List(40) { "*text" }.joinToString(",") + "]\n"
        assertTrue(TextFormatConverter.convert(expanded, Format.YAML, Format.JSON).isFailure)
    }
    @Test fun documentTypesAndExternalEntitiesFailWithoutReadingPayloads() = runBlocking<Unit> {
        for (input in listOf("<!DOCTYPE root><root>PUBLIC</root>", "<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///private/payload'>]><root>&x;</root>"))
            assertTrue(TextFormatConverter.convert(input, Format.XML, Format.JSON).isFailure)
    }
}
