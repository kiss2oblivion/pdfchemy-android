package com.pdfchemy.desktop

import com.pdfchemy.desktop.engine.DesktopUpdateManager
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    runBlocking {
        println("Checking for updates...")
        val releaseRes = DesktopUpdateManager.checkForUpdates("1.0.5")
        val release = releaseRes.getOrThrow()
        println("Release: ${release.tagName}")
        
        val asset = DesktopUpdateManager.findBestAssetForCurrentPlatform(release.assets)
            ?: throw IllegalStateException("No asset found")
        println("Asset: ${asset.name}")
        
        println("Starting secureDownloadAndVerify...")
        val result = DesktopUpdateManager.secureDownloadAndVerify(
            release = release,
            asset = asset,
            onProgress = { down, total ->
                if (down == total) println("Download complete: $total bytes")
            },
            isCancelled = { false }
        )
        
        if (result.isSuccess) {
            println("SUCCESS: ${result.getOrNull()?.absolutePath}")
        } else {
            println("FAILURE")
            result.exceptionOrNull()?.printStackTrace()
        }
    }
}
