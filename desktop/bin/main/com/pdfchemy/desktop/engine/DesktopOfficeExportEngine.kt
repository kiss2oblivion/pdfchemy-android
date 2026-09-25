package com.pdfchemy.desktop.engine

import kotlinx.coroutines.runBlocking
import java.io.File
import com.pdfchemy.desktop.jail.DesktopJailManager

object DesktopOfficeExportEngine {
    fun exportToWord(sourceFile: File, destFile: File): DesktopOfficeExportReport {
        return runBlocking {
            val result = DesktopJailManager.execute("exportOffice", mapOf("format" to "docx"), sourceFile)
            result.copyTo(destFile, overwrite = true)
            result.delete()
            DesktopOfficeExportReport(DesktopOfficeFormat.WORD, 0, destFile.length(), 0)
        }
    }

    fun exportToExcel(sourceFile: File, destFile: File): DesktopOfficeExportReport {
        return runBlocking {
            val result = DesktopJailManager.execute("exportOffice", mapOf("format" to "xlsx"), sourceFile)
            result.copyTo(destFile, overwrite = true)
            result.delete()
            DesktopOfficeExportReport(DesktopOfficeFormat.EXCEL, 0, destFile.length(), 0)
        }
    }

    fun exportToPowerPoint(sourceFile: File, destFile: File): DesktopOfficeExportReport {
        return runBlocking {
            val result = DesktopJailManager.execute("exportOffice", mapOf("format" to "pptx"), sourceFile)
            result.copyTo(destFile, overwrite = true)
            result.delete()
            DesktopOfficeExportReport(DesktopOfficeFormat.POWERPOINT, 0, destFile.length(), 0)
        }
    }
}
