package com.pdfchemy.app.logic

import android.net.Uri

open class PDDocument : java.io.Closeable {
    companion object {
        fun load(vararg args: Any?): PDDocument { throw SecurityException("Isolated process required") }
    }
    val numberOfPages: Int = 0
    fun getPage(i: Int): PDPage { throw SecurityException("Isolated process required") }
    fun save(vararg args: Any?) { throw SecurityException("Isolated process required") }
    override fun close() {}
    val documentCatalog: Any get() = throw SecurityException("Isolated process required")
    val documentInformation: Any get() = throw SecurityException("Isolated process required")
    fun addPage(page: PDPage) { throw SecurityException("Isolated process required") }
    val pages: Any get() = throw SecurityException("Isolated process required")
    val signatureDictionaries: List<Any> get() = throw SecurityException("Isolated process required")
    fun removePage(page: Any) { throw SecurityException("Isolated process required") }
    val isEncrypted: Boolean = false
    fun setAllSecurityToBeRemoved(b: Boolean) {}
}

class PDRectangle(val width: Float, val height: Float) {
    companion object {
        val A4 = PDRectangle(0f, 0f)
        val LETTER = PDRectangle(0f, 0f)
    }
}

class PDPage(rect: PDRectangle = PDRectangle.A4) {
    val resources: PDResources get() = throw SecurityException("Isolated process required")
    val mediaBox: Any get() = throw SecurityException("Isolated process required")
    val cropBox: Any get() = throw SecurityException("Isolated process required")
    val rotation: Int = 0
}

class PDResources {
    val xObjectNames: Iterable<String> get() = throw SecurityException()
    fun getXObject(name: String): Any { throw SecurityException() }
}

class PDImageXObject {
    companion object {
        fun createFromFile(path: String, doc: PDDocument): PDImageXObject { throw SecurityException("Isolated process required") }
        fun createFromByteArray(doc: PDDocument, bytes: ByteArray, name: String): PDImageXObject { throw SecurityException("Isolated process required") }
    }
    val image: android.graphics.Bitmap get() = throw SecurityException("Isolated process required")
}

class PDFont {
    fun getStringWidth(s: String): Float = 0f
}
object PDType1Font { val HELVETICA = PDFont(); val HELVETICA_BOLD = PDFont() }
object PDType0Font { fun load(vararg args: Any?): PDFont { throw SecurityException() } }

class Matrix(vararg args: Float)

class PDPageContentStream(doc: PDDocument, page: PDPage, append: Boolean = false, compress: Boolean = false, reset: Boolean = false) : java.io.Closeable {
    override fun close() {}
    fun drawImage(image: Any, x: Float, y: Float, w: Float, h: Float) {}
    fun drawImage(image: Any, m: Matrix) {}
    fun beginText() {}
    fun setFont(font: Any, size: Float) {}
    fun newLineAtOffset(x: Float, y: Float) {}
    fun showText(text: String) {}
    fun endText() {}
    fun saveGraphicsState() {}
    fun restoreGraphicsState() {}
    fun transform(m: Matrix) {}
    fun setNonStrokingColor(r: Int, g: Int, b: Int) {}
    fun setNonStrokingColor(color: Any) {}
    fun appendRawCommands(s: String) {}
}

object JPEGFactory { fun createFromStream(doc: PDDocument, stream: java.io.InputStream): PDImageXObject { throw SecurityException() } }
object LosslessFactory { fun createFromImage(doc: PDDocument, bmp: android.graphics.Bitmap): PDImageXObject { throw SecurityException() } }

class PdfRenderer(fd: android.os.ParcelFileDescriptor) : AutoCloseable {
    val pageCount: Int = 0
    fun openPage(index: Int): Page { throw SecurityException("Isolated process required") }
    override fun close() {}
    
    class Page : AutoCloseable {
        val width: Int = 0
        val height: Int = 0
        val index: Int = 0
        fun render(bitmap: android.graphics.Bitmap, dest: android.graphics.Rect?, transform: android.graphics.Matrix?, renderMode: Int) {}
        override fun close() {}
        companion object {
            const val RENDER_MODE_FOR_DISPLAY = 1
        }
    }
}
