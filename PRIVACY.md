# Privacy Policy — PDFchemy Tools (Shrink PDF)

**Last Updated:** October 6, 2026
**Developer:** Andrei Ioan Cucoș (John / cucosandreiioan@gmail.com)  
**Repository:** [https://github.com/kiss2oblivion/pdfchemy](https://github.com/kiss2oblivion/pdfchemy)

---

## 1. Local document processing

PDFchemy Tools is designed from the ground up as a **zero-leak, local-first, emergency document utility**.

* **Zero Server Uploads:** All document processing (compression, conversion, page organization, visual editing, digital signatures, OCR, metadata sanitization, and encryption) executes entirely on your device's CPU and RAM.
* **No Document-Processing Backend:** We do not operate external processing servers, conversion APIs, or cloud storage backends, and PDFchemy does not upload your documents to a processing server. Audio export uses the installed Android TTS engine; its voice declaration and privacy boundary are described below.
* **Zero Telemetry & Analytics:** PDFchemy does not track user behavior, does not collect analytics, does not log document names or content, and does not report usage statistics.

---

## 2. Document & Data Security

* **Original-Safe Operation:** Tools normally create new output files in locations selected by the user. The metadata tool also offers an explicit overwrite operation; selecting it replaces the chosen document.
* **Cryptographic Privacy:** Password protection and encryption algorithms (AES-128 and AES-256) are computed natively on-device. Your passphrases and encryption keys are held temporarily in volatile memory only for the duration of the cryptographic operation and are never stored or transmitted.
* **True Redaction:** When redacting confidential information (e.g. SSNs, credit cards, legal names), PDFchemy physically strips and erases the underlying vector text and raster pixels beneath the redaction bounding box so that the redacted information cannot be recovered via copy-pasting, reverse-engineering, or search indexing.

---

## 3. Network Access & Permissions

* **Desktop Application (Windows & Linux):** Requires zero network permissions. The application functions identically when completely disconnected from the internet (air-gapped environments).
* **Android Application:** 
  * Storage access is requested solely via standard Android Storage Access Framework (SAF) pickers to read and save documents chosen by the user.
  * Audio export passes the selected document text to a separate installed Android text-to-speech instance. PDFchemy selects only voices Android reports as not requiring a network connection; this is an engine-provided declaration, not a guarantee about third-party engine network behavior. PCM and temporary WAV files stay in private cache until the user-selected output is published, then are removed. Abandoned stages are removed after restart.
  * The ML Kit document scanner uses Google Play services' camera permission; PDFchemy does not request its own camera permission. Scanner models, scanning logic, and UI are dynamically downloaded by Google Play services, so initial scanner setup can require network access. Scanning and document processing run on-device. See [Google's document scanner documentation](https://developers.google.com/ml-kit/vision/doc-scanner/android).
  * The Android app has internet permission for AdMob, the User Messaging Platform (UMP), Play Billing, and the scanner dependency described above. There is no document-processing server or document upload API.

---

## 4. Third-Party Services

* **No Advertising on Desktop:** The Windows and Linux desktop editions contain zero advertisements and zero tracking SDKs.
* **Respectful Monetization on Mobile:** The Android mobile edition does not share document data with advertising networks. Document processing workflows are never interrupted or blocked.
* **Ads, Purchases, and Consent:** AdMob and UMP may process advertising identifiers, device information, and consent choices; Play Billing handles purchases through Google Play. Advertising requests require UMP's `canRequestAds()` approval. Where UMP requires privacy choices, they can be reopened from Settings → Privacy Policy → Privacy choices. App analytics collection is disabled; advertising SDK activity is distinct from app analytics.

---

## 5. Contact & Questions

If you have questions or inquiries regarding the privacy practices of PDFchemy Tools, contact the developer:

* **Email:** [cucosandreiioan@gmail.com](mailto:cucosandreiioan@gmail.com)
* **GitHub Issues:** [https://github.com/kiss2oblivion/pdfchemy/issues](https://github.com/kiss2oblivion/pdfchemy/issues)
