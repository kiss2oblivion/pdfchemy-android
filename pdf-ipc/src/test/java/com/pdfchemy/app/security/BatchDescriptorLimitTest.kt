package com.pdfchemy.app.security

import org.junit.Assert.*
import org.junit.Test

/** The release contract is 16 inputs and 16 outputs per batch, independently. */
class BatchDescriptorLimitTest {
    private val hash = "a".repeat(64)
    private fun rejected(action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue("Expected a client-visible argument error, got " + error, error is IllegalArgumentException)
    }

    @Test fun batchDescriptorContractIsSixteen() {
        assertEquals(16, SecurityLimits.MAX_BATCH_FDS)
    }

    @Test fun sixteenDescriptorsRetainEveryIdentity() {
        SecurityLimits.requireBatch(16, 16, Array(16) { hash }, LongArray(16) { 1L })
    }

    @Test fun seventeenthSourceIsRejected() {
        rejected { SecurityLimits.requireBatch(17, 1, Array(17) { hash }, LongArray(17) { 1L }) }
    }

    @Test fun seventeenthTargetIsRejected() {
        rejected { SecurityLimits.requireBatch(1, 17, arrayOf(hash), longArrayOf(1L)) }
    }

    @Test fun maximumBatchStillRequiresExactIdentityMetadata() {
        rejected { SecurityLimits.requireBatch(16, 16, Array(15) { hash }, LongArray(16) { 1L }) }
        rejected { SecurityLimits.requireBatch(16, 16, Array(16) { hash }, LongArray(15) { 1L }) }
        rejected { SecurityLimits.requireBatch(16, 16, Array(16) { hash }, LongArray(16) { if (it == 15) 0 else 1 }) }
    }

    @Test fun maximumBatchRetainsAggregateInputQuota() {
        rejected {
            SecurityLimits.requireBatch(16, 1, Array(16) { hash },
                LongArray(16) { SecurityLimits.MAX_BATCH_INPUT_BYTES / 16 + 1 })
        }
    }
}
