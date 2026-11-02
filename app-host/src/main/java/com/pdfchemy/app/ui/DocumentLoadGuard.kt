package com.pdfchemy.app.ui

import kotlinx.coroutines.CancellationException

/** Provider, quota and worker failures must return to the existing error UI. */
internal suspend inline fun guardDocumentLoad(onFailure: () -> Unit, block: () -> Unit) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { onFailure() }
}

internal suspend inline fun guardDocumentLoadWithCause(onFailure: (Exception) -> Unit, block: () -> Unit) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (e: Exception) { onFailure(e) }
}
