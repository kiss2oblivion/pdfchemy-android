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

object TextToPdfConverter {
    suspend fun convert(context: Context, text: String, destUri: Uri): Result<Unit> = runCatching {
        JailEngineBridge.call(context, "TEXT_TO_PDF", null, destUri, mapOf("text" to text)); Unit
    }
}
