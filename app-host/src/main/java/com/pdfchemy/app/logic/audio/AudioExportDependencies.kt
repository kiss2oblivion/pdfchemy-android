package com.pdfchemy.app.logic.audio

import android.content.Context

object AudioExportDependencies {
    val jobStore = AudioExportJobStore()
    lateinit var stagingManager: AudioStagingManager

    fun initialize(context: Context) {
        if (!::stagingManager.isInitialized) {
            stagingManager = AudioStagingManager(context.applicationContext)
        }
    }
}
