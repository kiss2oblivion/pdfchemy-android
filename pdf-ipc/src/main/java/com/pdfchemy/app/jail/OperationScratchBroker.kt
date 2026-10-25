package com.pdfchemy.app.jail

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.Closeable

/** Writes stay behind the host collector; only completed read-only files are returned. */
class OperationScratchBroker(
    context: Context,
    val storage: BoundedWorkerStorage = BoundedWorkerStorage(context)
) : IScratchBroker.Stub(), Closeable {
    override fun allocate(): ParcelFileDescriptor = storage.create(BoundedWorkerStorage.Kind.SCRATCH).writer
    override fun snapshot(capabilityId: Long): ParcelFileDescriptor = storage.snapshot(capabilityId)
    fun verifyBudget() = storage.verify(BoundedWorkerStorage.Kind.SCRATCH)
    override fun close() = storage.close()
}
