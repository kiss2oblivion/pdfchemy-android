# Preserve the minified Gson wire contract

Release: HOLD. Base source: 1e3d09b9af7e04f129c7ff54b77d938a16bdf3d6. Debug suites do not exercise release shrinking.

The exact signed base APK opened Reader and Editor but failed actual redaction export. On-device Developer Diagnostics reported that TypeToken must be created with a type argument and that shrinking must preserve generic signatures. The base uses Gson 2.10.1; its release configuration retained Signature attributes without retaining the reflected type-token classes. The new DEX check rejects that archived APK.

The release rules now retain Gson TypeToken and its subclasses, plus 17 specific reflected shared DTOs/enums. This preserves field names, constructors and nested collection types used by Editor and the migrated worker JSON wire. The rules follow [Gson's troubleshooting guidance](https://google.github.io/gson/Troubleshooting.html#illegalstateexception-typetoken-must-be-created-with-a-type-argument).

The release artifact gate uses Android SDK dexdump to inspect the actual minified APK. It requires both Editor Map<Integer, PageModification> token signatures, all 17 DTO schemas and six nested List element signatures. The existing CI build job runs parser regressions and this artifact check.

## Verification snapshot

- Old signed APK 33ac36aa...: actual UI export failure retained; the artifact guard rejects its removed/renamed Gson TypeToken.
- Five parser tests pass, including annotation ownership, field-versus-class signatures, enum fields and Windows SDK CRLF output. The added Windows case first reproduced a parser failure and then passed after newline normalization.
- Corrected signed prototype APK 7ddc27c03e61dc8d45caa9ccb55ec9df780aa89db5c9cdf50a17033f83199a52: release APK/AAB assembly and lint pass; actual DEX guard passes both token classes, 17 schemas and six collection signatures.
- Actual public-UI Reader -> Editor -> redaction -> SAF export passes on the owned offline API36 emulator for both exact NoView form fixtures, /V and /T. No host test code is injected into the production process.
- Reopened outputs have no canary in extracted text, raw bytes or any decoded serialized object/stream, and no remaining AcroForm or AlternatePayload catalog alias. Input canary checks are positive controls. Both full-selection renderings were inspected; no original glyph remains outside the selected rectangle.
- The first local package task failed with an opaque IncrementalSplitterRunnable error. A stacktrace diagnostic package task succeeded; the full APK/AAB/lint task set subsequently succeeded. All attempts are retained.

Raw evidence is outside the checkout in signed-launch-api36-1e3d09b9/form-export-diagnostics.xml, r8-editor-artifact-baseline.log, r8-dexdump-windows-parser-baseline.log, r8-editor-fixed-signed-build.log, r8-editor-package-diagnostic.log, r8-editor-fixed-signed-build-2.log, r8-editor-fixed-artifact-gate-2.json, r8-editor-prototype-2026-10-24/, r8-editor-ui-fixed-value-full/ and r8-editor-ui-fixed-name/. The earlier narrower test drag left the first glyph outside its rectangle; its evidence is preserved in r8-editor-ui-fixed-value/. Full-selection tests use a wider rectangle.

These are signed prototype results before the batch commit and final resulting-source CI. The output inspection also confirms a separate existing Editor raster geometry defect: a 300 x 200 point input becomes 750 x 500 points. That finding remains open for its own batch. Privacy results above do not certify physical-device or printing acceptance. Portable identities are in evidence/RELEASE_GSON_RECEIPT.json.
