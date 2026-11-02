package com.pdfchemy.app.jail

import com.pdfchemy.app.jail.BoundedWorkerStorage.Kind
import com.pdfchemy.app.security.SecurityLimits
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WorkerStorageQuotaTest {
    @Test fun outputLimitAggregatesWritesWithoutConsumingRejectedReservation() {
        val quota = WorkerStorageQuota(8, 20, 28)
        quota.reserve(Kind.OUTPUT, 5)
        assertThrows(IOException::class.java) { quota.reserve(Kind.OUTPUT, 4) }
        assertEquals(5, quota.totalBytes)
        quota.reserve(Kind.OUTPUT, 3)
        assertEquals(8, quota.totalBytes)
    }

    @Test fun scratchLimitIsIndependentOfOutputLimit() {
        val quota = WorkerStorageQuota(8, 4, 12)
        quota.reserve(Kind.SCRATCH, 4)
        assertThrows(IOException::class.java) { quota.reserve(Kind.SCRATCH, 1) }
        quota.reserve(Kind.OUTPUT, 8)
        assertEquals(12, quota.totalBytes)
    }

    @Test fun totalLimitIncludesBothKindsAndRejectsBeforeAddition() {
        val quota = WorkerStorageQuota(8, 8, 10)
        quota.reserve(Kind.OUTPUT, 6)
        quota.reserve(Kind.SCRATCH, 4)
        assertThrows(IOException::class.java) { quota.reserve(Kind.SCRATCH, 1) }
        assertEquals(10, quota.totalBytes)
    }

    @Test fun oversizedAndNegativeCountsCannotOverflowAccounting() {
        val quota = WorkerStorageQuota(8, 8, 10)
        assertThrows(IOException::class.java) { quota.reserve(Kind.OUTPUT, Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { quota.reserve(Kind.SCRATCH, -1) }
        assertEquals(0, quota.totalBytes)
    }

    @Test fun callerCannotRaiseProductionLimits() {
        assertThrows(IllegalArgumentException::class.java) { WorkerStorageQuota(SecurityLimits.MAX_OUTPUT_BYTES + 1, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { WorkerStorageQuota(1, SecurityLimits.MAX_OUTPUT_BYTES + 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { WorkerStorageQuota(1, 1, SecurityLimits.MAX_OPERATION_WRITE_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { WorkerStorageQuota(0, 1, 1) }
    }

    @Test fun allActiveCollectorsShareOneByteCeiling() {
        ActiveWorkerStorageQuota.reserveBytes(SecurityLimits.MAX_OPERATION_WRITE_BYTES)
        try {
            assertThrows(IOException::class.java) { ActiveWorkerStorageQuota.reserveBytes(1) }
        } finally { ActiveWorkerStorageQuota.release(SecurityLimits.MAX_OPERATION_WRITE_BYTES, 0) }
    }

    @Test fun allActiveCollectorsShareOneDescriptorCeiling() {
        val limit = SecurityLimits.MAX_SCRATCH_FDS + SecurityLimits.MAX_BATCH_FDS
        repeat(limit) { ActiveWorkerStorageQuota.reserveFile() }
        try { assertThrows(IOException::class.java) { ActiveWorkerStorageQuota.reserveFile() } }
        finally { ActiveWorkerStorageQuota.release(0, limit) }
        ActiveWorkerStorageQuota.reserveFile()
        ActiveWorkerStorageQuota.release(0, 1)
    }
}
