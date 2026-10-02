# Security Remediation Plan — pdfchemy-android

**Target:** `security/audit-remediation-2026-10-01` @ `9b6dd9cd2ff4b057a56b6cc4d17e42be71fd986f` (draft PR #1, 8 commits, 202 files, +7693/−6524)
**Baseline:** `repo-android` @ `cbafb998`
**Ledger of record:** `KILO_SECURITY_LEDGER.md` (repo root, committed)
**Companion:** `SECURITY_REMEDIATION_STATUS.md` — authoritative for PR #1's own `SEC-001`…`SEC-018` / `RISK-001`

---

## ID allocation rule

PR #1's register mints `SEC-001`…`SEC-018`. Between two pushes it grew by one (`SEC-018` appeared at `9b6dd9c`), colliding with an ID I had allocated. Allocating just past their current maximum is therefore not stable.

**Their `SEC-001`…`SEC-018` are adopted unrenumbered** — they are referenced across eight commit messages and rewriting them breaks traceability. **Cross-audit findings allocate from `SEC-901+`**, a disjoint band, so the two sequences cannot interleave however far theirs extends. Re-check their maximum only to cross-reference, never to allocate.

Verified adoptions: their `SEC-012` (Bouncy Castle belongs to jail) is the finding previously numbered for the crypto dependency misplacement — `FIXED`. Their `SEC-006`/`SEC-007`/`SEC-018` (worker admission, watchdog threads, process death) address what earlier drafts called process-isolation and resource-exhaustion defects.

## Evidence basis

Read at `9b6dd9c`: `app-host/build.gradle.kts`, `pdf-jail/build.gradle.kts`, the recursive tree, the `SECURITY_REMEDIATION_STATUS.md` diff, and the commit file list. No fetch, no build, no device run — network and mutating commands are blocked in plan mode. Nothing below is compile-verified.

## State at `9b6dd9c`

**Closed by PR #1:** the fake PDFBox API is gone from every module; BouncyCastle moved to the jail with its own `jdk15to18` exclusions; Coil dropped from Host; Jackson narrowed to `jackson-module-kotlin`; jsoup strictly pinned; `lint { abortOnError = true }`; a `securityArchitecture` gate on `preBuild`; 47 JVM tests.

**New in `9b6dd9c` — output authority (genuine).** Workers now pass only unlinked temporary FDs. The host re-validates result, target count and the 250 MiB aggregate budget, snapshots outputs into private FDs, then commits once per destination. Best-effort provider truncation is gone. Real Binder regressions for death, cancellation, deadline, quota bypass, retained-FD mutation, duplicate callbacks and batch output.

**Unchanged by either chunk.** `app-host/build.gradle.kts` is absent from the `9b6dd9c` change list, so every Host-side finding below carries over verbatim: the `securityAudit` grep, `gson` on the Host, `text-recognition` on the Host, `security-crypto:1.1.0-alpha06`. `ReflowReaderScreen` and `ArchitectureBoundaryTest` untouched. The split package is still 8 files — the new `HostOutputTransaction.kt` went into `com.pdfchemy.app.jail`, not `.logic`.

## Tasks

### R1 — Create the ledger

`KILO_SECURITY_LEDGER.md`: provenance, invariant register, severity bands, ID-reuse rules, status-transition rules, `SEC-901`…`SEC-918`, the `REG-###` register, phase acceptance. Record the adopted `SEC-001`…`SEC-018` with a pointer noting they are owned by the companion record.

Verify by: committed; no ID appears twice; no `SEC-901`-band ID referenced by PR #1.

### R2 — SEC-901: replace the retained `securityAudit` *(highest priority)*

Byte-for-byte unchanged since `557d334`: walk `app-host/src/main/java`, match three literal strings, throw, wire to `preBuild`. PR #1 added `securityArchitecture` beside it and kept the broken one. It has run green through five audits, including one that found `DummyPDFBox.kt` sitting in the directory it scans.

Keep it as a cheap tripwire; stop treating it as the gate.
1. Resolve `app-host` `releaseRuntimeClasspath`; fail on any `com.tom-roush` or `org.bouncycastle` artifact.
2. Scan **`app-host`'s compiled classes** for constant-pool references to `com/tom_roush/`, `org/bouncycastle/`, `android/graphics/pdf/PdfRenderer`, and any `com/pdfchemy/app/logic/PD*` type.
3. Apply (1)–(2) to every module that is not `pdf-jail`/`pdf-renderer`, added automatically as modules are registered.
4. Drop the blanket `BitmapFactory` ban; scope to parser-adjacent use.
5. Fail on any class named `Dummy*` under `com.pdfchemy.app.logic`.

Verify by: each gate **observed failing once**. An unexercised gate is not evidence.

### R3 — SEC-903: move `gson` off the Host

`gson:2.10.1` is in `app-host` and `pdf-jail`. It was jail-only before the refactor. Find what introduced the Host-side need; move the parse to `pdf-jail` behind an explicit DTO, or replace with `org.json` and justify.

Verify by: `gson` in one module, or a written justification.

### R4 — SEC-904: end the split package

`com.pdfchemy.app.logic` is declared in both `app-host` (engine layer) and `pdf-ipc` (8 contract files). Same-named types resolve without imports across module boundaries — the mechanism that made `DummyPDFBox` reachable in the first place.

Move `pdf-ipc`'s contracts to their own package, update `PdfJailClient.kt` and consumers.

Verify by: `com.pdfchemy.app.logic` declared in exactly one module. **Fails today.**

### R5 — SEC-908: close the contracts gate

Structure is correct and unverified: `implementation(project(":pdf-ipc"))` with `runtimeOnly` for jail and renderer. Nothing asserts it. Add a build gate resolving `app-host`'s `compileClasspath`, failing on any `pdf-jail` / `pdf-renderer` / parser artifact — a **classpath allowlist**, not a source grep.

Verify by: gate fails when a `pdf-jail` dependency is temporarily added.

### R6 — SEC-912: make `ArchitectureBoundaryTest` an allowlist

It executes but matches two FQNs. `PDPageContentStream`, `PDFont`, `COSDocument`, `Loader` are unconstrained, the fake stub's FQN (`com.pdfchemy.app.logic.PDDocument`) passes it, and `pdf-ipc` is never referenced.

Invert to a permitted-dependency allowlist; reject any Host dependency on `com.pdfchemy.app.logic.PD*`; replace the blanket `..jail.engines..` and `NativeRendererCoordinator` exemptions with enumerated exceptions.

Verify by: fails against a planted `PDPageContentStream` ref **and** a planted fake-stub ref.

### R7 — SEC-905, SEC-906, SEC-907: dependency and privacy rulings

- **SEC-905** `security-crypto:1.1.0-alpha06` — deprecated alpha in a security product. Justify in writing or remove.
- **SEC-906** `text-recognition:16.0.1` in `app-host` — the Host must not OCR document-derived bytes. Keep `play-services-mlkit-document-scanner`. Removing it does **not** resolve the jail-side API 36 OCR blocker; keep those separate.
- **SEC-907** `uriHash` — full document URI in reversible Base64 as the reader scroll key, 4 sites. Truncated SHA-256 or no persistence.

Verify by: no `text-recognition` on `app-host`'s runtime classpath; no full URI in `SharedPreferences`.

### R8 — SEC-910: decide the partial-batch guarantee *(design decision, not a code change)*

PR #1 states the boundary precisely: "Generic SAF providers do not offer a transaction across files: provider I/O failure or cancellation after host publication starts can leave a partial file/batch. This chunk guarantees validation of the entire batch before publication, not rollback after publication begins."

Validation-before-publication is real and worth keeping. Rollback across provider FDs is not achievable for arbitrary SAF providers. Decide explicitly whether partial multi-file batches are acceptable, and if not, what the product does instead — reject multi-file output, or require a provider that supports transactions. Then state the decision in `INV-05` so the invariant describes reality.

Verify by: `INV-05` names the actual guarantee, and the UI does not promise atomicity it cannot deliver.

### R9 — SEC-911, SEC-914: the newly surfaced availability and containment gaps

- **SEC-911** Legacy Jackson export throws `NoClassDefFoundError` for `java.lang.BootstrapMethodError` on API 24. The numeric callback never arrives, so the host times out after 125 seconds. Pre-existing at their frozen base. Leaving the test failing rather than skipping it is correct — keep it failing. Decide the fix path and whether the 125 s ceiling is right for a path that cannot succeed.
- **SEC-914** PR #1's own limits: a hostile worker can retain an unlinked temp FD until process death, and "arbitrary hostile-worker disk writes cannot be bounded by cooperative worker wrappers." The host publication checks are independent and good; they are not storage quotas. Record the containment boundary honestly rather than closing `INV-06` on the strength of the publication work.

Verify by: `SEC-911` has a fix or an accepted-risk note with a timeout ceiling; `INV-06` wording matches what is actually enforced.

### R10 — SEC-909: reconcile the release branch

`main` is `557d334`, now 8 commits behind. PR #1 targets `repo-android`. A release from `main` ships all three original criticals and no remediation.

Either fast-forward `main` once the criticals close, or declare the remediation branch the release branch in writing. **Do not release from `main` while SEC-901 is open.** Local `3449081`/`914e3d0` duplicate upstream `6119652`/`ddd2624`; drop once confirmed.

Verify by: `main` and the release branch agree, or the release branch is documented.

### R11 — SEC-913: `SecureScreenContent` coverage and teardown

Applied to ≥10 screens. Audit coverage against every document-rendering screen and confirm `DisposableEffect` clears the flag. A `FLAG_SECURE` set and never cleared locks the whole app.

Verify by: flag set and cleared; no document content renders unprotected.

### R12 — revalidate the inherited queue *(no IDs until checked)*

Check each at the merge commit, then adopt the matching existing ID if PR #1 raised it, else allocate from `SEC-919+`.

Broken redaction/compression · XFA sanitizer gaps (→ their `SEC-010`) · process isolation (→ their `SEC-006`/`007`/`018`) · signatures and DN injection (→ their `SEC-012`) · intent-URI allowlist (→ their `SEC-004`/`005`) · predictable temp files (→ their `SEC-017`) · resource exhaustion (→ their `SEC-016`) · backup protection · uncompilable tests (→ their `SEC-015`) · false claims in `PRIVACY.md`/`MANTRA.md`/`KILO_SESSION_CONTEXT.md`.

Verify by: each item leaves `UNVERIFIED` with a status and either an adopted or newly allocated ID.

## Invariants

| ID | Invariant |
|---|---|
| INV-01 | The parser surface is confined to the jail. The Host never resolves, compiles against, or calls a parser. |
| INV-02 | Crypto primitives and key material are resident only in the jail. |
| INV-03 | Boundary enforcement is structural (classpath / bytecode / architecture test), never text-grep based. |
| INV-04 | Untrusted bytes are re-validated (digest, size, structure) after every process and thread hop. |
| INV-05 | Output is published only after the whole batch is validated. Rollback across provider FDs is **not** guaranteed — record the actual guarantee. |
| INV-06 | Engine entry points are reachable only through a single gateway and are quota-gated. Quotas bound cooperative wrappers, not hostile-worker storage. |
| INV-07 | IPC contracts live in a contracts-only module; the Host does not compile against implementation modules, **and contract types do not share packages with the Host**. |
| INV-08 | Screen capture is blocked on every document-rendering surface, and the flag is cleared on dispose. |
| INV-09 | Persisted state contains no reversible document identifier. |
| INV-10 | Document-derived content (OCR, text, thumbnails) is produced inside the jail. |
| INV-11 | Backup and exfiltration surfaces are closed. |
| INV-12 | Dependencies are declared where used, pinned to non-deprecated non-alpha versions, CVE-free, never relied on transitively. |
| INV-13 | Components unexported unless required, permission-guarded when exported. |
| INV-14 | Signing and release configuration deterministic, non-failing at configure time, verifiable. |
| INV-15 | User-facing security and privacy claims match implemented behaviour. |

`INV-05` and `INV-06` were narrowed to what is actually enforced, using PR #1's own stated limits. Narrowing an invariant is a documentation act, not a closure.

## Ledger schema

```
### SEC-### — <title>
Severity:      CRITICAL | HIGH | MEDIUM | LOW | INFO
Type:          BOUNDARY PARSER INPUT TOCTOU RESOURCE INTEGRITY CONFIDENTIALITY
               CRYPTO SANITIZATION AUTH PRIVACY BUILD TEST UI DEPENDENCY RELIABILITY
Status:        NEW OPEN PARTIAL FIXED REGRESSED REOPENED SUPERSEDED
               FALSE_POSITIVE WONT_FIX UNVERIFIED
Evidence:      CONFIRMED | SUPPORTED | SUSPECTED | DISPROVEN
Components:    <module / file:line>
Invariant:     INV-##
Flow:          <untrusted input -> transform -> sink>
Preconditions: <what the attacker needs>
Impact:        <concrete outcome>
Evidence:      <what proves it>
Remediation:   <fix, and the structural prevention>
Regression:    <test name, and confirmation that it EXECUTES>
Related:       <other SEC-### / REG-### / companion-record ID>
```

### Cross-audit register

Their `SEC-001`…`SEC-018` and `RISK-001` are not restated here.

| ID | Sev | Type | Inv | Status | Evidence | Summary |
|---|---|---|---|---|---|---|
| SEC-901 | CRITICAL | BUILD | 03 | **OPEN** | CONFIRMED | `securityAudit` retained verbatim since `557d334`: single-module source-text grep for three literals on `preBuild`. Blind to the stub it should have caught, blind to other modules, defeated by reflection. |
| SEC-902 | CRITICAL | PARSER | 01 | **FIXED** | CONFIRMED | `DummyPDFBox.kt` — fake `PDDocument`/`PDPage`/`PdfRenderer` in the Host's main source set, members returning `0`/`{}`/`false`. Regressed into the Host by the `13810ac` module move; deleted at `f6b6f7d`. |
| SEC-903 | HIGH | DEPENDENCY | 12 | **REGRESSED** | CONFIRMED | `gson:2.10.1` added to `app-host` while remaining in `pdf-jail`. A JSON deserialization library crossed the boundary during the refactor. |
| SEC-904 | HIGH | BOUNDARY | 07 | **OPEN** | CONFIRMED | `com.pdfchemy.app.logic` declared in both `app-host` and `pdf-ipc` (8 contract files). Same-named types resolve without import across modules. |
| SEC-905 | HIGH | DEPENDENCY | 12 | **OPEN** | CONFIRMED | `security-crypto:1.1.0-alpha06` still in `app-host`. |
| SEC-906 | HIGH | PRIVACY | 10 | **OPEN** | CONFIRMED | `text-recognition:16.0.1` still in `app-host` main dependencies. |
| SEC-907 | HIGH | PRIVACY | 09 | **OPEN** | SUPPORTED | Full document URI persisted in reversible Base64 scroll key, 4 sites. Not re-checked since `557d334`. |
| SEC-908 | HIGH | BOUNDARY | 07 | **PARTIAL** | CONFIRMED | Contracts structure landed correctly; no gate prevents `app-host` re-acquiring a jail or parser compile dependency. |
| SEC-909 | HIGH | BUILD | 12, 14 | **OPEN** | CONFIRMED | `main` at `557d334`, 8 commits behind. A release from `main` ships the unremediated tree. |
| SEC-910 | HIGH | BOUNDARY | 05 | **PARTIAL** | CONFIRMED | Host validates the whole batch before publication, but SAF offers no cross-file transaction: provider I/O failure or cancellation mid-publication can leave a partial batch. No rollback. |
| SEC-911 | HIGH | RELIABILITY | 14 | **OPEN** | CONFIRMED | Legacy Jackson export throws `NoClassDefFoundError` for `BootstrapMethodError` on API 24; callback never arrives; host times out after 125 s. Pre-existing. |
| SEC-912 | MEDIUM | TEST | 03 | **OPEN** | CONFIRMED | `ArchitectureBoundaryTest` executes but matches 2 FQNs; blind to the fake stub; never references `pdf-ipc`. |
| SEC-913 | MEDIUM | UI | 08 | **PARTIAL** | SUPPORTED | `SecureScreenContent` on ≥10 screens; coverage and teardown unverified. |
| SEC-914 | MEDIUM | RESOURCE | 06 | **OPEN** | CONFIRMED | Hostile worker can retain an unlinked temp FD until process death; jail storage writes are not bounded by cooperative wrappers. Publication checks are not storage quotas. |
| SEC-915 | — | UI | 08 | **FIXED** | CONFIRMED | `isScreenshotRun` gated by `BuildConfig.DEBUG`. |
| SEC-916 | — | AUTH | 08 | **FIXED** | CONFIRMED | `IS_PREMIUM` `buildConfigField` removed. |
| SEC-917 | — | BUILD | 14 | **FIXED** | CONFIRMED | Release signing guarded by `if (password != null)`. |
| SEC-918 | — | BUILD | 12 | **SUPERSEDED** | CONFIRMED | Branch divergence resolved; `6119652`/`ddd2624` are in `repo-android` history. Superseded by SEC-909. |

### Status-transition rules

- `FIXED` requires all five: change landed, in a production path, structural prevention, regression test that **executes**, independently verified.
- **Partial reduction does not close a finding.** Narrowing an invariant to match reality is not closing the defect it describes.
- Re-broken by a later commit → `REGRESSED` plus a `REG-###` referencing the original ID. Never a new ID.
- `UNVERIFIED` carries no severity and is excluded from phase acceptance.

### Regression-event register

- **REG-001** `557d334` phase-6 event.
- **REG-002** `6119652`/`ddd2624` Android split + build fixes.
- **REG-003** first full re-verification sweep.
- **REG-004** `13810ac` module rename + `pdf-ipc`. SEC-902 → `REGRESSED`; SEC-908 → `PARTIAL`; SEC-904 opened.
- **REG-005** `cbafb998` staging invariant (`StagedPdf`, `verifyAndRewind`).
- **REG-006** `f6b6f7d` PR #1 chunk 0. SEC-902 → `FIXED`; their `SEC-012` → `FIXED`; SEC-903 → `REGRESSED`.
- **REG-007** `9b6dd9c` PR #1 chunk 1, host-owned output publication. SEC-910, SEC-911, SEC-914 opened. SEC-901, SEC-903, SEC-904, SEC-905, SEC-906 unchanged — `app-host/build.gradle.kts` absent from the change list.

## Validation

**Phase acceptance:** zero open `CRITICAL`, and every `HIGH` either `FIXED` (all five conditions) or explicitly accepted in writing with a rationale.

1. `git rev-parse HEAD` = `9b6dd9c` or its successor.
2. `:app-host` release and `:pdf-jail` build clean.
3. `:app-host` `compileClasspath` free of `pdf-jail`, `pdf-renderer`, `com.tom-roush`, `org.bouncycastle`. `releaseRuntimeClasspath` free of `com.tom-roush`, `org.bouncycastle`, `com.google.mlkit:text-recognition`.
4. `:app-host` compiled classes contain no constant-pool reference to `com/tom_roush/`, `org/bouncycastle/`, `android/graphics/pdf/PdfRenderer`, or `com/pdfchemy/app/logic/PD*`.
5. `com.pdfchemy.app.logic` declared in exactly one module — **fails today** (8 contract files in `pdf-ipc`).
6. Each new gate observed **failing** once before acceptance.
7. `ArchitectureBoundaryTest` fails against a planted real-PDFBox ref and a planted fake-stub ref.
8. No full document URI in any `SharedPreferences` key or value.
9. `INV-05` states the actual publication guarantee; the UI does not promise more.
10. `REG-003` complete; nothing `UNVERIFIED`; every `REGRESSED` finding has an open `REG-###`.

### PR #1 verification gaps to close before credit

From its own status doc: release lint "ended during host lint analysis without a result"; five device guard tests unexecuted; API 36 publication verification and the full 24/30/36 matrix not run for chunk 1; API 36 OCR `getAllHalInstanceNames` null deref under isolated UID unresolved. No closure is creditable until the final-merge-commit CI run is green.

## Risks

- **A green build certifies less than it appears.** Two of the open defects (`SEC-903` gson on the Host, `SEC-904` split package) arrived *during* the hardening, and the gate that should have caught structural regressions was left unchanged while a new one was added beside it.
- **`securityAudit` has passed through five audits**, including one that found the stub in the directory it scans. Treat any green gate as unproven until observed red.
- **The ID space is shared and theirs is still growing.** It gained `SEC-018` in the last push. The `SEC-901+` band removes the collision; it does not remove the need to re-read their register at each re-baseline.
- **Narrowing `INV-05`/`INV-06` risks reading as closure.** They were narrowed to what PR #1 actually enforces. `SEC-910` and `SEC-914` stay open on that basis.
- **The branch moves several times a day.** Re-derive `origin/security/audit-remediation-2026-10-01` at the start of every pass and re-baseline touched findings rather than trusting this document.

## Out of scope

- Desktop repositories. Play Console key material.
- The API 36 OCR backend decision — PR #1 leaves it pending and unapproved; this plan does not pre-empt it. `SEC-906` and that blocker are separate.

## Open questions

1. **PR #1 is an input, not the target.** It closes two criticals, regresses one HIGH, and opens three. Re-derive the register on its merge commit before crediting any closure — `9b6dd9c` is still a draft branch head.
2. **Should the revalidation queue adopt their IDs or allocate fresh?** R12 assumes fresh from `SEC-919+` unless their register already covers the defect. If their range keeps growing, some queue items may land above `SEC-919` by then.