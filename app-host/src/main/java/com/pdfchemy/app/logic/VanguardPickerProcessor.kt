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
sealed class PickerOutcome : java.io.Closeable {
    internal var owner: java.io.Closeable? = null
    override fun close() { owner?.close(); owner = null }
    data class Selected(val stagedUris: List<Uri>) : PickerOutcome()
    data class BlockedThreat(val reasonResId: Int) : PickerOutcome()
    data class Encrypted(val stagedUri: Uri) : PickerOutcome()
    data class Damaged(val failure: VanguardInspectionFailure = VanguardInspectionFailure.UNKNOWN) : PickerOutcome()
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
                        PickerOutcome.Damaged(threat.failure)
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
        stager: (suspend (Uri) -> Uri)? = null,
        threatChecker: suspend (Uri) -> VanguardThreatResult = { stagedUri ->
            PdfSanitizerEngine.checkVanguardThreat(context, stagedUri)
        },
        releaser: (Uri) -> Unit = { DocumentStager.release(it) },
        onItemScanning: (suspend (Uri) -> Unit)? = null
    ): PickerOutcome {
        if (uris.isEmpty()) return PickerOutcome.Selected(emptyList())

        val stagedUris = mutableListOf<Uri>()
        val owner = StagedSelectionOwner(releaser)

        return try {
            for (uri in uris) {
                onItemScanning?.invoke(uri)
                val staged = if (stager != null) {
                    stager(uri).also { owner.adopt(it, java.io.Closeable {}) }
                } else {
                    val snapshot = DocumentStager.stageDocumentCancellationSafe(context, uri)
                    snapshot.document.uri.also { owner.adopt(it, snapshot.lease) }
                }
                stagedUris.add(staged)

                if (isVanguardEnabled) {
                    when (val threat = threatChecker(staged)) {
                        is VanguardThreatResult.Clean -> {
                            // continue batch
                        }
                        is VanguardThreatResult.EncryptedCannotVerify -> {
                            owner.retainOnly(listOf(staged))
                            return PickerOutcome.Encrypted(staged).also { it.owner = owner }
                        }
                        is VanguardThreatResult.ExecutableThreat -> {
                            owner.close()
                            return PickerOutcome.BlockedThreat(resolveBlockedReasonResId(threat.report))
                        }
                        is VanguardThreatResult.ParseFailed -> {
                            owner.close()
                            return PickerOutcome.Damaged(threat.failure)
                        }
                    }
                }
            }
            PickerOutcome.Selected(stagedUris).also { it.owner = owner }
        } catch (cancelled: CancellationException) {
            owner.close()
            throw cancelled
        } catch (_: Exception) {
            owner.close()
            PickerOutcome.ProviderError
        }
    }
}
