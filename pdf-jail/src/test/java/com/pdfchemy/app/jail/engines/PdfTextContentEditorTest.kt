package com.pdfchemy.app.jail.engines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PdfTextContentEditorTest {
    @Before fun initialize() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>()) }
    private fun fixture(rotation: Int = 0, draw: (PDPageContentStream) -> Unit): PDDocument = PDDocument().apply {
        val page = PDPage(PDRectangle(400f,600f));page.cropBox=PDRectangle(40f,60f,250f,350f);page.rotation=rotation;addPage(page)
        page.annotations.add(PDAnnotationText().apply { rectangle=PDRectangle(60f,80f,25f,25f);contents="KEEP_REVIEW_NOTE" })
        PDPageContentStream(this,page).use { c ->
            c.setNonStrokingColor(0f,0f,1f);c.addRect(45f,70f,20f,20f);c.fill()
            c.beginText();c.setFont(PDType1Font.HELVETICA,12f);c.newLineAtOffset(50f,200f);draw(c);c.endText()
        }
    }
    private fun reload(document: PDDocument): PDDocument {
        val bytes=ByteArrayOutputStream();document.save(bytes);return PDDocument.load(bytes.toByteArray())
    }
    @Test fun preservesAnnotationsPageBoxesRotationVectorsAndUnrelatedSearchableText() {
        for(rotation in listOf(0,90,180,270)) fixture(rotation) { it.showText("PUBLIC_BEFORE ACME_CORP PUBLIC_AFTER") }.use { doc ->
            val result=PdfTextContentEditor(doc,"ACME_CORP",true,"ROMÂNĂ_ȚȘ").execute()
            assertEquals(1,result.totalMatches)
            reload(doc).use { saved ->
                val page=saved.getPage(0);assertEquals(rotation,page.rotation)
                assertEquals(400f,page.mediaBox.width,0f);assertEquals(600f,page.mediaBox.height,0f)
                assertEquals(40f,page.cropBox.lowerLeftX,0f);assertEquals(60f,page.cropBox.lowerLeftY,0f)
                assertEquals(250f,page.cropBox.width,0f);assertEquals(350f,page.cropBox.height,0f)
                assertEquals("KEEP_REVIEW_NOTE",page.annotations.single().contents)
                val text=PDFTextStripper().getText(saved).filterNot(Char::isWhitespace)
                assertTrue(text,text.contains("PUBLIC_BEFORE"));assertTrue(text,text.contains("PUBLIC_AFTER"))
                assertTrue(text,text.contains("ROMÂNĂ_ȚȘ"));assertFalse(text.contains("ACME_CORP"))
                val operators=page.contents.use { String(it.readBytes(),Charsets.ISO_8859_1) }
                assertTrue("Unrelated rectangle must remain vector content",operators.contains(" re"))
            }
        }
    }
    private fun tailPosition(doc:PDDocument):Pair<Float,Float> {
        val found=mutableListOf<Pair<Float,Float>>()
        val stripper=object:PDFTextStripper(){override fun processTextPosition(t:TextPosition){if(t.unicode=="T")found.add(t.xDirAdj to t.yDirAdj);super.processTextPosition(t)}}
        stripper.getText(doc);return found.last()
    }
    @Test fun replacesAcrossTextOperatorsWithoutMovingTheFollowingText() {
        fixture { it.showText("AC");it.showTextWithPositioning(arrayOf<Any>("ME",-30f,"_CORP"));it.showText(" TAIL") }.use { doc ->
            val before=tailPosition(doc)
            assertEquals(1,PdfTextContentEditor(doc,"ACME_CORP",true,"NEW").execute().totalMatches)
            reload(doc).use { saved ->
                val after=tailPosition(saved);assertEquals(before.first,after.first,.001f);assertEquals(before.second,after.second,.001f)
                val text=PDFTextStripper().getText(saved);assertTrue(text,text.contains("NEW"));assertTrue(text,text.contains("TAIL"));assertFalse(text.contains("ACME_CORP"))
            }
        }
    }
    @Test fun findsAndReplacesPhrasesAcrossInferredWordSpacing() {
        fixture { it.showTextWithPositioning(arrayOf<Any>("alpha",-300f,"beta"));it.showText(" TAIL") }.use { doc ->
            assertEquals(1,PdfTextContentEditor(doc,"alpha beta",false).execute().totalMatches)
            assertEquals(1,PdfTextContentEditor(doc,"alpha beta",false,"new phrase").execute().totalMatches)
            reload(doc).use { assertTrue(PDFTextStripper().getText(it).contains("new phrase")) }
        }
    }
    @Test fun exactCjkReplacementRemainsSearchable() {
        fixture { it.showText("ACME_CORP") }.use { doc ->
            val replacement="合同_日本語_한글"
            assertEquals(1,PdfTextContentEditor(doc,"ACME_CORP",true,replacement).execute().totalMatches)
            reload(doc).use { saved ->
                val text=PDFTextStripper().getText(saved);assertTrue(text,text.contains(replacement))
                assertEquals(1,PdfTextContentEditor(saved,replacement,true).execute().totalMatches)
            }
        }
    }
    @Test fun clonesSharedFormsPerInvocationAndPreservesUnmatchedUses() {
        PDDocument().use { doc ->
            val form=PDFormXObject(doc).apply { resources=PDResources();bBox=PDRectangle(0f,-5f,40f,25f) }
            PDPageContentStream(doc,form,form.cosObject.createOutputStream()).use { c ->
                c.beginText();c.setFont(PDType1Font.HELVETICA,12f);c.showText("ME");c.endText()
            }
            for(prefix in listOf("AC","SO")) {
                val page=PDPage();doc.addPage(page)
                PDPageContentStream(doc,page).use { c ->
                    c.beginText();c.setFont(PDType1Font.HELVETICA,12f);c.newLineAtOffset(50f,200f);c.showText(prefix);c.endText()
                    c.saveGraphicsState();c.transform(Matrix.getTranslateInstance(50f+PDType1Font.HELVETICA.getStringWidth(prefix)*12/1000,200f));c.drawForm(form);c.restoreGraphicsState()
                }
            }
            assertEquals(1,PdfTextContentEditor(doc,"ACME",true,"NEW").execute().totalMatches)
            reload(doc).use { saved ->
                val text=PDFTextStripper().getText(saved);assertTrue(text,text.contains("SOME"));assertTrue(text,text.contains("NEW"));assertFalse(text.contains("ACME"))
            }
        }
    }
    @Test fun emptyReplacementRemovesOnlyMatchedText() {
        fixture { it.showText("PUBLIC_BEFORE ACME_CORP PUBLIC_AFTER") }.use { doc ->
            assertEquals(1,PdfTextContentEditor(doc,"ACME_CORP",true,"").execute().totalMatches)
            reload(doc).use { saved ->
                val text=PDFTextStripper().getText(saved);assertTrue(text,text.contains("PUBLIC_BEFORE"));assertTrue(text,text.contains("PUBLIC_AFTER"));assertFalse(text.contains("ACME_CORP"))
            }
        }
    }
    private fun unicodeReplacement(replacement:String) {
        fixture { it.showText("ACME_CORP") }.use { doc ->
            assertEquals(1,PdfTextContentEditor(doc,"ACME_CORP",true,replacement).execute().totalMatches)
            reload(doc).use { saved ->
                val text=PDFTextStripper().getText(saved)
                assertTrue("Expected [$replacement] in [$text]",text.contains(replacement))
                assertEquals(1,PdfTextContentEditor(saved,replacement,true).execute().totalMatches)
            }
        }
    }
    @Test fun exactArabicReplacementRemainsSearchable()=unicodeReplacement("مرحبا بالعالم")
    @Test fun exactHindiReplacementRemainsSearchable()=unicodeReplacement("हिन्दी पाठ")
    @Test fun exactThaiReplacementRemainsSearchable()=unicodeReplacement("ภาษาไทย")

    @Test fun preservesLineMatrixAfterBothQuoteOperators() {
        for(operator in listOf("(PREFIX ACME_CORP) '\n", "3 2 (PREFIX ACME_CORP) \"\n")) fixture {
            it.setLeading(20f)
            @Suppress("DEPRECATION") it.appendRawCommands(operator)
            it.newLine();it.showText("TAIL")
        }.use { doc ->
            val before=tailPosition(doc)
            assertEquals(1,PdfTextContentEditor(doc,"ACME_CORP",true,"NEW").execute().totalMatches)
            reload(doc).use { saved ->
                val after=tailPosition(saved);assertEquals(before.first,after.first,.001f);assertEquals(before.second,after.second,.001f)
                assertTrue(PDFTextStripper().getText(saved).contains("NEW"))
            }
        }
    }
    @Test fun updatesTaggedActualTextWithoutRemovingOtherProperties() {
        fixture {
            @Suppress("DEPRECATION") it.appendRawCommands("/Span << /ActualText (PREFIX ACME_CORP SUFFIX) /MCID 7 >> BDC\n")
            it.showText("PREFIX ACME_CORP SUFFIX")
            it.endMarkedContent()
        }.use { doc ->
            assertEquals(1,PdfTextContentEditor(doc,"ACME_CORP",true,"NEW").execute().totalMatches)
            reload(doc).use { saved ->
                val content=saved.getPage(0).contents.use { String(it.readBytes(),Charsets.ISO_8859_1) }
                assertFalse(content,content.contains("ACME_CORP"));assertTrue(content,content.contains("PREFIX NEW SUFFIX"));assertTrue(content,content.contains("/MCID 7"))
            }
        }
    }

    @Test fun searchKeepsActualPageIndicesWhenEarlierPagesHaveNoContents() {
        fixture { it.showText("ACME_CORP") }.use { doc ->
            doc.pages.insertBefore(PDPage(),doc.getPage(0))
            val summary=PdfTextContentEditor(doc,"ACME_CORP",true).execute()
            assertEquals(1,summary.totalMatches);assertEquals(1,summary.occurrences.single().pageIndex)
        }
    }

    @Test fun sourceReadingOrderKeepsReplacementBetweenItsNeighbours() {
        fixture { it.showText("KEEP_LEFT ACME_CORP KEEP_RIGHT") }.use { doc ->
            assertEquals(1,PdfTextContentEditor(doc,"ACME_CORP",true,"NEW").execute().totalMatches)
            reload(doc).use { saved ->
                val text=PDFTextStripper().getText(saved)
                assertTrue("Reading order: [$text]",text.replace(Regex("\\s+")," ").contains("KEEP_LEFT NEW KEEP_RIGHT"))
                assertEquals(1,PdfTextContentEditor(saved,"KEEP_LEFT NEW KEEP_RIGHT",true).execute().totalMatches)
            }
        }
    }

    @Test fun duplicateOverpaintCannotRetainOriginalText() {
        PDDocument().use { doc ->
            val page=PDPage();doc.addPage(page)
            PDPageContentStream(doc,page).use { c ->
                for(offset in listOf(0f,.1f)) {
                    c.beginText();c.setFont(PDType1Font.HELVETICA,12f);c.newLineAtOffset(50f+offset,200f+offset)
                    c.showText("ACME_CORP");c.endText()
                }
            }
            assertEquals(1,PdfTextContentEditor(doc,"ACME_CORP",true,"NEW").execute().totalMatches)
            reload(doc).use { saved ->
                val text=PDFTextStripper().apply { suppressDuplicateOverlappingText=false }.getText(saved)
                assertFalse(text,text.contains("ACME_CORP"));assertTrue(text,text.contains("NEW"))
            }
        }
    }
    @Test fun supplementaryUnicodeReplacementRemainsSearchable()=unicodeReplacement("Δοκιμή Пример 🌍")

}
