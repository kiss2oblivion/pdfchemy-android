package com.pdfchemy.app.jail.engines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/** Uses the production cleanup/plan/replace/guard helpers with a clean substitute raster. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FreshRedactionAliasProbeTest {
    @Before fun initialize() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>()) }
    private fun runAlias(attachment: Boolean) {
        val secret = "REANALYSIS_NESTED_PRIVATE_PAYLOAD_529814"
        val input = PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            PDPageContentStream(doc, page).use { it.addRect(0f, 0f, 25f, 25f); it.fill() }
            val privatePayload = doc.document.createCOSStream().apply {
                createOutputStream().use { it.write(secret.toByteArray()) }
            }
            val catalog = doc.documentCatalog.cosObject
            if (attachment) {
                val specification = COSDictionary().apply {
                    setName(COSName.TYPE, "Filespec"); setString(COSName.F, "private.txt")
                    setItem(COSName.getPDFName("EF"), COSDictionary().apply { setItem(COSName.F, privatePayload) })
                }
                catalog.setItem(COSName.getPDFName("AF"), COSArray().apply { add(specification) })
            } else {
                catalog.setItem(COSName.getPDFName("PieceInfo"), COSDictionary().apply {
                    setItem(COSName.getPDFName("PrivateApp"), COSDictionary().apply {
                        setString(COSName.getPDFName("LastModified"), "D:20261021000000Z")
                        setItem(COSName.getPDFName("Private"), privatePayload)
                    })
                })
            }
            catalog.setItem(COSName.getPDFName("AlternatePayload"), privatePayload)
            ByteArrayOutputStream().also(doc::save).toByteArray()
        }
        val bytes = PDDocument.load(input).use { doc ->
            val page = doc.getPage(0)
            val catalog = doc.documentCatalog.cosObject
            RedactionResources.prepareForRedaction(doc)
            val plan = RedactionResources.rasterizationPlan(doc, setOf(0), emptySet())
            RedactionResources.replacePageContent(doc, page) { it.addRect(0f, 0f, 50f, 50f); it.fill() }
            RedactionResources.pruneUnused(doc)
            plan.requireOriginalsDetached(doc)
            assertFalse(catalog.containsKey(COSName.getPDFName(if (attachment) "AF" else "PieceInfo")))
            ByteArrayOutputStream().also(doc::save).toByteArray()
        }
        PDDocument.load(bytes).use { saved ->
            assertFalse(PDFTextStripper().getText(saved).contains(secret))
            val retained = saved.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("AlternatePayload")) as COSStream
            assertEquals("Observed defect: saved reachable private payload survives cleanup and the detachment guard", secret,
                retained.createInputStream().use { String(it.readBytes(), Charsets.UTF_8) })
        }
        val fixtures = File("build/reports/reanalysis-fixtures").apply { mkdirs() }
        val name = if (attachment) "attachment-alias" else "piece-info-alias"
        File(fixtures, "$name-input.pdf").writeBytes(input)
        File(fixtures, "$name-output.pdf").writeBytes(bytes)
    }
    @Test fun descendantOfPrivatePieceInfoSurvivesThroughAnAlias_probe() = runAlias(false)
    @Test fun untypedEmbeddedPayloadSurvivesThroughAnAlias_probe() = runAlias(true)
}
