package com.pdfchemy.app.logic

import kotlin.math.ceil

data class BookletSheetPlan(
    val sheetNumber: Int,
    val isFront: Boolean,
    val leftPageOriginalIndex: Int?,  // null means blank padding page
    val rightPageOriginalIndex: Int?  // null means blank padding page
)

object BookletPlanner {
    /**
     * Computes the saddle-stitch booklet imposition plan for a document with originalPageCount pages.
     */
    fun computeBookletPlan(originalPageCount: Int): List<BookletSheetPlan> {
        if (originalPageCount <= 0) return emptyList()

        val paddedTotal = ceil(originalPageCount / 4.0).toInt() * 4
        val sheetCount = paddedTotal / 4
        val plan = mutableListOf<BookletSheetPlan>()

        for (k in 0 until sheetCount) {
            // Sheet k Front (Outside fold)
            val frontLeft = paddedTotal - 1 - (2 * k)
            val frontRight = 2 * k
            plan.add(
                BookletSheetPlan(
                    sheetNumber = k + 1,
                    isFront = true,
                    leftPageOriginalIndex = if (frontLeft < originalPageCount) frontLeft else null,
                    rightPageOriginalIndex = if (frontRight < originalPageCount) frontRight else null
                )
            )

            // Sheet k Back (Inside fold)
            val backLeft = (2 * k) + 1
            val backRight = paddedTotal - 1 - ((2 * k) + 1)
            plan.add(
                BookletSheetPlan(
                    sheetNumber = k + 1,
                    isFront = false,
                    leftPageOriginalIndex = if (backLeft < originalPageCount) backLeft else null,
                    rightPageOriginalIndex = if (backRight < originalPageCount) backRight else null
                )
            )
        }

        return plan
    }
}
