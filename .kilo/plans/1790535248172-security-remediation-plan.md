# Security Remediation Plan — pdfchemy-android

**Target:** `security/audit-remediation-2026-10-01` @ `f6b6f7df581d2b4fccf46c9def1ac7c5e076fb30` (draft PR #1, base `repo-android`)
**Baseline audited:** `repo-android` @ `cbafb998`
**Ledger of record:** `KILO_SECURITY_LEDGER.md` (repo root, committed) — created by R1
**Companion record:** `SECURITY_REMEDIATION_STATUS.md` (PR #1) — authoritative for PR #1's own work, not for the finding register

---

## ID reconciliation (decide this first, it constrains everything)

`SECURITY_REMEDIATION_STATUS.md` already mints `SEC-001`…`SEC-017` with meanings unrelated to a previous ad-hoc numbering. Two documents in one repo would otherwise disagree about what `SEC-004` is.

**Rules applied:**
1. **Adopt their ID when the defect is genuinely the same.** Verified match: their `SEC-012` (Bouncy Castle belongs to jail) *is* my old SEC-002. It adopts `SEC-012`, already `FIXED`.
2. **New IDs run from `SEC-018`** for anything their register never raised.
3. **Their `SEC-001`…`SEC-017` are never renumbered.** They are referenced in seven commit messages; changing them breaks traceability.
4. **The inherited unverified set gets no IDs until revalidated.** Several look already fixed by PR #1 (XFA → their SEC-010, uncompilable tests → their SEC-015, resource exhaustion → their SEC-016). Minting IDs first would create duplicates for closed issues. They live in a revalidation queue until checked.

Crosswalk (old → final): `002→012 FIXED`, `001→018 FIXED`, `003→019`, `004→020`, `005→021`, `006→022`, `007→023`, `008→024 REGRESSED`, `009→025`, `010→026`, `011→029`, `012→030`, `013→031`, `014→027`, `015→032 SUPERSEDED`.

---

## State at `f6b6f7d`

**Closed by PR #1** (verified by reading the build files and tree at `f6b6f7d`):
- `DummyPDFBox.kt` deleted repo-wide. It was the fake parser API sitting in the Host's main source set.
- BouncyCastle moved Host → jail, with `jdk15to18` exclusions duplicated into `pdf-jail` via `configurations.configureEach`. Not left Host-scoped.
- Coil removed from Host; Jackson narrowed to `jackson-module-kotlin`; jsoup strictly pinned; `lint { abortOnError = true }`; new `securityArchitecture` gate on `preBuild`; 47 JVM tests.

**Not closed by PR #1** — each verified at `f6b6f7d`:

| Finding | State |
|---|---|
| `securityAudit` text grep | **Unchanged, byte-for-byte**, since `557d334`. Their own doc: "Original `securityAudit` retained." A second gate was added; the broken one still runs on every build. |
| Split package `com.pdfchemy.app.logic` | **Grew from 1 file to 8** in `pdf-ipc` while the contracts boundary was being hardened. |
| `gson:2.10.1` | **Moved onto the Host** — now in `app-host` *and* `pdf-jail`. |
| `text-recognition:16.0.1` | Still in `app-host` main dependencies. |
| `security-crypto:1.1.0-alpha06` | Still present. |
| `main` stale | Still `557d334`. PR #1 targets `repo-android`, so `main` is now 7 commits behind. |

**Honest credit:** the API 36 OCR crash (`getAllHalInstanceNames` null deref under isolated UID) is self-reported as a release blocker with no claim of closure. Release is blocked; the isolation boundary it broke is stated as still enforced.

---

## Tasks

### R1 — Create the reconciled ledger

Write `KILO_SECURITY_LEDGER.md`: provenance (owner, date, audited commit, companion record), invariant register, severity bands, ID-reuse rules, status-transition rules, finding records per the schema, the crosswalk table above, the REG-### register, phase acceptance.

Allocate `SEC-018`…`SEC-032` per the crosswalk. Adopt their `SEC-012` and `SEC-001` as-is with a pointer recording that they were folded into this register.

Verify by: committed; no ID appears twice; every crosswalk row resolves to exactly one final ID.

### R2 — R1a–R1c: confirm the two closure claims before crediting them

PR #1's evidence for the `DummyPDFBox` deletion and the BouncyCastle move is its own prose. Confirm on the tree: `DummyPDFBox` absent from all modules; `pdf-jail` resolves `1.86` and excludes `jdk15to18`; `app-host` has no `org.bouncycastle` on compile or runtime classpath.

Verify by: build resolves as stated; `SEC-018` and `SEC-012` move to `FIXED` only after all five D4 conditions, otherwise `PARTIAL`.

### R3 — SEC-019: replace the retained `securityAudit` *(highest priority)*

The task is unchanged since `557d334` and still scans `app-host/src/main/java` for three literal strings. It is single-module, reads source text rather than bytecode, is defeated by reflection, cannot see `pdf-jail`/`pdf-renderer`/`pdf-ipc` or any future module, and bans `BitmapFactory` which the Host legitimately needs.

Keep it as a cheap source-level tripwire, but demote it from *the* gate to *a* gate, and replace the real enforcement:
1. Resolve `app-host` `releaseRuntimeClasspath`; fail on any `com.tom-roush` or `org.bouncycastle` artifact.
2. Scan **`app-host`'s compiled classes** for constant-pool refs to `com/tom_roush/`, `org/bouncycastle/`, `android/graphics/pdf/PdfRenderer`, and any `com/pdfchemy/app/logic/PD*` type. Scan compiled output, not source.
3. Apply (1) and (2) to every module that is not `pdf-jail`/`pdf-renderer`, added automatically rather than by hand.
4. Drop the blanket `BitmapFactory` ban; scope to parser-adjacent use.
5. Fail on any class named `Dummy*` under `com.pdfchemy.app.logic`, so a future stub cannot be re-added silently.

Verify by: each gate **observed failing once** before it is trusted. An unexercised gate is not evidence — the current one has run green through four audits including one that caught the stub.

### R4 — SEC-024: remove `gson` from the Host *(regression)*

`gson:2.10.1` is now in `app-host` as well as `pdf-jail`. A JSON deserialization library was moved onto the Host side of the boundary during the refactor. Determine what introduced the Host-side need; if it is boundary parsing, move the parse into `pdf-jail` and keep the DTO schema explicit. If the Host genuinely needs JSON, replace with `org.json` or a typed schema and document why.

Verify by: `gson` appears in one module, or a written justification exists.

### R5 — SEC-019 companion: SEC-021 close the contracts gate

`pdf-ipc` exists and `app-host` correctly uses `implementation(project(":pdf-ipc"))` with `runtimeOnly` for jail and renderer. Nothing asserts it. A future `implementation(project(":pdf-jail"))` would pass silently.

Action: a build gate resolving `app-host`'s `compileClasspath` and failing on any `pdf-jail` / `pdf-renderer` / parser artifact, written as a **classpath allowlist** rather than a source grep.

Verify by: gate fails when a `pdf-jail` dependency is temporarily added.

### R6 — SEC-025: make the boundary test an allowlist

`ArchitectureBoundaryTest` is in `src/test` and does execute, but matches only two exact FQNs. `PDPageContentStream`, `PDFont`, `COSDocument`, and `Loader` are unconstrained, and — decisively — the fake stub's FQN is `com.pdfchemy.app.logic.PDDocument`, so a Host call to the fake **passes this rule**. It never references `pdf-ipc`.

Action: invert to an allowlist of permitted Host dependencies; reject any Host dependency on `com.pdfchemy.app.logic.PD*`; enumerate explicit exceptions instead of the blanket `..jail.engines..` and `NativeRendererCoordinator` exemptions; cover the `pdf-ipc` contract surface.

Verify by: the rule fails against a planted `PDPageContentStream` reference **and** a planted `com.pdfchemy.app.logic.PDDocument` reference, and passes clean.

### R7 — SEC-022, SEC-023, SEC-026: privacy and dependency rulings

- **SEC-022** `uriHash` = full document URI in reversible Base64, persisted as the reader scroll key, 4 sites. Revert to a truncated SHA-256 or drop persistence.
- **SEC-023** `security-crypto:1.1.0-alpha06` — deprecated artifact pinned to an alpha in a security product. Justify in writing or remove.
- **SEC-026** `text-recognition:16.0.1` in `app-host` — the Host must not run OCR over document-derived bytes. Keep `play-services-mlkit-document-scanner` (benign-camera case). Note this interacts with the open API 36 OCR blocker: removing it from the Host does not resolve the jail-side NNAPI crash.

Verify by: no full URI in `SharedPreferences`; `app-host` has no `text-recognition`; dependencies absent or justified.

### R8 — SEC-027: `SecureScreenContent` coverage and teardown

Applied to ≥10 screens; coverage against every document-rendering screen and the `DisposableEffect` teardown are unverified. A `FLAG_SECURE` set and never cleared locks the whole app.

Verify by: flag set and cleared; no document content renders unprotected.

### R9 — SEC-028: reconcile `main` with the remediation branch

`main` is at `557d334`. PR #1 targets `repo-android`. A release from `main` ships all three original criticals and none of the remediation.

Action: either fast-forward `main` to `f6b6f7d` once the criticals close, or declare the remediation branch the release branch and record that decision. **Do not release from `main` while SEC-019 is open.** Local `3449081`/`914e3d0` duplicate upstream `6119652`/`ddd2624` and can be dropped once confirmed.

Verify by: `main` and the release branch agree, or the release branch is documented.

### R10 — revalidate the inherited queue *(no IDs until checked)*

For each item: check `f6b6f7d`, then adopt the matching existing ID if PR #1 already raised it, else mint from `SEC-033`.

Broken redaction/compression · XFA sanitizer gaps (→ their `SEC-010`) · ineffective process isolation (→ their `SEC-001`/`006`/`007`) · insecure signatures and DN injection (→ their `SEC-012`) · intent-URI allowlist gaps (→ their `SEC-004`/`005`) · predictable temp files (→ their `SEC-017`) · resource exhaustion (→ their `SEC-016`) · incomplete backup protection · uncompilable tests (→ their `SEC-015`) · false claims in `PRIVACY.md`/`MANTRA.md`.

Verify by: each queue item leaves `UNVERIFIED` with a status and either an adopted or newly minted ID.

### R11 — reconcile user-facing claims

Check `PRIVACY.md`, `MANTRA.md`, `FEATURES.md`, and `KILO_SESSION_CONTEXT.md` against implemented behaviour. `KILO_SESSION_CONTEXT.md` asserts "Zero-Trust Hardening" and "IPC Sandbox Resource Quotas" as shipped while blockers are open. PR #1's own `SECURITY_REMEDIATION_STATUS.md` is correctly worded and needs no change — use it as the model.

Verify by: no shipping doc describes unimplemented protection.

---

## Invariant register

| ID | Invariant |
|---|---|
| INV-01 | The PDF parser surface is confined to the isolated jail. The Host never resolves, compiles against, or calls a parser. |
| INV-02 | Cryptographic primitives and signing key material are resident only in the jail. |
| INV-03 | Boundary enforcement is structural (classpath / bytecode / architecture test), never text-grep based. |
| INV-04 | Untrusted document bytes are re-validated (digest, size, structure) after every process and thread hop. |
| INV-05 | Output is committed atomically: staged artifacts validated before publish; no partial output visible. |
| INV-06 | Every engine entry point is reachable only through a single gateway and is quota-gated. |
| INV-07 | IPC contracts live in a contracts-only module; the Host does not compile against implementation modules, **and contract types do not share packages with the Host**. |
| INV-08 | Screen capture is blocked on every document-rendering surface, and the flag is cleared on dispose. |
| INV-09 | Persisted state contains no reversible document identifier (URI, filename, path). |
| INV-10 | Document-derived content (OCR, text, thumbnails) is produced inside the jail, never in the Host. |
| INV-11 | Backup and exfiltration surfaces are closed (`allowBackup`, `dataExtractionRules`, `AdServices`). |
| INV-12 | Dependencies are declared where used, pinned to non-deprecated non-alpha versions, CVE-free, never relied on transitively. |
| INV-13 | Components unexported unless required, permission-guarded when exported. |
| INV-14 | Signing and release configuration deterministic, non-failing at configure time, verifiable. |
| INV-15 | User-facing security and privacy claims match implemented behaviour. |

INV-07's package clause is carried forward: the contracts-only extraction landed while `com.pdfchemy.app.logic` remained split across `app-host` and `pdf-ipc`, which grew from 1 file to 8.

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

### Finding register

`SEC-001`…`SEC-017` — adopted from `SECURITY_REMEDIATION_STATUS.md`, not renumbered. `SEC-012` verified `FIXED` at `f6b6f7d`.

| ID | Sev | Type | Inv | Status | Evidence | Summary |
|---|---|---|---|---|---|---|
| SEC-018 | CRITICAL | PARSER | 01 | **FIXED** | CONFIRMED | `DummyPDFBox.kt` — fake `PDDocument`/`PDPage`/`PdfRenderer` in the Host's main source set, members returning `0`/`{}`/`false`. Regressed into the Host by the `13810ac` module move; deleted at `f6b6f7d`. |
| SEC-019 | CRITICAL | BUILD | 03 | **OPEN** | CONFIRMED | `securityAudit` retained verbatim since `557d334`: single-module, source-text grep for three literals, on `preBuild`. Blind to `SEC-018`, blind to other modules, defeated by reflection. |
| SEC-020 | HIGH | BOUNDARY | 01 | OPEN | SUPPORTED | Residual Host `PdfRenderer`/decoder exposure, ~28 sites. One finding with a path count. Related SEC-001. |
| SEC-021 | HIGH | BOUNDARY | 07 | **PARTIAL** | CONFIRMED | Contracts-only structure landed correctly; no gate or test prevents `app-host` from re-acquiring a jail/parser compile dependency. |
| SEC-022 | HIGH | PRIVACY | 09 | OPEN | SUPPORTED | Full document URI persisted in reversible Base64 scroll key, 4 sites. Not re-checked since `557d334`. |
| SEC-023 | HIGH | DEPENDENCY | 12 | OPEN | CONFIRMED | `security-crypto:1.1.0-alpha06` still in `app-host`. |
| SEC-024 | HIGH | DEPENDENCY | 12 | **REGRESSED** | CONFIRMED | `gson:2.10.1` added to `app-host` while remaining in `pdf-jail`. Was one module; now spans the boundary. |
| SEC-025 | MEDIUM | TEST | 03 | OPEN | CONFIRMED | `ArchitectureBoundaryTest` executes but matches 2 FQNs. Blind to the fake stub. Never references `pdf-ipc`. |
| SEC-026 | HIGH | PRIVACY | 10 | OPEN | CONFIRMED | `text-recognition:16.0.1` still in `app-host` main dependencies. |
| SEC-027 | MEDIUM | UI | 08 | **PARTIAL** | SUPPORTED | `SecureScreenContent` on ≥10 screens; coverage and teardown unverified. |
| SEC-028 | HIGH | BUILD | 12, 14 | OPEN | CONFIRMED | `main` at `557d334`, 7 commits behind. Releasing from `main` ships the unremediated tree. |
| SEC-029 | — | UI | 08 | **FIXED** | CONFIRMED | `isScreenshotRun` gated by `BuildConfig.DEBUG`. |
| SEC-030 | — | AUTH | 08 | **FIXED** | CONFIRMED | `IS_PREMIUM` `buildConfigField` removed. |
| SEC-031 | — | BUILD | 14 | **FIXED** | CONFIRMED | Release signing guarded by `if (password != null)`. |
| SEC-032 | — | BUILD | 12 | **SUPERSEDED** | CONFIRMED | Branch divergence resolved; `6119652`/`ddd2624` are in `repo-android` history. Superseded by SEC-028. |

### Status-transition rules

- `FIXED` requires all five: change landed, in a production path, structural prevention, regression test that **executes**, independently verified.
- **Partial reduction does not close a finding.** "Applied to 10 screens" is not "applied to all screens".
- Re-broken by a later commit → `REGRESSED` plus a `REG-###` referencing the original ID. Never a new ID.
- `UNVERIFIED` carries no severity and is excluded from phase acceptance.

### Regression-event register

- **REG-001** `557d334` phase-6 event.
- **REG-002** `6119652`/`ddd2624` Android split + build fixes.
- **REG-003** first full re-verification sweep.
- **REG-004** `13810ac` module rename + `pdf-ipc` extraction. SEC-018 → `REGRESSED` (stub moved into the Host); SEC-021 → `PARTIAL`; `SEC-027` split package opened.
- **REG-005** `cbafb998` staging invariant (`StagedPdf`, `verifyAndRewind`).
- **REG-006** `f6b6f7d` / PR #1. SEC-018 → `FIXED`; SEC-012 → `FIXED`; **SEC-024 → `REGRESSED`** (gson added to Host); SEC-019 unchanged; split package grew 1 → 8 files.

---

## Validation

**Phase acceptance:** zero open `CRITICAL`, and every `HIGH` either `FIXED` (all five conditions) or explicitly accepted in writing by the project owner with a rationale.

1. `git rev-parse HEAD` = `f6b6f7d` (or its successor after R3–R9 merge).
2. `:app-host` release and `:pdf-jail` build clean.
3. `:app-host` `compileClasspath` free of `pdf-jail`, `pdf-renderer`, `com.tom-roush`, `org.bouncycastle`. `releaseRuntimeClasspath` free of `com.tom-roush`, `org.bouncycastle`, `com.google.mlkit:text-recognition`.
4. `:app-host` compiled classes contain no constant-pool ref to `com/tom_roush/`, `org/bouncycastle/`, `android/graphics/pdf/PdfRenderer`, or `com/pdfchemy/app/logic/PD*`.
5. `com.pdfchemy.app.logic` declared in exactly one module — currently 9 files across `app-host` and `pdf-ipc`, so this **fails today**.
6. Each new gate observed **failing** once before acceptance.
7. `ArchitectureBoundaryTest` fails against a planted real-PDFBox ref and a planted fake-stub ref.
8. No full document URI in any `SharedPreferences` key or value.
9. Every AIDL entry point traced to a gateway call and a quota check.
10. `REG-003` complete; no finding `UNVERIFIED`; every `REGRESSED` finding has an open `REG-###`.

## Risks

- **PR #1 is substantial and mostly sound, which is exactly why the residue matters.** Two verified regressions (`gson` onto the Host, split package 1 → 8) arrived *during* the hardening, and the broken `securityAudit` was retained while a new gate was added beside it. A green build now certifies less than it appears to.
- **`securityAudit` has passed through four audits, including one that found the stub sitting in the directory it scans.** Treat any green gate as unproven until observed red.
- **The API 36 OCR blocker is unresolved and self-declared.** Do not record INV-04/INV-05 or OCR-related invariants as satisfied while `getAllHalInstanceNames` crashes under the isolated UID. `cbafb998` is self-described "Step 2" and PR #1 does not close it.
- **The reconciliation cuts both ways.** Adopting their IDs means `SECURITY_REMEDIATION_STATUS.md` remains authoritative for 17 findings whose evidence this plan has not independently reviewed. Their doc says so itself: "not a security certification."
- **The branch keeps moving.** Re-derive `origin/repo-android` at the start of any follow-up pass and re-baseline touched findings rather than trusting this document.

## Out of scope

- Desktop repositories (`pdfchemy`, `pdfchemy-linux`, `pdfchemy-windows`).
- Play Console key material.
- The API 36 OCR backend decision — PR #1 explicitly leaves it pending and unapproved. This plan does not pre-empt it.

## Open questions

1. **Nemotron reconciliation** — resolved in practice. No nemotron ledger exists; `SECURITY_REMEDIATION_STATUS.md` is the counterpart, and ID adoption above reconciles it. `KILO_SESSION_CONTEXT.md` is a project briefing, not a finding register.
2. **Is PR #1 the audit target or an input?** It is an input. It closes two criticals and regresses one HIGH. The register must be re-derived on its merge commit, not on `f6b6f7d`, before any closure is credited.