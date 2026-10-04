package com.pdfchemy.app.jail.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pdfchemy.app.jail.engines.IsolatedOcrRuntime

/**
 * ML Kit implementation of OcrBackend.
 */
class MlKitOcrBackend(context: Context) : OcrBackend {

    init {
        IsolatedOcrRuntime.initialize(context)
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun recognize(bitmap: Bitmap): OcrResult {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText: Text = Tasks.await(recognizer.process(inputImage))

        val blocks = visionText.textBlocks.map { block ->
            val lines = block.lines.map { line ->
                val elements = line.elements.map { element ->
                    val boxF = element.boundingBox?.let { RectF(it) }
                    OcrElement(element.text, boxF, element.confidence)
                }
                val lineBoxF = line.boundingBox?.let { RectF(it) }
                OcrLine(line.text, lineBoxF, elements)
            }
            val blockBoxF = block.boundingBox?.let { RectF(it) }
            OcrBlock(block.text, blockBoxF, lines)
        }

        return OcrResult(visionText.text, blocks)
    }

    override fun close() {
        recognizer.close()
    }
}
