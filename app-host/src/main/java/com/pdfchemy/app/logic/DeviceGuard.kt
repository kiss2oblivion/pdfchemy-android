package com.pdfchemy.app.logic

import android.app.ActivityManager
import android.content.Context
import com.pdfchemy.app.utils.AppLogger
import java.text.DecimalFormat

/**
 * Intelligent hardware & memory hierarchy guardian.
 * Assesses device memory, CPU capacity, and runtime heap before and during heavy tasks
 * to prevent OS freezes, GC thrashing, and OutOfMemoryError crashes.
 */
object DeviceGuard {

    enum class CapacityStatus {
        SAFE,
        CAUTION_CAN_THROTTLE,
        INSUFFICIENT_HARDWARE
    }

    enum class AlternativeAction {
        SPLIT_FIRST,
        PAGE_RANGE,
        LIGHTWEIGHT_MODE,
        FREE_RAM
    }

    data class CapacityAssessment(
        val status: CapacityStatus,
        val pageCount: Int,
        val fileSizeBytes: Long,
        val availableMemMb: Int,
        val requiredMemMb: Int,
        val recommendedAlternative: AlternativeAction,
        val canProceedAnyway: Boolean = true
    )

    /**
     * Inspects both system-level RAM and the app's current JVM heap headroom.
     */
    fun getAvailableMemoryMb(context: Context): Int {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)

            val runtime = Runtime.getRuntime()
            val maxHeap = runtime.maxMemory() // Max heap allocated to process
            val totalHeap = runtime.totalMemory()
            val freeHeap = runtime.freeMemory()
            val usedHeap = totalHeap - freeHeap
            val availableHeapBytes = (maxHeap - usedHeap).coerceAtLeast(0)

            val systemAvailBytes = memInfo.availMem
            val effectiveSafeBytes = if (systemAvailBytes > 0L) {
                minOf(availableHeapBytes, systemAvailBytes)
            } else {
                availableHeapBytes
            }
            val resultMb = (effectiveSafeBytes / (1024 * 1024)).toInt()
            if (resultMb > 0) resultMb else 128
        } catch (e: Exception) {
            AppLogger.e("DeviceGuard: Failed to inspect memory: ${e.message}", e)
            128 // Safe fallback
        }
    }

    /**
     * Real-time in-flight check to see if available memory has dropped into the danger zone (< 25MB).
     */
    fun isMemoryCritical(context: Context): Boolean {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)

            if (memInfo.lowMemory) return true

            val runtime = Runtime.getRuntime()
            val maxHeap = runtime.maxMemory()
            val totalHeap = runtime.totalMemory()
            val freeHeap = runtime.freeMemory()
            val heapRemainingBytes = maxHeap - (totalHeap - freeHeap)

            heapRemainingBytes < (25 * 1024 * 1024)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pre-flight assessment before executing a heavy document operation
     * (compression, OCR, merge, page organization, or image conversion).
     */
    fun assessTask(
        context: Context,
        pageCount: Int,
        fileSizeBytes: Long,
        isImageHeavy: Boolean = false
    ): CapacityAssessment {
        val availableMb = getAvailableMemoryMb(context)

        // Estimated working RAM:
        // 1. Base PDF tree parsing overhead (typically 2.5x - 4x file size in heap)
        val fileMb = (fileSizeBytes / (1024 * 1024)).coerceAtLeast(1)
        val baseEngineOverheadMb = (fileMb * 2.5f).toInt().coerceIn(15, 250)

        // 2. Working bitmap / raster memory per active page buffer
        val perPageCostMb = if (isImageHeavy) 8 else 3
        // Working buffer usually holds at least 3-4 concurrent pages in pipeline
        val pipelinePages = pageCount.coerceAtMost(6)
        val workingPageOverheadMb = pipelinePages * perPageCostMb

        val totalEstimatedRequiredMb = baseEngineOverheadMb + workingPageOverheadMb

        return when {
            // Extreme scenario: Document size & pages would overwhelm available RAM completely
            availableMb < 35 || (totalEstimatedRequiredMb > (availableMb * 2.5f) && pageCount > 80) -> {
                val alternative = if (pageCount > 40) {
                    AlternativeAction.SPLIT_FIRST
                } else if (pageCount > 15) {
                    AlternativeAction.PAGE_RANGE
                } else {
                    AlternativeAction.FREE_RAM
                }
                CapacityAssessment(
                    status = CapacityStatus.INSUFFICIENT_HARDWARE,
                    pageCount = pageCount,
                    fileSizeBytes = fileSizeBytes,
                    availableMemMb = availableMb,
                    requiredMemMb = totalEstimatedRequiredMb,
                    recommendedAlternative = alternative,
                    canProceedAnyway = false
                )
            }

            // Caution scenario: Document is large or memory is moderate. Safe streaming mode recommended.
            availableMb < 75 || totalEstimatedRequiredMb > (availableMb * 0.85f) || pageCount > 50 -> {
                val alternative = if (pageCount > 60) {
                    AlternativeAction.SPLIT_FIRST
                } else {
                    AlternativeAction.LIGHTWEIGHT_MODE
                }
                CapacityAssessment(
                    status = CapacityStatus.CAUTION_CAN_THROTTLE,
                    pageCount = pageCount,
                    fileSizeBytes = fileSizeBytes,
                    availableMemMb = availableMb,
                    requiredMemMb = totalEstimatedRequiredMb,
                    recommendedAlternative = alternative,
                    canProceedAnyway = true
                )
            }

            // Safe scenario: Ample RAM headroom
            else -> {
                CapacityAssessment(
                    status = CapacityStatus.SAFE,
                    pageCount = pageCount,
                    fileSizeBytes = fileSizeBytes,
                    availableMemMb = availableMb,
                    requiredMemMb = totalEstimatedRequiredMb,
                    recommendedAlternative = AlternativeAction.LIGHTWEIGHT_MODE,
                    canProceedAnyway = true
                )
            }
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val df = DecimalFormat("#,##0.#")
        return "${df.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }
}
