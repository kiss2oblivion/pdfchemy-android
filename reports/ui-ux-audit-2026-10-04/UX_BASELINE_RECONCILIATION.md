# PDFchemy Android — UX-0 Baseline Reconciliation

**Audit Date:** 4 October 2026  
**Audited Baseline:** `de053371333a773157ad66838d726071e32d1cab`  
**Current HEAD:** `de053371333a773157ad66838d726071e32d1cab` (0 commits ahead/behind)  
**Git Working Tree:** Clean (only `reports/ui-ux-audit-2026-10-04/` untracked)  

---

## 1. Baseline Test Execution

Prior to introducing any production code modifications, the full unit test suite was executed fresh with `--rerun-tasks`:

* **Command:** `$env:JAVA_HOME="E:\Android_Studio\jbr"; .\gradlew testDebugUnitTest --rerun-tasks`
* **Result:** `BUILD SUCCESSFUL in 3m 54s` (103/103 actionable tasks executed)
* **Unit Test Pass Rates:**
  * `:app-host`: **32 / 32 PASS** (0 failures, 0 errors, 0 skipped)
  * `:pdf-ipc`: **40 / 40 PASS** (0 failures, 0 errors, 0 skipped)
  * `:pdf-jail`: **25 / 25 PASS** (0 failures, 0 errors, 0 skipped)
  * **Total Baseline Tests:** **97 / 97 PASS**

---

## 2. Findings Reconciliation Matrix

Because current `HEAD` matches the audited commit `de053371...` exactly, all 50 findings and 4 observations remain in their audited state. None have been superseded, obsolete, or altered by intermediate commits.

| ID | Severity | Surface | Type | Baseline Status | Notes / Plan |
|---|---|---|---|---|---|
| **UX-01** | P1 | Dashboard | USABILITY DEFECT | **STILL PRESENT** | No search field or registry in `MainActivity.kt`. Phase UX-3. |
| **UX-02** | P2 | Reading Entry | USABILITY DEFECT | **STILL PRESENT** | No primary Open/Read entry point on Home. Phase UX-2. |
| **UX-03** | P2 | Categories | CONSISTENCY DEFECT | **STILL PRESENT** | Ambiguous category placements (Grayscale, Quick Fill). Phase UX-3. |
| **UX-04** | P2 | Safeguard → Split | USABILITY DEFECT | **STILL PRESENT** | Split First route does not forward selected URI. Phase UX-3. |
| **UX-05** | P2 | Progress / Cancel | USABILITY DEFECT | **STILL PRESENT** | Progress overlay generic ("Compressing PDF..."), no cancel. Phase UX-3. |
| **UX-06** | P2 | Completion | USABILITY DEFECT | **STILL PRESENT** | Result dialog only Share/OK; lacks Open/Files/Another. Phase UX-3. |
| **UX-07** | P2 | Output Naming | BUG | **STILL PRESENT** | `lastPathSegment` used for suggestions; generic timestamps. Phase UX-3. |
| **UX-08** | P2 | Destination Setting | USABILITY DEFECT | **STILL PRESENT** | No app preferred output location setting. Phase UX-3. |
| **UX-09** | P2 | Output Collisions | USABILITY DEFECT | **STILL PRESENT** | Provider owns duplicate resolution; no explicit user choice. Phase UX-3. |
| **UX-10** | P2 | Split/Delete/Rotate | USABILITY DEFECT | **STILL PRESENT** | Numerical-only page inputs; no visual thumbnail selection. Phase UX-3. |
| **UX-11** | P1 | Organizer History | USABILITY DEFECT | **STILL PRESENT** | No Undo/Redo; only full Reset exists. Phase UX-1. |
| **UX-12** | P1 | Organizer Export | BUG | **NEEDS REPRODUCTION** | Null preview thumbnail causes page model omission. Phase UX-1. |
| **UX-13** | P2 | Organizer Reorder | USABILITY DEFECT | **STILL PRESENT** | Copy promises drag; only arrow buttons implemented. Phase UX-3. |
| **UX-14** | P2 | Organizer Loading | PERFORMANCE DEFECT | **STILL PRESENT** | Serial thumbnails, publish-after-loop. Phase UX-4. |
| **UX-15** | P1 | PDF Reader | USABILITY DEFECT | **STILL PRESENT** | Lacks zoom/pan, continuous scroll, search, bookmarks, jump, thumbnails. Phase UX-2. |
| **UX-16** | P1 | Reader Chrome | USABILITY DEFECT | **STILL PRESENT** | All chrome permanently visible; no auto-hide. Phase UX-2. |
| **UX-17** | P1 | Editor Departure | BUG | **STILL PRESENT** | Back silently discards unsaved annotations. Phase UX-1. |
| **UX-18** | P1 | Reader → Tool | USABILITY DEFECT | **STILL PRESENT** | No tool launcher from Reader; forces re-picking. Phase UX-2 / UX-3. |
| **UX-19** | P1 | Editor / Quick Fill Undo | BUG | **STILL PRESENT** | Undo type-prioritized (drawings first, then text, stamps); no Redo. Phase UX-1. |
| **UX-20** | P1 | Wide / Tabletop Reader | BUG | **NEEDS REPRODUCTION** | Wide branches render plain Image without interactive overlays. Phase UX-1. |
| **UX-21** | P1 | Mixed Redaction Export | BUG | **NEEDS REPRODUCTION** | Redaction export writes original raster, skipping other edits. Phase UX-1. |
| **UX-22** | P1 | Coordinate Model | BUG | **NEEDS REPRODUCTION** | Fixed1000 and pixel/dp preview disagree with export units. Phase UX-1. |
| **UX-23** | P1 | Reader Decoding | PERFORMANCE DEFECT | **STILL PRESENT** | UI caller context decodes raw bitmap via PixelWire. Phase UX-2 / UX-4. |
| **UX-24** | P1 | Scanned Reflow OCR | BUG | **NEEDS REPRODUCTION** | OCR recovery gated on `sections.isEmpty()`. Phase UX-1. |
| **UX-25** | P2 | Reading Position | BUG | **STILL PRESENT** | Resume key hashes random snapshot URI; Editor does not persist. Phase UX-2. |
| **UX-26** | P2 | Reflow Defaults | CONSISTENCY DEFECT | **STILL PRESENT** | Screen init defaults to Light/16/Sans without persistence. Phase UX-2. |
| **UX-27** | P2 | Reflow Search / TOC | USABILITY DEFECT | **STILL PRESENT** | Search navigates sections, not occurrences; nested TOC dropped. Phase UX-2. |
| **UX-28** | P2 | Reader Initial State | CONSISTENCY DEFECT | **NEEDS REPRODUCTION** | `pageCount == 0` momentarily shows error/inaccessible UI. Phase UX-2. |
| **UX-29** | P2 | Vanguard Error Classification | BUG | **STILL PRESENT** | Damaged/truncated PDF reported as embedded script threat. Phase UX-1. |
| **UX-30** | P2 | Error Details | USABILITY DEFECT | **STILL PRESENT** | Raw exception classes/causes displayed to user. Phase UX-1. |
| **UX-31** | P1 | EPUB/CBZ Validation | BUG | **NEEDS REPRODUCTION** | Archive picker uses PDF Vanguard audit. Phase UX-1. |
| **UX-32** | P2 | Split Invalid Range | BUG | **NEEDS REPRODUCTION** | Empty page set produces no output but reports Success. Phase UX-1. |
| **UX-33** | P2 | Images→PDF Duplicate | BUG | **NEEDS REPRODUCTION** | Repeated URI selection creates duplicate Lazy list keys. Phase UX-1. |
| **UX-34** | P2 | Basic vs Advanced | USABILITY DEFECT | **STILL PRESENT** | Technical controls inline above Run button. Phase UX-3. |
| **UX-35** | P2 | Compression Overwrite | BUG | **NEEDS REPRODUCTION** | Async analysis overwrites explicit user choices. Phase UX-1. |
| **UX-36** | P2 | Redaction Review | USABILITY DEFECT | **STILL PRESENT** | No visual confirmation before irreversible redaction. Phase UX-3. |
| **UX-37** | P2 | Redaction Search Stale | BUG | **NEEDS REPRODUCTION** | Failed search leaves prior matches visible. Phase UX-1. |
| **UX-38** | P2 | Signing State | BUG | **NEEDS REPRODUCTION** | Silent PKI failure; old page bitmap on page index change. Phase UX-1. |
| **UX-39** | P2 | Repair State | BUG | **NEEDS REPRODUCTION** | Old diagnosis persists when new file selected. Phase UX-1. |
| **UX-40** | P2 | Dedicated History | USABILITY DEFECT | **STILL PRESENT** | Only 5 items on Home; no full screen or per-item remove. Phase UX-3. |
| **UX-41** | P2 | Recent Identity | BUG | **STILL PRESENT** | MIME inferred from label; Merge Recent contains non-PDFs. Phase UX-1. |
| **UX-42** | P2 | Settings/Premium Back | CONSISTENCY DEFECT | **STILL PRESENT** | System Back exits Activity; toolbar Back goes Home. Phase UX-3. |
| **UX-43** | P2 | Haptics Preference | BUG | **STILL PRESENT** | Cards call haptics directly regardless of app toggle. Phase UX-1. |
| **UX-44** | P2 | Home Theme Control | CONSISTENCY DEFECT | **STILL PRESENT** | 3-way cycle (System/Light/Dark) looks binary and unresponsive. Phase UX-3. |
| **UX-45** | P1 | First-Run Agreement | USABILITY DEFECT | **STILL PRESENT** | Non-dismissible agreement blocks app without decline. Phase UX-1. |
| **UX-46** | P1 | Reader Ads | USABILITY DEFECT | **STILL PRESENT** | Banner ad eligible on PDF Reader and Reflow Reader. Phase UX-2. |
| **UX-47** | P2 | Premium Purchase | BUG | **STILL PRESENT** | Button enabled before details loaded; silent failure. Phase UX-1. |
| **UX-48** | P2 | Font Scaling / Small Width | ACCESSIBILITY DEFECT | **STILL PRESENT** | Category titles truncate at 320dp / font scale 2.0. Phase UX-4. |
| **UX-49** | P2 | Reader Semantics | ACCESSIBILITY DEFECT | **STILL PRESENT** | Color/theme Box swatches lack accessible selection semantics. Phase UX-2 / UX-4. |
| **UX-50** | P3 | Security Copy Typo | POLISH ISSUE | **STILL PRESENT** | Literal `100%% Offline` rendered in verification screens. Phase UX-5. |
| **O-01** | Observation | Hardware Safeguard | OBSERVATION | **STILL PRESENT** | Conservative >50 page threshold. Profiling planned in UX-4. |
| **O-02** | Observation | Processing Overlay Touch | OBSERVATION | **STILL PRESENT** | Back/touch interception to be tested in UX-3. |
| **O-03** | Observation | Hardcoded $4.99 Price | OBSERVATION | **STILL PRESENT** | Deliberate product decision preserved. |
| **O-04** | Observation | Performance Candidates | OBSERVATION | **STILL PRESENT** | Candidate profiling scheduled in UX-4. |

---

## 3. Execution Sequencing

With UX-0 complete and clean, execution proceeds to:
1. **UX-1:** Correctness, State Integrity & User Control (Highest Priority)
2. **UX-2:** Full Reader Experience
3. **UX-3:** Discovery & Workflow Consistency
4. **UX-4:** Performance, Device Behavior & Accessibility Validation
5. **UX-5:** Contained Polish & Release Audit
