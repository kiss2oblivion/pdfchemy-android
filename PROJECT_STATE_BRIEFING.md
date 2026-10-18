# PDFchemy Tools — Master Briefing & Technical Capability Dossier 📋

> **Android status update, 2026-10-18:** this dossier contains historical version/release statements. The 2.1.0 candidate remains **HOLD**; use the [current remediation plan](SECURITY_REMEDIATION_PLAN.md) and [execution receipt](reports/security-remediation-2026-10-18/EXECUTION_STATUS.md) for implementation and verification status.
> **Document de referință complet pentru Claude sau orice alt model / dezvoltator.**

---

## 1. 🧭 Identitatea Proiectului & Filosofia Fundamentală
* **Nume Produs:** PDFchemy Tools (pe Android listat și ca *Shrink PDF*).
* **Dezvoltator:** Andrei Ioan Cucoș (John / `cucosandreiioan@gmail.com`).
* **Repo GitHub:** `https://github.com/kiss2oblivion/pdfchemy`
* **Mantra:** *"Open it when you need the document fixed now."* Aplicația este o unealtă de prim-ajutor pentru documente, nu o capcană pentru utilizator.
* **Invariante Arhitecturale (Defensabile Tehnic & Fără Ambiguități):**
  1. **100% Local Document Processing (Procesare Documente Fără Cloud):** Toate operațiunile cu fișiere (compresie, reorganizare pagini, randare imagini, reader reflow, criptare AES-256) rulează **exclusiv pe procesorul local al dispozitivului**. Niciun document, text sau metadat de fișier nu părăsește vreodată telefonul sau PC-ul. Dacă tai internetul sau ești în modul avion, toate uneltele funcționează identic.
  2. **Desktop (Windows & Linux) — Zero Rețea Literal:** Aplicația desktop nu conține reclame, nu are billing și are **zero socket-uri de rețea deschise, zero telemetrie și zero apeluri DNS**.
  3. **Android — Trafic Strict Delimitat:** Singurul trafic de rețea de pe Android aparține exclusiv librăriilor standard Google: verificarea licenței Pro prin Google Play Billing și furnizarea bannerelor Google AdMob în varianta gratuită. **Zero trafic legat de fișierele utilizatorului.**
  4. **Original-Safe:** Niciun fișier sursă nu este suprascris fără acordul explicit al utilizatorului; fișierele rezultate sunt salvate ca documente noi.
  5. **Anti-Abonamente (Anti-Subscription):** Pe desktop este 100% gratuit. Pe Android, varianta gratuită funcționează complet, iar versiunea Pro este o **plată unică pe viață** (fără abonamente lunare/anuale recurente).
  6. **The Lifetime Manifesto:** Manifestul dezvoltatorului este integrat direct în codul aplicației pe Android și Desktop, stipulând garanțiile noastre de transparență și suport direct.

---

## 2. ⚡ Arsenalul Complet de Funcționalități (Ce Face Aplicația)

### A. 🗜️ Compresor Inteligent & Țintit (Smart Compressor)
* **Preset Special de Birocrație („Sub 2MB”):** Creat special pentru limitele absurde impuse de portalurile guvernamentale (ANAF, ONRC, primării, spitale, bănci, vize, universități). Apăsare pe un singur buton pentru a aduce documentul fix sub pragul cerut.
* **Trepte de Calitate Avansate:**
  * *Extreme:* Compresie agresivă pentru documente doar-text, facturi alb-negru și chitanțe (reducere de până la 90%).
  * *Strong:* Echilibru optim pentru scanări cu ștampile și semnături.
  * *Balanced:* Păstrează calitatea fotografiilor color lizibile la dimensiuni mici.
  * *Light:* Doar curățare de metadate redundante și stream-uri fără atingerea rezoluției.
* **Optimizare Structurală & Vector Sanitization:**
  * Curăță XMP metadata bloated, thumbnails cache-uite din alte aplicații și fonturi parțial duplicate.
  * Păstrează textul vectorial și desenele tehnice clare (nu le transformă în imagini pixelate).
* **Indicator Live de Reducere:** Afișează în timp real dimensiunea inițială vs. finală și procentajul exact economisit.

---

### B. 📑 Visual Page Studio (Reorganizare & Editare Vizuală de Pagini)
* **Randare Instantanee de Miniaturi:** Generează thumbnail-uri vizuale clare pentru fiecare pagină direct în memorie.
* **Drag-and-Drop Reordering:** Schimbarea ordinii paginilor prin tragere și plasare simplă.
* **Rotire Rapidă:** Rotire individuală sau în masă (90° stânga/dreapta, 180° pentru scanări realizate invers).
* **Ștergere Pagini:** Eliminarea paginilor albe, a reclamelor sau a paginilor greșite dintr-un clic.
* **Duplicare Pagini:** Copierea unei pagini în document (util pentru formulare tipizate).
* **Split & Extragere:** Extragerea uneia sau mai multor pagini într-un PDF nou, separat.
* **Merge / Îmbinare Documente:** Combinarea mai multor PDF-uri într-un singur fișier unitar, păstrând cuprinsul și structura.

---

### C. 🔄 Format Converter & Image Suite (Convertor Imagini ⇄ PDF & Text)
* **Imagini în PDF (Image to PDF):**
  * Compilarea fotografiilor de pe cameră, a chitanțelor sau scanărilor de acte (JPG, PNG, WebP) într-un singur PDF compact.
  * Ajustare automată de orientare (Portret/Peisaj), margini și aliniere.
* **PDF în Imagini High-Res (PDF to Images):**
  * Exportul fiecărei pagini din PDF ca imagine individuală PNG sau JPEG la rezoluție nativă maximă.
* **Extragere Text Simplu (.txt):**
  * Extragerea curată a conținutului text din orice PDF vectorial, util pentru preluarea rapidă de date fără unelte OCR externe.
* **Suport EPUB & eBooks:**
  * Vizualizare și conversie de cărți electronice EPUB în PDF structurat.

---

### D. 🔐 Securitate, Criptare & Privacy Vault (Document Security)
* **Criptare AES 128-bit & 256-bit:** Parolarea documentelor conform standardelor bancare și guvernamentale internaționale.
* **Controlul Permisiunilor:**
  * Blocare printare document.
  * Blocare copiere text și extragere conținut.
  * Blocare adnotare sau modificare pagini.
* **Decriptare & Înlăturare Parolă (Unlock PDF):** Eliminarea permanentă a parolei unui document protejat (pe baza introducerii corecte a parolei cunoscute) pentru arhivare facilă.
* **Eliminare Metadate Sensibile (Metadata Stripping):** Înlătură datele ascunse despre autor, tipul dispozitivului, locația GPS sau software-ul care a creat documentul.

---

### E. 📖 Reflow Reader (Cititor Ergonomic Fără Distrageri)
* **Tehnologie Text Reflow:** Reîncadrează textul pe lățimea ecranului pentru a elimina complet gesturile obositoare de scroll orizontal pe telefoane.
* **Teme Ergonomice:** Dark Mode nativ, Sepia (protecție pentru ochi pe timp de noapte), High-Contrast Light.
* **Control Tipografic:** Dimensiune ajustabilă a fontului și spațiere confortabilă între rânduri.

---

### F. ⚡ Procesare în Masă / Batch Queue (Multi-Core Desktop Engine)
* **Arhitectură Paralelă pe CPU:** Pe desktop (Windows & Linux), poți arunca 50+ documente simultan în coadă.
* **Multi-Threading:** Utilizează toate nucleele procesorului pentru a comprima, converti sau procesa foldere întregi într-o fracțiune de secundă.

---

### G. 🛠️ Reparare de Urgență a Documentelor Corupte (Recovery Engine)
* **XREF & EOF Reconstruction:** Repară fișierele PDF deteriorate, descărcate incomplet de pe net sau salvate eronat de scanere vechi, refăcând tabela internă de referințe încrucișate.

---

## 3. 📱 Android Edition (v2.0.5)
* **Stack:** Kotlin, Jetpack Compose, Material 3, AndroidX.
* **Localizare Internațională (i18n):** Paritate completă pe **20 de limbi / 21 de locale** (inclusiv suport complet RTL pentru arabă) în `res/values-*/strings.xml`.
* **Binare Semnate de Producție:**
  * `app/release/app-release.aab`
  * `distribution/android/app-release-v2.0.5.aab`
* **Google Play Status:** Gata de lansare în consolă cu descrieri ASO curate și release notes traduse.

---

## 4. 💻 Desktop Edition — Windows & Linux (v1.0.8 "Multi-Language Edition")
* **Stack:** Kotlin, Compose Multiplatform for Desktop (Skiko), Java 21 (`E:\Android_Studio\jbr`).
* **Localizare Dinamică pe Desktop:**
  * 131 de șiruri de text per limbă compilate direct în bytecode (`DesktopStrings.kt`).
  * Schimbare instantanee a limbii din interfață prin reactive state management (`mutableStateOf`), fără restart.
  * Detectare automată a limbii sistemului de operare la prima pornire + dialog de onboarding.
* **Design UI:** Dashboard aerisit și curat, axat pe cele 6 superputeri de documente, fără billboard bannere agresive. Manifestul este accesibil discret în footer și în navigation rail (`∞ Our Manifesto`).
* **Pachete Windows Construite & Publicate:**
  * **MSI Installer:** `PDFchemy-1.0.8.msi` (~68.8 MB, instalator offline cu suport pentru 20 de limbi).
  * **Single-file EXE:** `PDFchemy-1.0.8.exe` (~69.5 MB, executabil standalone portabil).
  * **Portable JAR:** `PDFchemy-universal-1.0.8.jar` (~40.3 MB, JAR universal cross-platform).
* **Pachete Linux Construite & Publicate:**
  * **Debian / Ubuntu / Mint / Pop!_OS:** `pdfchemy_1.0.8_amd64.deb` (63.9 MB).
  * **Fedora / RHEL / openSUSE:** `pdfchemy-1.0.8-1.x86_64.rpm` (77.1 MB).
  * **Script Universal Linux:** `pdfchemy.sh` (rezolvă automat dependențele Java și lansează JAR-ul).

---

## 5. 🏬 Starea Canalelor de Distribuție & Magazine Digitale
1. **GitHub Releases:** Release-ul oficial `v1.0.8` este publicat live pe `kiss2oblivion/pdfchemy` conținând toate artefactele Windows, Linux și sumele de control `SHA256SUMS.txt`.
2. **Windows Package Manager (`winget`):** PR live: `microsoft/winget-pkgs #429064` (validări trecute, în moderare).
3. **Scoop:** Manifest `packaging/scoop/pdfchemy.json` actualizat cu SHA256 v1.0.8.
4. **Chocolatey:** Pachetul `pdfchemy.1.0.8.nupkg` este generat.
5. **Flathub (Linux App Store):**
   * PR `flathub/flathub #10071` a fost închis/abandonat conform deciziei curente. Ne concentrăm pe metodele de distribuție directe (DEB, RPM, JAR, AppImage, GitHub Releases).

---

## 6. 🎯 Campania de Outreach & Presă (Metodologia „One by One, Zero Bullshit”)
* **Strategia:** Fără limbaj corporatist de PR sau spam pe email. Fiecare publicație este abordată individual, atacând exact problemele de care le pasă jurnaliștilor respectivi.
* **Ținta #1: Zona IT (Dan Cadar & Redacția):**
  * **Emailuri:** `office@zonait.ro`, `sara@zonait.ro`
  * **Unghi de atac:** Spectator fidel TVR 2 din nopțile anilor 2000 + Marea criză a birocrației din România (ANAF, ONRC cer „PDF sub 2MB”, iar oamenii își urcă actele cu CNP pe site-uri dubioase de cloud).
  * **Resurse incluse:** 20 de coduri Google Play de deblocare pe viață (Pro Lifetime) pentru întreaga redacție (Dan, Tudor, redactori, montaj) și prieteni.
* **Următoarele Ținte din Listă:**
  * **Android Authority** (Joe Hindy — secțiunea de aplicații lunare utile).
  * **Android Police** (contrastul dintre utilitarele locale și aplicațiile-scam cu abonamente).
  * **XDA Developers** (audit tehnic: vector sanitization, reconstrucție EOF, zero socket-uri de rețea deschise).
  * **gHacks** (Martin Brinkmann — testul freeware cu Wireshark).
  * **It's FOSS & OMG! Ubuntu** (utilitarul de urgență pentru desktopul Linux).
