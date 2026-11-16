package com.pdfchemy.app.security

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.PickerOutcome
import com.pdfchemy.app.logic.VanguardPickerProcessor
import com.pdfchemy.app.logic.VanguardThreatResult
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PickerBatchRetentionTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val sources = mutableListOf<File>()
    @After fun cleanup() {
        DocumentStager.releaseAll()
        sources.forEach { it.delete() }
    }
    private fun source(index: Int): Uri {
        val file = File.createTempFile("picker_input_$index", ".pdf", context.cacheDir)
        file.writeText("%PDF-1.4 fixture $index")
        sources.add(file)
        return Uri.fromFile(file)
    }
    @Test fun fiveSelectedDocumentsRemainReadableWithVanguardOff() = checkBatch(5, false)
    @Test fun tenSelectedDocumentsRemainReadableWithVanguardOn() = checkBatch(10, true)
    private fun checkBatch(count: Int, enabled: Boolean) = runBlocking {
        val outcome = VanguardPickerProcessor.processMultiple(context, (1..count).map(::source), enabled,
            threatChecker = { VanguardThreatResult.Clean }) as PickerOutcome.Selected
        outcome.use {
            assertEquals(count, outcome.stagedUris.size)
            outcome.stagedUris.forEachIndexed { index, uri ->
                assertTrue("Selected input $index was evicted before handoff", File(uri.path!!).exists())
                assertEquals("%PDF-1.4 fixture ${index+1}", File(uri.path!!).readText())
            }
        }
        assertEquals(0, DocumentStager.stagedCount)
    }
    @Test fun smallBatchesStillWork() { checkBatch(1, true); checkBatch(4, false) }

    @Test fun consumerAndQueuedWorkerSurvivePickerCloseAndEviction() = runBlocking {
        val outcome = VanguardPickerProcessor.processMultiple(context, (1..5).map(::source), false) as PickerOutcome.Selected
        val screen = com.pdfchemy.app.logic.StagedSelectionOwner()
        val operation = com.pdfchemy.app.logic.StagedSelectionOwner()
        try {
            screen.retainOnly(outcome.stagedUris)
            outcome.close()
            operation.retainOnly(outcome.stagedUris)
            screen.close()
            repeat(10) { DocumentStager.stageStream(context, "noise $it".byteInputStream()) }
            outcome.stagedUris.forEach { assertTrue(File(it.path!!).exists()) }
            operation.close()
            outcome.stagedUris.forEach { assertFalse(File(it.path!!).exists()) }
            operation.close()
        } finally { outcome.close(); screen.close(); operation.close() }
    }

    @Test fun removingOneSelectionReleasesOnlyThatInput() = runBlocking {
        val outcome = VanguardPickerProcessor.processMultiple(context, (1..5).map(::source), false) as PickerOutcome.Selected
        val screen = com.pdfchemy.app.logic.StagedSelectionOwner()
        try {
            screen.retainOnly(outcome.stagedUris)
            outcome.close()
            screen.retainOnly(outcome.stagedUris.drop(1))
            assertFalse(File(outcome.stagedUris.first().path!!).exists())
            outcome.stagedUris.drop(1).forEach { assertTrue(File(it.path!!).exists()) }
        } finally { outcome.close(); screen.close() }
        assertEquals(0, DocumentStager.stagedCount)
    }

    @Test fun rejectedBatchReleasesAllLeases() = runBlocking {
        var scanned = 0
        val outcome = VanguardPickerProcessor.processMultiple(context, (1..6).map(::source), true,
            threatChecker = { if (++scanned == 6) VanguardThreatResult.ParseFailed(it) else VanguardThreatResult.Clean })
        assertEquals(PickerOutcome.Damaged, outcome)
        assertEquals(0, DocumentStager.stagedCount)
    }

    @Test fun cancelledBatchReleasesAllLeases() = runBlocking {
        var scanned = 0
        try {
            VanguardPickerProcessor.processMultiple(context, (1..6).map(::source), false,
                onItemScanning = { if (++scanned == 6) throw kotlinx.coroutines.CancellationException("cancelled") })
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(0, DocumentStager.stagedCount)
    }

    @Test fun encryptedBatchTransfersOnlyEncryptedInput() = runBlocking {
        var scanned = 0
        val outcome = VanguardPickerProcessor.processMultiple(context, (1..6).map(::source), true,
            threatChecker = { if (++scanned == 6) VanguardThreatResult.EncryptedCannotVerify(it) else VanguardThreatResult.Clean }) as PickerOutcome.Encrypted
        try {
            assertEquals(1, DocumentStager.stagedCount)
            assertEquals(1, DocumentStager.activeLeases(outcome.stagedUri))
            assertTrue(File(outcome.stagedUri.path!!).exists())
        } finally { outcome.close() }
        assertEquals(0, DocumentStager.stagedCount)
    }

}
