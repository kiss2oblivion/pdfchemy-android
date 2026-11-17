package com.pdfchemy.app.security

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FreshOutputOwnershipTest {
    @Test fun transferPreservesExclusivityAndRetiresOnlyAtPublication() {
        val uri = Uri.parse("content://fixture/transfer.pdf")
        val operation = Any()
        val publisher = Any()
        FreshOutputRegistry.register(uri)
        FreshOutputRegistry.claimKnown(listOf(uri), operation)
        try {
            FreshOutputRegistry.transferKnown(listOf(uri), operation, publisher)
            assertTrue(FreshOutputRegistry.isClaimed(uri))
            assertFalse(FreshOutputRegistry.deleteIfFresh(uri) { fail("Claimed output was deleted"); true })
            assertTrue(runCatching { FreshOutputRegistry.claimKnown(listOf(uri), Any()) }.isFailure)
            FreshOutputRegistry.published(listOf(uri), operation)
            assertTrue("Old owner must not retire the publisher's claim", FreshOutputRegistry.contains(uri))
            FreshOutputRegistry.published(listOf(uri), publisher)
            assertFalse(FreshOutputRegistry.contains(uri))
        } finally {
            FreshOutputRegistry.published(listOf(uri), publisher)
            FreshOutputRegistry.published(listOf(uri), operation)
        }
    }

    @Test fun invalidBatchTransferLeavesEveryOriginalClaimIntact() {
        val first = Uri.parse("content://fixture/first.pdf")
        val second = Uri.parse("content://fixture/second.pdf")
        val owner = Any()
        val other = Any()
        listOf(first, second).forEach(FreshOutputRegistry::register)
        FreshOutputRegistry.claimKnown(listOf(first), owner)
        FreshOutputRegistry.claimKnown(listOf(second), other)
        try {
            assertTrue(runCatching { FreshOutputRegistry.transferKnown(listOf(first, second), owner, Any()) }.isFailure)
            FreshOutputRegistry.requireSafeSafTargets(listOf(first), owner)
            FreshOutputRegistry.requireSafeSafTargets(listOf(second), other)
        } finally {
            FreshOutputRegistry.published(listOf(first), owner)
            FreshOutputRegistry.published(listOf(second), other)
        }
    }
}
