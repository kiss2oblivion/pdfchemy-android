package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.R
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outcome of picking and staging one or more PDF documents (FOLLOW-02 / RA-03).
 */
sealed class PickerOutcome {
    data class Selected(val stagedUris: List<Uri>) : PickerOutcome()
    data class BlockedThreat(val reasonResId: Int) : PickerOutcome()
    data class Encrypted(val stagedUri: Uri) : PickerOutcome()
    object Damaged : PickerOutcome()
    object ProviderError : PickerOutcome()
    object Cancelled : PickerOutcome()
}

/**
 * Pure, testable processing logic for single and multiple document picker staging
 * and Vanguard Zero-Trust threat evaluation.
 *
 * Invariants (RA-03):
 * 1. Provider/I/O errors when Vanguard is disabled MUST NEVER produce [PickerOutcome.BlockedThreat].
 *    They must always result in [PickerOutcome.ProviderError].
 * 2. Cancellation during single or multi picking MUST NEVER produce [PickerOutcome.BlockedThreat]
 *    or [PickerOutcome.ProviderError]. Any staged temporary files must be cleaned up immediately,
 *    and [CancellationException] rethrown.
 * 3. In multi picking, if any item fails or is cancelled, all previously staged items in the
 *    batch (except unverified encrypted items kept for unlock) are released immediately.
 */
object VanguardPickerProcessor {

    fun resolveBlockedReasonResId(report: SanitizerAuditReport): Int {
        return when {
            report.jsCount > 0 -> R.string.vanguard_blocked_message_js
            report.launchActionsCount > 0 -> R.string.vanguard_blocked_message_launch
            report.otherActionsCount > 0 -> R.string.vanguard_blocked_message_other
            report.untrustedUriCount > 0 -> R.string.vanguard_blocked_message_uri
            else -> R.string.vanguard_blocked_message_default
        }
    }

    suspend fun processSingle(
        context: Context,
        originalUri: Uri,
        isVanguardEnabled: Boolean,
        stager: suspend (Uri) -> Uri = { uri ->
            withContext(Dispatchers.IO) {
                DocumentStager.stageDocumentCancellable(context, uri).uri
            }
        },
        threatChecker: suspend (Uri) -> VanguardThreatResult = { stagedUri ->
            PdfSanitizerEngine.checkVanguardThreat(context, stagedUri)
        },
        releaser: (Uri) -> Unit = { DocumentStager.release(it) }
    ): PickerOutcome {
        var stagedUri: Uri? = null
        return try {
            val staged = stager(originalUri)
            stagedUri = staged

            if (isVanguardEnabled) {
                when (val threat = threatChecker(staged)) {
                    is VanguardThreatResult.Clean -> {
                        PickerOutcome.Selected(listOf(staged))
                    }
                    is VanguardThreatResult.EncryptedCannotVerify -> {
                        PickerOutcome.Encrypted(staged)
                    }
                    is VanguardThreatResult.ExecutableThreat -> {
                        releaser(staged)
                        PickerOutcome.BlockedThreat(resolveBlockedReasonResId(threat.report))
                    }
                    is VanguardThreatResult.ParseFailed -> {
                        releaser(staged)
                        PickerOutcome.Damaged
                    }
                }
            } else {
                PickerOutcome.Selected(listOf(staged))
            }
        } catch (cancelled: CancellationException) {
            stagedUri?.let(releaser)
            throw cancelled
        } catch (_: Exception) {
            stagedUri?.let(releaser)
            PickerOutcome.ProviderError
        }
    }

    suspend fun processMultiple(
        context: Context,
        uris: List<Uri>,
        isVanguardEnabled: Boolean,
        stager: suspend (Uri) -> Uri = { uri ->
            withContext(Dispatchers.IO) {
                DocumentStager.stageDocumentCancellable(context, uri).uri
            }
        },
        threatChecker: suspend (Uri) -> VanguardThreatResult = { stagedUri ->
            PdfSanitizerEngine.checkVanguardThreat(context, stagedUri)
        },
        releaser: (Uri) -> Unit = { DocumentStager.release(it) },
        onItemScanning: ((Uri) -> Unit)? = null
    ): PickerOutcome {
        if (uris.isEmpty()) return PickerOutcome.Selected(emptyList())

        val stagedUris = mutableListOf<Uri>()
        var encryptedUri: Uri? = null

        return try {
            for (uri in uris) {
                onItemScanning?.invoke(uri)
                val staged = stager(uri)
                stagedUris.add(staged)

                if (isVanguardEnabled) {
                    when (val threat = threatChecker(staged)) {
                        is VanguardThreatResult.Clean -> {
                            // continue batch
                        }
                        is VanguardThreatResult.EncryptedCannotVerify -> {
                            encryptedUri = staged
                            // Release all staged files except the encrypted one kept for unlock navigation
                            stagedUris.filter { it != encryptedUri }.forEach(releaser)
                            return PickerOutcome.Encrypted(staged)
                        }
                        is VanguardThreatResult.ExecutableThreat -> {
                            stagedUris.forEach(releaser)
                            return PickerOutcome.BlockedThreat(resolveBlockedReasonResId(threat.report))
                        }
                        is VanguardThreatResult.ParseFailed -> {
                            stagedUris.forEach(releaser)
                            return PickerOutcome.Damaged
                        }
                    }
                }
            }
            PickerOutcome.Selected(stagedUris)
        } catch (cancelled: CancellationException) {
            stagedUris.forEach(releaser)
            throw cancelled
        } catch (_: Exception) {
            stagedUris.forEach(releaser)
            PickerOutcome.ProviderError
        }
    }
}
