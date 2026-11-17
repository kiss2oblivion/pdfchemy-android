package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FindReplaceFidelityDeviceTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    @Before fun initialize() { PDFBoxResourceLoader.init(context) }
    private fun fixture(file:File,rotation:Int=0) {
        PDDocument().use { doc ->
            val page=PDPage(PDRectangle(400f,600f));page.cropBox=PDRectangle(40f,60f,200f,300f);page.rotation=rotation;doc.addPage(page)
            page.annotations.add(PDAnnotationText().apply { rectangle=PDRectangle(100f,100f,20f,20f);contents="KEEP_REVIEW_NOTE" })
            PDPageContentStream(doc,page).use { c ->
                c.setNonStrokingColor(1f,0f,0f);c.addRect(50f,80f,30f,20f);c.fill()
                c.setNonStrokingColor(0f,0f,1f);c.addRect(180f,300f,30f,30f);c.fill()
                c.setNonStrokingColor(0f,0f,0f)
                c.beginText();c.setFont(PDType1Font.HELVETICA,9f);c.newLineAtOffset(50f,200f)
                c.showText("KEEP_LEFT ACME_CORP KEEP_RIGHT");c.endText()
            }
            doc.save(file)
        }
    }
    private fun centre(bitmap:Bitmap,red:Boolean):Pair<Double,Double> {
        var xsum=0L;var ysum=0L;var count=0L
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){
            val p=bitmap.getPixel(x,y);val a=if(red)Color.red(p)else Color.blue(p);val b=if(red)Color.blue(p)else Color.red(p)
            if(a>100&&a>b*2&&a>Color.green(p)*2){xsum+=x;ysum+=y;count++}
        }
        assertTrue("Unrelated vector marker missing",count>20)
        return xsum.toDouble()/count/bitmap.width to ysum.toDouble()/count/bitmap.height
    }
    @Test(timeout=240000) fun preservesRotatedCropAnnotationsVectorsAndUnrelatedSearchableText()=runBlocking<Unit>{
        for(rotation in listOf(0,90,180,270)){
            val source=File.createTempFile("find-source-",".pdf",context.cacheDir)
            val output=File.createTempFile("find-edited-",".pdf",context.cacheDir)
            var before:Bitmap?=null;var after:Bitmap?=null
            try{
                fixture(source,rotation);val original=source.readBytes()
                before=requireNotNull(PdfEditor.renderPageBitmap(context,Uri.fromFile(source),0,600))
                assertEquals(1,PdfFindAndReplaceEngine.replaceAll(context,Uri.fromFile(source),Uri.fromFile(output),"ACME_CORP","ROMÂNĂ_ȚȘ",true).getOrThrow())
                assertArrayEquals("Input must be untouched",original,source.readBytes())
                PDDocument.load(output).use { doc ->
                    val page=doc.getPage(0);assertEquals(rotation,page.rotation)
                    assertEquals(400f,page.mediaBox.width,0f);assertEquals(600f,page.mediaBox.height,0f)
                    assertEquals(40f,page.cropBox.lowerLeftX,0f);assertEquals(60f,page.cropBox.lowerLeftY,0f)
                    assertEquals(200f,page.cropBox.width,0f);assertEquals(300f,page.cropBox.height,0f)
                    assertEquals("KEEP_REVIEW_NOTE",page.annotations.single().contents)
                    val text=PDFTextStripper().getText(doc).filterNot(Char::isWhitespace)
                    assertTrue(text,text.contains("KEEP_LEFT"));assertTrue(text,text.contains("KEEP_RIGHT"));assertTrue(text,text.contains("ROMÂNĂ_ȚȘ"));assertFalse(text.contains("ACME_CORP"))
                    val raw=page.contents.use { String(it.readBytes(),Charsets.ISO_8859_1) };assertTrue(raw.contains(" re"))
                }
                after=requireNotNull(PdfEditor.renderPageBitmap(context,Uri.fromFile(output),0,600))
                assertEquals(before.width,after.width);assertEquals(before.height,after.height)
                for(red in listOf(false,true)){
                    val expected=centre(before,red);val actual=centre(after,red)
                    assertEquals(expected.first,actual.first,.005);assertEquals(expected.second,actual.second,.005)
                }
            }finally{before?.recycle();after?.recycle();source.delete();output.delete()}
        }
    }
    @Test(timeout=240000) fun exactUnicodeReplacementIsSearchableAndInputSafe()=runBlocking<Unit>{
        for(replacement in listOf("ROMÂNĂ_ȚȘ","合同_日本語_한글","مرحبا بالعالم","हिन्दी पाठ","ภาษาไทย")){
            val source=File.createTempFile("find-unicode-",".pdf",context.cacheDir)
            val output=File.createTempFile("find-unicode-out-",".pdf",context.cacheDir)
            try{
                fixture(source);val original=source.readBytes()
                assertEquals(1,PdfFindAndReplaceEngine.replaceAll(context,Uri.fromFile(source),Uri.fromFile(output),"ACME_CORP",replacement,true).getOrThrow())
                PDDocument.load(output).use { doc ->
                    val text=PDFTextStripper().getText(doc)
                    assertTrue("Exact Unicode replacement lost: [$replacement] in [$text]",text.contains(replacement))
                    assertFalse(text.contains("ACME_CORP"))
                }
                assertEquals(1,PdfFindAndReplaceEngine.findOccurrences(context,Uri.fromFile(output),replacement,true).totalMatches)
                assertArrayEquals(original,source.readBytes())
                // Independent Android/PDFium search on platforms that expose that API.
                if(Build.VERSION.SDK_INT>=35){
                    ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        android.graphics.pdf.PdfRenderer(fd).use { renderer -> renderer.openPage(0).use { page ->
                            assertTrue("PDFium must find the replacement",page.searchText(replacement).isNotEmpty())
                            assertTrue("PDFium must not find the replaced text",page.searchText("ACME_CORP").isEmpty())
                        } }
                    }
                }
            }finally{source.delete();output.delete()}
        }
    }
    @Test(timeout=90000) fun replacementKeepsTheOriginalLogicalReadingOrder()=runBlocking<Unit>{
        val source=File.createTempFile("find-order-",".pdf",context.cacheDir)
        val output=File.createTempFile("find-order-out-",".pdf",context.cacheDir)
        try{
            fixture(source)
            assertEquals(1,PdfFindAndReplaceEngine.replaceAll(context,Uri.fromFile(source),Uri.fromFile(output),"ACME_CORP","NEW",true).getOrThrow())
            PDDocument.load(output).use { val text=PDFTextStripper().getText(it);assertTrue("Reading order: [$text]",text.replace(Regex("\\s+")," ").contains("KEEP_LEFT NEW KEEP_RIGHT")) }
        }finally{source.delete();output.delete()}
    }
    @Test(timeout=180000) fun scannedReplacementRemainsVisibleAcrossImagePaintOrder()=runBlocking<Unit>{
        for(imageFirst in listOf(false,true)){
            val base=File.createTempFile("find-base-",".pdf",context.cacheDir)
            val source=File.createTempFile("find-scan-",".pdf",context.cacheDir)
            val output=File.createTempFile("find-scan-out-",".pdf",context.cacheDir)
            var scan:Bitmap?=null;var after:Bitmap?=null
            try{
                PDDocument().use { doc ->
                    val page=PDPage(PDRectangle(400f,200f));doc.addPage(page)
                    PDPageContentStream(doc,page).use { c ->
                        c.beginText();c.setFont(PDType1Font.HELVETICA,24f);c.newLineAtOffset(50f,100f);c.showText("ACME_CORP");c.endText()
                    };doc.save(base)
                }
                scan=requireNotNull(PdfEditor.renderPageBitmap(context,Uri.fromFile(base),0,800))
                PDDocument().use { doc ->
                    val page=PDPage(PDRectangle(400f,200f));doc.addPage(page)
                    val image=LosslessFactory.createFromImage(doc,scan)
                    PDPageContentStream(doc,page).use { c ->
                        if(imageFirst)c.drawImage(image,0f,0f,400f,200f)
                        c.beginText();c.setFont(PDType1Font.HELVETICA,24f);c.setRenderingMode(RenderingMode.NEITHER)
                        c.newLineAtOffset(50f,100f);c.showText("ACME_CORP");c.endText()
                        if(!imageFirst)c.drawImage(image,0f,0f,400f,200f)
                    };doc.save(source)
                }
                val original=source.readBytes()
                assertEquals(1,PdfFindAndReplaceEngine.replaceAll(context,Uri.fromFile(source),Uri.fromFile(output),"ACME_CORP","NEW",true).getOrThrow())
                assertArrayEquals(original,source.readBytes())
                PDDocument.load(output).use { doc ->
                    val text=PDFTextStripper().getText(doc)
                    assertEquals("Exactly one replacement search layer", "NEW", text.trim())
                }
                after=requireNotNull(PdfEditor.renderPageBitmap(context,Uri.fromFile(output),0,800))
                fun dark(bitmap:Bitmap,left:Int,right:Int):Int {
                    var count=0
                    for(y in 155 until 205)for(x in left until right){val pixel=bitmap.getPixel(x,y)
                        if(Color.red(pixel)<100&&Color.green(pixel)<100&&Color.blue(pixel)<100)count++}
                    return count
                }
                val originalTail=dark(scan,260,390)
                assertTrue("Fixture must contain the old word tail",originalTail>50)
                assertTrue("Old scan text must be covered for imageFirst=$imageFirst",dark(after,260,390)<originalTail/10)
                assertTrue("Replacement must be visible above the scan for imageFirst=$imageFirst",dark(after,100,210)>50)
            }finally{scan?.recycle();after?.recycle();base.delete();source.delete();output.delete()}
        }
    }

}
