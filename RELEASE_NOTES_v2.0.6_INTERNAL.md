# PDFchemy Tools Android v2.0.6 — Internal Release Notes (Agent/Developer)

> **Version:** 2.0.6 (versionCode 13)  
> **Branch:** `repo-android`  
> **Tag:** `v2.0.6` (pushed to `origin` and `repo-android`)  
> **Base:** v2.0.5 (versionCode 12, commit `41c0513`)  
> **Commits:** 10 commits since v2.0.4 → 11 commits since v2.0.5  
> **Date:** 2026-09-28

---

## Commit History (v2.0.5 → v2.0.6)

| Commit | Date | Type | Summary |
|--------|------|------|---------|
| `13810ac` | Sep 28 | **ARCH** | Extract shared DTOs to `pdf-ipc`; rename modules: `app` → `app-host` + new `pdf-jail` / `pdf-renderer` / `pdf-ipc` |
| `557d334` | Sep 27 | **SECURITY** | Phase 6 adversarial regression tests; `SecureScreenContent` on remaining UI surfaces; rotate Play Console key |
| `2194e23` | Sep 27 | **TEST** | Fix test compilation; migrate testing signatures to `Uri` |
| `c8fcccf` | Sep 25 | **FIX** | Two-Service IPC Topology: bypass legacy `PdfWorkerService` in `SandboxCoordinator` |
| `fb24217` | Sep 24 | **SECURITY** | Quotas enforcement; boundary test renamed; output atomicity |
| `b3f6596` | Sep 24 | **CHORE** | Remove dev artifacts; IPC & Billing hardening |
| `8433d68` | Sep 23 | **FEAT** | Migrate Attachment & Booklet engines to `PdfGateway` IPC |
| `332a363` | Sep 23 | **SECURITY** | Fix `PdfOutlineReaderWorker` & `PdfTextExtractorWorker` Binder vulnerabilities |
| `6119652` | Sep 22 | **CHORE** | Split android branch; remove desktop module |
| `ddd2624` | Sep 22 | **FIX** | Resolve build errors; BouncyCastle packaging conflicts |

---

## Detailed Change Breakdown

### 1. Module Architecture Refactor (`13810ac`) — **BREAKING INTERNAL**

**Before:**
```
app/ (monolithic: UI + PDF parsing + rendering + IPC)
```

**After:**
```
app-host/          # UI, ViewModels, orchestration, IPC client
pdf-jail/          # Sandbox worker: PdfBox ops, compression, editing, security, forms
pdf-renderer/      # Dedicated renderer: thumbnails, page images, print rasterization
pdf-ipc/           # Shared: DTOs, JailIpc protocol, quotas (JailQuotas), callbacks
```

**Key Files:**
- `pdf-ipc/src/main/java/com/pdfchemy/ipc/` — `JailIpc.kt`, `JailQuotas.kt`, `PdfCommand/Response DTOs`, `IPdfJailCallback.aidl`
- `pdf-jail/src/main/java/com/pdfchemy/jail/` — `PdfJailWorker.kt`, `JailIpcHandler.kt`, workers (Compress, Edit, Security, Forms, etc.)
- `pdf-renderer/src/main/java/com/pdfchemy/renderer/` — `PdfRendererWorker.kt`, thumbnail/page rendering
- `app-host/build.gradle.kts` — `runtimeOnly(project(":pdf-jail"))`, `runtimeOnly(project(":pdf-renderer"))`, `implementation(project(":pdf-ipc"))`

**Security Audit Task** (`app-host/build.gradle.kts:185-208`): Scans host sources for forbidden imports (`com.tom_roush.pdfbox`, `android.graphics.pdf.PdfRenderer`, `BitmapFactory`) — fails build if violated.

---

### 2. Phase 6 Adversarial Regression Tests (`557d334`)

**New Test Suites** (in `pdf-jail` / `pdf-ipc` test sources):
- `ArkhamEscapeTest` — Worker escape attempts (fs, process, credentials)
- `IpcFuzzTest` — Malformed frames, replay, sequence ID manipulation, forged `ARKHAM_SECRET`
- `HostSurvivalTest` — Host resilience: worker crashes, stderr spam, OOM, hang timeouts
- `ParserBrutalityTest` — ZIP bombs (2M char EPUB limit), corrupted streams, truncated trailers, XREF attacks
- `QuotaEnforcementTest` — `MAX_OUTPUT_FILES=1000`, `MAX_BATCH_FDS=50`, `MAX_BATCH_INPUT_BYTES=50MB`, `MAX_RENDER_DIMENSION=8192`

**SecureScreenContent** applied to:
- `PdfEditorScreen`, `ReflowReaderScreen`, `SecurityScreens`, `QuickFillSignScreen`, `SignPdfScreen`, `AcroFormScreens`, `FormBuilderScreen`, `VisualSigner`, `WatermarkScreen`, `HeaderFooterScreen`, `PageNumberScreen`, `BatesNumberScreen`, `FindAndReplaceScreen`, `ImageReplacerScreen`, `RedactionScreen`, `DocumentSanitizerScreen`, `MetadataSanitizerScreen`, `PdfAValidatorScreen`, `FontInspectorScreen`, `AttachmentManagerScreen`, `RepairPdfScreen`, `EncryptPdfScreen`, `DecryptPdfScreen`, `VanguardPicker`, `MainActivity` pickers

---

### 3. IPC Topology Fix (`c8fcccf`)

**Problem:** Legacy `PdfWorkerService` (single-process `PdfRenderer` + `PdfBox` in host) bypassed jail.

**Fix:** `SandboxCoordinator` now routes **all** PDF operations through `PdfGateway` → `JailIpc` → `pdf-jail` / `pdf-renderer` workers. `PdfWorkerService` deprecated/removed.

**Protocol:** `JailIpc` binary framing:
```
MAGIC (0x4A41494C) | VERSION (u8) | SEQUENCE_ID (u64) | PAYLOAD_LEN (u32) | PAYLOAD | SIGNATURE (ARKHAM_SECRET)
```

**Quotas enforced at host via `AggregateBoundedInputStream`:**
```kotlin
MAX_PAYLOAD_SIZE = 50 MB
MAX_TOTAL_OUTPUT_SIZE = 200 MB
MAX_OUTPUT_FILES = 1000
```

---

### 4. Binder Vulnerability Fixes (`332a363`)

**Affected Workers:** `PdfOutlineReaderWorker`, `PdfTextExtractorWorker`

**Issue:** Exported AIDL interfaces accepted untrusted `ParcelFileDescriptor` without validation — could leak host FDs or trigger parser in host.

**Fix:**
- Workers now run inside `pdf-jail` process
- Input FDs validated via `JailQuotas` before passing to PdfBox
- `MemoryUsageSetting.setupTempFileOnly()` mandatory for all `PDDocument.load()`
- Callback results returned via `IPdfJailCallback` (one-way, no return data in Binder)

---

### 5. BouncyCastle Packaging Fix (`ddd2624`)

**Conflict:** `bcprov-jdk15to18` / `bcpkix-jdk15to18` / `bcutil-jdk15to18` pulled transitively by PdfBox-Android clashed with explicit `bcprov-jdk18on:1.86` / `bcpkix-jdk18on:1.86`.

**Resolution** (`app-host/build.gradle.kts:105-109`):
```kotlin
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
}
```
Explicit 1.86 dependencies retained for PKI signing (`.p12`/`.pfx` X.509 certs).

---

### 6. Attachment & Booklet Migration to IPC (`8433d68`)

**Engines moved to `pdf-jail`:**
- `AttachmentEngine` → `AttachmentWorker` (streaming `copyTo`, 100MB cap)
- `BookletEngine` → `BookletWorker` (imposition logic, signature ordering)

**IPC Commands added:** `ATTACHMENT_LIST`, `ATTACHMENT_EXTRACT`, `ATTACHMENT_EMBED`, `BOOKLET_CREATE`

---

### 7. Test Migration to Uri (`2194e23`)

**Changes:**
- All test signatures using `File` / `String` paths → `Uri` (SAF-compatible)
- `DocumentFile.fromSingleUri(context, uri)` pattern throughout
- Removed `IPdfJailCallback.aidl` (replaced by `JailIpc` callback channel)
- Fixed Robolectric 4.11.1 + Compose BOM 2024.02.01 test compilation

---

### 8. Quotas, Atomicity, Boundary Tests (`fb24217` / `b3f6596`)

**JailQuotas.kt** enforced at worker startup:
```kotlin
MAX_OUTPUT_FILES = 1000
MAX_BATCH_FDS = 50
MAX_BATCH_INPUT_BYTES = 50_000_000
MAX_RENDER_DIMENSION = 8192
MAX_PAYLOAD_SIZE = 50_000_000
MAX_TOTAL_OUTPUT_SIZE = 200_000_000
TEMP_DIR_SWEEP_ON_START = true
```

**Atomic Output:** Workers write to `.tmp` → `renameTo()` on success; host verifies size < quota before committing.

**Boundary Test Renamed:** `BoundaryTest` → `QuotaBoundaryTest` (clarity).

---

### 9. Dev Artifact Cleanup & Billing Hardening (`b3f6596`)

- Removed debug logging, test keys, temporary config files
- Play Billing: `billing-ktx:8.0.0`, query `ProductDetails` only (no legacy SKU)
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` via env / `keystore.properties` only (no hardcoded)
- ProGuard: `-keep class com.pdfchemy.ipc.**`, `-keep class com.pdfchemy.jail.**`

---

## Version Bump Trail

| Version | Commit | Date | Notes |
|---------|--------|------|-------|
| 2.0.4 (v11) | `80191a9` | Sep 18 | First post-split version |
| 2.0.5 (v12) | `41c0513` | Sep 20 | Security hardening phase 14 |
| 2.0.6 (v13) | `c95000b` | Sep 20 | Security hardening (same day) |

---

## Build & Release Commands

```bash
# Local debug build + install
$env:JAVA_HOME="E:\Android_Studio\jbr"; .\gradlew :app-host:installDebug

# Release AAB (requires keystore.properties + env vars)
$env:JAVA_HOME="E:\Android_Studio\jbr"; .\gradlew :app-host:bundleRelease

# Output: app-host/build/outputs/bundle/release/app-host-release.aab
# Rename for distribution:
#   distribution/android/app-release-v2.0.6.aab
```

---

## Play Console Upload Checklist

- [ ] AAB: `distribution/android/app-release-v2.0.6.aab`
- [ ] Version: 2.0.6 (13)
- [ ] Release notes: Use `RELEASE_NOTES_v2.0.6_PUBLIC.md` (adapt to 500-char limit per locale)
- [ ] Target: Production track (or Internal Test first)
- [ ] Signing: Rotated key (from `557d334`) — verify `keystore.properties` matches
- [ ] Data Safety: No changes (still: no data collected, no network for docs)

---

## Known Risks / Watchlist

1. **Module split is new** — monitor crashlytics for `ClassNotFoundException` on `pdf-jail` / `pdf-renderer` classes (should be `runtimeOnly` but verify)
2. **IPC protocol v1** — any schema change requires version bump + backward compat
3. **SecureScreenContent** may interfere with accessibility services (TalkBack) — test on API 24+
4. **Play Console key rotation** — existing installs will need update (not re-install); verify upgrade path
5. **Binder FD limits** — `MAX_BATCH_FDS=50` may be tight for massive merges; watch `Parcelable` size logs

---

## Agent Handoff Notes

- **Next version:** 2.0.7 (v14) — increment in `app-host/build.gradle.kts`
- **Update `FEATURES_REGISTRY.md`** if any new user-visible features added (none in 2.0.6 — purely architectural/security)
- **Update `CHANGELOG.md`** with v2.0.6 entry (template: follow v2.0.0 format)
- **Update `PROJECT_STATE_BRIEFING.md`** Android edition version to 2.0.6
- **Run full test suite** before next release: `./gradlew :app-host:test :pdf-jail:test :pdf-renderer:test :pdf-ipc:test`
- **Adversarial tests** are in `pdf-jail/src/androidTest` + `pdf-ipc/src/test` — keep expanding per `arkham_asylum.md` rule 4

---

## File Manifest (Key Changes)

```
app-host/build.gradle.kts                    # versionCode 13, versionName 2.0.6, module deps
app-host/src/main/AndroidManifest.xml        # removed PdfWorkerService, added jail services
pdf-ipc/                                     # NEW MODULE (DTOs, protocol, quotas)
pdf-jail/                                    # NEW MODULE (worker, security, engines)
pdf-renderer/                                # NEW MODULE (rendering worker)
.gradle/                                     # generated
distribution/android/app-release-v2.0.6.aab  # build artifact (after bundleRelease)
RELEASE_NOTES_v2.0.6_PUBLIC.md               # this file (public)
RELEASE_NOTES_v2.0.6_INTERNAL.md             # this file (internal)
```

---

**End of Internal Notes** — For developer & agent eyes only.  
Do not publish verbatim. Use `RELEASE_NOTES_v2.0.6_PUBLIC.md` for external communications.