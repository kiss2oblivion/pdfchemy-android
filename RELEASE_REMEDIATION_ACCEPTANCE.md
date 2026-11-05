# Release remediation acceptance

Baseline: PR #21 head 00a1575879ae568b29cd199ae0d6c9f70216ef57. Branch: fix/2.1.0-release-remediation. The existing billing checkout and its untracked Vulcan handoff are preserved. No merge, tag, deployment or Play action.

## Task 1 - Monotonic interstitial timing

Confirmed: cooldown and cached-ad age used wall time. The default and reset now use Android elapsedRealtime; no elapsed timestamps are persisted. Nullable timestamps distinguish no previous display/load from elapsed time zero. Cooldown remains 60 seconds and expiry remains 55 minutes.

Original task1-original-local: four new JVM regressions, four failures, zero errors/skips. Fixed task1-accepted: 103 billing/ad JVM tests passed, zero failures/errors/skips. Tests verify default/reset clock wiring, a wall-clock advance without cooldown bypass, exact 60-second admission, first display at elapsed zero and expiry of an ad loaded at zero. The original sandbox attempt eventually completed with the same four expected failures; the separate accepted local run is authoritative. The initial fixed full-package run exposed a fixture resetting cooldown with the old zero sentinel; it now explicitly clears previous-display state with null. The timing assertions remain intact.

Changed files: AdManager.kt, AdMonotonicClockTest.kt, AdOpportunityDeliveryTest.kt, this report. No real ad request/display is asserted. Four-API, complete final-source and signed replacement acceptance remain pending after the remaining tasks.

## Task 2 - Read-only sharing with separate camera write capabilities

Confirmed conditional defect: non-retained FileProvider roots accepted writable opens when given a write grant. Normal share intents already grant read only; no ungranted foreign access is claimed. The retained-copy hook already rejected writes; the fallback did not. A guard now rejects every mode except r before dispatch, and provider deletion is denied so broader grants cannot bypass reader-aware backend cleanup. Existing shared URI roots remain readable.

The requested blanket guard alone would break Images-to-PDF TakePicture, which intentionally gives a camera app a writable scan URI. That capability now uses a separate non-exported, URI-grant-only CameraCaptureFileProvider, exposing only cache/scans. The read-only provider retains its original authority and roots. No broader private path is exposed. External intent admission continues to use installed provider UID ownership.

Original task2-original-fallback-native: three native cases failed on the preserved original APK. Actual foreign-UID writable fallback open succeeded; the camera helper still used the shared authority; host write-mode rejection failed. Initial fixture run used a shared_ export name, exercising the already-protected retained hook instead of the intended fallback; it is retained but superseded by the corrected original reproduction. The recipient writer also uses an owned duplicated descriptor rather than closing a borrowed raw FD.

Accepted task2-accepted-native: 13 native tests passed (three mode/camera cases plus ten retained management cases), zero failures/errors/skips, owned offline API36. All four shared roots reject w/wt/wa/rw/rwt without changing bytes; a foreign UID with both grants cannot write or delete, but can reopen for reading; camera writing succeeds under its separate authority and cannot map exports. Retained leases/accounting/explicit cleanup remain intact. Original installed APK restored and hash checked after every native attempt. SecurityAudit/securityArchitecture and six focused ownership/sharing JVM tests passed in task2-fixed-build. Final four-API and signed replacement checks remain pending.

Changed files: RetainedShareFileProvider.kt, CameraCaptureFileProvider.kt, camera_capture_paths.xml, AndroidManifest.xml, one camera URI authority line in CreateScreens.kt, ShareProviderModeIntegrityTest.kt, test-only ShareRecipientProvider.java and this report. A physical camera application was not exercised.

## Task 3 - Owned and bounded print spooling

Confirmed: the original routine opened a cancelled source, kept a stalled write alive after adapter finish, and reported successful output beyond the 250 MiB host quota. Extracted the original anonymous adapter into an internal factory without changing its algorithm for reproduction. task3-original-native: seven actual Android spool/pipe cases, three failures (pre-cancelled open, adapter-finish cleanup, real quota overflow), four passing. Normal cancellation happened to finish in that original run; no deterministic original failure is claimed for that particular timing.

Correction: an adapter-owned SupervisorJob is cancelled on finish/replacement; a process-wide two-slot admission budget refuses excess work instead of spawning unlimited workers. IO jobs use runInterruptible and a 120-second deadline. Polling checks cancellation while pipes have no data or space. BoundedOutputStream enforces MAX_OUTPUT_BYTES. Cancellation before output starts can terminate promptly even if a provider open is uncooperative; after output starts, the IO owner closes its duplicated descriptor before emitting a terminal callback, preventing obsolete writes after the spooler is notified. The supplied destination is closed through its ParcelFileDescriptor. Callbacks are terminal once only. Asset slices retain their offsets/lengths, and reliable source errors remain errors. No output pathname is reopened and no document parser moved to the host.

Accepted task3-accepted-native: ten native cases passed, zero failures/errors/skips, owned offline API36, including successful byte-for-byte copy, actual 250 MiB plus one-chunk overflow, pre-cancellation, stalled input, adapter finish, a full output pipe with writer-HUP required before cancellation callback, unavailable input, unsupported page ranges, asset slices and reliable producer failure. Builds/securityAudit/securityArchitecture passed. Original installed APK restored and hash checked. Initial fixture compilation failed because Android's callback constructor is package-private; it now uses the existing Android test mocking dependency, without a new dependency. Intermediate build-only attempts are retained separately and are not runtime passes. Only indentation changed after the accepted native run; final-source CI remains required.

Limits: this is real adapter/descriptor instrumentation, not a physical printer or signed print-UI acceptance. Arbitrary foreign provider opens or kernel filesystem operations cannot be forcibly terminated by a coroutine; admission remains occupied until the IO block actually exits, limiting such stragglers to two. No universal provider rollback or hard kernel-termination guarantee is claimed. Failed/cancelled spooler-owned partial output is never reported as completed; existing user SAF destinations are not used by this path.

Changed files: DocumentActions.kt, new OwnedPdfPrintAdapter.kt, PrintSpoolIntegrityTest.kt, test-only PrintSourceProvider.java/androidTest manifest and this report.

Reachability clarification: no production caller of DocumentActions.print was found in the current checkout. This corrects the requested backend contract; it is not evidence of a currently exposed printing workflow or permission to add one. R8 may remove unused print code. Signed public print acceptance is therefore not claimed.

## Task 4 - Targeted compiler-warning cleanup

Removed the two dead OutputPolicy.createCopy null branches and the unreachable nullable descriptor branch. Signature date parsing now uses an empty non-null fallback, preserving omitted/blank-date behavior. Its temporary-directory property is explicitly required rather than passed as a nullable String to File. No fallback worker filesystem path or nullable-quota escape was introduced.

The handoff misidentified the image warning: the actual always-true check was bitmap != null at line 55, not page resources != null. Removed only that redundant bitmap check. Nullable page-resource handling remains, including resource-less pages. These are warning/contract hygiene changes, not reproduced new user-facing PDF defects. The original compile logs preserve all six affected warnings; the subsequent production compilation emits none at these sites. Unrelated warnings remain.

Accepted task4-accepted-build: 147 focused JVM cases (136 worker plus 11 host concurrency/output cases), zero failures/errors/skips; securityAudit/securityArchitecture and debug/test builds passed. Native signing/flattening output contains six passing cases, including four actual exports with omitted, empty, whitespace-only and real signature dates. Exported text, signature image, Letter dimensions and source bytes are checked. The initial test fixture failed compilation because positional arguments overlooked the model's leading ID; named arguments corrected the fixture. The driver then expected five native cases while the class actually contained six: its count assertion failed after all six passed. receipt-corrected-count.json strictly validates that same raw output, without rerunning or ignoring any test. Original APK restoration was verified.

Changed files: MainViewModel.kt, PdfLayoutEngine.kt, SignatureEngineWorker.kt, PdfImageExtractorEngine.kt, PdfSigningAndFlattenTest.kt and this report. No broad deprecation/dependency modernization, table-worker change, AIDL change or ViewModel decomposition.

## Final source acceptance

All four tasks are implemented in focused commits. Exact final commit/tree, same-source full JVM/four-API CI, signed artifact identities and available signed-runtime results are recorded in PR metadata and external release-remediation-evidence receipts after this source is frozen. The earlier PR #21 acceptance remains evidence for its unchanged source, not for this new head. No final-source CI, signed runtime, physical device or real Play pass is asserted by this section before those checks complete. Owner-controlled merge, Play/Billing/UMP/AdMob/hardware and publication gates remain separate.
