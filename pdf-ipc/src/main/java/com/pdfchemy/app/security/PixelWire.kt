package com.pdfchemy.app.security

import android.graphics.Bitmap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Fixed raw ARGB rows; no encoded image decoder is involved in the host. */
object PixelWire {
    fun write(bitmap: Bitmap?, output: OutputStream) {
        val out = DataOutputStream(output.buffered())
        if (bitmap == null) { out.writeInt(0); out.writeInt(0); return }
        SecurityLimits.requirePixels(bitmap.width, bitmap.height)
        out.writeInt(bitmap.width); out.writeInt(bitmap.height)
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            row.forEach(out::writeInt)
        }
        out.flush()
    }
    fun read(input: InputStream): Bitmap? {
        val stream = DataInputStream(input.buffered())
        val width = stream.readInt(); val height = stream.readInt()
        if (width == 0 && height == 0) return null
        SecurityLimits.requirePixels(width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) row[x] = stream.readInt()
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            return bitmap
        } catch (e: Exception) { bitmap.recycle(); throw e }
    }
}
