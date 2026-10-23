package com.pdfchemy.app.security

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.PickerOutcome
import com.pdfchemy.app.logic.SanitizerAuditReport
import com.pdfchemy.app.logic.VanguardPickerProcessor
import com.pdfchemy.app.logic.VanguardThreatResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VanguardPickerSecurityTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun mockUri(id: String) = Uri.parse("content://test.provider/document/$id.pdf")

    private fun reportWithJs() = SanitizerAuditReport(
        jsCount = 1,
        launchActionsCount = 0,
        otherActionsCount = 0,
        untrustedUriCount = 0
    )

    @Test
    fun testSinglePickerVanguardOffProviderErrorProducesProviderErrorNotBlockedThreat() = runTest {
        val released = mutableListOf<Uri>()
        val outcome = VanguardPickerProcessor.processSingle(
            context = context,
            originalUri = mockUri("broken"),
            isVanguardEnabled = false,
            stager = { throw IOException("Content provider cursor read failed") },
            releaser = { released.add(it) }
        )

        assertEquals("Provider error with Vanguard OFF must produce ProviderError", PickerOutcome.ProviderError, outcome)
        assertTrue("No URI was staged so none should be released", released.isEmpty())
    }

    @Test
    fun testSinglePickerVanguardOffCancellationRethrowsAndCleansUp() = runTest {
        val staged = mockUri("staged_1")
        val released = mutableListOf<Uri>()

        try {
            VanguardPickerProcessor.processSingle(
                context = context,
                originalUri = mockUri("doc1"),
                isVanguardEnabled = false,
                stager = {
                    released.add(staged)
                    throw CancellationException("User cancelled picker")
                },
                releaser = { released.add(it) }
            )
            fail("CancellationException must be rethrown")
        } catch (e: CancellationException) {
            assertEquals("User cancelled picker", e.message)
        }
    }

    @Test
    fun testSinglePickerVanguardOnProviderErrorProducesProviderErrorNotBlockedThreat() = runTest {
        val outcome = VanguardPickerProcessor.processSingle(
            context = context,
            originalUri = mockUri("fail"),
            isVanguardEnabled = true,
            stager = { throw IOException("Permission denied") }
        )

        assertEquals("Provider error with Vanguard ON must produce ProviderError", PickerOutcome.ProviderError, outcome)
    }

    @Test
    fun testSinglePickerVanguardOnCancellationRethrows() = runTest {
        try {
            VanguardPickerProcessor.processSingle(
                context = context,
                originalUri = mockUri("doc1"),
                isVanguardEnabled = true,
                stager = { throw CancellationException("Cancelled") }
            )
            fail("CancellationException must be rethrown")
        } catch (e: CancellationException) {
            assertEquals("Cancelled", e.message)
        }
    }

    @Test
    fun testSinglePickerVanguardOnExecutableThreatReleasesStagedUriAndReturnsBlockedThreat() = runTest {
        val stagedUri = mockUri("staged_malicious")
        val released = mutableListOf<Uri>()

        val outcome = VanguardPickerProcessor.processSingle(
            context = context,
            originalUri = mockUri("malicious"),
            isVanguardEnabled = true,
            stager = { stagedUri },
            threatChecker = { VanguardThreatResult.ExecutableThreat(reportWithJs()) },
            releaser = { released.add(it) }
        )

        assertTrue(outcome is PickerOutcome.BlockedThreat)
        assertEquals(R.string.vanguard_blocked_message_js, (outcome as PickerOutcome.BlockedThreat).reasonResId)
        assertEquals("Malicious staged document must be released immediately", listOf(stagedUri), released)
    }

    @Test
    fun testSinglePickerVanguardOnCleanReturnsSelected() = runTest {
        val stagedUri = mockUri("staged_clean")
        val outcome = VanguardPickerProcessor.processSingle(
            context = context,
            originalUri = mockUri("clean"),
            isVanguardEnabled = true,
            stager = { stagedUri },
            threatChecker = { VanguardThreatResult.Clean }
        )

        assertEquals(PickerOutcome.Selected(listOf(stagedUri)), outcome)
    }

    @Test
    fun testMultiPickerVanguardOffProviderErrorOnSubsequentItemReleasesPriorItemsAndReturnsProviderError() = runTest {
        val released = mutableListOf<Uri>()
        val staged1 = mockUri("staged_1")

        val outcome = VanguardPickerProcessor.processMultiple(
            context = context,
            uris = listOf(mockUri("doc1"), mockUri("doc2")),
            isVanguardEnabled = false,
            stager = { uri ->
                if (uri.toString().contains("doc1")) staged1 else throw IOException("Provider stream died")
            },
            releaser = { released.add(it) }
        )

        assertEquals("Partial batch failure with Vanguard OFF must return ProviderError", PickerOutcome.ProviderError, outcome)
        assertEquals("Prior staged item 1 must be cleaned up on failure", listOf(staged1), released)
    }

    @Test
    fun testMultiPickerVanguardOffCancellationOnSubsequentItemReleasesPriorItemsAndRethrows() = runTest {
        val released = mutableListOf<Uri>()
        val staged1 = mockUri("staged_1")

        try {
            VanguardPickerProcessor.processMultiple(
                context = context,
                uris = listOf(mockUri("doc1"), mockUri("doc2")),
                isVanguardEnabled = false,
                stager = { uri ->
                    if (uri.toString().contains("doc1")) staged1 else throw CancellationException("Picker cancelled")
                },
                releaser = { released.add(it) }
            )
            fail("CancellationException must be rethrown")
        } catch (e: CancellationException) {
            assertEquals("Picker cancelled", e.message)
        }

        assertEquals("Prior staged item 1 must be cleaned up on cancellation", listOf(staged1), released)
    }

    @Test
    fun testMultiPickerVanguardOnThreatOnSubsequentItemReleasesAllAndReturnsBlockedThreat() = runTest {
        val released = mutableListOf<Uri>()
        val staged1 = mockUri("staged_1")
        val staged2 = mockUri("staged_2")

        val outcome = VanguardPickerProcessor.processMultiple(
            context = context,
            uris = listOf(mockUri("doc1"), mockUri("doc2")),
            isVanguardEnabled = true,
            stager = { uri -> if (uri.toString().contains("doc1")) staged1 else staged2 },
            threatChecker = { uri ->
                if (uri == staged1) VanguardThreatResult.Clean else VanguardThreatResult.ExecutableThreat(reportWithJs())
            },
            releaser = { released.add(it) }
        )

        assertTrue(outcome is PickerOutcome.BlockedThreat)
        assertEquals(R.string.vanguard_blocked_message_js, (outcome as PickerOutcome.BlockedThreat).reasonResId)
        assertEquals("Both staged items must be released", listOf(staged1, staged2), released)
    }

    @Test
    fun testMultiPickerVanguardOnEncryptedItemPreservesEncryptedUriForUnlockAndReleasesOthers() = runTest {
        val released = mutableListOf<Uri>()
        val staged1 = mockUri("staged_1")
        val staged2 = mockUri("staged_2")

        val outcome = VanguardPickerProcessor.processMultiple(
            context = context,
            uris = listOf(mockUri("doc1"), mockUri("doc2")),
            isVanguardEnabled = true,
            stager = { uri -> if (uri.toString().contains("doc1")) staged1 else staged2 },
            threatChecker = { uri ->
                if (uri == staged1) VanguardThreatResult.Clean else VanguardThreatResult.EncryptedCannotVerify(uri)
            },
            releaser = { released.add(it) }
        )

        assertTrue(outcome is PickerOutcome.Encrypted)
        assertEquals(staged2, (outcome as PickerOutcome.Encrypted).stagedUri)
        assertEquals("Only staged1 must be released; staged2 preserved for unlock navigation", listOf(staged1), released)
    }

    @Test
    fun testMultiPickerVanguardOnAllCleanReturnsAllStagedUris() = runTest {
        val staged1 = mockUri("staged_1")
        val staged2 = mockUri("staged_2")
        val released = mutableListOf<Uri>()

        val outcome = VanguardPickerProcessor.processMultiple(
            context = context,
            uris = listOf(mockUri("doc1"), mockUri("doc2")),
            isVanguardEnabled = true,
            stager = { uri -> if (uri.toString().contains("doc1")) staged1 else staged2 },
            threatChecker = { VanguardThreatResult.Clean },
            releaser = { released.add(it) }
        )

        assertEquals(PickerOutcome.Selected(listOf(staged1, staged2)), outcome)
        assertTrue("No URIs should be released when all clean", released.isEmpty())
    }
}
