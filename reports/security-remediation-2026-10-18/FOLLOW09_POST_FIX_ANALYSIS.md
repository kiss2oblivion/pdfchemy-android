# FOLLOW09 focused post-fix analysis

Review checkpoint: 2026-10-22. Base commit: `79bc3ed7f12f1a3340bc52b98874653a213f5a0a`. Reviewed working-tree `StartupWindowRegressionTest.kt` SHA-256: `6facffd9e19ac8dfa1f653eadd6e3ac802e9e05ae7ce7ea3adaf3d3f911e54ed`. Corrected runtime verification is pending. Release remains HOLD.

The first Home/launcher driver contained a confirmed test bug missed by the initial static review. Its initial ActivityScenario intent named only the component, while each return used ACTION_MAIN/CATEGORY_LAUNCHER. `MainActivity.onNewIntent` stores the new intent. The retained API24 log records ten complete pause/stop/start/resume cycles with the same activity, task and content, then an actual DESTROYED callback rejected by ActivityScenario because the intent did not match. The test consequently failed during `close()` waiting for DESTROYED. This attempt is a failed test run, not a passing ten-cycle result.

The corrected background test launches ActivityScenario with a clone of the same launcher intent used for return. The action, categories, package and component therefore remain consistent after `onNewIntent`; intent flags do not distinguish the filter match. The unchanged recreation test still uses its original launch helper. Both test names and ten repetitions remain. Normal startup is enabled; consent/ads are not bypassed. The background driver requires real Home pause/stop callbacks and CREATED state before return, then STARTED/RESUMED with the original activity, task, content container and Compose view. Callback records precede latch release; destruction wakes waiters but fails the destruction checks. Registration is removed in `finally`. No additional material race or security regression was identified in this corrected source review.

The original API34 partial-pause failure remains separately classified: both retained SC04 and SC10 logs show the test invoker's EmptyFloatingActivity, MainActivity stopping, the EmptyActivity helper being removed, and MainActivity restarting to STARTED without a subsequent RESUMED/PAUSED callback. Its underlying platform/helper divergence is not established. MainActivity and the original test were unchanged between those source revisions. Consent already starts in `onPostResume`, after content installation; the earlier proposal that it ran before content is obsolete. The logs do not establish UMP, ads or redaction as the cause. The changed test validates full background/return behavior; it does not prove the cause of the former partial-pause transition.

Local execution evidence must remain separate:

| Attempt | Established result |
|---|---|
| Initial Home driver, API24 | Ten cycles observed; test failed at ActivityScenario close because of the confirmed intent mismatch. |
| Initial Home driver, API36 | Instrumentation reported process loss before any test method started. ApplicationExitInfo records reason 6 (ANR), failed process startup. The captured native crash buffer is empty. This is not a completed test or a demonstrated native crash; the startup ANR cause remains unresolved. |
| Three local API34 baseline attempts | Emulator loss during the unchanged recreation method, before any test completion. The two retained monitored exit receipts record `3221225477` (`0xC0000005`, Windows host access violation). These incomplete runs supply no app-test pass/failure count. |
| Corrected Home driver | Runtime verification pending at this source-hash checkpoint. |

Evidence inspected: `.security-runtime/follow09/attempt0-fixed/fixed-api24-failed-lifecycle.log`, the corresponding API36 run/exit-info/native-crash logs, all three API34 baseline runner receipts, and `emulator34-exit.txt` / `emulator34-gles-exit.txt`. The original API34 evidence remains under `reports/security-remediation-2026-10-18/sc04/` and `sc10/`. This review made no production-source changes and executed no builds or device tests.
