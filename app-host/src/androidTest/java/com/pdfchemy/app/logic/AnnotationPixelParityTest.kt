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

    @Test
    fun opaqueRedactionsCoverNewFilledShapes() {
        val mod = PageModification(
            0,
            shapes = listOf(
                ShapeAnnotation(
                    type = ShapeType.RECTANGLE,
                    startXRatio = .1f,
                    startYRatio = .1f,
                    endXRatio = .9f,
                    endYRatio = .9f,
                    strokeColor = Color.RED,
                    fillColor = Color.RED
                )
            ),
            redactions = listOf(RedactionBox(0, RectF(.2f, .3f, .4f, .5f), overlayLabel = ""))
        )
        val bitmap = AnnotationRenderer.render(mod, 1000, 1000)!!
        try {
            assertEquals(Color.BLACK, bitmap.getPixel(300, 400))
            assertEquals(Color.RED, bitmap.getPixel(600, 600))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun customStampTextChangesTheRenderedStampWidth() {
        fun width(custom: String?): Int {
            val bitmap = AnnotationRenderer.render(
                PageModification(
                    0,
                    stamps = listOf(
                        StampAnnotation(
                            type = StampType.APPROVED,
                            xRatio = .5f,
                            yRatio = .5f,
                            rotation = 0f,
                            customText = custom
                        )
                    )
                ),
                1000,
                1000
            )!!
            try {
                var left = bitmap.width
                var right = -1
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                            left = minOf(left, x)
                            right = maxOf(right, x)
                        }
                    }
                }
                return right - left + 1
            } finally {
                bitmap.recycle()
            }
        }
        assertTrue(width(null) > width("X"))
    }
}
