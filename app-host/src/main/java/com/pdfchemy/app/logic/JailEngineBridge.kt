package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import com.pdfchemy.app.security.PixelWire
import com.pdfchemy.app.security.SecurityLimits
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal object JailEngineBridge {
    suspend fun call(context: Context, name: String, source: Uri?, dest: Uri?, params: Any = emptyMap<String, Any>(), extra: Uri? = null): String =
        if (extra == null) PdfGateway.executeEngine(context, name, source, dest, Gson().toJson(params))
        else PdfGateway.executeEngineExtra(context, name, source, dest, extra, Gson().toJson(params))

    suspend fun bitmap(context: Context, name: String, source: Uri, params: Any = emptyMap<String, Any>()): Bitmap? {
        val file = File.createTempFile("pixels_", ".argb", context.cacheDir)
        try {
            call(context, name, source, Uri.fromFile(file), params)
            return file.inputStream().use(PixelWire::read)
        } finally { file.delete() }
    }
    suspend fun streams(context: Context, name: String, input: java.io.InputStream, output: java.io.OutputStream, params: Any): Boolean {
        val staged = com.pdfchemy.app.utils.DocumentStager.stageStreamCancellable(context, input)
        var file: File? = null
        try {
            val destination = File.createTempFile("converted_", ".tmp", context.cacheDir)
            file = destination
            call(context, name, staged.uri, Uri.fromFile(destination), params)
            require(destination.length() <= SecurityLimits.MAX_OUTPUT_BYTES)
            destination.inputStream().use { it.copyTo(output) }
            return true
        } finally { file?.delete(); com.pdfchemy.app.utils.DocumentStager.release(staged) }
    }
}
