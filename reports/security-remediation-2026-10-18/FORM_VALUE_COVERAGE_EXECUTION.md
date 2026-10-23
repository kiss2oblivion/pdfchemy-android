# Form value regression coverage

Release: HOLD. Isolated branch based on `61f40b5e85eb28a894215dd68b33feb5c1a75bf0`; production form traversal is from `c52233141b09607255dcc2b86d3401ae35fe045b`.

The integrated traversal and the four Editor/Search native methods match the previously tested isolated correction after normalizing line endings. The seven role/parent cases are already present in `PrivatePayloadAliasTest`; this batch preserves eight other cases that were absent from the integrated branch:

- Serialized indirect `/V` identity, saved-byte removal and reloaded catalog absence.
- Default value `/DV` string aliases.
- Rich-text `/RV` stream aliases.
- Nested `/Opt` descendants.
- Cyclic private value graphs.
- Conservative rejection of aliases to retained fonts and the document hierarchy.
- Preservation of unrelated visible form text.

The fixture is synthetic and hash-identified in its adjacent README. Changes affect tests/resources only; production code, manifests and native tests are unchanged.

## Local verification

Focused command: `gradlew :pdf-jail:testDebugUnitTest --tests com.pdfchemy.app.jail.engines.FormValueAliasTest --tests com.pdfchemy.app.jail.engines.PrivatePayloadAliasTest --tests com.pdfchemy.app.jail.engines.RedactionResourcesTest --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process --console=plain`.

Result: PASS, 53 tests: eight value cases, 23 private-payload cases and 22 resource cases; zero failures/errors/skips. Subsequent `:pdf-jail:testDebugUnitTest :pdf-jail:lintDebug` completed successfully: all 94 worker tests passed, zero failures/errors/skips; worker debug lint had zero errors and 36 warnings. The source release gate passed with 14 fixture hashes, 62 smoke routes and production ad IDs. `git diff --check` passed. Commit, push and resulting-source CI remain pending at this source snapshot.

Raw focused build log and three suite XML files are preserved outside the checkout under `release-validation-2.1.0-2026-10-23/release-form-regressions-focused.log` and `release-form-regressions-focused-results/`. Full worker XML is under `release-form-regressions-worker-results/`, with its build log in `release-form-regressions-worker-validation.log`. Portable file and result identities are recorded in `evidence/FORM_VALUE_COVERAGE_RECEIPT.json`.

The separate manifest-repair source `281319da29916d8bb1a473478035d5e13c731fcb` has independently checked CI evidence: run `39982361631`, all nine jobs successful, 270 native methods on each API24/30/34/36 (1,080 executions), including eight cross-UID and three redaction-count methods. That source did not contain `FormValueAliasSecurityTest`; no form-native coverage is attributed to that run. The newer integrated form source has its own run `40003029379`, whose completion must be collected separately.

Physical devices, Play registration, Billing/UMP, accessibility/performance, SR-01 disposition and final signed candidate evidence remain separate release gates. Gemini's tracked checkout was not edited.
