# PDFchemy Tools — Master Features Registry 📜

> **Single Source of Truth for Implemented Capabilities**
> **Core Invariant:** 100% Local-First & Private. Zero Cloud. Zero AI/LLMs. Zero Telemetry. Original-Safe.
> **Performance Architecture:** Linear-Time O(N) Document Traversal. Single-pass custom TextStripper architecture eliminates quadratic O(N^2) page-tree lookups across all text search, redaction, reflow reading, visual diffing, EPUB conversion, and Office export modules.
> **Last Updated:** September 2026

---

## 📱 Android Edition (`app`)

### 1. 🗜️ Compression & Optimization
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **PDF Compressor** | `CompressScreen.kt` | `PdfCompressor.kt` | ✅ Live | 4 Presets (Extreme, Recommended, High Quality, Custom DPI/Quality), Flate & JBIG2 |
| **Grayscale Optimizer** | `GrayscaleOptimizerScreen.kt` | `PdfCompressor.kt` | ✅ Live | Converts color PDF pages to monochrome/grayscale to drastically reduce size |
| **Linearize (Fast Web View)** | `LinearizePdfScreen.kt` | `PdfManipulator.kt` | ✅ Live | Restructures PDF dictionary and stream orders for instant first-page web/mobile streaming |
| **Flatten PDF** | `FlattenPdfScreen.kt` | `PdfFlattenEngine.kt` | ✅ Live | Forensic annotation baking: bakes AcroForms via `acroForm.flatten()` and non-widget annotations via high-res `RENDER_MODE_FOR_PRINT` rasterization without destroying user highlights or drawings |

---

### 2. 📑 Page Studio & Organization
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Merge PDFs** | `MergePdfsScreen.kt` | `PdfManipulator.kt` | ✅ Live | Multi-document combiner with drag-and-drop reordering |
| **Split PDFs (Range / All)** | `OrganizeScreens.kt` | `PdfManipulator.kt` | ✅ Live | Split into individual pages or arbitrary page ranges (e.g. `1-3, 5, 8-10`) |
| **Split by Blank Pages** | `OrganizeScreens.kt` | `MainViewModel.splitByBlankPages` | ✅ Live | Auto-detects blank separator sheets in batch scanner feeds and splits into individual documents |
| **Split by Bookmarks / Chapters** | `OrganizeScreens.kt` | `MainViewModel.splitByBookmarks` | ✅ Live | Auto-splits multi-chapter books or court bundles based on PDF document outlines |
| **Page Organizer** | `PageOrganizerScreen.kt` | `PdfManipulator.kt` | ✅ Live | Visual thumbnail grid: reorder, delete, duplicate, rotate individual pages |
| **Rotate Pages** | `OrganizeScreens.kt` | `PdfManipulator.kt` | ✅ Live | Lossless 90°, 180°, 270° orientation correction |
| **Auto-Deskew & Straighten** | `DeskewScreen.kt` | `PdfDeskewEngine.kt` | ✅ Live | Hough transform scan tilt auto-detection, manual angle slider, live rotation preview |
| **Page Cropper & Margin Trimmer** | `PageCropperScreen.kt` | `PdfManipulator.kt` | ✅ Live | CropBox adjustment to remove scanner borders and margins |
| **Paper Canvas Resizer** | `PageLayoutScreen.kt` | `PdfLayoutEngine.kt` | ✅ Live | Resizes standard paper dimensions (A4, Letter, Legal, A3, Executive) with content re-centering |
| **N-Up Handouts** | `NUpScreen.kt` | `PdfManipulator.kt` | ✅ Live | Imposes 2, 4, 6, 9, or 16 pages per sheet with Z-order / N-order and subtle borders |
| **Booklet Imposition** | `BookletScreen.kt` | `PdfManipulator.kt` | ✅ Live | Saddle-stitch fold printer ordering (4-page signature booklet imposition) |

---

### 3. 🔄 Creation & Conversion
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Images to PDF** | `ImageToPdfScreen.kt` | `ImageToPdfConverter.kt` | ✅ Live | Converts camera photos, receipts, and gallery images into standardized PDF |
| **PDF to High-Res Images** | `PdfToImageScreen.kt` | `PdfRenderer` | ✅ Live | Exports all pages as PNG or JPEG image files |
| **Scan to PDF** | `ScanPdfScreen.kt` | `CameraCaptureHelper.kt` | ✅ Live | Hardware camera scan with edge auto-detection and perspective correction |
| **EPUB to PDF Converter** | `EbookConverterScreen.kt` | `EpubConverter.kt` | ✅ Live | Parses standard EPUB ebooks, styles fonts/margins, and exports paginated PDF |
| **Markdown to PDF Studio** | `MarkdownStudioScreen.kt` | `MarkdownParser.kt` | ✅ Live | Rich Markdown text editor with instant live HTML/PDF rendering |
| **Text to PDF Converter** | `TextConverterScreen.kt` | `TextConverter.kt` | ✅ Live | Converts `.txt`, logs, and source code into clean paginated documents |
| **Table Extractor to CSV** | `TableExtractorScreen.kt` | `PdfTableExtractorEngine.kt` | ✅ Live | Spatial 2D column clustering: page-by-page extraction prevents cross-page Y coordinate collisions; exports to RFC 4180 CSV / Excel spreadsheets |
| **Office Export (Word / Excel / PPTX)** | `OfficeExportScreen.kt` | `OfficeExportEngine.kt` | ✅ Live | Pure OpenXML archive generators: exports PDF to `.docx`, `.xlsx`, and `.pptx` (with XML 1.0 control character sanitization) |
| **On-Device OCR** | `OcrScreens.kt` | `PdfOcrEngine.kt` | ✅ Live | 100% offline optical character recognition, creating searchable text layers |

---

### 4. ✍️ Form Filling & Document Editing
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Visual PDF Editor** | `PdfEditorScreen.kt` | `PdfEditor.kt` | ✅ Live | Freehand pen, highlighter, text overlays, shape rectangles, stamps; true redaction destroys underlying plaintext streams via selective single-page rasterization & stream purging |
| **Quick Fill & Sign** | `QuickFillSignScreen.kt` | `PdfEditor.kt` | ✅ Live | Designed for flat/scanned forms: tap anywhere to place Text, Checkmarks (✓), Crosses (✗), Dates, localized Signatures (scaled to tap position without full-page blowout), with Undo capability |
| **Interactive Form Builder** | `FormBuilderScreen.kt` | `AcroFormEngine.createAcroFormWithFields` | ✅ Live | Converts flat PDFs into genuine fillable forms with interactive text fields, checkboxes, and dropdowns; enforces `NeedAppearances = true` across creation and saving so field contents render visibly in Adobe Acrobat, Chrome, Edge, and Apple Preview; full 90°/180°/270° orientation geometry correction prevents coordinate drift on rotated pages |
| **AcroForm Interactive Filler** | `AcroFormScreens.kt` | `AcroFormEngine.fillAndSaveForm`, `PdfEditor.kt` | ✅ Live | Inspects and fills standard interactive PDF forms, text boxes, and checkboxes; preserves `NeedAppearances = true` for unflattened forms and supports full form flattening with Helvetica font fallback |
| **Visual Signer** | `SignPdfScreen.kt` | `SignatureEngine.kt`, `PdfEditor.kt` | ✅ Live | Real-time drag-and-drop repositioning, aspect-ratio lock box, LocalDensity pixel-to-dp scaling, selection badge with delete icon, vector smoothing, date stamps, and full coordinate transformation alignment with `cropBox.lowerLeftX/Y` offsets and 90°/180°/270° Matrix rotations |
| **Watermark Studio** | `WatermarkScreen.kt` | `PdfEditor.kt` | ✅ Live | Custom text/image watermarks with opacity, angle, scaling, and diagonal tiling |
| **Header & Footer Studio** | `HeaderFooterScreen.kt` | `PdfEditor.kt` | ✅ Live | Embed running headers and footers with custom margins and alignment |
| **Page Numbering Studio** | `PageNumberScreen.kt` | `PdfEditor.kt` | ✅ Live | Custom page numbers (`Page X of Y`, `X/Y`, `X`), position, font, and start offset |
| **Bates Numbering** | `BatesNumberScreen.kt` | `PdfEditor.kt` | ✅ Live | Legal numbering (`PREFIX-00001-SUFFIX`), 6 placement positions, custom zero-padding |
| **Find & Replace Text** | `FindAndReplaceScreen.kt` | `PdfFindAndReplaceEngine.replaceAll` | ✅ Live | True text obliteration via selective 2x print rasterization (`AppendMode.OVERWRITE`) on modified pages with transparent WinAnsi-sanitized searchable text layer (full Unicode diacritic & ligature decomposition); purges original content streams and annotations to guarantee zero plaintext leakage; untouched pages remain 100% native vector text |
| **Image Replacer** | `ImageReplacerScreen.kt` | `PdfEditor.kt` | ✅ Live | Replace embedded raster image objects in PDF streams without touching text |

---

### 5. 🛡️ Security, Privacy & Compliance
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Encrypt / Password Protect** | `EncryptPdfScreen.kt` | `PdfSecurity.kt` | ✅ Live | AES-128 / AES-256 standard PDF encryption with user and owner passwords |
| **Decrypt / Unlock PDF** | `DecryptPdfScreen.kt` | `PdfSecurity.kt` | ✅ Live | Strips passwords and permissions restrictions permanently |
| **Permanent Smart Redaction** | `RedactionScreen.kt` | `PdfRedactionEngine.kt` | ✅ Live | Regex PII auto-detection (emails, phone numbers, credit cards, IBAN) with inter-word whitespace preservation via `writeString()` capturing multi-word patterns and spaced sequences; selective page rasterization purges underlying plaintext while untouched pages retain vector text; inverted bounding box normalization prevents negative-width inverted boxes across line wraps |
| **Deep Threat Sanitizer** | `DocumentSanitizerScreen.kt` | `PdfSanitizerEngine.kt`, `ActiveContentScrubber.kt` | ✅ Implemented; release verification pending | Bounded graph traversal removes JavaScript, chained actions, associated/embedded files, XFA, and rich media; serialized output is audited again before publication. Ordinary clicked web links and page destinations remain distinct from executable or automatic actions during preflight. |
| **Zero-Trust Hardening & OOM Guard** | Cross-cutting | `SecurityLimits`, `JailMemory`, `SafeImageDecoder`, `JailOutput` (Android); existing desktop safeguards | ✅ Implemented; release verification pending | Android parsers and image decoders run in isolated services with a 32 MiB PDFBox buffer limit, bounded FD scratch, 100 MiB per input, bounded archive expansion, and 2048-pixel / 4,194,304-pixel raster limits. Desktop buffering and limits are separate and unchanged by the Android remediation. |
| **Vanguard Zero-Trust Shield** | Universal across entire app (`VanguardPicker.kt`, `MainActivity.kt`, `PdfEditorScreen.kt`, `ReflowReaderScreen.kt`, `SecurityScreens.kt`, and 30+ standalone tool screens) | `PdfSanitizerEngine.checkVanguardThreat`, `VanguardScanningOverlay`, `rememberVanguardPdfPicker`, `rememberVanguardMultiplePdfPicker` | ✅ Live | Pre-flight zero-trust gatekeeper with animated non-dismissible Material 3 overlay; differentiated threat detection allows standard web hyperlinks (`/S /URI`) and document destinations while strictly blocking malicious `/Launch`, `/JavaScript`, and auto-run `/OpenAction` executables; unified `shrinkpdf_settings` SharedPreferences with live listeners |
| **Metadata Sanitizer** | `MetadataSanitizerScreen.kt` | `PdfMetadataSanitizer.kt` | ✅ Live | Inspects and purges author name, software creator, GPS coordinates, editing history |
| **PDF/A Preflight Validator** | `PdfAValidatorScreen.kt` | `PdfAValidator.kt` | ✅ Live | Audits ISO 19005 compliance (OutputIntents, DeviceRGB/CMYK, font subsets, XMP) |
| **Typography & Font Inspector** | `FontInspectorScreen.kt` | `FontInspector.kt` | ✅ Live | Lists embedded font programs, TrueType/Type1/Type0, subsets, and character encodings |
| **Embedded Attachments Manager**| `AttachmentManagerScreen.kt` | `PdfManipulator.kt` | ✅ Live | Inspects, extracts, and embeds arbitrary file attachments and PDF portfolios |
| **PDF Repair Studio** | `RepairPdfScreen.kt` | `PdfRepairEngine.kt` | ✅ Live | Reconstructs broken cross-reference tables, truncated trailers, and corrupted streams |
| **IPC Sandbox Resource Quotas** | Cross-cutting | `SecurityLimits`, `PdfJailService`, `PdfNativeRendererService` | ✅ Implemented; release verification pending | 32 batch input/output descriptors; 250 MiB aggregate input and output; 500 output files; 64 scratch descriptors and 500 MiB operation writes. Single-flight admission rejects overlap with BUSY; independent 120 s parser and 30 s renderer deadlines kill the worker process. Exact SHA-256 and size metadata is mandatory for every input. |
| **Immutable Document Staging** | All document tools | `DocumentStager`, `PdfGateway`, `OperationScratchBroker` | ✅ Implemented; release verification pending | Private sealed snapshots are reused across analysis, render, and processing; bounded streaming handles unknown provider lengths; failed staging and operation cleanup reclaim owned snapshots and anonymous scratch. Filename resemblance never grants staged provenance. |
| **Private History Controls** | Settings / history | `HistoryRepository`, `MainViewModel` | ✅ Implemented | History is disabled by default. Opting out clears existing records; release logging omits document paths. |

---

### 6. 📖 Reading & Accessibility
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Reflow Reader Studio** | `ReflowReaderScreen.kt` | `ReflowEngine.kt` | ✅ Live | E-reader mode with font scaling, themes (Light, Sepia, Dark, OLED), continuous flow |
| **Offline TTS (Read Aloud)** | `ReflowReaderScreen.kt` | `android.speech.tts.TextToSpeech` | ✅ Live | 100% offline text-to-speech with speed controls (0.75x–2.0x) and synced paragraph tracking |
| **Dual-Page Spread & Tabletop** | `ReflowReaderScreen.kt` | Compose layout | ✅ Live | Supports tabletop / flex mode on Samsung Fold / Pixel Fold devices and tablets |
| **Device Memory Safeguard** | `DeviceSafeguard.kt` | Runtime memory monitor | ✅ Live | Prevents phone lag/OOM on ultra-heavy scans with lightweight sequential safe mode |

---

## 💻 Desktop Edition (`desktop`)

| Tab / Category | Tools & Features | Engine / Implementation | Status |
| **Home Dashboard** | Recent documents list, quick drag-and-drop, quick action cards, in-app update checks | `DesktopApp.kt`, `RecentDocumentsManager.kt` | ✅ Live |
| **Compress** | 3 Presets + Custom Quality/DPI, Grayscale toggle, Metadata stripping | `DesktopPdfEngine.compressPdf` | ✅ Live |
| **Page Studio** | Rotate (left/right), Reorder, Duplicate, Delete, Extract, Impose N-Up (2-Up, 4-Up), Booklet Creator, Margin Cropper, Auto-Deskew | `DesktopPdfEngine.kt` | ✅ Live |
| **Convert** | • Images to PDF<br>• PDF to High-Res PNG Images<br>• Extract Plain Text (.txt)<br>• OCR Searchable PDF (Tesseract)<br>• PDF to ISO 19005-1b PDF/A<br>• Extract Tables to RFC 4180 CSV<br>• **Office Export:** PDF to Word (.docx), Excel (.xlsx), PowerPoint (.pptx) | `DesktopPdfEngine.kt`, `DesktopOfficeExportEngine.kt` | ✅ Live |
| **Reader (Ultimate PDF Viewing Experience)** | • **Borderless Immersion:** Full Screen mode (`F11` / `⛶`) auto-hides window chrome & nav rail with zero canvas footers.<br>• **Document Outline & TOC:** Multi-level collapsible bookmarks tree extracted from PDF catalog with 1-click chapter jumps.<br>• **Page Orientation:** View rotation (90° CW/CCW) + permanent single-page rotation (`DesktopPdfEngine.rotateSinglePage`).<br>• **Magnification & Zoom:** Smooth `Ctrl+Wheel` (25%–800%), Fit Width, Fit Page, 100%, and circular floating Magnifier Loupe tool (2.5x).<br>• **Eye-Comfort & Nightlight Engine:** Amber candlelight warmth slider, paper dimmer slider, curated palettes (Paperwhite, Sepia, Mint, Charcoal, OLED Pitch Black) & smart color inversion.<br>• **Dyslexia Suite:** Reading ruler horizontal line guide, OpenDyslexic-styled spacing, and Bionic reading fixation bolding.<br>• **Color Blindness Vision Suite:** Hardware color matrices for Deuteranopia, Protanopia, Tritanopia, and High-Contrast Mono.<br>• **Blind & Low-Vision Suite:** 100% offline text-to-speech (`DesktopSpeechSynthesizer`) with speed selector and screen-reader accessibility semantics (`contentDescription`).<br>• **In-Doc Search:** Persistent drawer with occurrence counts and direct snippet navigation. | `DesktopPdfEngine.kt`, `DesktopSpeechSynthesizer`, `DesktopApp.kt` (`ReaderView`) | ✅ Live |
| **Security** | • Encrypt & Lock (User/Owner Password)<br>• Decrypt & Unlock<br>• Deep Threat Sanitizer (JS, Actions, Beacons)<br>• PDF Recovery & Repair (Broken XRef/Trailers)<br>• Permanent Redaction (Pattern & manual scrub)<br>• Embedded File Attachments (Inspect, Extract, Embed) | `DesktopPdfEngine.kt` | ✅ Live |
| **Sandbox Engine** | Zero-trust execution isolation running all PDF Box logic inside a child process stripped of all OS permissions. | `Sandbox.kt` | ✅ Live |
| **Secure Unified Staging** | All host-side intermediate file handling and installer staging uses tightly locked internal directories. | `DesktopStaging.kt` | ✅ Live |
| **Compare Studio** | Side-by-side synchronized comparison, line-by-line textual diffs, revision change statistics | `DesktopApp.kt` (`CompareView`), `DesktopPdfEngine.compareDocuments` | ✅ Live |
| **Merge** | Multi-document combiner with reorderable list | `DesktopPdfEngine.mergePdfs` | ✅ Live |
| **Batch Studio** | Multi-file batch processing queue (Batch Compress, Batch Decrypt, Batch PDF/A) | `DesktopPdfEngine.kt` | ✅ Live |
| **Sign & Form Studio** | Draw signatures, upload seal images, business stamps, AcroForm fill & flatten, **Interactive Form Builder** (add text/checkbox/dropdown fields) | `DesktopPdfEngine.kt`, `SignAndStampView` | ✅ Live |
| **Spotlight Search** | Multi-file directory keyword search across hundreds of PDFs with line snippet extraction and instant 1-click page jump to Reader | `DesktopDirectorySearchEngine.kt`, `DirectorySpotlightSearchDialog` | ✅ Live |
| **Digital Signatures (PKI)** | Cryptographic certificate signing (`.p12` / `.pfx` keystores) with visual seal | `PdfCryptoSigner.kt` (BouncyCastle) | ✅ Live |
| **Bates Numbering** | Legal bates numbering engine with 6 placement positions | `DesktopPdfEngine.applyBatesNumbering` | ✅ Live |
| **Split Studio** | Split by Page Ranges, Split by Blank Pages, Split by Bookmarks | `DesktopPdfEngine.splitByBlankPages`, `splitByBookmarks` | ✅ Live |
| **Multi-Language (i18n)** | 20 languages / 21 locales with runtime top-bar switcher, permanent default startup language setting with `~/.pdfchemy/config.properties` file-backed persistence (surviving OS restarts and packaged MSI/Debian environments), and CLI `--lang` flags | `DesktopLocalization.kt`, `DesktopStrings.kt` | ✅ Live |
| **In-App Updater & Auto-Installer** | Checks GitHub Releases, downloads platform installers (.msi/.exe on Windows, .deb/.rpm/.jar on Linux) with real-time progress, verifies cryptographic SHA-256 checksum against official `SHA256SUMS.txt`, securely launches installer without shell injection, and exits running process | `DesktopUpdateManager.kt`, `DesktopApp.kt` | ✅ Live |
| **The Lifetime Manifesto** | 4 Guarantees dialog | `DesktopApp.kt` | ✅ Live |

---

## 🌍 Supported Locales (20 Languages / 21 Locales)
The application supports the following locales. Android release lint tracks 38 inherited `MissingTranslation` findings in the exact `app-host/lint-localization-baseline.xml`; those entries currently fall back to English. Security and correctness lint errors remain fatal. Full translation coverage is still pending.
1. `en` (English)
2. `ro` (Română)
3. `de` (Deutsch)
4. `es` (Español)
5. `fr` (Français)
6. `it` (Italiano)
7. `pt` (Português - Portugal)
8. `pt-rBR` (Português - Brasil)
9. `nl` (Nederlands)
10. `pl` (Polski)
11. `ru` (Русский)
12. `tr` (Türkçe)
13. `ar` (العربية)
14. `hi` (हिन्दी)
15. `in` / `id` (Bahasa Indonesia)
16. `ja` (日本語)
17. `ko` (한국어)
18. `th` (ไทย)
19. `vi` (Tiếng Việt)
20. `zh-rCN` (简体中文)
21. `zh-rTW` (繁體中文)

---

## ⏳ What Is NOT Yet Implemented (Future Roadmap)
1. **Audiobook / MP3 Audio Export:** Exporting TTS read aloud output to `.mp3` / `.wav` audio files.

