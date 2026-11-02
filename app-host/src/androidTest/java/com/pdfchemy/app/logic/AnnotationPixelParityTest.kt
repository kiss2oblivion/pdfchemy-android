package com.pdfchemy.app.logic
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class AnnotationPixelParityTest {
    @Test fun redactionCoordinatesRemainNormalizedAcrossPreviewAndExportWidths() {
        val modification = PageModification(0, redactions = listOf(RedactionBox(0, RectF(.2f,.3f,.4f,.5f), overlayLabel = "")))
        val preview = AnnotationRenderer.render(modification, 200, 300)!!
        val export = AnnotationRenderer.render(modification, 1000, 1500)!!
        try {
            assertEquals(Color.BLACK, preview.getPixel(60,120))
            assertEquals(Color.BLACK, export.getPixel(300,600))
            assertEquals(Color.TRANSPARENT, preview.getPixel(10,10))
            assertEquals(Color.TRANSPARENT, export.getPixel(50,50))
        } finally { preview.recycle(); export.recycle() }
    }

}
