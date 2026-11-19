# Audio export backpressure correction

The signed version-code-14 build at source 77422a43 failed actual audio exports with both F-Droid eSpeak and the emulator's Google speech engine. A one-page document reproduced `PCM writer queue overflow`. Those captures are diagnostics, not foreground-service submission videos. The corrected 2.1.0 candidate uses code 15 because code 14 was already uploaded to Play.

The PCM queue remains bounded at 64 messages. Audio and completion callbacks serialize their submissions and wait for capacity without holding the writer state monitor. The IO consumer, cancellation and invalidation can therefore make progress while a producer waits. Existing format, generation, file-space, RIFF-size and flush-before-publication checks remain intact.

The queue regressions fail against the previous writer: fast production beyond queue capacity, a completion barrier behind a full queue, and cancellation while a producer is waiting. The corrected audio suite passes 79 tests with zero failures, errors or skips. These checks verify bounded producer waiting, ordered PCM preservation, completion after flush and cancellation release. An additional Main.immediate-style regression reproduces cleanup trying to stop TTS while a callback is blocked. Queue cancellation and deferred completion now happen after releasing both writer locks, with blocked senders released before cleanup resumes. The consumer also invalidates a job that becomes inactive, so callbacks cannot be left waiting behind a stopped consumer.

Release verification must cover actual signed Google/eSpeak audio exports as well as the native lifecycle/ownership tests, release contracts, exact-source CI and the registered signing certificate. Two foreground-service videos must show actual public UI on the matching Android service types. Failed or black captures are not valid evidence. The release package records the resulting artifact hashes and runtime receipts separately from the baseline diagnostics.

Android 13+ can show a foreground task under Active apps when notification permission is unavailable. Declaration text and videos must describe the behavior shown by the device. No Play publication or video upload is performed by the agent. Owner phone testing remains after deployment as agreed.

Local diagnostics and baseline/fixed test reports: `.release-ready/play-fgs-videos-20261119/`. No signing keys are copied into source or release artifacts.

The signed code-15 runtime check also reproduced a foreground-service teardown failure after user cancellation: staging was removed and CANCELLED was published, but the foreground service remained active. Returning from the first NonCancellable IO context checked the cancelled parent and skipped the subsequent service-stop context. Teardown now has one outer NonCancellable block spanning file cleanup and the Main-dispatcher foreground/service stop. The new lifecycle regression fails on the previous code specifically because stopSelf was not called; it checks both service stop and foreground-notification removal after real IO cleanup.

With the teardown regression included, all 80 audio JVM tests pass with zero failures, errors or skips. The release source gate passes 14 fixtures and 62 routes; all 16 Gson release-contract selftests pass.
