# FOLLOW-10: detached AcroForm value aliases

Status: confirmed confidentiality defect corrected with local helper and real-worker proof; remote resulting-source CI pending. Release remains HOLD. Baseline production source: `005489bf275f550ae94735bad79ac8f4f95b33aa`. Work occurs in an isolated checkout; the shared Gemini checkout is unchanged.

## Reproduction

The synthetic 1,073-byte fixture is retained under `pdf-jail/src/test/resources/redaction-form-value-alias/no-view-indirect-value.pdf`, SHA-256 `3dbaf3ead5ea96d34b39aa01a1f239ee7ed632079a71c3c06e56cdb222a53abb`. It contains a NoView text field whose `/V` and catalog `/AlternatePayload` reference the same indirect COSString. Reload assertions verify object reference numbers and resolved identity, NoView state and visible source text before export.

On Android API36, both real Editor export and secure Search/search-and-apply export succeeded. Both saved outputs had no AcroForm and no selected secret in text extraction, yet retained the private value through the catalog alias and in raw serialized bytes. Both native methods failed their alias assertions. Baseline saved output SHA-256:

| Route | Saved output SHA-256 |
|---|---|
| Editor | `e3cb8a7ad06edc2ac8519334741b15e0fa2be6e7b09a26b04c13674a056b9219` |
| Secure Search | `8f42a658451d83b3e46398029299dfb3658324a22ec070e73bc3ed10da01a1be` |

Baseline app APK SHA-256: `b0c678518055b3d6d11e5a8602faad478bd62570be3de035e2fdae4d399cd6b0`. Device fingerprint: `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.D1/13818094:user/release-keys`. Local installation logs, native output, input/output PDFs and hash receipt are retained at workspace `release-validation-2.1.0-2026-10-23/follow10-api36-baseline1/`.

Eight new worker-helper regressions also fail on unchanged production source: serialized indirect value, default value, rich-text stream, nested option descendant, cyclic value graph, live-font alias refusal, document-hierarchy alias refusal and preservation of an unrelated visible form. Baseline XML and log are retained outside the source checkout. These are distinct from SC-10 PieceInfo/AF tests.

## Correction

`prepareForRedaction` records field `/V`, `/DV`, `/RV` and `/Opt` roots throughout the bounded field hierarchy before flattening. After visible appearances are preserved and field/form identities detached, these roots enter the existing bounded private-carrier graph collector. Aliases to their dictionary/array/stream/string descendants are removed throughout the reachable output graph. The collector preserves its identity/cycle/depth/node limits, actual document-hierarchy guards and refusal of intersections with retained rendering/navigation objects. Names, numbers and other shared primitive singletons remain excluded from identity deletion, consistent with the existing carrier policy.

This ordering keeps flattened, unrelated visible appearances available. It also prevents the original AcroForm value root from being classified as retained content merely because it was initially reachable through the form. Ambiguous live-font and hierarchy crossings reject rather than deleting live document objects.

## Verification

- Focused helper command: `:pdf-jail:testDebugUnitTest --tests com.pdfchemy.app.jail.engines.FormValueAliasTest --tests com.pdfchemy.app.jail.engines.RedactionResourcesTest --tests com.pdfchemy.app.jail.engines.PrivatePayloadAliasTest` passes all 42 methods after correction, including all eight new methods.
- Full worker suite: 83 tests, zero failures/errors/skips. Worker release lint: zero errors, 36 warnings. Debug and native test APK assembly succeeded. Full command: `:pdf-jail:testDebugUnitTest :pdf-jail:lintRelease :app-host:assembleDebug :app-host:assembleDebugAndroidTest --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process -PunsignedReleaseVerification=true`.
- Corrected API36 real Editor/Search: both methods pass; both original input hashes match the retained fixture exactly. Saved outputs have no AcroForm or value alias, no selected secret in extraction, and no raw marker. Editor output SHA-256 `6271010a0a4082b9f8b1fe6046dace90ba61e8babd32d255ea3b8db34e5c54fa`; Search output SHA-256 `0c0f6da81891da7da57bb1170d1fe08ffd60da243d5abee8d31b3a665670e4c6`. Evidence: workspace `follow10-api36-fixed2/` and `follow10-validation-receipt.json`. Baseline artifacts remain unchanged.
- Separate commit, Android-only remote SHA and resulting-source API24/30/34/36 CI: pending.

Corrected native attempt 1 (`follow10-api36-fixed1/`) completed no tests: Android killed the app for a startup ANR. Preserved logcat, Dropbox stack and process exit info identify PID6548 and main-thread ML Kit TextRegistrar/ComponentRuntime initialization through MlKitInitProvider while the emulator reports heavy CPU/memory pressure. This locates the stall; a full root-cause attribution remains open. No redaction method had started. One unchanged retry after the local build finished passed both methods in 30.757 seconds. Before that retry, on-device app/test APK hashes were verified against the built artifacts: app `4e2b1062ca25402d307b6cf9ade75c967ef883fdbcb50142154d1cddef9f49b7`, test `6632f3d58785b94ff7fe0721e618a45e364f3641c71b0f5c57bad5549eaed9ee`. The retry is limited fix evidence; it does not close startup reliability or erase the first failed attempt.

Native regressions live in `FormValueAliasSecurityTest`; run with AndroidJUnitRunner class `com.pdfchemy.app.security.FormValueAliasSecurityTest`. The tests reload actual worker output, require the alias and AcroForm to be absent, check selected text extraction, and check the raw output for the synthetic marker. They do not establish pixel coverage, all form types, physical-device acceptance or complete release readiness.

The prior candidate CI run `39923873559` is green (247 native methods per API24/30/34/36, plus unit/architecture/security/lint/unsigned assembly gates), but predates these regressions. Its success does not clear FOLLOW-10.
