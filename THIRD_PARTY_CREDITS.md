# Third-Party Open-Source Credits & Attributions 💖

PDFchemy Tools stands on the shoulders of the global open-source community. We believe in absolute honesty, radical transparency, and giving credit where credit is due.

The core promise of PDFchemy is **100% offline, local-first document utility**. Every single feature—from rendering and compressing to signing, form building, and optical character recognition—is powered by battle-tested, permissive open-source software running locally on your device with **zero cloud dependencies and zero telemetry**.

Below is the exhaustive, complete record of every open-source library, engine, model asset, icon set, and framework that powers PDFchemy across Android and Desktop.

---

## 🏛️ 1. Core PDF & Document Processing Engines

### [Apache PDFBox](https://pdfbox.apache.org/)
* **Authors:** The Apache Software Foundation & PDFBox Community
* **Version:** `2.0.31`
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Usage in PDFchemy (Desktop):**
  * Powers document parsing, page geometry, visual thumbnail rendering, and iterative target-size compression.
  * Interactive AcroForms architecture: field discovery, interactive field authoring (Text, Checkboxes, Radio buttons), and form flattening.
  * Document security: 128/256-bit AES encryption, decryption, and password protection.
  * PDF/A compliance conversion, linear Fast Web View optimization, Bates numbering, and watermarking.
  * Full-text document extraction for the local Directory Spotlight Search engine.

### [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
* **Authors:** Tom Roush & The Apache Software Foundation
* **Version:** `2.0.27.0`
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Usage in PDFchemy (Android):**
  * Mobile port of Apache PDFBox enabling native Android PDF parsing, AcroForm filler, metadata editing, page tree reordering, and document manipulation without any remote cloud dependencies.

---

## 🎨 2. UI Frameworks & Language Ecosystems

### [JetBrains Compose Multiplatform & Skiko](https://github.com/JetBrains/compose-multiplatform)
* **Authors:** JetBrains s.r.o. & Contributors
* **Version:** `1.7.3`
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Usage in PDFchemy:**
  * Cross-platform declarative UI for the Desktop application (Linux & Windows).
  * Modern, reactive state management and high-performance hardware-accelerated desktop canvas rendering via Skia/Skiko.

### [Kotlinx Coroutines](https://github.com/Kotlin/kotlinx.coroutines)
* **Authors:** JetBrains s.r.o.
* **Version:** `1.8.0` (Desktop) / `1.7.3` (Android)
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Usage in PDFchemy:**
  * Asynchronous processing pipelines, non-blocking UI operations, and multi-core parallel execution across CPU threads in the Batch Processing Queue.

### [Android Jetpack & Jetpack Compose](https://developer.android.com/jetpack)
* **Authors:** Google LLC & The Android Open Source Project (AOSP)
* **Components:** `androidx.compose.material3`, `androidx.compose.ui`, `androidx.core:core-ktx`, `androidx.lifecycle`, `androidx.activity:activity-compose`, `androidx.window`, `androidx.documentfile`
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Usage in PDFchemy (Android):**
  * Modern declarative UI architecture, Material 3 theming, adaptive window sizes for foldable devices and tablets, and Android Storage Access Framework (SAF) integration.

---

## 🔐 3. Cryptography, PKI & Document Security

### [The Legion of the Bouncy Castle](https://www.bouncycastle.org/)
* **Authors:** The Legion of the Bouncy Castle Inc.
* **Libraries:** `org.bouncycastle:bcprov-jdk18on`, `org.bouncycastle:bcpkix-jdk18on` (`1.86` Android; `1.78` Desktop)
* **License:** [Bouncy Castle Licence](https://www.bouncycastle.org/licence.html) (Permissive MIT/BSD-style license)
* **Usage in PDFchemy (Android & Desktop):**
  * Industrial-grade cryptographic provider.
  * Powers PKI digital signatures, self-signed X.509 certificate generation, CMS/PKCS#7 detached signature verification, and secure document integrity checking.

---

## 👁️ 4. Optical Character Recognition (OCR) & Scanning

### [Tess4J & Tesseract OCR](https://tess4j.sourceforge.net/)
* **Authors:** Quan Nguyen, Ray Smith, Google Inc. & Tesseract OCR Contributors
* **Version:** `5.7.0`
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Usage in PDFchemy (Desktop):**
  * Local, offline Optical Character Recognition (OCR) engine for converting scanned PDFs and bitmap pages into searchable text.

### [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) & [Tesseract OCR](https://github.com/tesseract-ocr/tesseract)
* **Authors:** Zdenko Podobný, Adaptech s.r.o., Ray Smith, Google Inc. & Tesseract OCR Contributors
* **Version:** `4.9.0` (packaging Tesseract `5.5.1` and Leptonica `1.84.1`)
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Usage in PDFchemy (Android):**
  * Production optical character recognition engine executed inside the isolated worker process (`pdf-jail`). Uses an audited in-memory native JNI bridge directly invoking upstream `TessBaseAPI::Init(const char *data, int data_size, ...)` from a bounded byte buffer, achieving 100% offline, local-first OCR without requiring filesystem access (`filesDir`/`cacheDir`/`fopen`) under the Android isolated UID.

### [Tesseract OCR English Trained Data Model (`eng.traineddata`)](https://github.com/tesseract-ocr/tessdata)
* **Authors:** Ray Smith & The Tesseract OCR Open Source Community
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Usage in PDFchemy (Android & Desktop):**
  * Bundled offline neural OCR model enabling instantaneous character recognition without needing an internet connection or external downloads. In Android, model data is strictly bounded in memory and loaded once per isolated worker process.

### [Google ML Kit (Document Scanner)](https://developers.google.com/ml-kit)
* **Authors:** Google LLC
* **Libraries:** `play-services-mlkit-document-scanner`
* **License:** Android Software Development Kit License
* **Usage in PDFchemy (Android):**
  * Host UI-side camera capture, perspective warping, and page boundary detection. Standard text recognition in isolated processing is handled by Tesseract.

---

## 🔄 5. Document Formats, Parsers & Text Converters

### [jsoup: Java HTML Parser](https://jsoup.org/)
* **Authors:** Jonathan Hedley
* **Version:** `1.23.2` (Android; strictly constrained) / `1.17.2` (Desktop)
* **License:** [MIT License](https://jsoup.org/license)
* **Usage in PDFchemy:**
  * HTML and EPUB eBook DOM parsing, tag stripping, entity unescaping, and structured text extraction for the EPUB-to-PDF and Web-to-PDF engines.

### [Flexmark-Java](https://github.com/vsch/flexmark-java)
* **Authors:** Vladimir Schneider & Contributors
* **Version:** `0.64.8`
* **License:** [BSD 2-Clause License](https://github.com/vsch/flexmark-java/blob/master/LICENSE.txt)
* **Usage in PDFchemy:**
  * CommonMark / Markdown parser and AST renderer for compiling Markdown files into structured PDFs.

### [FasterXML Jackson](https://github.com/FasterXML/jackson)
* **Authors:** FasterXML, LLC & Tatu Saloranta
* **Libraries:** `jackson-module-kotlin`, `jackson-dataformat-csv`, `jackson-dataformat-yaml`, `jackson-dataformat-xml` (`2.18.11` Android; `2.17.0` Desktop)
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Usage in PDFchemy:**
  * Structured data streaming and formatting for CSV spreadsheet tables, XML, and YAML document export tools.

---

## 🖼️ 6. Media, Assets & Typography

### [Guava](https://github.com/google/guava) & [Gson](https://github.com/google/gson)
* **Authors:** Google & Contributors
* **Versions:** Guava `33.7.2-android`; Gson `2.10.1` (Android)
* **License:** Apache License 2.0
* **Usage in PDFchemy:** Worker collection utilities and serialization of bounded IPC document-operation contracts.

### [Coil (Coroutine Image Loader)](https://coil-kt.github.io/coil/) — Historical Android Dependency
* **Authors:** Colin White & Coil Contributors
* **Version:** `2.6.0`
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Former usage in PDFchemy (Android):**
  * Asynchronous image loading and memory caching for page previews and UI graphics. Removed from the current Android production graph; document previews now use bounded decoding in an isolated worker and raw pixel transport.

### [Google Material Design Icons & Symbols](https://fonts.google.com/icons)
* **Authors:** Google LLC
* **Libraries:** `androidx.compose.material:material-icons-extended` (Android) & `libs/material-icons-pruned.jar` (Desktop)
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Usage in PDFchemy:**
  * Visual iconography across all desktop and mobile navigation bars, buttons, and studio toolbars.

---

## 🧪 7. Quality Assurance & Testing Frameworks

### [TruffleHog](https://github.com/trufflesecurity/trufflehog)
* **Authors:** Truffle Security Co. & Contributors
* **Version:** `3.97.9`; official Linux/Windows release archives are SHA-256 pinned.
* **License:** AGPL-3.0; used as a separate CI scanner, not distributed in the app.
* **Usage in PDFchemy:** Full reachable-history secret scanning. Only exact reviewed historical fingerprints can pass; verified findings, verification errors, new findings and incomplete scans fail. Review provenance is recorded in `.github/secret-scan-reviewed.json`.

### [ArchUnit](https://www.archunit.org/)
* **Authors:** TNG Technology Consulting GmbH & Contributors
* **Version:** `1.3.0`
* **License:** Apache License 2.0
* **Usage in PDFchemy:** Compiled host bytecode checks that reject document parser and decoder dependencies outside isolated services.

### [JUnit 4](https://junit.org/junit4/)
* **Authors:** Kent Beck, Erich Gamma & JUnit team
* **License:** [Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html)
* **Usage in PDFchemy:** Unit testing runner for core compression, parsing, and arithmetic algorithms.

### [MockK](https://mockk.io/)
* **Authors:** Oleksii Tymchenko & MockK Contributors
* **License:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
* **Usage in PDFchemy:** Kotlin-first mocking framework for Android and JVM unit tests.

### [Robolectric](https://robolectric.org/)
* **Authors:** Google LLC & Robolectric Contributors
* **License:** [MIT License](https://github.com/robolectric/robolectric/blob/master/LICENSE)
* **Usage in PDFchemy:** Headless Android environment runner for fast unit testing of Android framework logic on the host JVM.

---

## 📦 8. Platform Ecosystems & Packaging

* **[Gradle Build Tool](https://gradle.org/)** (Apache 2.0) — Automation build system for JVM and Android compilation.
* **[AppStream Specification](https://www.freedesktop.org/wiki/Distributions/AppStream/)** (CC0-1.0 / LGPL) — Standard Linux metadata format for software distribution.
* **[WiX Toolset](https://wixtoolset.org/)** (MS-RL / Open Source) — Windows `.msi` native installer compilation via Compose Desktop native packager.

---

## ⚖️ License Summary & Open-Source Gratitude

All third-party open-source components used in PDFchemy are distributed under permissive, open-source licenses:
* **Apache License 2.0** (Apache PDFBox, PdfBox-Android, JetBrains Compose, Kotlin Coroutines, Android Jetpack, Tess4J, Jackson, Coil, Material Icons)
* **MIT License** (jsoup, Robolectric)
* **BSD 2-Clause License** (flexmark-java)
* **Bouncy Castle Licence** (Bouncy Castle Cryptography)
* **Eclipse Public License 1.0** (JUnit 4)

These permissive licenses explicitly allow free use, modification, and redistribution. We express our deepest gratitude to all authors, maintainers, and communities who created these incredible tools. PDFchemy could not exist without your generous contribution to humanity.
