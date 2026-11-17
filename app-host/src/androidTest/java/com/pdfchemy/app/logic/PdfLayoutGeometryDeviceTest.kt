package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfLayoutGeometryDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun fixture(file: File, rotation: Int, annotated: Boolean = false) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(400f,600f))
            page.cropBox = PDRectangle(40f,60f,200f,300f)
            page.rotation = rotation
            document.addPage(page)
            PDPageContentStream(document,page).use { content ->
                content.setNonStrokingColor(1f,0f,0f)
                content.addRect(55f,80f,35f,45f); content.fill()
                content.setNonStrokingColor(0f,0f,1f)
                content.addRect(175f,280f,45f,55f); content.fill()
                content.beginText(); content.setFont(PDType1Font.HELVETICA,12f)
                content.newLineAtOffset(85f,200f); content.showText("VECTOR"); content.endText()
            }
            if (annotated) page.annotations.add(PDAnnotationText().apply {
                rectangle = PDRectangle(100f,100f,25f,25f); contents = "Keep this annotation"
            })
            document.save(file)
        }
    }

    private fun centre(bitmap: Bitmap, red: Boolean): Pair<Double,Double> {
        var sx=0L; var sy=0L; var count=0L
        for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
            val pixel=bitmap.getPixel(x,y)
            val a=if(red) Color.red(pixel) else Color.blue(pixel)
            val b=if(red) Color.blue(pixel) else Color.red(pixel)
            if(a>100 && a>b*2 && a>Color.green(pixel)*2) { sx+=x;sy+=y;count++ }
        }
        assertTrue("Visible vector marker missing",count>20)
        return sx.toDouble()/count/bitmap.width to sy.toDouble()/count/bitmap.height
    }

    @Test(timeout=240000) fun resizeAndNupPreserveRotatedCropGeometryAndVectorText() = runBlocking<Unit> {
        for(rotation in listOf(0,90,180,270)) {
            val source=File.createTempFile("layout-source-",".pdf",context.cacheDir)
            val resized=File.createTempFile("layout-resize-",".pdf",context.cacheDir)
            val nup=File.createTempFile("layout-nup-",".pdf",context.cacheDir)
            val bitmaps=mutableListOf<Bitmap>()
            try {
                fixture(source,rotation)
                val baseline=PdfEditor.renderPageBitmap(context,Uri.fromFile(source),0,600)!!;bitmaps+=baseline
                assertTrue(PdfLayoutEngine.resizePages(context,Uri.fromFile(source),Uri.fromFile(resized),TargetPaperSize.A4))
                assertTrue(PdfLayoutEngine.createNUpLayout(context,Uri.fromFile(source),Uri.fromFile(nup),NUpMode.TWO_UP,TargetPaperSize.A4,false))
                for((file,isNup) in listOf(resized to false,nup to true)) {
                    PDDocument.load(file).use { assertTrue("Text was rasterized",PDFTextStripper().getText(it).contains("VECTOR")) }
                    val actual=PdfEditor.renderPageBitmap(context,Uri.fromFile(file),0,600)!!;bitmaps+=actual
                    val pageW=if(!isNup && rotation%180==90) TargetPaperSize.A4.heightPts else TargetPaperSize.A4.widthPts
                    val pageH=if(!isNup && rotation%180==90) TargetPaperSize.A4.widthPts else TargetPaperSize.A4.heightPts
                    val origW=if(rotation%180==90) 300.0 else 200.0
                    val origH=if(rotation%180==90) 200.0 else 300.0
                    val availW=if(isNup) (pageW-48.0)/NUpMode.TWO_UP.cols-12.0 else pageW.toDouble()
                    val availH=if(isNup) (pageH-48.0)/NUpMode.TWO_UP.rows-12.0 else pageH.toDouble()
                    val scale=minOf(availW/origW,availH/origH)
                    val left=(if(isNup) 30.0 else 0.0)+(availW-origW*scale)/2
                    val top=(if(isNup) 30.0 else 0.0)+(availH-origH*scale)/2
                    for(red in listOf(false,true)) {
                        val expected=centre(baseline,red);val observed=centre(actual,red)
                        assertEquals("x rotation=$rotation nup=$isNup",(left+expected.first*origW*scale)/pageW,observed.first,.015)
                        assertEquals("y rotation=$rotation nup=$isNup",(top+expected.second*origH*scale)/pageH,observed.second,.015)
                    }
                }
            } finally {bitmaps.forEach{it.recycle()};source.delete();resized.delete();nup.delete()}
        }
    }

    @Test(timeout=60000) fun layoutRejectsAnnotationsWithoutPublishingAnIncompleteCopy() = runBlocking<Unit> {
        val source=File.createTempFile("layout-annotated-",".pdf",context.cacheDir)
        val output=File.createTempFile("layout-rejected-",".pdf",context.cacheDir)
        try {
            fixture(source,0,true)
            assertFalse(PdfLayoutEngine.resizePages(context,Uri.fromFile(source),Uri.fromFile(output),TargetPaperSize.A4))
            assertEquals(0L,output.length())
            assertFalse(PdfLayoutEngine.createNUpLayout(context,Uri.fromFile(source),Uri.fromFile(output),NUpMode.TWO_UP))
            assertEquals(0L,output.length())
        } finally {source.delete();output.delete()}
    }
}
