# Kilo Session Context — PDFchemy Tools (Shrink PDF)

> **Auto-generated session context for Kilo AI agent**  
> **Project:** PDFchemy Tools / Shrink PDF  
> **Developer:** Andrei Ioan Cucoș (John / @kiss2oblivion)  
> **Last Updated:** 2026-09-28

---

## 🎯 Project Identity & Philosophy

**Product:** PDFchemy Tools (Android: "Shrink PDF")  
**Core Mantra:** *"Open it when you need the document fixed now."* — A tool, not a trap.

**Cardinal Ethical Mantra (Inviolable):**
- NEVER LIE IN THE DEVELOPER'S NAME. NEVER MISREPRESENT REALITY.
- Absolute honesty and transparency at all times.
- No fabricated information, hidden AI involvement, or falsified compliance.
- Radical open-source transparency: full credit to all libraries, authors, maintainers.

**Lifetime Manifesto (Developer's Personal Pledge):**
> *"Lifetime of updates until I personally die and free of charge; and all you have to do is make a solid valid request and it will be done and implemented; cuz it's from the people to the people; it may or may not be the same as a corpo app would do but at least it's gonna be free and I will make it as best as I possibly can; if I can't well I can't and that's that at least you have an option oh you enigmatic edge case that you are."*

---

## 🏗️ Architectural Invariants (Non-Negotiable)

1. **100% Local-First & Private:** All document processing (compression, conversion, editing, OCR, repair, encryption) executes entirely on-device. Zero server uploads, zero cloud dependencies, zero telemetry.
2. **Original-Safe:** Never overwrite source files. Default to creating new output files in user-chosen locations.
3. **Respectful Monetization:** 
   - Desktop: 100% free, zero ads, zero tracking
   - Android: Optional banner ads (never during active tasks), 60s cooldown, Pro = one-time lifetime purchase
4. **UI Standards:** Android phones/tablets, 360° fullSensor, Material 3, WCAG contrast, edge-to-edge insets
5. **i18n:** 20 languages / 21 locales with proper XML escaping (`%%` for literal `%`, `%1$s` positional args)

---

## 📱 Platforms & Distribution

| Platform | Channels | Formats |
|----------|----------|---------|
| **Android** | Google Play Store | AAB (v2.0.5) |
| **Windows** | winget, Microsoft Store, Chocolatey, Scoop, Direct | MSI (~68.8 MB), EXE (~69.5 MB) |
| **Linux** | Flathub (PR #10071), Snap, AppImage, Direct | DEB (63.9 MB), RPM (77.1 MB), JAR (40.3 MB) |

**GitHub Repos:**
- `origin` → `kiss2oblivion/pdfchemy` (main)
- `repo-android` → `kiss2oblivion/pdfchemy-android`
- `repo-linux` → `kiss2oblivion/pdfchemy-linux`
- `repo-windows` → `kiss2oblivion/pdfchemy-windows`

---

## ⚡ Feature Registry (Source: FEATURES_REGISTRY.md)

### Android Edition (v2.0.5)

**1. Compression & Optimization**
- PDF Compressor (4 presets: Extreme, Recommended, High Quality, Custom)
- Grayscale Optimizer
- Linearize (Fast Web View)
- Flatten PDF (forensic annotation baking)

**2. Page Studio & Organization**
- Merge PDFs (drag-drop reorder)
- Split PDFs (ranges, blank pages, bookmarks)
- Page Organizer (thumbnails: reorder, delete, duplicate, rotate)
- Auto-Deskew (Hough transform)
- Page Cropper & Margin Trimmer
- Paper Canvas Resizer (A4, Letter, Legal, A3, Executive)
- N-Up Handouts (2, 4, 6, 9, 16 pages/sheet)
- Booklet Imposition (saddle-stitch)

**3. Creation & Conversion**
- Images to PDF (camera photos, receipts, scans)
- PDF to High-Res Images (PNG/JPEG)
- Scan to PDF (ML Kit camera + edge detection)
- EPUB to PDF
- Markdown to PDF Studio
- Text to PDF Converter
- Table Extractor to CSV (spatial 2D clustering)
- Office Export (Word/Excel/PPTX via pure OpenXML)
- On-Device OCR

**4. Form Filling & Editing**
- Visual PDF Editor (pen, highlighter, text, shapes, stamps, **true redaction** via selective rasterization)
- Quick Fill & Sign (tap-to-place text, checkmarks, signatures)
- Interactive Form Builder (AcroForms: text, checkbox, dropdown with `NeedAppearances=true`)
- AcroForm Interactive Filler
- Visual Signer (drag-drop, aspect-ratio lock, coordinate transform)
- Watermark Studio
- Header & Footer Studio
- Page Numbering Studio
- Bates Numbering (legal format)
- Find & Replace Text (true obliteration via 2x print rasterization + WinAnsi text layer)
- Image Replacer

**5. Security, Privacy & Compliance**
- Encrypt/Decrypt (AES-128/256)
- Permanent Smart Redaction (regex PII auto-detection)
- Deep Threat Sanitizer (JS, launch actions, URI beacons)
- Zero-Trust Hardening & OOM Guard (31+ sites, disk-backed, streaming I/O)
- **Vanguard Zero-Trust Shield** (pre-flight gatekeeper, animated overlay)
- Metadata Sanitizer
- PDF/A Preflight Validator
- Font Inspector
- Embedded Attachments Manager
- PDF Repair Studio (XREF/EOF reconstruction)
- IPC Sandbox Resource Quotas

**6. Reading & Accessibility**
- Reflow Reader (font scaling, themes, continuous flow)
- Offline TTS (0.75x–2.0x, synced paragraphs)
- Dual-Page Spread & Tabletop (foldables)
- Device Memory Safeguard

---

### Desktop Edition (v1.0.8 "Multi-Language Edition")

**Tabs/Categories:**
- **Home Dashboard** (recent docs, quick actions, in-app updates)
- **Compress** (3 presets + custom, grayscale, metadata strip)
- **Page Studio** (rotate, reorder, duplicate, delete, extract, N-Up, Booklet, crop, deskew)
- **Convert** (Images↔PDF, Text extraction, OCR, PDF/A, Tables→CSV, Office Export)
- **Reader** (Ultimate viewing: borderless fullscreen, outline/TOC, rotation, zoom + loupe, nightlight engine, dyslexia suite, color blindness suite, blind/low-vision TTS, in-doc search)
- **Security** (encrypt/decrypt, threat sanitizer, repair, redaction, attachments)
- **Sandbox Engine** (zero-trust child process isolation)
- **Secure Unified Staging**
- **Compare Studio** (side-by-side, line-by-line diffs)
- **Merge** (multi-doc with reorder)
- **Batch Studio** (multi-file queue)
- **Sign & Form Studio** (signatures, stamps, AcroForm fill/flatten, Form Builder)
- **Spotlight Search** (multi-file directory keyword search)
- **Digital Signatures (PKI)** (BouncyCastle, .p12/.pfx)
- **Bates Numbering** (6 positions)
- **Split Studio** (ranges, blank pages, bookmarks)
- **Multi-Language i18n** (20 langs, runtime switcher, config persistence, CLI flags)
- **In-App Updater** (GitHub Releases, SHA256 verification, secure installer launch)
- **Lifetime Manifesto Dialog**

---

## 🛡️ Security Architecture Highlights

**Vanguard Zero-Trust Shield:** Pre-flight gatekeeper across all entry points (pickers, editor, reader, security screens). Differentiated threat detection:
- ✅ Allowed: Standard web hyperlinks (`/S /URI`), document destinations
- ❌ Blocked: `/Launch`, `/JavaScript`, auto-run `/OpenAction` executables

**Arkham Asylum (Desktop Jail):**
- Zero-trust worker process (`PdfJailWorker`)
- Framed binary protocol (MAGIC 0x4A41494C, version, payload bounds, `ARKHAM_SECRET`)
- `MemoryUsageSetting.setupTempFileOnly()` — disk-backed buffering only
- `AggregateBoundedInputStream` enforces `MAX_PAYLOAD_SIZE`, `MAX_TOTAL_OUTPUT_SIZE`, `MAX_OUTPUT_FILES`
- Async `StderrDrainer` prevents host deadlock
- Fail-closed on framing violations
- Adversarial regression tests: `ArkhamEscapeTest`, `IpcFuzzTest`, `HostSurvivalTest`, `ParserBrutalityTest`

**OOM Guards:** 
- Dynamic image downsampling (max 2048×2048 / 4096×4096)
- Streaming I/O (`copyTo`) for attachments
- Repair file caps (100MB Android / 150MB Desktop)
- ZIP bomb guards (2M char limit for EPUBs)

---

## 🛠️ Tech Stack

| Layer | Android | Desktop |
|-------|---------|---------|
| **Language** | Kotlin | Kotlin |
| **UI** | Jetpack Compose, Material 3 | Compose Multiplatform (Skiko) |
| **PDF Engine** | PdfBox-Android 2.0.27.0 | Apache PDFBox 2.0.31 |
| **Crypto** | Bouncy Castle 1.78 | Bouncy Castle 1.78 |
| **OCR** | Google ML Kit (on-device) | Tess4J 5.7.0 + Tesseract `eng.traineddata` |
| **Scanning** | ML Kit Document Scanner | — |
| **Coroutines** | Kotlinx 1.7.3 | Kotlinx 1.8.0 |
| **Image Loading** | Coil 2.6.0 | — |
| **Parsers** | jsoup 1.17.2, flexmark-java 0.64.8, Jackson 2.17 | Same |
| **Build** | Gradle (KTS) | Gradle (KTS) |
| **JDK** | JetBrains Runtime / OpenJDK 17+ | `E:\Android_Studio\jbr` |

---

## 🌍 Supported Locales (20 Languages / 21 Locales)

`en`, `ro`, `de`, `es`, `fr`, `it`, `pt` (Portugal), `pt-rBR` (Brazil), `nl`, `pl`, `ru`, `tr`, `ar` (RTL), `hi`, `in`/`id`, `ja`, `ko`, `th`, `vi`, `zh-rCN`, `zh-rTW`

---

## 📦 Key Open-Source Dependencies (Apache 2.0 unless noted)

- **Apache PDFBox** — Core PDF engine
- **PdfBox-Android** (Tom Roush) — Mobile port
- **JetBrains Compose Multiplatform & Skiko** — Desktop UI
- **Kotlinx Coroutines** — Async/parallel
- **Android Jetpack Compose** — Mobile UI
- **Bouncy Castle** (BC License) — PKI/crypto
- **Tess4J / Tesseract** — Desktop OCR
- **Google ML Kit** — Mobile scanning/OCR
- **jsoup** (MIT) — HTML/EPUB parsing
- **flexmark-java** (BSD-2) — Markdown→PDF
- **FasterXML Jackson** — CSV/YAML/XML
- **Coil** — Android image loading
- **Material Icons** — Iconography
- **JUnit 4** (EPL), **MockK**, **Robolectric** (MIT) — Testing
- **Gradle**, **WiX Toolset**, **AppStream** — Build/packaging

---

## 🔧 Build & Deploy Commands

```bash
# Prerequisites
$env:JAVA_HOME="E:\Android_Studio\jbr"

# Run Desktop
./gradlew :desktop:run

# Build Desktop JAR
./gradlew :desktop:packageUberJarForCurrentOS

# Build Android Debug
./gradlew :app:assembleDebug

# Android Rebuild/Reset/Push Workflow
git commit -am "<message>"
$env:JAVA_HOME="E:\Android_Studio\jbr"; .\gradlew installDebug
# Then manually reopen app on emulator
```

---

## 📋 Current State (as of 2026-09-28)

- **Git branch:** `repo-android` (up to date with origin)
- **Working tree:** Clean
- **Last commit:** `13810ac` — "arch: extract shared DTOs to pdf-ipc, rename modules to app-host/pdf-jail/pdf-renderer/pdf-ipc"
- **Android version:** v2.0.5 (AAB ready for Play Console)
- **Desktop version:** v1.0.8 "Multi-Language Edition" (all binaries built & published)
- **Roadmap gap:** Audiobook / MP3 Audio Export (only item in FEATURES_REGISTRY.md "What Is NOT Yet Implemented")

---

## 🎯 Key Files for Reference

| File | Purpose |
|------|---------|
| `FEATURES_REGISTRY.md` | Single source of truth for all implemented features |
| `PROJECT_STATE_BRIEFING.md` | Master technical dossier |
| `README.md` | Public-facing documentation |
| `CHANGELOG.md` | Version history (v2.0.0 major overhaul, v1.0.0 initial) |
| `MANTRA.md` | Product philosophy & decision framework |
| `DISTRIBUTION.md` | Multi-store publishing guide |
| `PRESS_KIT.md` | Media/reviewer kit with test workflows |
| `THIRD_PARTY_CREDITS.md` | Exhaustive attribution list |
| `PRIVACY.md` | Privacy policy |
| `.agents/AGENTS.md` | Agent guidelines & invariants |
| `.agents/rules/arkham_asylum.md` | Desktop jail security rules |
| `PLAYSTORE_RELEASE_NOTES_v2.0.0.md` | Localized Play Store notes (21 locales) |

---

## 🚀 Suggested Next Actions for Kilo

Based on project state, high-value tasks:

1. **Implement Audiobook/MP3 Export** — Only roadmap item; leverage existing TTS + audio encoding
2. **Release v2.0.6 Android** — Minor fixes, version bump, Play Console deploy
3. **Desktop v1.0.9** — Incremental improvements, update all store packages
4. **Flathub PR #10071** — Resubmit/advocate for Flatpak acceptance
5. **Outreach Campaign** — Execute PRESS_KIT.md reviewer test workflows with target media (Zona IT, Android Authority, XDA, gHacks, etc.)
6. **Adversarial Testing** — Extend Arkham test suites for new attack vectors

---

## ⚠️ Guardrails for This Session

- **Never** introduce network calls for document processing
- **Never** overwrite user source files
- **Always** use `$env:JAVA_HOME="E:\Android_Studio\jbr"` for Gradle
- **Always** escape `%%` and use `%1$s` in localized strings
- **Always** update `FEATURES_REGISTRY.md` when adding features
- **Maintain** radical transparency: credit all OSS, disclose AI assistance, no false claims