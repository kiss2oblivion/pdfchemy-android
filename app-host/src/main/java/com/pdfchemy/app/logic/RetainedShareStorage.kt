package com.pdfchemy.app.logic

enum class RetainedShareKind { RETAINED_COPY, INCOMPLETE_COPY, LEGACY_UNVERIFIED }
enum class RetainedShareProtection { ACTIVE_COPY, ACTIVE_READER, UNVERIFIED_LEGACY_OWNERSHIP }

/** Opaque selection ID and display metadata; never exposes a private filesystem path. */
data class RetainedShareEntry(
    val id: String,
    val displayName: String,
    val kind: RetainedShareKind,
    val bytes: Long,
    val protections: Set<RetainedShareProtection>
)

data class RetainedShareStorage(
    val entries: List<RetainedShareEntry>,
    val reservedCopyBytes: Long,
    val activeReaderCount: Int
) {
    val totalCount: Int get() = entries.size
    val totalBytes: Long get() = entries.sumOf { it.bytes }
    val retainedCopyCount: Int get() = entries.count { it.kind == RetainedShareKind.RETAINED_COPY }
    val retainedCopyBytes: Long get() = entries.filter { it.kind == RetainedShareKind.RETAINED_COPY }.sumOf { it.bytes }
    val incompleteCopyCount: Int get() = entries.count { it.kind == RetainedShareKind.INCOMPLETE_COPY }
    /** Current copy producers and open provider reads; completed copies are not active transfers. */
    val activeCopyCount: Int get() = entries.count { RetainedShareProtection.ACTIVE_COPY in it.protections }
    val activeTransferCount: Int get() = activeCopyCount + activeReaderCount
    val legacyCandidateCount: Int get() = entries.count { it.kind == RetainedShareKind.LEGACY_UNVERIFIED }
    val protectedCount: Int get() = entries.count { it.protections.isNotEmpty() }
}

data class RetainedShareCleanupResult(
    val deleted: Map<String, Long>,
    val protected: Map<String, Set<RetainedShareProtection>>,
    val failed: Map<String, String>,
    val missing: Set<String>,
    val unprocessed: Set<String>,
    val cancelled: Boolean
) {
    val deletedCount: Int get() = deleted.size
    val deletedBytes: Long get() = deleted.values.sum()
    val complete: Boolean get() = protected.isEmpty() && failed.isEmpty() && unprocessed.isEmpty() && !cancelled
}
