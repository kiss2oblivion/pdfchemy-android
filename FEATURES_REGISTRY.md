# PDFchemy Tools — Master Features Registry 📜

> **Single Source of Truth for Implemented Capabilities**
> **Core Invariant:** 100% Local-First & Private. Zero Cloud. Zero AI/LLMs. Zero Telemetry. Original-Safe.
> **Performance Architecture:** Linear-Time O(N) Document Traversal. Single-pass custom TextStripper architecture eliminates quadratic O(N^2) page-tree lookups across all text search, redaction, reflow reading, visual diffing, EPUB conversion, and Office export modules.
> **Last Updated:** October 2026

---

## 📱 Android Edition (`app`)

### 1. 🗜️ Compression & Optimization
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **PDF Compressor** | `CompressScreen.kt` | `PdfCompressor.kt` | ✅ Live | 4 Presets (Extreme, Recommended, High Quality, Custom DPI/Quality), Flate & JBIG2 |
| **Standalone Image Compressor** | `ImageCompressorScreen.kt` | `ImageCompressor.kt` | ✅ Live | Standalone photo/raster image compressor with target size estimation, presets (Web, Email, Max), custom target MB/KB sizing, and JPEG/PNG/WebP format conversions |
| **Grayscale Optimizer** | `GrayscaleOptimizerScreen.kt` | `PdfCompressor.kt` | ✅ Live | Converts color PDF pages to monochrome/grayscale to drastically reduce size |
| **Linearize (Fast Web View)** | `LinearizePdfScreen.kt` | `PdfManipulator.kt` | ✅ Live | Restructures PDF dictionary and stream orders for instant first-page web/mobile streaming |
| **Flatten PDF** | `FlattenPdfScreen.kt` | `PdfFlattenEngine.kt` | ✅ Live | Forensic annotation baking: bakes AcroForms via `acroForm.flatten()` and non-widget annotations via high-res `RENDER_MODE_FOR_PRINT` rasterization without destroying user highlights or drawings |

---

### 2. 📑 Page Studio & Organization
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Merge PDFs** | `MergePdfsScreen.kt` | `PdfManipulator.kt` | ✅ Live | Multi-document combiner with drag-and-drop reordering |
| **Split PDFs (Range / All)** | `OrganizeScreens.kt` | `PdfManipulator.kt` | ✅ Live | Split into individual pages or arbitrary page ranges (e.g. `1-3, 5, 8-10`) |
| **Split by Blank Pages** | `OrganizeScreens.kt` | `MainViewModel.splitByBlankPages` | ✅ Live | Auto-detects blank separator sheets in batch scanner feeds with 5% margin insets and perceptual luminance, eliminating false negatives from scanner edge lines, roller marks, and feed shadows |
| **Split by Bookmarks / Chapters** | `OrganizeScreens.kt` | `MainViewModel.splitByBookmarks` | ✅ Live | Auto-splits multi-chapter books or court bundles based on PDF document outlines |
| **Page Organizer** | `PageOrganizerScreen.kt` | `PdfManipulator.kt` | ✅ Live | Visual thumbnail grid: reorder, delete, duplicate, rotate individual pages. Integrated bounded chronological history for safe Undo/Redo |
| **Rotate Pages** | `OrganizeScreens.kt` | `PdfManipulator.kt` | ✅ Live | Lossless 90°, 180°, 270° orientation correction |
| **Auto-Deskew & Straighten** | `DeskewScreen.kt` | `PdfDeskewEngine.kt` | ✅ Live | Scan tilt auto-detection, manual angle slider, live rotation preview, with bounding box scale preservation to prevent rotated corner clipping |
| **Page Cropper & Margin Trimmer** | `PageCropperScreen.kt` | `PdfManipulator.kt` | ✅ Live | CropBox adjustment to remove scanner borders and margins |
| **Paper Canvas Resizer** | `PageLayoutScreen.kt` | `PdfLayoutEngine.kt` | ✅ Live | Resizes standard paper dimensions (A4, Letter, Legal, A3, Executive) with vector Matrix scaling and centering via LayerUtility to prevent content clipping or rasterization |
| **N-Up Handouts** | `NUpScreen.kt`, `PageLayoutScreen.kt` | `PdfNUpEngineWorker.kt`, `PdfLayoutEngine.kt` | ✅ Live | Imposes 2, 4, 6, 9, or 16 pages per sheet with Z-order / N-order and subtle borders; vector-first imposition via LayerUtility and proportional matrix scaling eliminates lossy raster JPEG re-compression across all screens |
| **Booklet Imposition** | `BookletScreen.kt` | `PdfManipulator.kt` | ✅ Live | Saddle-stitch fold printer ordering (4-page signature booklet imposition) |

---

### 3. 🔄 Creation & Conversion
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Images to PDF** | `ImageToPdfScreen.kt` | `ImageToPdfConverter.kt` | ✅ Live | Converts camera photos, receipts, and gallery images into standardized PDF |
| **PDF to High-Res Images** | `PdfToImageScreen.kt` | `PdfRenderer` | ✅ Live | Exports all pages as PNG or JPEG image files |
| **Scan to PDF** | `ScanPdfScreen.kt` | `GmsDocumentScanning` | ✅ Live | Hardware camera scan with edge auto-detection, filters, and post-capture continuity (Save & Open, OCR, Compress) |
| **EPUB to PDF & PDF to EPUB** | `EbookConverterScreen.kt` | `EpubConverter.kt`, `PdfToEpubEngine.kt` | ✅ Live | Bi-directional EPUB suite: EPUB to PDF with Unicode NFD diacritic preservation, and PDF to EPUB with document outline/bookmarks chapter extraction, dynamic metadata language detection, and EPUB 3/NCX navigation structures |
| **Comic Book Suite (CBZ & CBR)** | `EbookConverterScreen.kt` | `ComicBookEngineWorker.kt`, `ComicBookEngine.kt` | ✅ Live | Bi-directional comic archive conversions: CBZ/CBR to PDF with pre-flight RAR 4.x/5.0 and 7-Zip magic byte inspection and user-facing guidance, and PDF to CBZ with isolated native renderer streaming, sequential JPEG compression, bounded memory limits, and zero accumulator leaks |
| **Markdown to PDF Studio** | `MarkdownStudioScreen.kt` | `MarkdownEngine.kt`, `MarkdownParser.kt` | ✅ Live | Rich Markdown text editor with instant live HTML/PDF rendering; Unicode NFD decomposition (sanitizeForType1) preserves accented glyphs and typographical punctuation without character dropping |
| **Text to PDF Converter** | `TextConverterScreen.kt` | `TextConverter.kt` | ✅ Live | Converts `.txt`, logs, and source code into clean paginated documents |
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
| **Interactive Form Builder** | `FormBuilderScreen.kt` | `AcroFormEngine.createAcroFormWithFields`, `AcroFormEngineWorker.kt` | ✅ Live | Converts flat PDFs into genuine fillable forms with interactive text fields, checkboxes, dropdowns, radio button groups (PDRadioButton), and digital signature fields (PDSignatureField); enforces `NeedAppearances = true` across creation and saving so field contents render visibly in Adobe Acrobat, Chrome, Edge, and Apple Preview; full 90°/180°/270° orientation geometry correction prevents coordinate drift on rotated pages |
| **AcroForm Interactive Filler** | `AcroFormScreens.kt` | `AcroFormEngine.fillAndSaveForm`, `AcroFormEngineWorker.kt`, `PdfEditor.kt` | ✅ Live | Inspects and fills standard interactive PDF forms; features dual-mode fill ergonomics with List View and Document View (in-situ live interactive overlays directly over rendered page bitmaps with true bounding box ratio mapping); preserves `NeedAppearances = true` and supports full form flattening with Helvetica font fallback |
| **Visual Signer** | `SignPdfScreen.kt` | `SignatureEngine.kt`, `PdfEditor.kt` | ✅ Live | Real-time drag-and-drop repositioning, aspect-ratio lock box, LocalDensity pixel-to-dp scaling, selection badge with delete icon, vector smoothing, date stamps, and full coordinate transformation alignment with `cropBox.lowerLeftX/Y` offsets and 90°/180°/270° Matrix rotations |
| **Watermark Studio** | `WatermarkScreen.kt` | `PdfEditor.kt` | ✅ Live | Custom text/image watermarks with opacity, angle, scaling, and diagonal tiling |
| **Header & Footer Studio** | `HeaderFooterScreen.kt` | `PdfEditor.kt` | ✅ Live | Embed running headers and footers with custom margins, alignment, Bates numbers, and Unicode diacritic/typographic quote preservation |
| **Page Numbering Studio** | `PageNumberScreen.kt` | `PdfStampAndNumberEngine.kt`, `PdfStampAndNumberWorker.kt` | ✅ Live | Custom page numbers (`Page X of Y`, `X/Y`, `X`, Roman numerals `i, ii`/`I, II`), custom starting page index offsets, position, and font |
| **Bates Numbering** | `BatesNumberScreen.kt` | `PdfEditor.kt` | ✅ Live | Legal numbering (`PREFIX-00001-SUFFIX`), 6 placement positions, custom zero-padding |
| **Find & Replace Text** | `FindAndReplaceScreen.kt` | `PdfFindAndReplaceEngine.replaceAll` | ✅ Live | True text obliteration via selective 2x print rasterization (`AppendMode.OVERWRITE`) on modified pages with transparent WinAnsi-sanitized searchable text layer (full Unicode diacritic & ligature decomposition); purges original content streams and annotations to guarantee zero plaintext leakage; untouched pages remain 100% native vector text |
| **Image Replacer** | `ImageReplacerScreen.kt` | `PdfEditor.kt` | ✅ Live | Replace embedded raster image objects in PDF streams without touching text; aspect-ratio letterboxing eliminates anamorphic stretching/distortion |
| **Bookmark Studio** | `BookmarkEditorScreen.kt` | `PdfBookmarkEngineWorker.kt`, `PdfBookmarkEngine.kt` | ✅ Live | Hierarchical outline/table of contents editor: inspects, adds, edits title, updates page destinations, deletes outline items, with multi-level nesting up to 5 levels (indent/outdent chevrons) and move up/down reordering |
| **Cryptographic PKI Signer** | `SignPdfScreen.kt` | `AndroidPdfCryptoSigner.kt`, `SignatureEngineWorker.kt` | ✅ Live | Cryptographic PDF digital signature engine: supports custom PKCS#12 (.p12 / .pfx) client certificates with password protection and full certificate chain embedding (JcaCertStore), with fallback to ephemeral on-device RSA-2048 keypairs |

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
| **PDF/A Preflight Validator & Converter** | `PdfAValidatorScreen.kt` | `PdfArchiveValidatorEngineWorker.kt`, `PdfArchiveValidatorEngine.kt` | ✅ Live | Audits ISO 19005 compliance (OutputIntents, DeviceRGB/CMYK, font subsets, XMP) and actively converts/repairs documents into ISO 19005-1b PDF/A archival files |
| **Typography & Font Inspector** | `FontInspectorScreen.kt` | `PdfFontInspectorEngineWorker.kt`, `PdfFontInspectorEngine.kt` | ✅ Live | Lists embedded font programs, subsets, encodings, and extracts embedded TrueType/OpenType/Type1 font binaries directly to ZIP archives |
| **Embedded Attachments Manager**| `AttachmentManagerScreen.kt` | `PdfAttachmentEngineWorker.kt`, `PdfManipulator.kt` | ✅ Live | Inspects, extracts, embeds, and removes/deletes arbitrary file attachments and PDF portfolios |
| **PDF Repair Studio** | `RepairPdfScreen.kt` | `PdfRepairEngine.kt` | ✅ Live | Reconstructs broken cross-reference tables, truncated trailers, and corrupted streams |
| **Document Visual Comparison** | `PdfCompareScreen.kt` | `PdfDiffEngine.kt` | ✅ Live | Synchronized side-by-side & overlay pixel diffs with dynamic programming LCS page-similarity alignment (handles inserted/deleted pages) and line diff extraction |
| **IPC Sandbox Resource Quotas** | Cross-cutting | `SecurityLimits`, `PdfJailService`, `PdfNativeRendererService` | ✅ Implemented; release verification pending | 32 batch input/output descriptors; 250 MiB aggregate input and output; 500 output files; 64 scratch descriptors and 500 MiB operation writes. Single-flight admission rejects overlap with BUSY; independent 120 s parser and 30 s renderer deadlines kill the worker process. Exact SHA-256 and size metadata is mandatory for every input. |
| **Immutable Document Staging** | All document tools | `DocumentStager`, `PdfGateway`, `OperationScratchBroker` | ✅ Implemented; release verification pending | Private sealed snapshots are reused across analysis, render, and processing; bounded streaming handles unknown provider lengths; failed staging and operation cleanup reclaim owned snapshots and anonymous scratch. Filename resemblance never grants staged provenance. |
| **Private History Controls** | Settings / history | `HistoryRepository`, `MainViewModel` | ✅ Implemented | History is disabled by default. Opting out clears existing records; release logging omits document paths. |
| **Host-Owned Output Publication** | All worker-backed saves | `HostOutputTransaction`, `PdfGateway`, `PdfJailClient` | ✅ Implemented; release verification pending | Workers receive only unlinked temporary output FDs. The host validates successful results, count and the 250 MiB aggregate size, then snapshots the whole batch into private FDs before opening SAF destinations for a bounded commit. Worker death/cancellation/overflow before commit preserve destination bytes; generic provider failures during publication cannot be rolled back atomically. |
| **Worker Failure Recycling** | Worker-backed operations | `PdfGateway`, `PdfJailClient`, `PdfJailService` | ✅ Implemented; release verification pending | Only 429 BUSY preserves the current worker. Other failure callbacks, suspect host validation failures, timeout and cancellation after submission request termination. Failed workers keep their admission gate occupied and watchdog armed until recycling; batch metadata validation follows admission so malformed overlap cannot retire another operation. Focused 20-test runtime runs pass on API 24 and 36, including old-PID death, new-PID recovery and successful parsing of the next document. |
| **Tokenized Worker Ownership & Host Acceptance Handshake** | Worker-backed operations | `WorkerGate`, `IPdfJailService`, `PdfGateway`, `PdfJailClient` | ✅ Implemented; release verification pending | Replaced ambiguous boolean flags with explicit, non-reusable 64-bit operation tokens. Synchronous `beginOperation(ownerBinder)` eliminates asynchronous cancellation/BUSY races. Worker remains exclusively reserved in `AWAITING_HOST_ACCEPT` until Host acceptance or rejection; Host ACKs output after private snapshotting and before real SAF publication. Stale cancellations and completions cannot kill or release another operation's PID. Fatal Throwables poison gate and kill worker immediately. ArchUnit enforces that production Host classes never invoke raw `abortWorker()`. |
| **Universal Worker Response Validation** | Worker-backed operations | `WorkerResponseValidator`, `WorkerResponseContracts`, `PdfGateway` | ✅ Implemented; release verification pending | All worker output is treated as hostile. Host eliminates raw JSONObject/fromJson parsing across engines; WorkerResponseValidator centralizes parsing into strongly typed contracts for all 82 operations. Rejects trailing non-whitespace after single JSON root value, enforces <= 8 depth and <= 5000 node quotas, fails closed on unknown operations, and bounds outline/CSV files to 2 MiB. |

---

### 6. 📖 Reading & Accessibility
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Reflow Reader Studio** | `ReflowReaderScreen.kt` | `ReflowEngine.kt` | ✅ Live | E-reader mode with font scaling, themes (Light, Sepia, Dark, OLED), continuous flow |
| **Offline TTS (Read Aloud)** | `ReflowReaderScreen.kt` | `android.speech.tts.TextToSpeech` | ✅ Live | 100% offline text-to-speech with speed controls (0.75x–2.0x) and synced paragraph tracking |
| **Continuous Document Reader** | `PdfReaderScreen.kt` | `PdfRenderer` | ✅ Live | Hardware-accelerated continuous vertical page renderer with bookmark outline navigation, in-document keyword search, and page slider controls |
| **Audiobook & TTS Audio Export** | `ReflowReaderScreen.kt` | `AudioExportService.kt`, `AacM4aWriter.kt`, `WavWriter.kt` | ✅ Live | Synthesizes reflowed document text via foreground service with quota projection into either compressed AAC `.m4a` (~0.9 MB/min via native hardware MediaCodec encoder) or studio-quality 16-bit PCM `.wav` (~10.5 MB/min); surfaced in Reflow Reader and Tool Registry |
| **Dual-Page Spread & Tabletop** | `ReflowReaderScreen.kt` | Compose layout | ✅ Live | Supports tabletop / flex mode on Samsung Fold / Pixel Fold devices and tablets |
| **Device Memory Safeguard** | `DeviceSafeguard.kt` | Runtime memory monitor | ✅ Live | Prevents phone lag/OOM on ultra-heavy scans with lightweight sequential safe mode |

---

### 7. 💎 Monetization, Licensing & Privacy-Respecting Ads
| Feature | UI Screen | Engine / Logic | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Lifetime Premium Upgrade** | `PremiumUpgradeScreen` (`MainActivity.kt`) | `BillingManager.kt`, `MainViewModel.kt` | ✅ Live | Lifetime non-consumable in-app purchase (`premium_upgrade`); removes all ads permanently with zero subscriptions, zero cloud accounts, and zero telemetry |
| **Localized Play Pricing** | `PremiumUpgradeScreen` | `BillingManager.queryProductDetails` | ✅ Live | Dynamic localized price formatting via Google Play Billing (`formattedPrice`); never exposes hardcoded USD fallback; shows localized loading/available state across 21 locales |
| **Generation-Safe Restore** | `PremiumUpgradeScreen` | `BillingManager.restorePurchases`, `LatestRequest` | ✅ Live | Monotonic ticket tracking drops stale out-of-order inventory queries so a delayed background response cannot revoke a newly verified lifetime entitlement |
| **Offline Entitlement Caching** | App Startup / All Screens | `BillingManager.securePrefs` | ✅ Live | AES-256 GCM encrypted shared preferences caches verified lifetime entitlement; preserves premium state during offline launches and Google Play service interruptions |
| **Lock-Free Ad Concurrency** | `MainActivity.kt` | `AdManager.kt` | ✅ Live | UMP `canRequestAds()` and premium state checked outside internal monitor locks; cooldown rejection callbacks dispatched outside locks to eliminate re-entrant deadlocks |
| **Strict Zero-Ad Reader Zones** | Reader & Reflow | `MainActivity.kt` (`isReaderActive`) | ✅ Live | Continuous Reader (`Screen.PdfReader`) and Reflow Reader (`Screen.ReflowReader`) enforce 100% zero banner ads and zero interstitials |
| **Respectful Ad Policy** | Non-reader tool completions | `AdManager.kt` | ✅ Live | Strict 60-second cooldown between interstitial impressions; clearAd invalidates pending callbacks upon consent revocation or premium purchase |

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
1. **Directory-Wide Spotlight Search:** Multi-document keyword search crawling external folder trees via SAF ACTION_OPEN_DOCUMENT_TREE (parity with Desktop Spotlight Search).
2. **Unified Multi-File Batch Studio:** Unified batch queue for Batch Compress, Batch Decrypt, Batch PDF/A, and Batch Flatten (parity with Desktop Batch Studio).
3. **Desktop Parity (Dyslexia & Paper Warmth):** Specialized reader filters for Bionic reading fixation and amber candlelight warmth.
