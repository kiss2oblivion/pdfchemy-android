# PDFchemy Tools — Master Features Registry 📜

> **Single Source of Truth for Implemented Capabilities**
> **Core Invariant:** 100% Local-First & Private. Zero Cloud. Zero AI/LLMs. Zero Telemetry. Original-Safe.
> **Performance Architecture:** Linear-Time O(N) Document Traversal. Single-pass custom TextStripper architecture eliminates quadratic O(N^2) page-tree lookups across all text search, redaction, reflow reading, visual diffing, EPUB conversion, and Office export modules.
> **Last Updated:** November 2026

---

## 📱 Android Edition (`app`)

### 1. 🗜️ Compression & Optimization
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **PDF Compressor** | `MainActivity.kt` (`CompressPdfScreen`) | `PdfCompressor.kt` | ✅ Live | 4 Presets (Extreme, Recommended, High Quality, Custom DPI/Quality), Flate & JBIG2 |
| **Standalone Image Compressor** | `ImageCompressorScreen.kt` | `ImageCompressor.kt` | ✅ Live | Standalone photo/raster image compressor with target size estimation, presets (Web, Email, Max), custom target MB/KB sizing, and JPEG/PNG/WebP format conversions |
| **Grayscale Optimizer** | `GrayscaleOptimizerScreen.kt` | `PdfCompressor.kt` | ✅ Live | Converts color PDF pages to monochrome/grayscale to drastically reduce size |
| **Fast Web View information** | `LinearizePdfScreen.kt` | `PdfLinearizeEngine.kt` | Inspection only | Reports PDF page information with linearization NOT_VERIFIED; generation is explicitly unsupported and disabled |
| **Flatten PDF** | `FlattenPdfScreen.kt` | `PdfFlattenEngine.kt` | ✅ Live | Forensic annotation baking: bakes AcroForms via `acroForm.flatten()` and non-widget annotations via high-res `RENDER_MODE_FOR_PRINT` rasterization without destroying user highlights or drawings |

---

### 2. 📑 Page Studio & Organization
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Merge PDFs** | `OrganizeScreens.kt` (`MergePdfScreen`) | `PdfManipulator.kt` | ✅ Live | Multi-document combiner with drag-and-drop reordering |
| **Split PDFs (Range / All)** | `OrganizeScreens.kt` | `PdfManipulator.kt` | ✅ Live | Split into individual pages or arbitrary page ranges (e.g. `1-3, 5, 8-10`) |
| **Split by Blank Pages** | `OrganizeScreens.kt` | `MainViewModel.splitByBlankPages` | ✅ Live | Auto-detects blank separator sheets in batch scanner feeds with 5% margin insets and perceptual luminance, eliminating false negatives from scanner edge lines, roller marks, and feed shadows |
| **Split by Bookmarks / Chapters** | `OrganizeScreens.kt` | `MainViewModel.splitByBookmarks` | ✅ Live | Auto-splits multi-chapter books or court bundles based on PDF document outlines |
| **Page Organizer** | `PageOrganizerScreen.kt` | `PdfManipulator.kt` | ✅ Live | Visual thumbnail grid: reorder, delete, duplicate, rotate individual pages. Integrated bounded chronological history for safe Undo/Redo |
| **Rotate Pages** | `OrganizeScreens.kt` | `PdfManipulator.kt` | ✅ Live | Lossless 90°, 180°, 270° orientation correction |
| **Auto-Deskew & Straighten** | `DeskewScreen.kt` | `PdfDeskewEngine.kt` | ✅ Live | Scan tilt auto-detection, manual angle slider, live rotation preview, with bounding box scale preservation to prevent rotated corner clipping |
| **Page Cropper & Margin Trimmer** | `PageCropperScreen.kt` | `PdfManipulator.kt` | ✅ Live | CropBox adjustment to remove scanner borders and margins |
| **Paper Canvas Resizer** | `PageLayoutScreen.kt` | `PdfLayoutEngine.kt` | ✅ Live | Fits static page content to A4, Letter, Legal, A3 or A5 using vector scaling; normalizes rotated crop boxes. Forms and annotations must be flattened first |
| **N-Up Handouts** | `NUpScreen.kt`, `PageLayoutScreen.kt` | `PdfNUpEngineWorker.kt`, `PdfLayoutEngine.kt` | ✅ Live | Page Layout provides vector 2/4/6-up for static pages with crop and rotation normalization; forms and annotations must be flattened first. The separate N-Up screen retains its existing engine and options |
| **Booklet Imposition** | `BookletScreen.kt` | `PdfManipulator.kt` | ✅ Live | Saddle-stitch fold printer ordering (4-page signature booklet imposition) |

---

### 3. 🔄 Creation & Conversion
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Images to PDF** | `CreateScreens.kt` (`ImagesToPdfScreen`) | `ImagePdfWorker.kt` (`PdfGateway.kt`) | ✅ Live | Converts camera photos, receipts, and gallery images into standardized PDF |
| **PDF to High-Res Images** | `SecurityScreens.kt` (`PdfToImagesScreen`) | `PdfManipulator.kt`, `PdfManipulatorWorker.kt` | ✅ Live | Exports all pages as PNG or JPEG image files |
| **Scan to PDF** | `ScanPdfScreen.kt` | `GmsDocumentScanning` | ✅ Live | Hardware camera scan with edge auto-detection, filters, and post-capture continuity (Save & Open, OCR, Compress) |
| **EPUB to PDF & PDF to EPUB** | `EbookConverterScreen.kt` | `PdfToEpubEngine.kt` | ✅ Live | Bi-directional EPUB suite: EPUB to PDF with Unicode NFD diacritic preservation, and PDF to EPUB with document outline/bookmarks chapter extraction, dynamic metadata language detection, and EPUB 3/NCX navigation structures |
| **Comic Book Suite (CBZ & CBR)** | `EbookConverterScreen.kt` | `ComicBookEngineWorker.kt`, `ComicBookEngine.kt` | ✅ Live | CBZ-to-PDF and PDF-to-CBZ use bounded archive handling and the isolated native renderer. RAR/CBR and 7-Zip signatures are detected and rejected with conversion guidance |
| **Markdown to PDF Studio** | `MarkdownStudioScreen.kt` | `MarkdownEngine.kt` | ✅ Live | Rich Markdown text editor with instant live HTML/PDF rendering; Unicode NFD decomposition (sanitizeForType1) preserves accented glyphs and typographical punctuation without character dropping |
| **Text to PDF Converter** | `MainActivity.kt` (`TextToPdfScreen`), `TextConverterScreen.kt` | `TextToPdfConverter.kt`, `TextFormatConverter.kt` | ✅ Live | Converts `.txt`, logs, and source code into clean paginated documents with NFD diacritic decomposition and typographic mapping; format conversions between TXT/RTF/HTML |
| **Table Extractor to CSV & Excel** | `TableExtractorScreen.kt` | `PdfTableExtractorEngine.kt`, `OfficeExportEngine.kt` | ✅ Live | Spatial 2D column clustering: page-by-page extraction prevents cross-page Y coordinate collisions; dual export supports both RFC 4180 CSV and direct native OpenXML Excel (.xlsx) spreadsheets |
| **Office Export (Word / Excel / PPTX)** | `OfficeExportScreen.kt` | `OfficeExportEngine.kt` | ✅ Live | Pure OpenXML archive generators: exports PDF to `.docx`, `.xlsx`, and `.pptx` (with XML 1.0 control character sanitization) |
| **Extract Plain Text** | `TextScreens.kt`, `CheckScreens.kt` | `PdfTextExtractorWorker.kt` | ✅ Live | Extracts raw plaintext streams from all or selected pages into `.txt` documents or system clipboard |
| **On-Device OCR** | `OcrScreens.kt` | `PdfOcrEngine.kt` / `pdf-jail` isolated worker | ✅ Live | Bounded offline OCR decoupled via `OcrBackend` & `OcrResultBounds` abstraction. Upstream Tesseract 5.5.1 initialized strictly in-memory via patched `TessBaseAPI.init(byte[], ...)` from a trusted privately-held buffer with streaming 30 MiB bound. Packaged as reproducibly built vendored AAR with build-time Gradle SHA-256 verification (`verifyTesseractAarHash`). 100% operational across API 24, API 30, and API 36 with zero filesystem access (`filesDir`/`cacheDir`) and zero NNAPI crashes under isolated UID. |

---

### 4. ✍️ Form Filling & Document Editing
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Visual PDF Editor** | `PdfEditorScreen.kt` | `PdfEditor.kt`, `PdfEditorWorker.kt` | ✅ Live | Freehand pen, highlighter, text overlays, geometric shapes (Rectangle, Circle/Ellipse, Line, Arrow) with live drag-to-draw preview, predefined and custom text stamps; true redaction destroys underlying plaintext streams via selective single-page rasterization & stream purging |
| **Quick Fill & Sign** | `QuickFillSignScreen.kt` | `PdfEditor.kt` | ✅ Live | Designed for flat/scanned forms: tap anywhere to place Text, Checkmarks (✓), Crosses (✗), Dates, localized Signatures (scaled to tap position without full-page blowout), with Undo capability |
| **Interactive Form Builder** | `FormBuilderScreen.kt` | `AcroFormEngine.createAcroFormWithFields`, `AcroFormEngineWorker.kt` | ✅ Live | Converts flat PDFs into genuine fillable forms with interactive text fields, checkboxes, dropdowns and empty digital signature fields. Radio group creation is held until widget appearances and group semantics are implemented; enforces `NeedAppearances = true` across creation and saving so field contents render visibly in Adobe Acrobat, Chrome, Edge, and Apple Preview; full 90°/180°/270° orientation geometry correction prevents coordinate drift on rotated pages |
| **AcroForm Interactive Filler** | `AcroFormScreens.kt` | `AcroFormEngine.fillAndSaveForm`, `AcroFormEngineWorker.kt`, `PdfEditor.kt` | ✅ Live | Inspects and fills standard interactive PDF forms; features dual-mode fill ergonomics with List View and Document View (in-situ live interactive overlays directly over rendered page bitmaps with true bounding box ratio mapping); preserves `NeedAppearances = true` and supports full form flattening with Helvetica font fallback |
| **Visual Signer** | `SignPdfScreen.kt` | `SignatureEngine.kt`, `PdfEditor.kt` | ✅ Live | Real-time drag-and-drop repositioning, aspect-ratio lock box, LocalDensity pixel-to-dp scaling, selection badge with delete icon, vector smoothing, date stamps, and full coordinate transformation alignment with `cropBox.lowerLeftX/Y` offsets and 90°/180°/270° Matrix rotations |
| **Watermark Studio** | `WatermarkScreen.kt` | `PdfStampAndNumberEngine.kt`, `PdfStampAndNumberWorker.kt` | ✅ Live | Custom text watermarks with opacity, angle, scaling, and diagonal tiling |
| **Header & Footer Studio** | `HeaderFooterScreen.kt` | `PdfHeaderFooterEngine.kt` (host & jail) | ✅ Live | Embed running headers and footers with custom margins, alignment, Bates numbers, and Unicode diacritic/typographic quote preservation |
| **Page Numbering Studio** | `PageNumberScreen.kt` | `PdfStampAndNumberEngine.kt`, `PdfStampAndNumberWorker.kt` | ✅ Live | Custom page numbers (`Page X of Y`, `X/Y`, `X`, Roman numerals `i, ii`/`I, II`), custom starting page index offsets, position, and font |
| **Bates Numbering** | `HeaderFooterScreen.kt` | `PdfStampAndNumberEngine.kt`, `PdfHeaderFooterEngine.kt` | ✅ Live | Legal numbering (`PREFIX-00001-SUFFIX`), 6 placement positions, custom zero-padding |
| **Find & Replace Text** | `FindAndReplaceScreen.kt` | `PdfFindAndReplaceEngine.replaceAll` | ✅ Live | True text obliteration via selective 2x print rasterization (`AppendMode.OVERWRITE`) on modified pages with transparent WinAnsi-sanitized searchable text layer (full Unicode diacritic & ligature decomposition); purges original content streams and annotations to guarantee zero plaintext leakage; untouched pages remain 100% native vector text |
| **Image Replacer** | `ImageReplacerScreen.kt` | `PdfImageReplacerEngine.kt` | ✅ Live | Replace embedded raster image objects in PDF streams without touching text; recursive Form XObject traversal discovers and updates images embedded in reusable forms; aspect-ratio letterboxing eliminates anamorphic stretching/distortion |
| **Bookmark Studio** | `BookmarkEditorScreen.kt` | `PdfBookmarkEngineWorker.kt`, `PdfBookmarkEngine.kt` | ✅ Live | Hierarchical outline/table of contents editor: inspects, adds, edits title, updates page destinations, deletes outline items, with multi-level nesting up to 5 levels (indent/outdent chevrons) and move up/down reordering |
| **Cryptographic PKI Signer** | `SignPdfScreen.kt` | `AndroidPdfCryptoSigner.kt`, `SignatureEngineWorker.kt` | ✅ Live | Cryptographic PDF digital signature engine: supports custom PKCS#12 (.p12 / .pfx) client certificates with password protection and full certificate chain embedding (JcaCertStore), for a single matching RSA private-key entry (32 KiB import bound). Self-signed RSA-2048 is a separate explicit mode; an invalid imported identity fails without fallback |

---

### 5. 🛡️ Security, Privacy & Compliance
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Encrypt / Password Protect** | `SecurityScreens.kt` (`ProtectPdfScreen`) | `PdfManipulator.kt`, `PdfManipulatorWorker.kt` | ✅ Live | AES-128 / AES-256 standard PDF encryption with user and owner passwords |
| **Decrypt / Unlock PDF** | `SecurityScreens.kt` (`UnlockPdfScreen`) | `PdfManipulator.kt`, `PdfManipulatorWorker.kt` | ✅ Live | Strips passwords and permissions restrictions permanently |
| **Permanent Smart Redaction** | `RedactionScreen.kt` | `PdfRedactionEngine.kt` | ✅ Live | Regex PII auto-detection (emails, phone numbers, credit cards, IBAN) with inter-word whitespace preservation via `writeString()` capturing multi-word patterns and spaced sequences; selective page rasterization purges underlying plaintext while untouched pages retain vector text; inverted bounding box normalization prevents negative-width inverted boxes across line wraps |
| **Deep Threat Sanitizer** | `DocumentSanitizerScreen.kt` | `PdfSanitizerEngine.kt`, `ActiveContentScrubber.kt` | ✅ Live (device instrumentation run separate) | Bounded graph traversal removes JavaScript, chained actions, associated/embedded files, XFA, and rich media; serialized output is audited again before publication. Ordinary clicked web links and page destinations remain distinct from executable or automatic actions during preflight. |
| **Zero-Trust Hardening & OOM Guard** | Cross-cutting | `SecurityLimits`, `JailMemory`, `SafeImageDecoder`, `JailOutput` | ✅ Live | Android parsers and image decoders run in isolated services with a 32 MiB PDFBox buffer limit, bounded FD scratch, 100 MiB per input, bounded archive expansion, and 2048-pixel / 4,194,304-pixel raster limits. |
| **Vanguard Zero-Trust Shield** | Universal across entire app (`VanguardPicker.kt`, `MainActivity.kt`, `PdfEditorScreen.kt`, `ReflowReaderScreen.kt`, `SecurityScreens.kt`, and 30+ standalone tool screens) | `PdfSanitizerEngine.checkVanguardThreat`, `VanguardScanningOverlay`, `rememberVanguardPdfPicker`, `rememberVanguardMultiplePdfPicker` | ✅ Live | Pre-flight zero-trust gatekeeper with animated non-dismissible Material 3 overlay; differentiated threat detection allows standard web hyperlinks (`/S /URI`) and document destinations while strictly blocking malicious `/Launch`, `/JavaScript`, and auto-run `/OpenAction` executables; unified `shrinkpdf_settings` SharedPreferences with live listeners |
| **Metadata Sanitizer** | `MetadataSanitizerScreen.kt` | `PdfMetadataEngine.kt`, `PdfMetadataManager.kt` | ✅ Live | Inspects and purges author name, software creator, GPS coordinates, editing history |
| **Preliminary PDF/A checks** | `PdfAValidatorScreen.kt` | `PdfArchiveValidatorEngine.kt` | Preliminary only | Reports limited presence checks with NOT_CERTIFIED status; ISO 19005 certification and PDF/A conversion are unavailable |
| **Typography & Font Inspector** | `FontInspectorScreen.kt` | `PdfFontInspectorEngineWorker.kt`, `PdfFontInspectorEngine.kt` | ✅ Live | Lists embedded font programs, subsets, encodings, and extracts embedded TrueType/OpenType/Type1 font binaries directly to ZIP archives |
| **Embedded Attachments Manager**| `AttachmentManagerScreen.kt` | `PdfAttachmentEngineWorker.kt`, `PdfManipulator.kt` | ✅ Live | Inspects, extracts, embeds, and removes/deletes arbitrary file attachments and PDF portfolios |
| **PDF Repair Studio** | `RepairPdfScreen.kt` | `PdfRepairEngine.kt` | ✅ Live | Reconstructs broken cross-reference tables, truncated trailers, and corrupted streams |
| **Document Visual Comparison** | `PdfCompareScreen.kt` | `PdfDiffEngine.kt` | ✅ Live | Synchronized side-by-side & overlay pixel diffs with dynamic programming LCS page-similarity alignment (handles inserted/deleted pages) and line diff extraction |
| **IPC Sandbox Resource Quotas** | Cross-cutting | `SecurityLimits`, `PdfJailService`, `PdfNativeRendererService` | ✅ Live (ArchUnit + unit-tested; device instrumentation separate) | 16 batch input and 16 batch output descriptors; 250 MiB aggregate input and output; 500 output files; 64 scratch descriptors and 500 MiB operation writes. Single-flight admission rejects overlap with BUSY; independent 120 s parser and 30 s renderer deadlines kill the worker process. Exact SHA-256 and size metadata is mandatory for every input. |
| **Immutable Document Staging** | All document tools | `DocumentStager`, `PdfGateway`, `OperationScratchBroker` | ✅ Live (unit-tested; device instrumentation separate) | Private sealed snapshots are reused across analysis, render, and processing; bounded streaming handles unknown provider lengths; failed staging and operation cleanup reclaim owned snapshots and anonymous scratch. Filename resemblance never grants staged provenance. |
| **Private History Controls** | Settings / history | `HistoryRepository`, `MainViewModel` | ✅ Live | History is disabled by default. Opting out clears existing records; release logging omits document paths. |
| **Host-Owned Output Publication** | All worker-backed saves | `HostOutputTransaction`, `PdfGateway`, `PdfJailClient` | ✅ Live (unit-tested; device instrumentation separate) | Workers receive only unlinked temporary output FDs. The host validates successful results, count and the 250 MiB aggregate size, then snapshots the whole batch into private FDs before opening SAF destinations for a bounded commit. Worker death/cancellation/overflow before commit preserve destination bytes; generic provider failures during publication cannot be rolled back atomically. |
| **Worker Failure Recycling** | Worker-backed operations | `PdfGateway`, `PdfJailClient`, `PdfJailService` | ✅ Live (unit-tested; device instrumentation separate) | Only 429 BUSY preserves the current worker. Other failure callbacks, suspect host validation failures, timeout and cancellation after submission request termination. Failed workers keep their admission gate occupied and watchdog armed until recycling; batch metadata validation follows admission so malformed overlap cannot retire another operation. |
| **Tokenized Worker Ownership & Host Acceptance Handshake** | Worker-backed operations | `WorkerGate`, `IPdfJailService`, `PdfGateway`, `PdfJailClient` | ✅ Live (ArchUnit + unit-tested; device instrumentation separate) | Replaced ambiguous boolean flags with explicit, non-reusable 64-bit operation tokens. Synchronous `beginOperation(ownerBinder)` eliminates asynchronous cancellation/BUSY races. Worker remains exclusively reserved in `AWAITING_HOST_ACCEPT` until Host acceptance or rejection; Host ACKs output after private snapshotting and before real SAF publication. Stale cancellations and completions cannot kill or release another operation's PID. Fatal Throwables poison gate and kill worker immediately. ArchUnit enforces that production Host classes never invoke raw `abortWorker()`. |
| **Universal Worker Response Validation** | Worker-backed operations | `WorkerResponseValidator`, `WorkerResponseContracts`, `PdfGateway` | ✅ Live (unit-tested; device instrumentation separate) | All worker output is treated as hostile. Host eliminates raw JSONObject/fromJson parsing across engines; WorkerResponseValidator centralizes parsing into strongly typed contracts for all 82 operations. Rejects trailing non-whitespace after single JSON root value, enforces <= 8 depth and <= 5000 node quotas, fails closed on unknown operations, and bounds outline/CSV files to 2 MiB. |
| **Cross-UID Provider Boundary Protection** | `InternalDocumentOpen`, Document viewing/sharing | `ExternalUriAdmission`, `InternalDocumentIntents`, `MainActivity` | ✅ Live (unit-tested; device instrumentation separate) | Gating external VIEW/SEND/SEND_MULTIPLE intents against private paths and app-owned FileProvider authorities. Strips user qualifiers (`0%40`) and rejects foreign claims claiming host UID authority. Internal document viewing routes through a non-exported `<activity-alias>` (`InternalDocumentOpen`) authenticated by Android OS before delivery. |
| **Redaction Page Bounds Preflight & Atomicity** | `RedactionScreen.kt` | `PdfRedactionEngineWorker.kt`, `PdfRedactionEngine.kt` | ✅ Live (unit-tested; device instrumentation separate) | Strict preflight validation ensures all requested page indices exist in the document before form mutation, appearance flattening, or rasterization. Fails closed with zero modified pages or partial publication on out-of-bounds targets. Exact validated worker count is propagated directly to callers and UI notifications. |
| **Staging Stream Quota Lifecycle Retention on Shutdown** | All document staging workflows | `DocumentStager.kt` | ✅ Live (unit-tested; device instrumentation separate) | Fine-grained in-flight slot and byte quota reservations are decoupled from the global stager monitor lock and retained across `releaseAll()` activity shutdown/destroy cycles. Prevents background streaming transfers from underflowing quotas or corrupting capacity reservations upon completion. |

---

### 6. 📖 Reading & Accessibility
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **PDF Reader Studio (R01–R06)** | `PdfReaderScreen.kt` | `ReaderNavigationCoordinator.kt`, `ReaderViewportController.kt`, `ReaderControlsController.kt`, `DocumentIdentity.kt` | ✅ Live | Consistent Reader-first entry across Home, Recent Files, Tool Search, and external `ACTION_VIEW`/`ACTION_SEND` PDF intents; stateful Reader→Tool→Reader continuity preserving active document; native inertial fling scrolling at `1.0x` zoom, focal double-tap zoom (`2.5x`), Fit Width / Fit Page modes, and reference-counted LRU bitmap cache (`BoundedPageBitmapCache`) with `ComponentCallbacks2` memory trimming and tokenized `ReaderRenderGate` cancellation; orientation-stable tap-to-reveal overlay controls with zero layout jump, floating page badge, and Table of Contents sheet; 1–2 tap Command Centre for 17 core PDF operations; normalized document identity and exact `(pageIndex, scrollOffsetPx)` restoration with graceful revoked SAF cleanup; full WCAG 48dp touch targets, TalkBack semantics, 200% font-scale / small-screen / landscape responsiveness, and per-page render retry |
| **Reflow Reader Studio** | `ReflowReaderScreen.kt` | `PdfTextExtractor.kt`, `ReflowReaderScreen.kt` | ✅ Live | E-reader mode with font scaling, Light/Warmth/Sepia/Dark/OLED themes, optional word-prefix bolding (Bionic reading) and expanded letter/line spacing (Dyslexia spacing). These display preferences persist and reset with reader defaults |
| **Offline TTS (Read Aloud)** | `ReflowReaderScreen.kt` | `android.speech.tts.TextToSpeech` | ✅ Live | 100% offline text-to-speech with speed controls (0.75x–2.0x) and synced paragraph tracking |
| **Continuous Document Reader** | `PdfReaderScreen.kt` | `PdfRenderer` | ✅ Live | Hardware-accelerated continuous vertical page renderer with bookmark outline navigation, in-document keyword search, and page slider controls |
| **Audiobook & TTS Audio Export** | `ReflowReaderScreen.kt` | `AudioExportService.kt`, `AacM4aWriter.kt`, `WavWriter.kt` | ✅ Live | Synthesizes document text through a foreground service into explicitly selected AAC/M4A or PCM/WAV. M4A requires an available platform AAC encoder and PCM16 input; unsupported encoding fails without writing WAV bytes to an M4A file. Both formats retain owned publication and cancellation handling |
| **Dual-Page Spread & Tabletop** | `ReflowReaderScreen.kt` | Compose layout | ✅ Live | Supports tabletop / flex mode on Samsung Fold / Pixel Fold devices and tablets |
| **Device Memory Safeguard** | `DeviceGuard.kt` | `DeviceGuard.kt` | ✅ Live | Prevents phone lag/OOM on ultra-heavy scans with lightweight sequential safe mode |

---

## 💻 Desktop Edition (`desktop`)
> **Workspace Note:** Desktop application Kotlin sources and Gradle build targets reside outside this Android workspace (`desktop/` here contains only local C++ stubs `desktop/src/main/cpp/hello.cpp` and `desktop/test_lpac.cpp`). Desktop capabilities are maintained in their separate repository/release artifacts and are not compiled or verified by this Android build.

---

## 🌍 Supported Locales (20 Languages / 21 Locales)
Android reconciliation retains localized UI and runtime status/error string wiring across all 20 localized `values-*` resource sets matching all 1,388 translatable default string keys (plus 27 explicitly English-only / non-translatable audio and integrity keys, for 1,415 total keys), with zero missing, extra, or duplicate keys. Desktop coverage is outside this Android reconciliation.
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

## 🔒 Security Hardening & Remediation Registry (2.1.0)
- **Fail-Closed Flattening:** `PdfFlattenEngineWorker` enforces strict failure propagation if form flattening or annotation rasterization fails.
- **ReDoS Prevention & Regex Guard:** `PdfRedactionEngineWorker` pre-compiles search regexes, enforces input length boundaries (1024 chars), and rejects nested quantifier patterns to prevent catastrophic backtracking.
- **Forensic Find & Replace Destruction:** `PdfFindAndReplaceEngine` resets page resources (`PDResources()`), purges page carriers (`Annots`, `Thumb`, `Metadata`, `PieceInfo`, `AA`, `AF`, `StructParents`), and runs `RedactionResources.prepareForRedaction` and `pruneUnused` to prevent replaced text from surviving in structure trees (`/StructTreeRoot`), outlines, or XMP streams.
- **Unconditional Forensic Redaction:** `PdfRedactionEngineWorker` deprecates cosmetic non-forensic overlay modes, ensuring all redaction operations perform forensic rasterization and carrier scrubbing.
- **Spreadsheet Formula Neutralization:** `OfficeExportEngineWorker.exportToExcel` and `StructuredTextCodec.writeCsv` neutralize leading formula metacharacters (`=`, `+`, `-`, `@`, `\t`, `\r`, `\0`) in non-numeric cells.
- **Strict Canonical File Admission:** `DocumentStager` resolves canonical paths for external `file://` URIs and verifies boundaries before staging.
- **Audio Publication & Late Cancellation Guard:** `AudioOutputPublisher` guarantees already-published audio outputs are never deleted during late-cancellation cleanups.
- **Deep Metadata Sanitization:** `PdfMetadataEngineWorker` and `JailCompressionWorker` recursively purge embedded `/Metadata` and `/PieceInfo` dictionaries from document object graphs and trailer `/ID`.
- **Private Offline TTS Policy:** `ReflowReaderScreen` and `AudioExportService` enforce offline voice selection without emitting the legacy deprecated `networkTts` bundle override key.
- **Watermark & Bates Quota Bounding:** `PdfStampAndNumberWorker` strictly clamps font sizes, tile steps, and numbering digits.
- **Image Decode Quota Alignment:** `PdfImageExtractorEngine` and `PdfServiceEngineWorker` use `SecurityLimits.requireImageDecodePixels` (up to 32 MP) and recycle bitmaps promptly.
- **Path Traversal Accuracy:** `ExternalUriAdmission` checks directory segments to prevent false rejection of filenames containing consecutive dots.
- **Device-to-Device Backup Hardening:** `data_extraction_rules.xml` and manifest exclude exports, shared copies, and staged caches from cloud backup and D2D device transfers on Android 12+ (API 31+).

---

## 🧹 Code Cleanup & Architectural Hygiene Registry (2.1.0)
- **Dead Asset Purge:** Removed unreferenced `mascot.jpg` (saving 618.7 KB in release APK and AAB bundles).
- **Dead Code Elimination:** Removed uncalled `extractPdfUri` in `MainActivity.kt` and unread `ALLOWED_MIME_TYPES` in `ImageCompressor.kt`.
- **Hot-Path Memory Optimization:** Replaced per-request `Gson()` instantiations with cached singletons across `JailEngineBridge`, `MigratedEngineDispatch`, `PdfFlattenEngineWorker`, `PdfJailService`, `PdfEditor`, and `PdfFlattenEngine`, reducing heap allocation churn and GC pauses during high-throughput PDF processing.
- **Unified Telemetry & Logging:** Routed raw `Log.w` calls in `MainActivity.kt` to `AppLogger.w`, ensuring uniform debug-mode logging controls.

---

## ⏳ What Is NOT Yet Implemented (Future Roadmap)
1. **Directory-Wide Spotlight Search:** Multi-document keyword search crawling external folder trees via SAF ACTION_OPEN_DOCUMENT_TREE.
2. **Unified Multi-File Batch Studio:** Unified batch queue for Batch Compress, Batch Decrypt, Batch PDF/A, and Batch Flatten.
3. **MP3 export:** AAC/M4A and WAV are available; MP3 encoding is not implemented.
