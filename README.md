# PDFchemy Tools (Shrink PDF) 📄✨

> **The emergency document utility for the people.**
> 100% Free • 100% Offline & Private • Zero Ads on Desktop • Original-Safe

---

## 📜 The Developer's Lifetime Manifesto

> *"Lifetime of updates until I personally die and free of charge; and all you have to do is make a solid valid request and it will be done and implemented; cuz it's from the people to the people; it may or may not be the same as a corpo app would do but at least it's gonna be free and I will make it as best as I possibly can; if I can't well I can't and that's that at least you have an option oh you enigmatic edge case that you are."*
> 
> — **Andrei Ioan Cucoș (John)**, Developer

---

## ⚡ Cross-Platform Support & Digital Stores

* **Android:** Google Play Store (Phones, Tablets & Foldables).
* **Windows:**
  * **Windows Package Manager:** `winget install PDFchemy.PDFchemy`
  * **Microsoft Store:** Available via Win32 Store Program (see [DISTRIBUTION.md](DISTRIBUTION.md)).
  * **Chocolatey & Scoop:** `choco install pdfchemy` • `scoop install pdfchemy`
  * **Native Installers:** 64-bit `.msi` and `.exe` direct downloads.
* **Linux:**
  * **Flathub (Flatpak):** `flatpak install flathub com.pdfchemy.PDFchemy`
  * **Snap Store:** `snap install pdfchemy`
  * **Universal AppImage:** Portable 1-click executable (`.AppImage`)
  * **Native Packages:** Debian/Ubuntu (`.deb`) and Fedora/RHEL (`.rpm`)

*See [DISTRIBUTION.md](DISTRIBUTION.md) for full publishing guides and [PRESS_KIT.md](PRESS_KIT.md) for the Media & Reviewer Kit.*

## 🚀 Desktop Superpowers

1. **Ultimate Reader Studio (Distraction-Free Immersion):**
   * **Borderless Full Screen (`F11` / `⛶`):** 100% immersion with auto-hiding top bar, auto-hiding navigation rail, and zero distracting footers on the canvas.
   * **Document Outline & Bookmarks Tree:** Hierarchical table of contents extracted from the PDF catalog with instant 1-click chapter jumps.
   * **Smooth Zoom & Floating Magnifier Loupe:** Smooth `Ctrl + Wheel` zooming (25% to 800%), `Fit Page` / `Fit Width` presets, and a floating **2.5× Circular Magnifier Loupe (`🔍`)** to inspect fine print, footnotes, and schematics.
   * **Page Rotation:** Global view rotation (90° CW/CCW) and permanent single-page in-place rotation.
   * **Nightlight & Eye-Comfort Engine:** Amber candlelight blue-light filter slider (0%–100%), ambient paper dimmer slider (30%–100%), and 5 curated reading palettes (*Paperwhite*, *Warm Sepia*, *Sage Mint*, *Charcoal*, *OLED Pitch Black*) plus smart color inversion.
   * **Dyslexia Suite:** Reading Ruler horizontal highlight guide following the cursor, OpenDyslexic-styled letter and line spacing, and Bionic reading fixation bolding.
   * **Color Blindness Vision Suite:** Real-time GPU color matrices for Deuteranopia (green-weak), Protanopia (red-weak), Tritanopia (blue-yellow), and High-Contrast Monochromatic.
   * **Blind & Low-Vision Suite:** 100% offline text-to-speech Read Aloud (`DesktopSpeechSynthesizer`) with playback speed controls and full screen-reader accessibility semantics (`contentDescription`).
2. **Visual Page Studio (The "PDF Arranger Killer"):**
   * Real-time Compose thumbnail grid of all pages.
   * In-place quick actions: 🔄 Rotate 90°, ⬅️ ➡️ Reorder sequence, 📄 Duplicate page, and 🗑️ Delete page.
   * One-click "Save Organized PDF".
3. **Smart Target-Size Compressor ("Fit Under 2MB"):**
   * Automatic iterative DPI and JPEG quality optimizer to guarantee your output fits under government, email, and portal upload thresholds (500 KB, 1.0 MB, 2.0 MB, 5.0 MB).
4. **Interactive Form Builder & AcroForm Studio:**
   * Convert flat PDFs into genuine fillable forms by adding interactive Text Fields, Checkboxes, and Dropdowns.
   * Interactive form filling with full form flattening and appearance preservation across Adobe Acrobat, Chrome, and Apple Preview.
5. **Directory Spotlight Search:**
   * High-speed multi-document keyword search across hundreds of local PDFs with extracted line snippets and 1-click direct page jump to Reader.
6. **Side-by-Side Revision Compare Studio:**
   * Synchronized side-by-side comparison with line-by-line textual diffs and revision change statistics.
7. **Images ⇄ PDF & Office Export Studio:**
   * **Images to PDF:** Select photos, scans, or receipts (PNG, JPG, BMP, WebP) and compile them into a unified PDF.
   * **PDF to Images:** Batch-extract every page as high-res 150/300 DPI PNG images into any local folder.
   * **Office Export:** Pure OpenXML archive generator exporting PDF to Word (`.docx`), Excel (`.xlsx`), and PowerPoint (`.pptx`).
8. **Multi-Core Batch Queue:**
   * Drop 10, 20, or 50+ PDFs and batch compress or merge in parallel across all CPU cores.
9. **Legal Bates Stamping & Watermarking:**
   * Standard legal Bates numbering with 6 placement positions, custom zero-padding, prefix, and suffix.
10. **Zero-Trust Privacy & Security:**
    * 128/256-bit AES encryption, decryption, deep threat sanitizer, and forensic vector redaction.
    * 100% air-gapped on-device execution: zero cloud uploads, zero telemetry, zero file leaks.

---

## ☕ Support the Developer (Tip Jar)

PDFchemy is 100% free with zero paywalls and zero subscriptions. If this tool saved your day, consider leaving a tip to support independent development:

* ☕ **Ko-fi:** [https://ko-fi.com/andreiioancucos](https://ko-fi.com/andreiioancucos)
* 💳 **Revolut:** [https://revolut.me/andreiy886](https://revolut.me/andreiy886) (`@andreiy886`)
* 📬 **Contact / Inquiries:** `cucosandreiioan@gmail.com`

---

## 🛠️ Building & Running Locally

### Prerequisites
* JDK 17 or higher (e.g. JetBrains Runtime / OpenJDK).

### Run Desktop App
```bash
./gradlew :desktop:run
```

### Build Runnable JAR
```bash
./gradlew :desktop:packageUberJarForCurrentOS
```
The output JAR is generated at `desktop/build/compose/jars/PDFchemy-<os>-x64-1.0.0.jar`.

### Build Android Debug APK
```bash
./gradlew :app:assembleDebug
```

---

## 🤖 Development Transparency & AI Disclosure

PDFchemy Tools is conceived, directed, architected, and continuously maintained by **Andrei Ioan Cucoș (John)**. 

In the spirit of complete, radical transparency:
* **AI-Assisted Pair Programming:** Significant parts of the codebase, multiplatform scaffolding, and iterative refactoring have been developed with the assistance of AI coding agents (including Google Antigravity). 
* **Human Oversight & Vision:** Every feature, design choice, architectural constraint, and test verification is directed, tested, and vetted by the human developer.
* **100% Deterministic Local Processing:** While AI tools are used to write and organize the application code, the application itself contains **zero runtime AI dependencies, zero cloud LLMs, and zero network calls**. All PDF compression, image rendering, parsing, and encryption run deterministically and entirely offline on your local device.

---

## 💖 Open-Source Credits & Attributions

PDFchemy Tools stands on the shoulders of the global open-source community. Every core capability runs locally and deterministically thanks to these outstanding libraries and their maintainers:

* **[Apache PDFBox](https://pdfbox.apache.org/)** (Apache 2.0) — The foundational Java PDF engine powering document compression, geometry rendering, AcroForms authoring, PDF/A conversion, and search indexing on Desktop.
* **[PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)** (Apache 2.0) by Tom Roush — The Android port powering mobile PDF manipulation and form filling.
* **[JetBrains Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) & [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines)** (Apache 2.0) — Modern declarative desktop rendering and multi-threaded parallel queues.
* **[Android Jetpack & Jetpack Compose](https://developer.android.com/jetpack)** (Apache 2.0) by Google/AOSP — Modern Material3 UI, adaptive window layouts, and system integration.
* **[The Legion of the Bouncy Castle](https://www.bouncycastle.org/)** (Bouncy Castle Licence / MIT) — Cryptographic provider for PKI X.509 digital signatures and integrity verification.
* **[Tess4J & Tesseract OCR](https://tess4j.sourceforge.net/)** (Apache 2.0) — Offline Optical Character Recognition for searchable PDF conversions on Desktop.
* **[Google ML Kit](https://developers.google.com/ml-kit)** — Hardware-accelerated on-device document camera perspective scanning and mobile OCR.
* **[jsoup](https://jsoup.org/)** (MIT) by Jonathan Hedley — HTML/EPUB DOM parser and sanitizer for document and eBook conversions.
* **[flexmark-java](https://github.com/vsch/flexmark-java)** (BSD-2-Clause) — Markdown parser and AST engine for Markdown-to-PDF rendering.
* **[FasterXML Jackson](https://github.com/FasterXML/jackson)** (Apache 2.0) — High-throughput serialization for CSV, YAML, and XML conversions.
* **[Guava](https://github.com/google/guava) & [Gson](https://github.com/google/gson)** (Apache 2.0) by Google and contributors — Worker utilities and bounded operation-contract serialization on Android.
* **[ArchUnit](https://www.archunit.org/)** (Apache 2.0) by TNG and contributors — Tests that enforce host parser isolation in compiled bytecode.
* **[Coil](https://coil-kt.github.io/coil/)** (Apache 2.0) by Colin White — Credited for its former Android preview loader; current document previews decode in isolated workers.
* **[Tesseract OCR Models](https://github.com/tesseract-ocr/tessdata)** (Apache 2.0) — Bundled neural OCR English trained model (`eng.traineddata`).
* **[Google Material Design Icons](https://fonts.google.com/icons)** (Apache 2.0) — System UI iconography across desktop and mobile suites.

*For the complete bill of materials, versions, test frameworks, and license details, see [THIRD_PARTY_CREDITS.md](THIRD_PARTY_CREDITS.md).*

---

## 📄 License
Built with passion for the people. Free for personal and commercial use.
