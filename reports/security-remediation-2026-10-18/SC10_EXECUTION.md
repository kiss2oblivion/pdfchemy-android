# SC-10 execution receipt

Date: 2026-10-21. Release: **HOLD**. Local verification passes; commit/push and full same-source CI remain pending at this checkpoint.

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
| Full same-source CI | PENDING |
| Physical device / signed release / Play | NOT RUN |

The eight SC-10 native methods exercise two byte-identical archived inputs through Editor, four searchable extensions through Editor/Search, and two used-font overlap refusals. Saved raw bytes, COS strings, decoded streams, extracted text, opaque redaction pixels, untouched Search background, independent page pixels/vector text and internal destinations are checked. The original audit inputs contain no searchable text; Search coverage deliberately extends those alias topologies with text and a second page.

All final JVM XML and raw local logs are retained under [sc10](sc10/), with byte hashes in [RAW_LOG_XML_SHA256SUMS.txt](sc10/RAW_LOG_XML_SHA256SUMS.txt) and source/APK/fixture identities in [local-verification.json](sc10/local-verification.json). Original audit artifacts remain unchanged.

## Attempts and limits

The first native receipt parser expected four copies of an assertion message; instrumentation prints it in multiple diagnostic sections. Raw evidence already showed four distinct expected test failures. `baseline-validation.log` validates the unchanged saved evidence using per-method failure status records. The initial runner error is retained.

The first fixed helper selection passed 30 of 31 methods: the indirect-string fixture's input identity control failed because PDFBox inlined its string wrappers. A genuine serialized indirect-string input replaced that fixture. This was a fixture failure, not a production result; the original XML/log remain retained. Intermediate passing builds precede the final stricter anchor policy and are separate from final verification.

Conservative refusal includes legitimate tagged-PDF OBJR annotation or MCR Form references that overlap live objects. This batch prioritizes safe rejection over detaching live rendering/navigation. The OBJR refusal has a helper regression. Seeded destination preservation is verified for local file outputs; no new SAF-provider behavior is claimed. No universal forensic-redaction guarantee or release approval follows from this bounded fixture coverage. Other SC/RA findings, SR-01 and external release gates remain open.
