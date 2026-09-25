package com.pdfchemy.desktop.ui

import java.io.File
import java.text.DecimalFormat

internal fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
    return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

internal fun openFileInExplorer(target: File) {
    try {
        if (java.awt.Desktop.isDesktopSupported()) {
            val desktop = java.awt.Desktop.getDesktop()
            val fileToOpen = if (target.isDirectory) target else target.parentFile ?: target
            if (fileToOpen.exists()) {
                desktop.open(fileToOpen)
            }
        }
    } catch (_: Exception) {}
}

internal fun openDocument(target: File) {
    try {
        if (java.awt.Desktop.isDesktopSupported() && target.exists()) {
            java.awt.Desktop.getDesktop().open(target)
        }
    } catch (_: Exception) {}
}
