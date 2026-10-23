# SC-10 post-fix code analysis

Reviewed source: `4ec21c40a81f7aa9eab9f696ccb755131dc7310c`. Review performed 2026-10-21/22. This is a focused review of the private-carrier alias fix, its helper regressions and real export tests. It does not replace the broader [fresh analysis](../security-code-reanalysis-2026-10-21/SECURITY_CODE_REANALYSIS.md).

## Changed cleanup

Independent source review found no additional concrete saved-stream/string alias bypass in the committed PieceInfo/metadata/attachment cleanup, unsafe live-object deletion, or unbounded traversal introduced by this change. Private descendants are identity-collected before mutation, strict private roots are processed first, arbitrary hierarchy crossings reject, and retained rendering/navigation intersections reject before sanitization changes the document.

Two anchor escape variants were identified during implementation and corrected before the final tests/commit: arbitrary private edges into real pages/catalog/trailer, and private objects claiming a structure type to borrow `/Pg` handling. The final helper regression covers both the spoofed type and an identity also referenced through `SE`. Twelve helper tests and eight new native methods are retained in the [execution receipt](SC10_EXECUTION.md).

Known compatibility limit: valid tagged-PDF OBJR annotation/MCR Form references can overlap retained live objects and conservatively reject. This is a supported refusal policy in this batch, not evidence of private bytes surviving a successful export. The OBJR refusal has a helper regression. Local native used-font refusals preserve seeded file destination bytes.

## New source-level follow-up: AcroForm value aliases

2026-10-23 update: FOLLOW-10 is now confirmed by both real Editor and secure Search exports on source `005489bf`; the saved outputs retain the serialized private value alias. A separate correction and its resulting-source validation are recorded in [FOLLOW10_EXECUTION.md](FOLLOW10_EXECUTION.md). The following paragraphs preserve the original hypothesis and the evidence available when this analysis was written.

**FOLLOW-10: potential confidentiality gap, runtime probe pending.** The unchanged AcroForm branch in `RedactionResources.prepareForRedaction` collects field dictionaries and the form dictionary, then detaches those identities. It does not collect field value descendants. A proposed fixture has a hidden/NoView field `/V 7 0 R` and catalog `/AlternatePayload 7 0 R`, where object 7 is an indirect private COSString. Removing the form and field dictionaries would leave the catalog alias outside that removal set.

This path predates SC-10's changed carrier cleanup. It is a source-derived hypothesis, not a confirmed saved-output leak: no new runtime probe, worker export or device result was executed for it while source was frozen for CI. Validate a serialized/reloaded input with identity controls, then actual Editor and secure Search output before assigning a confirmed finding ID or marking remediation complete. Keep supported form appearance and navigation behavior covered. This follow-up is separate from completed PieceInfo/AF fixture coverage.

## Recurrent CI startup issue

The first SC-10 API34 attempt completed 244 methods: 243 passed, one failed, no skips/errors. All eight SC-10 methods passed. The failure was `StartupWindowRegressionTest.backgroundAndResumeRetainsContent` at line 46, `moveToState(STARTED)`, before any new SC-10 test ran. Startup test and MainActivity source are unchanged from the preceding SC-04 candidate.

The method's device log shows `RESUMED -> PAUSED -> STOPPED -> RESTARTED -> STARTED`, followed by the ActivityScenario timeout; cleanup also reports unexpectedly null current state. No fatal exception, ANR or process-death entry appears in that method log. Jank warnings and consent/GMS work do not establish a cause. Retain this as recurrent **FOLLOW-09**, with cause unknown. A single unchanged failed-job retry is an outcome check; even a pass does not resolve FOLLOW-09. Original job log, XML, two startup logcats and summary are retained under `sc10/ci-api34-attempt1*`.

SC-05 through SC-09, SC-11 through SC-14, retained RA/FOLLOW issues and SR-01 remain open. Release stays **HOLD**.
