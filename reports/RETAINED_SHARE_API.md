# Retained-share backend contract for Gemini Settings

Core entry points in `com.pdfchemy.app.logic.DocumentActions` (both blocking, `@WorkerThread`):

```kotlin
val snapshot = withContext(Dispatchers.IO) {
    DocumentActions.inspectRetainedShares(context)
}

// Invoke only from an explicit user cleanup action, using IDs from that snapshot.
val signal = CancellationSignal()
val result = withContext(Dispatchers.IO) {
    DocumentActions.cleanupRetainedShares(
        context, snapshot.entries.map { it.id }.toSet(), signal
    )
}
```

`RetainedShareStorage` reports logical file bytes and counts, completed-copy bytes/count, incomplete-copy count, legacy-candidate count, protected count, active readers and reserved bytes for ongoing copies. Entries contain opaque stable IDs, display names, kind, logical bytes and protection reasons. No private filesystem path is returned. Inspection has no deletion side effect. An unreadable/unsafe store raises an inspection error, rather than pretending storage is empty.

Cleanup accepts at most 500 valid snapshot IDs. It never accepts paths/URIs as deletion targets and never expands selection to copies created after the snapshot. Unknown valid IDs are reported as missing. A renamed pending file has a new ID; refresh before requesting its removal again.

`RetainedShareCleanupResult` contains actual deleted IDs/bytes, protected IDs/reasons, failed IDs/reasons, missing IDs, unprocessed IDs and cancellation status. `complete` is false for protected, failed, unprocessed or cancelled work. Show those counts; do not display unconditional success. Refresh inspection afterwards. A false delete result or per-file exception does not hide successful deletions or abort reporting of other selected files.

For a cleanup cancellation control, explicitly call `signal.cancel()`. The API reports work already done and stops before the next deletion. Thread interruption also stops it without clearing the interrupt flag. Coroutine cancellation alone does not interrupt a blocking filesystem call; callers should retain/report the operation result in their ViewModel.

Active copy producers, copies awaiting caller delivery and open managed read descriptors are protected under the same lock as cleanup. Up to 64 managed read descriptors are admitted; additional opens fail explicitly while existing transfers remain intact. Descriptor closure restores capacity. The existing FileProvider authority and XML roots remain unchanged. Completed retained copies are read-only. The subclass uses the platform's [descriptor close listener](https://developer.android.com/reference/android/content/ContentProvider#openFile(android.net.Uri,%20java.lang.String)) to release reader protection; closing a descriptor never deletes the file.

There is no automatic completed-copy expiry and no grant revocation. Explicit cleanup of an inactive completed copy means future opens of that deleted copy will fail even though Android's URI grant still exists. Android does not expose a complete inventory of other apps' temporary URI consumers; this API tracks copy ownership and open read descriptors, not every future recipient action. Reader tracking is process-local; an already-open/duplicated kernel descriptor retains its data if the host process restarts. Logical named-file totals do not claim to measure unlinked descriptors' physical blocks.

Historical `cache/exports/shared_*` candidates are counted but protected as `UNVERIFIED_LEGACY_OWNERSHIP`. A filename alone cannot prove that an older file is a disposable share copy. They cannot be removed by this API. New retained copies use the dedicated private `files/shared_copies` store; incomplete unreturned copies retain the existing safe cleanup behavior. Existing 64-copy/250 MiB budgets count legacy candidates as before; no capacity pressure evicts completed shares.

No Settings/MainActivity/ViewModel/translation edits are included. Gemini should connect explicit cleanup, actual partial-result reporting and an explanation that deleted inactive copies cannot be opened again. Separately, fix the `deskew_detected_angle` resource's escaped `%%1$.1f` placeholder; core preview ownership and numeric accessibility state are corrected without editing localized XML. Office descriptions should promise visual page reproduction, without editable-text reconstruction claims.
