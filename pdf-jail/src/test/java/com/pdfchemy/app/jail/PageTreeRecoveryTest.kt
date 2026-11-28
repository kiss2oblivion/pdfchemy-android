package com.pdfchemy.app.jail

import com.pdfchemy.app.jail.engines.PageTreeRecovery
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.*
import org.junit.Test

class PageTreeRecoveryTest {
    private fun root(document: PDDocument) =
        document.documentCatalog.cosObject.getDictionaryObject(COSName.PAGES) as COSDictionary

    private fun reject(block: () -> Unit) {
        try { block(); fail("Malformed page hierarchy accepted") }
        catch (_: IllegalArgumentException) {}
        catch (_: IllegalStateException) {}
    }

    @Test fun wrongCountCorrectedFromActualChildren() {
        PDDocument().use { document ->
            repeat(2) { document.addPage(PDPage()) }
            val root = root(document)
            root.setInt(COSName.COUNT, 99)
            reject { PageTreeRecovery.inspect(document) }
            assertEquals(99, root.getInt(COSName.COUNT))
            val result = PageTreeRecovery.inspect(document, repairCounts = true)
            assertEquals(2, result.pages)
            assertEquals(1, result.correctedCounts)
            assertEquals(2, document.numberOfPages)
            assertEquals(0, PageTreeRecovery.inspect(document).correctedCounts)
        }
    }

    @Test fun missingCountIsRestoredButChildOrderIsUnchanged() {
        PDDocument().use { document ->
            repeat(3) { document.addPage(PDPage()) }
            val root = root(document)
            val children = root.getDictionaryObject(COSName.KIDS) as COSArray
            val before = (0 until children.size()).map { children.get(it) }
            root.removeItem(COSName.COUNT)
            assertEquals(3, PageTreeRecovery.inspect(document, true).pages)
            before.forEachIndexed { index, child -> assertSame(child, children.get(index)) }
        }
    }

    @Test fun rejectsDuplicatePagesWithoutApplyingCountCorrections() {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val root = root(document)
            val kids = root.getDictionaryObject(COSName.KIDS) as COSArray
            kids.add(kids.get(0))
            root.setInt(COSName.COUNT, 7)
            reject { PageTreeRecovery.inspect(document, true) }
            assertEquals(7, root.getInt(COSName.COUNT))
        }
    }

    @Test fun rejectsConflictingParentRatherThanGuessingInheritance() {
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            page.cosObject.setItem(COSName.PARENT, COSDictionary())
            reject { PageTreeRecovery.inspect(document, true) }
        }
    }

    @Test fun rejectsCycleAndInvalidChild() {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val root = root(document)
            val kids = root.getDictionaryObject(COSName.KIDS) as COSArray
            kids.add(root)
            reject { PageTreeRecovery.inspect(document, true) }
            kids.remove(kids.size() - 1)
            kids.add(COSNull.NULL)
            reject { PageTreeRecovery.inspect(document, true) }
        }
    }

    @Test fun nestedBranchCountsCorrectedBottomUp() {
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            val root = root(document)
            val originalKids = root.getDictionaryObject(COSName.KIDS) as COSArray
            val branch = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.PAGES)
                setItem(COSName.PARENT, root)
                setItem(COSName.KIDS, originalKids)
                setInt(COSName.COUNT, 50)
            }
            page.cosObject.setItem(COSName.PARENT, branch)
            root.setItem(COSName.KIDS, COSArray().apply { add(branch) })
            root.setInt(COSName.COUNT, 100)
            val result = PageTreeRecovery.inspect(document, true)
            assertEquals(1, result.pages)
            assertEquals(2, result.correctedCounts)
            assertEquals(1, branch.getInt(COSName.COUNT))
            assertEquals(1, root.getInt(COSName.COUNT))
        }
    }
}
