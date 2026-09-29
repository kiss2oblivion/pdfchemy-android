# Security Remediation Plan — pdfchemy-android

**Target:** `origin/repo-android` @ `cbafb998666219862b64e8d3d8dd1c172217b4e6`
**Lineage:** `557d334` → `13810ac` (tag `v2.0.6`, module rename + `pdf-ipc` extraction) → `cbafb998` (staging invariant)
**Phase 1 verdict:** REJECTED — 3 blocking criticals. **All three still open at `cbafb998`; one has regressed.**
**Ledger of record:** `KILO_SECURITY_LEDGER.md` (repo root, committed) — created by R1

---

## Module layout (verified from the `cbafb998` tree)

| Symbol | Gradle module | Role | Package |
|---|---|---|---|
| `M_HOST` | `:app-host` | Android app, UI, orchestration | `com.pdfchemy.app` |
| `M_JAIL` | `:pdf-jail` | Isolated parser/crypto process | `com.pdfchemy.pdfjail` |
| `M_RENDER` | `:pdf-renderer` | Native renderer | — |
| `M_CONTRACT` | `:pdf-ipc` | AIDL + DTOs | `com.pdfchemy.app.jail`, **`com.pdfchemy.app.logic`** |

These names are confirmed, not provisional. `pdf-ipc` **exists** — the contracts-only extraction landed at `13810ac`.

---

## State correction

Earlier drafts targeted `557d334` and treated a 119/112 branch divergence as the dominant process risk. Both are obsolete.

- The divergence is gone. `6119652` and `ddd2624` are in `repo-android`'s history. Local `3449081`/`914e3d0` are local duplicates of those upstream commits under different hashes — the "merge the lines" work described previously is unnecessary.
- `main` is **stale at `557d334`**, two commits behind `repo-android`. It lacks the `pdf-ipc` extraction *and* the staging fix. See SEC-028.
- Findings below are re-baseleted against `cbafb998` by reading `app-host/build.gradle.kts`, `pdf-jail/build.gradle.kts`, the `cbafb998` tree, and `ArchitectureBoundaryTest.kt` directly.

**Evidence honesty:** verified this session — SEC-001/002/003/005/007/008/009/010/026 are file-level `CONFIRMED` against `cbafb998`. SEC-006 and SEC-014 were not re-checked and remain `SUPPORTED` from Phase 1. SEC-016…025 are inherited and `UNVERIFIED`.

---

## Locked decisions

| # | Decision |
|---|---|
| D1 | Ledger at repo root as `KILO_SECURITY_LEDGER.md`, committed. |
| D2 | `SEC-###` grouped by severity band, sequential within band, ordered by first observation. IDs permanent: reuse for the same invariant, attack surface, flow, or defect. Never re-mint. |
| D3 | Each ledger carries a `## Provenance` header. IDs are **never** cross-referenced between agent ledgers. |
| D4 | `FIXED` requires all five: (a) change landed, (b) in a production build path, (c) structural prevention, (d) regression test exists **and executes**, (e) independently verified. |
| D5 | Inherited older-tree findings seed at `UNVERIFIED` with **no severity** until revalidated. |
| D6 | This plan is authoritative for *what to do*; the ledger is authoritative for *what is true*. |
| D7 | The `13810ac` refactor is **not** evidence of remediation. A move that relocates a defect without closing it keeps its ID and moves it to `REGRESSED` under a new `REG-###`. |
| D8 | Boundary rules are rewritten as **allowlists**, not denylists. A denylist of two FQNs cannot constrain a parser surface. |
| D9 | Audit target is `repo-android@cbafb998`. `main` staleness is a tracked finding, not a prerequisite. |

---

## Task list

### R1 — Create the ledger

Write `KILO_SECURITY_LEDGER.md` per the schema below, seeding SEC-001…SEC-028 with the states in the register, plus provenance, invariant register, ID-reuse rules, status-transition rules, the REG-### register, and the phase-acceptance block.

Verify by: committed; every seeded ID present exactly once; no ID cross-referenced into another agent's ledger.

### R2 — SEC-001: delete `DummyPDFBox.kt` *(highest priority; REGRESSED)*

Delete `app-host/src/main/java/com/pdfchemy/app/logic/DummyPDFBox.kt`.

It has moved *into the Host module* since Phase 1 — it was in `pdfjail`, it is now in `app-host` in the main source set, in package `com.pdfchemy.app.logic`, alongside ~40 Host engine files. `app-host` has no PDFBox on its main classpath (only `testImplementation`), so this file is now the *only* `PDDocument`/`PDPage`/`PdfRenderer` the Host can resolve — and it returns `0`/`{}`/`false` instead of failing.

Before deleting, enumerate every `app-host` file that references these types and confirm none does. If any do, they are silently producing empty output today.

Verify by: file absent; `grep` for `com.pdfchemy.app.logic.PD` across `app-host` returns nothing; build green; regression test asserts no `com.pdfchemy.app.logic.PD*` type exists in any module.

### R3 — SEC-002, SEC-026: move BouncyCastle into the jail

`pdf-jail/build.gradle.kts` declares **no** BouncyCastle, and `pdf-jail` has **no** `configurations.all` excluding `jdk15to18`. `AndroidPdfCryptoSigner.kt` (at `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/`) imports `org.bouncycastle.*` and resolves it transitively through `pdfbox-android`. The `jdk15to18` exclusions exist only in `app-host`.

Actions: declare `bcprov-jdk18on:1.86` + `bcpkix-jdk18on:1.86` as `implementation` in `pdf-jail`; add `pdf-jail`'s own `configurations.all` excluding `bcprov-jdk15to18`, `bcpkix-jdk15to18`, `bcutil-jdk15to18`; remove both BC `implementation` lines from `app-host`.

SEC-026 is the general form: the exclusion is scoped to one module, so any future module inherits the excluded transitive. Close it structurally, not per-module.

Verify by: `pdf-jail` resolves 1.86; no `org.bouncycastle` on `app-host` compile or runtime classpath; signing tests pass.

### R4 — SEC-003: replace the `securityAudit` text grep

The task is unchanged from `557d334`: walks `src/main/java` for three literal strings, wired to `preBuild`. It is blind to `DummyPDFBox.kt` — that file sits in the very directory it scans and is not flagged. It also cannot see `pdf-jail`, `pdf-renderer`, `pdf-ipc`, or any future module, and reflection defeats it.

Actions:
1. Resolve `app-host` `releaseRuntimeClasspath`; fail on any `com.tom-roush` or `org.bouncycastle` artifact.
2. Scan **`app-host`'s own compiled classes** for constant-pool references to `com/tom_roush/`, `org/bouncycastle/`, and `android/graphics/pdf/PdfRenderer` — not the merged APK, which can never be green.
3. Repeat (1) and (2) for every module that is not `pdf-jail`/`pdf-renderer`, and add new modules automatically rather than by hand.
4. Drop the blanket `BitmapFactory` ban; scope it to parser-adjacent use.
5. Add a rule that fails on any class named `Dummy*` under `com.pdfchemy.app.logic` — the specific shape of this defect, so a future stub cannot be added silently.

Verify by: each gate **observed failing once** before it is trusted. An unexercised gate is not evidence.

### R5 — SEC-027: eliminate the split package *(new)*

`pdf-ipc/src/main/java/com/pdfchemy/app/logic/PdfContracts.kt` declares package `com.pdfchemy.app.logic` — the same package `app-host` uses for its engine layer. Java and Kotlin resolve same-package types without imports, across module boundaries. This is the exact mechanism that lets a Host file bind to a type it did not intend to.

Action: move `pdf-ipc`'s contracts to their own package (e.g. `com.pdfchemy.app.contract`), update `PdfJailClient.kt` and all consumers. Keep `M_HOST` depending on `M_CONTRACT` via `implementation` and `M_JAIL`/`M_RENDER` via `runtimeOnly` as they now are.

Related: SEC-001. The two are distinct defects with distinct fixes — deleting the stub does not fix the package, and renaming the package does not delete the stub. Their *combination* is what makes silent-empty-output reachable.

Verify by: `grep` shows `com.pdfchemy.app.logic` declared in exactly one module; build green.

### R6 — SEC-005: close the contracts boundary properly *(currently PARTIAL)*

The structure landed and is correct: `app-host` has `implementation(project(":pdf-ipc"))` and `runtimeOnly` for `pdf-jail` and `pdf-renderer`. Real PDFBox stays on `pdf-jail` only. **Preserve this.**

It is `PARTIAL` because D4 requires structural prevention and an executing regression test, and neither exists. Nothing asserts that `app-host` has no `pdf-jail`/`pdf-renderer` on its compile classpath, so a future `implementation(project(":pdf-jail"))` would silently pass.

Action: add a build-time gate resolving `app-host`'s `compileClasspath` and failing on any `pdf-jail`/`pdf-renderer`/parser artifact. See D8 — this is a classpath allowlist, not a source grep.

Verify by: gate fails when a `pdf-jail` dependency is temporarily added to `app-host`.

### R7 — SEC-009: make the boundary test an allowlist

`ArchitectureBoundaryTest.kt` is in `src/test` and does execute, so the earlier "declared but never run" concern is resolved. The rule is still ineffective: it matches only two exact FQNs (`com.tom_roush.pdfbox.pdmodel.PDDocument`, `android.graphics.pdf.PdfRenderer`), so `PDPageContentStream`, `PDFont`, `COSDocument`, `Loader` and the rest are unconstrained — and, decisively, the fake stub's FQN is `com.pdfchemy.app.logic.PDDocument`, so **a Host file calling the fake passes this rule**. It never references `pdf-ipc`.

Actions: invert to an allowlist (`app-host` may depend on a defined set of packages; anything else is a violation); import from `pdf-ipc` and assert the contract surface; add a rule rejecting any Host dependency on a type named `com.pdfchemy.app.logic.PD*`; drop the `..jail.engines..` and `NativeRendererCoordinator` blanket exemptions in favour of explicit, enumerated exceptions.

Verify by: the rule fails against (a) a planted `PDPageContentStream` reference and (b) a planted `com.pdfchemy.app.logic.PDDocument` reference, and passes on the clean tree.

### R8 — SEC-007, SEC-008, SEC-010: dependency rulings

- SEC-007 `androidx.security:security-crypto:1.1.0-alpha06` — still in `app-host`. Deprecated artifact pinned to an alpha in a security product. Justify in writing or remove.
- SEC-008 `com.google.code.gson:gson:2.10.1` — still in `pdf-jail`, replacing framework `org.json` on a boundary-parsing path. Review for unsafe deserialization; revert to `org.json` or constrain to a typed schema.
- SEC-010 `com.google.mlkit:text-recognition:16.0.1` — still in **both** `app-host` and `pdf-jail`. Remove from `app-host`; the Host must not run OCR over document-derived bytes. Keep `play-services-mlkit-document-scanner` in the Host (benign-camera case).

Verify by: dependencies absent, or a written justification; `:app-host` has no `com.google.mlkit:text-recognition`.

### R9 — SEC-006: revert `uriHash`

Revert to a non-reversible digest (SHA-256 truncated) or drop persistence. Base64 currently stores the full document URI — provider, path, usually filename — in reversible form in the persisted scroll key, where a 32-bit int used to be. 4 sites. Not re-checked at `cbafb998`; confirm before editing.

Verify by: no full URI in `SharedPreferences`; reader position still restores.

### R10 — SEC-014: `SecureScreenContent` coverage and teardown

Applied to ≥10 screens. Audit coverage against every document-rendering screen; confirm `DisposableEffect` clears the flag on dispose. **Partial application does not close this** — a `FLAG_SECURE` set and never cleared locks the whole app permanently.

Verify by: flag set and cleared; no document content renders unprotected.

### R11 — SEC-012: `IS_PREMIUM` sweep

`IS_PREMIUM` `buildConfigField` is confirmed absent from `app-host/build.gradle.kts`. Grep for remaining consumers.

Verify by: zero hits outside build-config history.

### R12 — SEC-028: reconcile `main` with `repo-android`

`main` is at `557d334`; `repo-android` is at `cbafb998`. Anything released from `main` today ships without the `pdf-ipc` extraction **and** without the staging fix, while still carrying all three open criticals.

Action: decide the release branch. Either fast-forward `main` to `cbafb998` once the criticals are closed, or declare `repo-android` the release branch and record that decision. Do not release from `main` while SEC-001/002/003 are open.

Local `3449081`/`914e3d0` are local duplicates of upstream `6119652`/`ddd2624`; they carry no unique work and may be dropped once confirmed.

Verify by: `git log main` and `git log repo-android` agree, or the release branch is documented.

### R13 — SEC-016…SEC-025: revalidate the inherited set

Revalidate against `cbafb998`; assign severity and status, or mark `FALSE_POSITIVE` / `SUPERSEDED`. Each must leave `UNVERIFIED`.

Seeded: broken redaction/compression (016), uncompilable tests (017), XFA sanitizer gaps (018), false claims in `PRIVACY.md`/`MANTRA.md` (019), ineffective process isolation (020), insecure signatures (021), intent-URI allowlist gaps (022), predictable temp files (023), resource exhaustion (024), incomplete backup protection (025).

### R14 — audit the unassessed surface → SEC-029+

`PdfGateway.kt` as single choke point (INV-06); all `pdf-jail/.../jail/engines/*Worker.kt` for quota gating and reachability; `JailQuotas.kt` coverage of every AIDL entry point; `AdversarialPdfTest.kt` for whether it asserts anything; the staging/digest/atomic-commit work begun in `cbafb998` (`StagedPdf`, `verifyAndRewind`) for completeness — "Step 2" implies further steps, and the invariant is only `PARTIAL` until the whole path is covered; `pdf-renderer` process config and whether the jail→renderer bind is real; `AndroidManifest.xml` for `allowBackup` / `dataExtractionRules` / `AdServices` / exported components (INV-11, INV-13).

Verify by: every AIDL entry point traced to a gateway call and a quota check.

### R15 — INV-15: reconcile user-facing claims

Reconcile `PRIVACY.md`, `MANTRA.md`, `FEATURES.md`, `KILO_SESSION_CONTEXT.md` against implemented behaviour. The context file asserts "Zero-Trust Hardening" and "IPC Sandbox Resource Quotas" as shipped features while three criticals are open — check specifically for claims describing protections that do not exist.

Verify by: no claim in shipping docs describes unimplemented protection.

---

## Invariant register

| ID | Invariant |
|---|---|
| INV-01 | The PDF parser surface is confined to the isolated jail process. The Host never resolves, compiles against, or calls a parser. |
| INV-02 | Cryptographic primitives and signing key material are resident only in the jail. |
| INV-03 | Boundary enforcement is structural (classpath / bytecode / architecture test), never text-grep based. |
| INV-04 | Untrusted document bytes are re-validated (digest, size, structure) after every process and thread hop. |
| INV-05 | Output is committed atomically: staged artifacts validated before publish; no partial output ever visible. |
| INV-06 | Every engine entry point is reachable only through a single gateway and is quota-gated (time, memory, pages, recursion). |
| INV-07 | IPC contracts live in a contracts-only module; the Host does not compile against implementation modules, and contract types do not share packages with the Host. |
| INV-08 | Screen capture is blocked on every document-rendering surface, and the flag is cleared on dispose. |
| INV-09 | Persisted state contains no reversible document identifier (URI, filename, path). |
| INV-10 | Document-derived content (OCR, text, thumbnails) is produced inside the jail, never in the Host. |
| INV-11 | Backup and exfiltration surfaces are closed (`allowBackup`, `dataExtractionRules`, `AdServices`). |
| INV-12 | Dependencies are declared where used, pinned to non-deprecated non-alpha versions, CVE-free, never relied on transitively. |
| INV-13 | Components (services, receivers, providers) unexported unless required, permission-guarded when exported. |
| INV-14 | Signing and release configuration deterministic, non-failing at configure time, verifiable. |
| INV-15 | User-facing security and privacy claims match implemented behaviour. |

INV-07 was extended at this revision to cover package separation, because `13810ac` showed the contracts-only structure is necessary but not sufficient.

---

## Ledger schema

Sections: provenance → invariant register → severity bands → ID-reuse rules → status-transition rules → finding records → regression-event register → phase acceptance.

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
Related:       <other SEC-### / REG-### / older-tree label>
```

### Finding register as of `cbafb998`

| ID | Sev | Type | Inv | Status | Evidence | Summary |
|---|---|---|---|---|---|---|
| SEC-001 | CRITICAL | PARSER | 01 | **REGRESSED** | CONFIRMED | `DummyPDFBox.kt` moved `pdfjail` → `app-host/src/main/java/com/pdfchemy/app/logic/`. Now the Host's only resolvable `PDDocument`, returning `0`/`{}`/`false`. |
| SEC-002 | CRITICAL | DEPENDENCY | 02, 12 | OPEN | CONFIRMED | `pdf-jail` declares no BouncyCastle and no `jdk15to18` exclusion; `AndroidPdfCryptoSigner.kt` resolves it transitively. Exclusions exist only in `app-host`. |
| SEC-003 | CRITICAL | BUILD | 03 | OPEN | CONFIRMED | `securityAudit` unchanged: single-module text grep over `app-host/src/main/java`, wired to `preBuild`. Scans the directory containing `DummyPDFBox.kt` without flagging it. |
| SEC-004 | HIGH | BOUNDARY | 01 | OPEN | SUPPORTED | Host `PdfRenderer`/`BitmapFactory` exposure, ~28 sites. **One finding with a path count.** Interacts with SEC-001 and SEC-027. |
| SEC-005 | HIGH | BOUNDARY | 07 | **PARTIAL** | CONFIRMED | `pdf-ipc` landed; `app-host` uses `implementation(:pdf-ipc)` + `runtimeOnly` for jail/renderer. Structure correct; no gate or test prevents regression. |
| SEC-006 | HIGH | PRIVACY | 09 | OPEN | SUPPORTED | `uriHash` = full document URI in reversible Base64, persisted as scroll key, 4 sites. Not re-checked at `cbafb998`. |
| SEC-007 | HIGH | DEPENDENCY | 12 | OPEN | CONFIRMED | `security-crypto:1.1.0-alpha06` still in `app-host`. |
| SEC-008 | HIGH | DEPENDENCY | 12 | OPEN | CONFIRMED | `gson:2.10.1` still in `pdf-jail`. |
| SEC-027 | HIGH | BOUNDARY | 07, 03 | **NEW** | CONFIRMED | `pdf-ipc` declares package `com.pdfchemy.app.logic`, shared with `app-host`. Same-package types resolve without import across the module boundary. |
| SEC-028 | HIGH | BUILD | 12, 14 | **NEW** | CONFIRMED | `main` at `557d334`, two commits behind `repo-android@cbafb998`. A release from `main` ships all three open criticals and neither the `pdf-ipc` nor staging fix. |
| SEC-009 | MEDIUM | TEST | 03 | **PARTIAL** | CONFIRMED | `ArchitectureBoundaryTest` exists in `src/test` and executes, but matches only two FQNs. **Cannot detect the fake stub** — a Host call to `com.pdfchemy.app.logic.PDDocument` passes. Never references `pdf-ipc`. |
| SEC-010 | MEDIUM | PRIVACY | 10 | OPEN | CONFIRMED | `text-recognition:16.0.1` in both `app-host` and `pdf-jail`. |
| SEC-014 | MEDIUM | UI | 08 | **PARTIAL** | SUPPORTED | `SecureScreenContent` on ≥10 screens; coverage and teardown unverified. |
| SEC-026 | MEDIUM | DEPENDENCY | 12 | OPEN | CONFIRMED | `jdk15to18` exclusion scoped to `app-host` only; any future module inherits the excluded transitive. |
| SEC-011 | — | UI | 08 | **FIXED** | CONFIRMED | `isScreenshotRun` gated by `BuildConfig.DEBUG`. |
| SEC-012 | — | AUTH | 08 | **FIXED** | CONFIRMED | `IS_PREMIUM` `buildConfigField` absent from `app-host/build.gradle.kts`. |
| SEC-013 | — | BUILD | 14 | **FIXED** | CONFIRMED | Release signing guarded by `if (password != null)`; no configure-time throw. |
| SEC-015 | — | BUILD | 12 | **SUPERSEDED** | CONFIRMED | Divergence resolved — `6119652` and `ddd2624` are in `repo-android` history. Superseded by R12/SEC-028. |
| SEC-016…025 | *none* | *various* | — | **UNVERIFIED** | — | Inherited older-tree set, no severity per D5. R13. |

### ID-reuse rules

- Reuse the existing `SEC-###` when invariant, attack surface, flow, or defect matches.
- **SEC-001 stayed SEC-001 across the module move.** Relocating a defect is not remediating it; it moves to `REGRESSED` under REG-004. It does not become a new ID, and it is not re-reported as a new critical.
- **SEC-003 absorbed the `DummyPDFBox` blindness.** "The audit does not flag the file it scans" is evidence within the audit-gate defect, not a new finding. It becomes a new ID only if fixing the gate would not fix the blindness.
- **SEC-005 covers the missing boundary gate, not the landed extraction.** The extraction closed half the finding; the other half keeps the ID.
- **SEC-004 is one finding with a path count.** The ~28 call sites are its evidence.
- **SEC-001 and SEC-027 are two IDs, not one.** Distinct defects, distinct fixes. Their interaction is recorded in both records' `Related` fields.

### Status-transition rules

- `FIXED` requires all five in D4. Missing any one keeps it `PARTIAL` — this is why SEC-005 and SEC-009 are `PARTIAL`, not `FIXED`.
- **Partial reduction in scope or exploitability does not close a finding.** Applies to SEC-014 and SEC-005.
- Re-broken by a later commit → `REGRESSED` plus a `REG-###` referencing the original ID. Never a new `SEC-###`.
- `UNVERIFIED` carries no severity and is not counted in phase acceptance.
- `SUPERSEDED` requires a stated reason and retains the ID permanently.

### Regression-event register

```
### REG-### — <event>   | date | commit range | triggered by
Re-tested: SEC-###, SEC-###  ->  status transition per finding
```

- **REG-001** — `557d334` phase-6 event.
- **REG-002** — `6119652`/`ddd2624` Android split + build fixes (the local `3449081`/`914e3d0` duplicates).
- **REG-003** — first full re-verification sweep. Every `SEC-###` retested, every transition recorded.
- **REG-004** — `13810ac` module rename + `pdf-ipc` extraction. **SEC-001 → `REGRESSED`** (stub moved into the Host). SEC-005 → `PARTIAL` (structure landed, no gate). SEC-027 opened.
- **REG-005** — `cbafb998` staging invariant (`StagedPdf`, `verifyAndRewind` fails closed). INV-04/INV-05 in progress; no `SEC-###` closed. SEC-028 opened (`main` stale).

---

## Validation

**Phase acceptance gate:** zero open `CRITICAL`, and every `HIGH` either `FIXED` (all five in D4) or explicitly accepted in writing by the project owner with a rationale.

1. `git rev-parse HEAD` = `cbafb998`; `origin/repo-android` is the audited ref.
2. `:app-host` release and `:pdf-jail` build clean.
3. `:app-host` `compileClasspath` contains no `pdf-jail`, `pdf-renderer`, or `com.tom-roush`; `releaseRuntimeClasspath` contains no `com.tom-roush`, `org.bouncycastle`, or `com.google.mlkit:text-recognition`.
4. `:app-host` compiled classes contain no constant-pool reference to `com/tom_roush/`, `org/bouncycastle/`, `android/graphics/pdf/PdfRenderer`, or any `com/pdfchemy/app/logic/PD*` type.
5. `com.pdfchemy.app.logic` is declared in exactly one module.
6. Each R4 gate observed **failing** once before acceptance.
7. R7's ArchUnit rule fails against both a planted real-PDFBox reference and a planted fake-stub reference.
8. No full document URI in any `SharedPreferences` key or value.
9. Every AIDL entry point traced to a `PdfGateway` call and a `JailQuotas` check.
10. `REG-003` completed; no finding left `UNVERIFIED`; every `REGRESSED` finding has an open `REG-###`.

---

## Risks

- **SEC-001 may already be silently active.** `DummyPDFBox.kt` is in the Host alongside ~40 engine files. If any of them binds to the fake types, the app is producing empty PDFs in release with no error. R2 enumerates references before deleting — do that first.
- **A structural fix is not a behavioural one.** The `pdf-ipc` extraction is real progress, but the split package it introduced (SEC-027) is the same class of hazard the architecture was built to prevent. Renaming the package is not cosmetic.
- **The existing gates give false confidence.** `securityAudit` runs on every build and passes. `ArchitectureBoundaryTest` runs and passes. Both pass *because of the exact defects they should catch*. Treat a green gate as unproven until it has been observed red.
- **R14's staging work is in progress.** `cbafb998` is "Step 2" with "verifyAndRewind fails closed" — partial by its own framing. Do not record INV-04/INV-05 as satisfied until the full path is covered.
- **Declared-but-unexecuted tests remain the most likely silent failure** (SEC-009 was declared and ran for the first time at `13810ac`). D4(d) and validation steps 6–7 exist for this class.
- **R13 may close most of SEC-016…025 as `SUPERSEDED`.** That is success, not wasted work.
- **Another agent may push further commits.** `cbafb998` is a moving target. R0 of any follow-up pass must re-read `origin/repo-android` and re-baseline touched findings rather than trusting this document.

## Out of scope

- Desktop (`pdfchemy` / `pdfchemy-linux` / `pdfchemy-windows`) repositories.
- Play Console key material; store-console action, not a code change.
- Performance work not tied to a quota or resource-exhaustion finding.

## Open questions

1. **Nemotron reconciliation.** No nemotron ledger exists in this workspace, on any ref, or in `KILO_SESSION_CONTEXT.md` (read at `cbafb998` — it is a project briefing, not a finding register). D3 governs: separate ID spaces, no silent absorption, human-performed unions with both provenance headers retained. Nothing else depends on it.
2. **Is `cbafb998` an audit point or a stepping stone?** It is self-described "Step 2". If steps 3+ are landing, the ledger should be re-baselined per commit rather than per session. Confirm whether more steps are expected before R13 and R14, so revalidation is not repeated against a moving tree.
