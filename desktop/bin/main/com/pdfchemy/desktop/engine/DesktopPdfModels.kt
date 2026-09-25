package com.pdfchemy.desktop.engine

import java.awt.BasicStroke
import java.awt.Font
import java.awt.RenderingHints
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.imageio.ImageIO

enum class AcroFieldType {
    TEXT,
    CHECKBOX,
    RADIO,
    CHOICE,
    OTHER
}

data class DesktopAcroField(
    val name: String,
    val fullyQualifiedName: String,
    val type: AcroFieldType,
    val value: String,
    val options: List<String> = emptyList(),
    val isReadOnly: Boolean = false,
    val isRequired: Boolean = false
)

data class DesktopFormFieldSpec(
    val pageIndex: Int,
    val name: String,
    val type: AcroFieldType = AcroFieldType.TEXT,
    val xRatio: Float = 0.1f,
    val yRatio: Float = 0.1f,
    val widthRatio: Float = 0.35f,
    val heightRatio: Float = 0.045f,
    val defaultValue: String = "",
    val options: List<String> = emptyList()
)

data class PageDiff(
    val pageNum: Int,
    val isIdentical: Boolean,
    val addedLines: List<String>,
    val removedLines: List<String>,
    val lineCountA: Int,
    val lineCountB: Int
)

data class PdfDiffSummary(
    val arePageCountsEqual: Boolean,
    val pagesA: Int,
    val pagesB: Int,
    val pageDiffs: List<PageDiff>,
    val totalAddedLines: Int,
    val totalRemovedLines: Int,
    val isEntirelyIdentical: Boolean
)

data class DesktopPdfBookmark(
    val title: String,
    val pageIndex: Int,
    val children: List<DesktopPdfBookmark> = emptyList(),
    val depth: Int = 0
)

// --- BATES STAMPING DATA MODELS ---
enum class DesktopBatesPosition {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
}

data class DesktopBatesConfig(
    val prefix: String = "EXHIBIT-",
    val suffix: String = "",
    val startNumber: Int = 1,
    val digits: Int = 6,
    val position: DesktopBatesPosition = DesktopBatesPosition.BOTTOM_RIGHT,
    val fontSize: Float = 10f,
    val marginPt: Float = 36f,
    val pageRange: IntRange? = null
)

// --- SANITIZE & SCRUB DATA MODELS ---
data class DesktopSanitizeResult(
    val threatsFound: Int,
    val jsCount: Int,
    val launchActionsCount: Int,
    val metadataPurged: Boolean,
    val attachmentsPurged: Int,
    val annotationsPurged: Int
)

// --- PDF REPAIR DATA MODELS ---
data class DesktopRepairResult(
    val isSuccess: Boolean,
    val pagesRecovered: Int,
    val issuesRepaired: List<String>,
    val originalSize: Long,
    val repairedSize: Long
)

// --- MARGIN CROP DATA MODELS ---
data class DesktopCropConfig(
    val leftPt: Float,
    val topPt: Float,
    val rightPt: Float,
    val bottomPt: Float,
    val applyToAllPages: Boolean = true
)

// --- ATTACHMENT DATA MODELS ---
data class DesktopAttachment(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val description: String? = null
)

data class TextAnnotationItem(
    val text: String,
    val xRatio: Float,
    val yRatio: Float,
    val fontSize: Float = 14f,
    val colorHex: String = "#18181B"
)

enum class HeaderFooterPos {
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    TOP_CENTER,
    TOP_RIGHT
}

enum class RedactPattern(val regex: Regex, val label: String) {
    SSN(Regex("""\b\d{3}-\d{2}-\d{4}\b"""), "SSN"),
    CREDIT_CARD(Regex("""\b(?:\d[ -]*?){13,16}\b"""), "Credit Card"),
    EMAIL(Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,7}\b"""), "Email"),
    PHONE(Regex("""\b(?:\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b"""), "Phone Number")
}

data class PageItemSpec(
    val originalPageIndex: Int,
    val rotation: Int = 0
)

data class DesktopPdfMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val creator: String? = null,
    val producer: String? = null,
    val creationDate: String? = null,
    val modificationDate: String? = null,
    val hasXmpMetadata: Boolean = false
) {
    val hasAnyMetadata: Boolean
        get() = !title.isNullOrBlank() || !author.isNullOrBlank() || !subject.isNullOrBlank() ||
                !keywords.isNullOrBlank() || !creator.isNullOrBlank() || !producer.isNullOrBlank() ||
                !creationDate.isNullOrBlank() || !modificationDate.isNullOrBlank() || hasXmpMetadata
}



data class PageDimension(val widthPt: Float, val heightPt: Float) { val aspectRatio: Float get() = widthPt / heightPt }


object DesktopSpeechSynthesizer {
    private var currentProcess: Process? = null
    private val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
    private val isLinux = System.getProperty("os.name")?.lowercase()?.contains("linux") == true

    fun speak(text: String, rate: Int = 0, onFinished: () -> Unit = {}) {
        stop()
        val clean = text.trim()
        if (clean.isBlank()) {
            onFinished()
            return
        }
        Thread {
            try {
                if (isWindows) {
                    val textFile = com.pdfchemy.desktop.engine.DesktopStaging.createTempFile("pdfchemy_tts_text_", ".txt").apply {
                        deleteOnExit()
                        writeText(clean.take(6000), Charsets.UTF_8)
                    }
                    val tempScript = com.pdfchemy.desktop.engine.DesktopStaging.createTempFile("pdfchemy_tts_", ".ps1").apply {
                        deleteOnExit()
                        val scriptContent = """
                            Add-Type -AssemblyName System.Speech
                            ${'$'}text = Get-Content -Path '${textFile.absolutePath.replace("'", "''")}' -Raw -Encoding UTF8
                            ${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
                            ${'$'}synth.Rate = $rate
                            ${'$'}synth.Speak(${'$'}text)
                        """.trimIndent()
                        writeText(scriptContent, Charsets.UTF_8)
                    }
                    val pb = ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", tempScript.absolutePath)
                    currentProcess = pb.start()
                    currentProcess?.waitFor()
                    tempScript.delete()
                    textFile.delete()
                } else if (isLinux) {
                    val safeText = clean.take(6000)
                    val pb = ProcessBuilder("spd-say", "-r", (rate * 15).toString(), "--", safeText)
                    currentProcess = pb.start()
                    currentProcess?.waitFor()
                }
            } catch (_: Exception) {}
            finally {
                currentProcess = null
                onFinished()
            }
        }.start()
    }

    fun stop() {
        try {
            currentProcess?.destroyForcibly()
        } catch (_: Exception) {}
        currentProcess = null
    }

    fun isSpeaking(): Boolean = currentProcess?.isAlive == true
}


data class SearchMatchSnippet(
    val pageNumber: Int, // 1-indexed
    val lineSnippet: String,
    val matchStart: Int = 0,
    val matchEnd: Int = 0
)

data class FileSearchResult(
    val file: File,
    val totalMatches: Int,
    val snippets: List<SearchMatchSnippet>
)

data class DirectorySearchProgress(
    val filesScanned: Int,
    val totalFiles: Int,
    val results: List<FileSearchResult>,
    val isComplete: Boolean,
    val isCancelled: Boolean = false
)

enum class DesktopOfficeFormat(val displayName: String, val extension: String) {
    WORD("Microsoft Word (.docx)", "docx"),
    EXCEL("Microsoft Excel (.xlsx)", "xlsx"),
    POWERPOINT("Microsoft PowerPoint (.pptx)", "pptx")
}

data class DesktopOfficeExportReport(
    val format: DesktopOfficeFormat,
    val pageCount: Int,
    val outputSizeBytes: Long,
    val itemsExtracted: Int
)

