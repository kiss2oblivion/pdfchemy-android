# SC-10 execution receipt

Implementation: 2026-10-21. Closeout: 2026-10-22. Release: **HOLD**. SC-10 source, local gates, Android push/ref and complete same-source CI pass. The closeout changes documentation/evidence only.

## Problem and change

Redaction removed private carrier roots but could leave their child streams reachable through alternate references. Both retained serialized audit inputs demonstrate this: PieceInfo application data and an AF/EF attachment payload without an EmbeddedFile stream type.

`RedactionResources` now discovers carrier roots before mutation and collects their complete descendant dictionaries, arrays, streams and strings by identity. Depth, node and reference limits apply. Strict application/metadata/attachment roots are validated before accessibility structure roots. Only real document identities define hierarchy boundaries; a claimed private `/Type /Page` or `/Type /StructElem` cannot create an escape. The standard structure `/Pg` exception is restricted to structure-carrier traversal.

Explicit page rendering, form and navigation roots protect live objects. A private overlap with these objects, or an arbitrary private edge into the document hierarchy, rejects before carrier removal or metadata mutation. Otherwise all alternate references to private descendants are detached before replacement/render/save. Existing page resource and original-stream checks remain in place.

## Verification

| Layer | Local result |
|---|---|
| Baseline JVM | Three absence regressions fail against unchanged production source, including both retained serialized inputs |
| Baseline native API24 | Four real successful Editor/Search exports fail the expected saved-private-byte assertion; empty crash buffer |
| Final JVM | PASS: pdf-ipc 48, pdf-jail 75, app-host 165; 288 total, zero failures/errors/skips |
| New helper regressions | PASS: 12 cases, including retained fixtures, indirect strings, cycles, spoofed types/shared structure identity, resource/navigation overlap and graph bounds |
| Native API24/36 | PASS: 24 methods each, 48 executions, zero failures/skips and empty crash buffers |
| Native scope | All eight existing Editor and eight Search security methods plus eight new SC-10 methods per API |
| Debug/test APK and static architecture gates | PASS |
| Release lint and minified unsigned assembly | PASS with explicit unsigned verification property |
| Artifact/static corpus gate | PASS: 14 fixture hashes, 62 static route rows, production identifiers/version/SDK/manifests and artifact hygiene |
| Full same-source CI | PASS: all nine jobs at `4ec21c40`; 244 native methods per API24/30/34/36, 976 executions |
| Physical device / signed release / Play | NOT RUN |

The eight SC-10 native methods exercise two byte-identical archived inputs through Editor, four searchable extensions through Editor/Search, and two used-font overlap refusals. Saved raw bytes, COS strings, decoded streams, extracted text, opaque redaction pixels, untouched Search background, independent page pixels/vector text and internal destinations are checked. The original audit inputs contain no searchable text; Search coverage deliberately extends those alias topologies with text and a second page.

All final JVM XML and raw local logs are retained under [sc10](sc10/), with byte hashes in [RAW_LOG_XML_SHA256SUMS.txt](sc10/RAW_LOG_XML_SHA256SUMS.txt) and source/APK/fixture identities in [local-verification.json](sc10/local-verification.json). Original audit artifacts remain unchanged.

## Attempts and limits

The first native receipt parser expected four copies of an assertion message; instrumentation prints it in multiple diagnostic sections. Raw evidence already showed four distinct expected test failures. `baseline-validation.log` validates the unchanged saved evidence using per-method failure status records. The initial runner error is retained.

The first fixed helper selection passed 30 of 31 methods: the indirect-string fixture's input identity control failed because PDFBox inlined its string wrappers. A genuine serialized indirect-string input replaced that fixture. This was a fixture failure, not a production result; the original XML/log remain retained. Intermediate passing builds precede the final stricter anchor policy and are separate from final verification.

Conservative refusal includes legitimate tagged-PDF OBJR annotation or MCR Form references that overlap live objects. This batch prioritizes safe rejection over detaching live rendering/navigation. The OBJR refusal has a helper regression. Seeded destination preservation is verified for local file outputs; no new SAF-provider behavior is claimed. No universal forensic-redaction guarantee or release approval follows from this bounded fixture coverage. Other SC/RA findings, SR-01 and external release gates remain open.

## Complete same-source CI

Application/test source `4ec21c40a81f7aa9eab9f696ccb755131dc7310c` is pushed and Android-remote verified. [Run 39590800550](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39590800550) passes all nine jobs after one unchanged API34 failed-job retry. Downloaded native artifacts independently validate 244 distinct completed methods per API24/30/34/36, 976 successful required-matrix executions, zero failures/errors/skips in those passing results. All eight SC-10, eight Editor, eight Search, five Warning and two batch-publication regressions are present per API. API24 strict result validation passes and its crash buffer is empty.

Architecture, reachable-history secret scanning, dependency security, full JVM tests and the dependent lint/debug/minified unsigned/artifact job pass. The first API34 attempt separately retains 244 methods with one startup lifecycle failure and all eight SC-10 methods passing. That failed attempt is excluded from the 976 passing-matrix count. FOLLOW-09 remains open; retry success does not establish its cause. See [post-fix analysis](SC10_POST_FIX_ANALYSIS.md) for that diagnosis and the unverified FOLLOW-10 form-value alias hypothesis.

| Job | Actual execution ID | Final metadata ID | Carried forward |
|---|---|---|---|
| android-instrumented-security (34, x86_64) | `119668285587` | `119668285587` | False |
| dependency-security | `119190957187` | `119668286110` | True |
| architecture | `119190957380` | `119668286610` | True |
| android-instrumented-security (36, x86_64) | `119192994737` | `119668286842` | True |
| secrets | `119190956855` | `119668318232` | True |
| android-instrumented-security (30, x86_64) | `119192994787` | `119668331420` | True |
| tests | `119192994584` | `119668331913` | True |
| android-instrumented-security (24, x86) | `119192994706` | `119668336290` | True |
| build | `119676275450` | `119676275450` | False |

Carried-forward metadata does not represent an additional job execution or additional native test count. Final closeout source comparison confirms no application/test/build/workflow changes after the tested source. No merge, signing, upload, rollout or physical-device acceptance is performed.
