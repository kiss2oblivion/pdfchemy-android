package com.pdfchemy.app.utils

import android.util.Log
import com.pdfchemy.app.BuildConfig

object AppLogger {
    private const val TAG = "PDFchemy"

    fun d(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, message, throwable)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, message, throwable)
        }
    }
}
