package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.JailMemory
import com.pdfchemy.app.jail.boundedFileOutput
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory

object ImagePdfWorker {
    fun convert(sources: List<ParcelFileDescriptor>, target: ParcelFileDescriptor): String {
        PDDocument(JailMemory.settings()).use { document ->
            for (source in sources) {
                val bitmap = SafeImageDecoder.decode(source)
                try {
                    val image = LosslessFactory.createFromImage(document, bitmap)
                    val page = PDPage(PDRectangle.A4)
                    document.addPage(page)
                    val scale = minOf((page.mediaBox.width - 40) / image.width, (page.mediaBox.height - 40) / image.height)
                    val width = image.width * scale; val height = image.height * scale
                    PDPageContentStream(document, page).use { it.drawImage(image, (page.mediaBox.width - width) / 2, (page.mediaBox.height - height) / 2, width, height) }
                } finally { bitmap.recycle() }
            }
            boundedFileOutput(target.fileDescriptor).use { document.save(it) }
        }
        return "{\"success\":true}"
    }
}
