package com.pdfchemy.app.logic.audio

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic

/** Service fixtures supply regular files. Robolectric 4.11.1 loses their type in fstat(fd).
 * Stub that metadata per test; replacing the cached Linux shadow causes order-dependent casts.
 * Real kernel pipes remain covered by instrumentation tests.
 */
object AudioRegularFileMetadata {
    fun install() {
        mockkStatic(Os::class)
        every { Os.fstat(any()) } returns
            StructStat(0L, 0L, OsConstants.S_IFREG, 0L, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
    }

    fun clear() = unmockkStatic(Os::class)
}
