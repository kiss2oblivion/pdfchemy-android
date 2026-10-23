# FOLLOW-08: redaction count and complete-request export contract

Release: HOLD. Isolated baseline `f4e17eb8fafb1ed7a33cf6f01475dec8e1c4269c`. Count means selected rectangles on valid document pages; a multi-line match can produce several rectangles. Work occurs outside Gemini's checkout.

## Actual baseline exports

Three native regressions call the public PdfRedactionEngine API through the real isolated worker and inspect the saved destination. All use the same deterministic one-page PDF, SHA-256 `6d11321b4996b7e77f2e3a7c9ceb6e2f315180f38a1d7aa30e1113e733b0ec84`.

| Request | Observed unchanged behavior | Required behavior |
|---|---|---|
| Two selected rectangles covering both private labels | Saved labels are removed, but the host returns 1 | Return 2 |
| Zero rectangles | Saved source text remains, but the host returns 1 | Return 0 |
| A valid rectangle and another on nonexistent page 42 | Export succeeds and replaces the existing destination after silently skipping page 42 | Refuse the entire request and preserve the existing destination |

All three assertions fail against unchanged production source. Baseline app APK SHA-256 `a4d3eea535521e07138f3411a0754567545a3160e6e2bbc9387b9ad37c898dc1`; test APK SHA-256 `31c46f8f8967541e075882875d52bbddb077c472ef91ead85e28351fa3716c9d`. Original inputs, saved outputs, per-case receipts, hashes and instrumentation are retained under workspace `redaction-count-api36-baseline2/`. This proves a count/publication contract defect; exfiltration was not demonstrated.

## Correction

The host and shared response changes adopt reviewed Gemini commit `b56af03f657ef96d95ffff62fca800c482295333`: standard JSON responses preserve a strictly integral, non-negative explicit count, and PdfRedactionEngine returns that validated count directly, including zero. Legacy single-output responses without count retain their default of one.

Independently, the worker now requires every requested page index to exist before changing form data or painting. It no longer turns skipped invalid targets into a successful partial export. Existing host transaction failure handling preserves the original destination when the worker refuses the request.

Three new native methods keep identical test source and the exact same test APK between baseline and correction. They reload successful saved PDFs, check expected text retention/removal, and require byte-for-byte preservation of a synthetic existing destination on invalid input. The release hygiene gate rejects the new test class in production DEX.

## Verification snapshot

- Focused response-validator and host workflow suites: 29 methods, zero failures/errors/skips.
- Corrected debug/test APK assembly: PASS.
- Corrected API36 native: all three methods pass in one invocation, 9.397 seconds. Returns 2 and 0 correctly; invalid page fails and the existing destination is unchanged. Corrected app APK SHA-256 `18109fbbaaab93456ffd7d37ac307ef7f9dfd8c3ca087d7b2760e6943cea306c`; the test APK remains the exact baseline SHA above.
- Independent saved-artifact verifier: PASS for all six baseline/corrected inputs and three outcome pairs. Evidence: workspace `redaction-count-api36-fixed1/` and `redaction-count-validation-receipt.json`.
- Full module suites: 51 IPC, 83 worker and 265 host methods, 399 total, zero failures/errors/skips. Release lint: zero errors, 660 warnings. Full command: `:pdf-ipc:testDebugUnitTest :pdf-jail:testDebugUnitTest :app-host:testDebugUnitTest :app-host:lintRelease --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`. Source fixture/route metadata and whitespace checks pass. Portable proof: [FOLLOW08_COUNT_RECEIPT.json](evidence/FOLLOW08_COUNT_RECEIPT.json).
- Separate commit, Android-only remote identity and resulting-source API24/30/34/36 CI: pending at this source receipt snapshot. No merge, signed replacement or Play upload is claimed.

## Retained pre-test startup failure

Baseline attempt 1 completed no methods: PID2679 hit a process-start ANR. Its current DropBox main-thread trace is ART ZIP/DEX inflation through ActivityThread.initInstrumentation. The cumulative schedstat tuple `(2067616374 26964797030 1902)` records about 2 seconds of scheduled CPU and 27 seconds waiting on a run queue, using the [kernel schedstat definition](https://www.kernel.org/doc/html/latest/scheduler/sched-stats.html#proc-pid-schedstat). The guest reported 98% CPU use and memory-pressure some avg10 32.47. Older ML Kit stacks in the same DropBox dump belong to earlier entries and are not attributed to this PID.

After the guest settled, top reported 193/200% idle CPU, MemAvailable 1,281,828 kB and uptime 369.53 seconds. One unchanged retry verified the on-device app/test APK hashes before executing baseline2 and demonstrating all three functional failures. Fixed1 passed on its first invocation after a separate idle/memory preflight. These are scoped functional results; they do not establish the complete startup root cause or close startup reliability. Original failure logs, DropBox, exit information and diagnosis remain under `redaction-count-api36-baseline1/`.
