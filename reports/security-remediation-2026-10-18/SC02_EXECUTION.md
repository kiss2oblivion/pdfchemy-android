# SC-02 search-redaction page graph

Release: **HOLD**. Batch validation in progress; no later finding has started.

SC-01 closes at documentation/evidence commit `b24f897178a9a9355a11929015bcfc2a456d7220`, Android-only pushed with matching remote SHA. Its final application/test source `62d1b1e8dca4893d6889ee59a95598bf02b219db` passes all nine CI jobs in run `39228725164`, including 221 native methods on each API24/30/34/36. This closeout commit changes no application/test/script/workflow source relative to that CI target.

## Negative evidence and limits

The retained baseline component proof serializes a replacement first page and imports a second page containing an internal destination to the original first page. Saved page-tree extraction omits `AUDIT_SECRET_731904`, but decoded streams recover it. This establishes the imported-object-graph leak; it is not a successful real-worker export.

Two new native API24 methods use actual worker search results and the host redaction gateway with direct and GoTo links. On the unchanged SC-01 APK, both fail before publication with `COSStream has been closed and cannot be read`. Production closes `baseDoc` before saving the imported stream graph. The real-worker failure is preserved separately in `sc02/baseline-api24.log`; it does not establish a successful leaking native export. The retained baseline APK/test APK hashes identify this attempt.

After the page-graph/lifetime fix, both actual-worker exports succeed and pass saved-stream absence and sanitized-destination assertions (`sc02/fixed-api24.log`). Extending the fixture to inspect rendered glyph coverage then produces four visual failures, one at each rotation with a nonzero crop origin (`sc02/geometry-api24.log`). Text/object removal alone therefore did not establish visual blackout. That same seven-method run also exposes the existing output-count contract: multiple selected matches report one output. The fixture now checks successful output and verifies all selected private lines are absent instead of treating output count as matched-box count. That contract remains unchanged and is tracked separately.

## Implementation

`PdfRedactionEngineWorker` keeps one loaded page graph alive through rasterization and save. It replaces sensitive content on existing page identities, preserving direct/GoTo destinations without importing original pages. Native-renderer and PDF page counts must match. Shared original streams expand the rasterization set transitively; the final trailer graph must not retain replaced streams. The SC-01 preparation/resource helpers remove unsupported form/document carriers and fail closed before final output copying.

Raster placement preserves each page's media/crop coordinates and rotation, so existing navigation coordinates remain meaningful. As in the prior implementation, annotations on replaced pages are removed; safe annotations/destinations on independent pages survive. Independent pages retain vector text. A dependent clipped page is rasterized to preserve visible public content while detaching the hidden shared original.

Search bounds now include ascenders/descenders and conservative padding around the baseline. Bounds are transformed from the unrotated crop plane into displayed-page coordinates. The 90/270 horizontal inverse used to paint the blackout is corrected. Four native pixel tests compare the original glyph region against opaque output pixels at rotations 0/90/180/270 with a nonzero crop origin.

## Verification

Eight new native security methods cover direct destinations, GoTo actions, multiple lines, four crop/rotation pixel cases and an unselected clipped shared Form with independent vector content/navigation. Each local selection also runs the two existing search-redaction tests.

Latest local checks pass: 63 worker methods (zero failures/errors/skips), debug/test assembly, security static audit and release lint in 4m 52s. The complete focused native selections pass ten methods each on API24 (11.276s) and API36 (54.655s); both strict result validations pass and both crash buffers are empty. These are selected native executions, not complete local suites.

The local unsigned minified release build passes in 3m 8s. The artifact gate passes 14 fixture hashes, 62 smoke-matrix rows, production IDs, manifests/version/SDK and packaged-asset hygiene. Matrix completeness is a static check, not execution of 62 UI routes. The first gate invocation passes an unsupported path argument and executes no check; its error log is retained separately from the successful corrected invocation using the script's boolean `--artifact` flag.

Raw logs/XML and artifact/source hashes are recorded under `sc02/`; 35 raw files are hashed and checked against staged bytes. Local gates are complete. Full same-source CI remains pending until explicitly recorded. No physical-device, production-signing or Play acceptance is claimed. Both owned emulators are stopped after verification.
