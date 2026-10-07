# Benign producer compatibility gate

The permanent corpus is `app-host/src/androidTest/assets/vanguard-benign/`. Every PDF must audit Clean, pass Vanguard, open in the isolated renderer, and render its first and last page. Known attachment/URI counts are asserted against `manifest.json`; unidentified counts are explicitly null.

Three actual Microsoft Word 2021 exports were added on 2026-10-07. Word COM version 16.0, build 16.0.14334 exported neutral source text through `ExportAsFixedFormat`, with heading bookmarks and tagged text enabled. `word-source.json` records source content; `word-export-receipt.json` records source/PDF SHA-256, engine version, and export timestamps. `scripts/export_word_corpus.ps1` reproduces the export on a machine with Word installed. Export timestamps can change bytes; reproduce into a separate directory and review before replacing fixtures.

All three PDFs were inspected with PyMuPDF: one page each, Microsoft Word 2021 producer, heading bookmark, no encryption, URI counts 0/1/0. Each page was rendered and visually inspected. Romanian diacritics, CJK and supplementary Unicode characters rendered without clipping. The source contains no customer documents or personal data.

| Producer / evidence | Coverage | Release status |
|---|---|---|
| Microsoft Word 2021 | Three native exports: document properties/headings, HTTPS link/bookmarks, Unicode | PASS: Clean/counts/open/render on API 24/30/34/36 in Run 30 at 51d0128 |
| LibreOffice | Synthetic combined export-style fixture only | Actual export needed |
| ChatGPT / OpenAI | Synthetic combined export-style fixture only | Actual export needed |
| Google Docs | No approved neutral producer export in committed corpus | Actual export needed |
| Adobe Acrobat | None | Actual export needed |
| Canva | None | Actual export needed |
| Scanner and image-to-PDF tools | Synthetic image/font fixture only | Actual exports needed |
| Genuine C2PA / Content Credentials | Synthetic benign attachment carrier only; no genuine signed C2PA sample | Actual sample needed |
| Original false-positive incident PDF | Local compatibility fixture has unconfirmed incident provenance | Original bytes and provenance needed |

The connected Google Docs creation API was tested for a neutral sample on this date and returned HTTP 403 PERMISSION_DENIED. No Google document was created and no producer coverage was claimed. Restoring connector authorization or supplying a neutral native export can close this coverage gap.

Ten synthetic semantic fixtures remain useful regression tests, but do not establish compatibility with named producers. The unchanged local compatibility PDF is a separate unidentified source. Adjacent tester/production-access documents were not published as test fixtures.

To admit another producer sample: use neutral content, preserve original exported bytes, record producer/version/export route and source provenance, hash the PDF, add its manifest entry, then require Clean/Vanguard/open/render on all CI API levels. A benign failure must be fixed with a permanent regression; do not modify a sample to conceal the failure.
