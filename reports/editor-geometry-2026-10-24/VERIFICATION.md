# Editor raster-redaction correctness verification

Base: `a9cbe935609eceb9c6add3690dfa314e3e43e70f`. Branch: `fix/2.1.0-editor-geometry`. Verification date: 2026-10-24.

## Root cause and correction

The raster branch used bitmap pixel width/height for the PDF MediaBox, cleared CropBox and replaced the original rotation with the requested additional rotation. At 2.5x resolution, a 300 x 200 point page became 750 x 500 points, and crop offsets/navigation coordinates were lost.

PdfRenderer has already applied the crop and original rotation. The replacement image is now mapped inversely from that display plane into the original PDF crop coordinates, using the same quarter-turn transforms as PdfRedactionEngineWorker. MediaBox, CropBox and their offsets remain untouched. Original rotation is preserved; a requested additional rotation is added to it. Raster resolution and existing pixel/byte quotas, worker isolation, resource detachment, fail-closed behavior and host publication are unchanged. The independent redaction engine is unmodified.

## Exact changed files

- `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfEditorWorker.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/security/EditorPageGeometrySecurityTest.kt`
- `reports/editor-geometry-2026-10-24/VERIFICATION.md`

## Native regression results

Owned offline API36 x86_64 emulator, debug application; actual PdfEditor gateway and isolated worker, reopening saved PDFs. Baseline: 11 methods executed, 10 geometry failures, raster-failure protection passed. Fixed: all 11 pass. The baseline and fixed runs use the identical test APK: `97b2058ad9fc4c659308887ef8783b2ba7dceaf010f5dd0f013c71a60d6f3e6c`.

| Export case | Verified assertions |
|---|---|
| 300 x 200 points | Output MediaBox/CropBox equality; raster pixels remain high resolution |
| A4 and Letter | Original page boxes and physical point sizes |
| Fractional 300.5 x 200.25 | No rounding of point dimensions to pixels |
| Offset MediaBox [-30,40,570,840], CropBox [50,100,350,300] | All four coordinates remain equal |
| Original 90/180/270 degrees | Original rotation, crop coordinates, public registration mark, green annotation and opaque redaction alignment |
| Original 270 plus requested 90 | Added rotation without rebasing page boxes; rotated annotation/redaction alignment |
| Compressed ObjStm private form value and catalog alias | Positive input control; output text, raw bytes, decoded streams and serialized strings contain no canary; AcroForm and alias absent |
| Bounded raster cannot complete (100000 x 1 point page) | Real Raster quota exceeded diagnostic; operation fails; existing destination and input bytes unchanged |

Each of the nine page-geometry cases contains visible sensitive text and a compressed private metadata stream with two references. Output privacy scans walk the trailer and all reloaded object-pool entries, including decoded streams and COSStrings. Both metadata references are absent. Each case checks unchanged input bytes, a real replacement image, public-mark alignment and opaque near-black samples throughout the selected redaction.

Existing suites also pass: EditorRedactionResourceSecurityTest (8), FormValueAliasSecurityTest (4) and AnnotationExportParityTest (1, internally eight rotation/redaction combinations). Total: 24 native methods, zero failures. All 94 worker unit tests and securityAudit/securityArchitecture pass. Release lint: zero errors, 640 warnings.

The first existing-suite attempt executed 13 methods with four evidence-file collisions in FormValueAliasSecurityTest before export: its default follow10/probe directory already contained prior outputs. The run was retained. Supplying a unique follow10EvidenceRun instrumentation argument produced the successful 13-method run above, without changing the test or application APK.

## Signed minified release verification

The signed, minified production APK was installed on the owned API36 emulator. Actual public UI Reader -> Editor -> redaction -> SAF publication was exercised for three synthetic inputs. No host instrumentation was injected into the release process. Every output was reopened independently with pypdf and PyMuPDF: input/output MediaBox, CropBox and rotation are equal; all decoded xref objects/streams, extracted text and raw bytes lack the sensitive canary; AcroForm and private payload alias are absent. Input canary checks are positive. Rendered output selections were visually inspected.

APK SHA-256: `2e10f676be6cae72175bf4eeffe78b64e9d4878fd17c03549737ddcde634c9f8`. Signer SHA-256: `f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b`. Signature verification, 16-KiB ZIP alignment and the actual-DEX Gson contract/artifact gate pass.

| Signed UI input | Output SHA-256 | Point geometry |
|---|---|---|
| value | `dd4a12c830990a3c39e3bc0e061658a8738f3d5a21c84fc23fcc58a969dd8c64` | {'media': [0.0, 0.0, 300.0, 200.0], 'crop': [0.0, 0.0, 300.0, 200.0], 'rotation': 0} |
| name | `224529012b9d6b00475848ae7be56bbdd3757c5a0867e7aeb91030d77f499bc5` | {'media': [0.0, 0.0, 300.0, 200.0], 'crop': [0.0, 0.0, 300.0, 200.0], 'rotation': 0} |
| crop90 | `a7721b83ae2b5b731c6732ddbceecce25d32033de19050f88bf6f73709ccb0c9` | {'media': [-30.0, 40.0, 570.0, 840.0], 'crop': [50.0, 100.0, 350.0, 300.0], 'rotation': 90} |

Two signed name-fixture harness attempts stopped before opening a PDF. The first waited for a grid-mode toggle although list mode was already active and the file was offscreen. The next left Android DocumentsUI above the relaunched app after that interrupted picker. The harness now accepts list mode, scrolls, and dismisses an observed leftover foreground picker before launch. Failed attempts remain in ../editor-coordinate-ui-name/ and ../editor-coordinate-ui-name-list/; the successful attempt is in ../editor-coordinate-ui-name-normalized/. No application or test APK changed.

## Reproduction and evidence

Build native tests with :app-host:assembleDebug :app-host:assembleDebugAndroidTest. Run the four classes listed above with AndroidJUnitRunner; the new geometry class has 11 methods. Local validation ran :pdf-jail:testDebugUnitTest :app-host:securityAudit securityArchitecture :app-host:lintRelease. Signed build ran :app-host:assembleRelease with the existing private signing configuration supplied outside the repository.

Raw logs, APK/mapping, public-UI XML, synthetic exported PDFs, rendered PNGs and forensic JSON receipts are retained outside the checkout under ../editor-coordinate-api36-{baseline,fixed,privacy-parity,privacy-parity-unique}/, ../editor-coordinate-{baseline-build,fixed-build,validation,signed-build}.log, ../editor-coordinate-signed-2026-10-24/ and ../editor-coordinate-ui-{value,name,crop90}/. Private signing files are not copied into this branch.

## Limits

This branch has local API36 native and signed-release verification. API24/30/34, a complete branch-specific CI run, physical devices and printer acceptance were not performed. No deployment-readiness claim follows from these tests. Billing, UMP, ads, Premium UI, Reader design and shared release-status documents are outside this change. The 24 targeted native methods, including the bounded-raster failure case, were run on the debug APK. Signed-release checks cover the three public-UI export fixtures; the native test suite was not injected into the minified production process.
