package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FormOverlayGeometryDeviceTest {
    @Test(timeout = 120000)
    fun fieldsFollowRotatedCropCoordinatesAcrossTheTypedGateway() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val expected = listOf(
            floatArrayOf(.1f, .2f, .3f, .1f),
            floatArrayOf(.7f, .1f, .1f, .3f),
            floatArrayOf(.6f, .7f, .3f, .1f),
            floatArrayOf(.2f, .6f, .1f, .3f)
        )
        for ((index, rotation) in listOf(0, 90, 180, 270).withIndex()) {
            val source = File.createTempFile("overlay-geometry-", ".pdf", context.cacheDir)
            try {
                PDDocument().use { document ->
                    val page = PDPage(PDRectangle(400f, 600f))
                    document.addPage(page)
                    page.cropBox = PDRectangle(40f, 60f, 200f, 300f)
                    page.rotation = rotation
                    val form = PDAcroForm(document)
                    document.documentCatalog.acroForm = form
                    val field = PDTextField(form)
                    field.partialName = "fixture"
                    form.fields.add(field)
                    val widget = field.widgets.single()
                    widget.page = page
                    widget.rectangle = PDRectangle(60f, 270f, 60f, 30f)
                    page.annotations.add(widget)
                    document.save(source)
                }
                val field = AcroFormEngine.extractFields(context, Uri.fromFile(source)).single()
                assertEquals(0, field.pageIndex)
                val actual = floatArrayOf(field.xRatio, field.yRatio, field.widthRatio, field.heightRatio)
                assertArrayEquals("Rotation $rotation", expected[index], actual, .0001f)
            } finally {
                source.delete()
            }
        }
    }

    @Test(timeout = 120000)
    fun createdFieldsRoundTripInDisplayedCoordinates() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        for (rotation in listOf(0, 90, 180, 270)) {
            val source = File.createTempFile("created-form-source-", ".pdf", context.cacheDir)
            val output = File.createTempFile("created-form-output-", ".pdf", context.cacheDir)
            try {
                PDDocument().use { document ->
                    val page = PDPage(PDRectangle(400f, 600f))
                    document.addPage(page)
                    page.cropBox = PDRectangle(40f, 60f, 200f, 300f)
                    page.rotation = rotation
                    document.save(source)
                }
                val spec = InteractiveFieldSpec(0, "fixture", FormFieldType.TEXT, .15f, .25f, .3f, .1f, "Geometry")
                assertTrue(AcroFormEngine.createAcroFormWithFields(context, Uri.fromFile(source), Uri.fromFile(output), listOf(spec)))
                val field = AcroFormEngine.extractFields(context, Uri.fromFile(output)).single()
                assertArrayEquals(
                    "Round trip rotation $rotation",
                    floatArrayOf(spec.xRatio, spec.yRatio, spec.widthRatio, spec.heightRatio),
                    floatArrayOf(field.xRatio, field.yRatio, field.widthRatio, field.heightRatio),
                    .0001f
                )
                assertEquals("Geometry", field.value)
            } finally {
                source.delete()
                output.delete()
            }
        }
    }

    @Test(timeout = 60000)
    fun incompleteRadioCreationCannotPublishAnEmptyWidget() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("radio-source-", ".pdf", context.cacheDir)
        val output = File.createTempFile("radio-output-", ".pdf", context.cacheDir)
        try {
            PDDocument().use {
                it.addPage(PDPage())
                it.save(source)
            }
            assertFalse(
                AcroFormEngine.createAcroFormWithFields(
                    context,
                    Uri.fromFile(source),
                    Uri.fromFile(output),
                    listOf(InteractiveFieldSpec(0, "radio", FormFieldType.RADIO))
                )
            )
            assertEquals(0L, output.length())
        } finally {
            source.delete()
            output.delete()
        }
    }
}
