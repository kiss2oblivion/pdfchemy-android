package com.pdfchemy.app.logic.audio

object AudioForegroundPolicy {
    // ServiceInfo: mediaProcessing = 8192 (API35+); dataSync = 1 (local file processing on older systems).
    // Select exactly one declared type; passing an unknown manifest type fails on Android14.
    fun typeForSdk(sdk: Int): Int = when {
        sdk >= 35 -> 8192
        sdk >= 29 -> 1
        else -> 0
    }
}
