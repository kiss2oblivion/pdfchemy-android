# FOLLOW-10 Execution Receipt: AcroForm Value-Descendant Alias Remediation

## 1. Problem Statement & Baseline Demonstration

In `SC10_POST_FIX_ANALYSIS.md`, a source-derived hypothesis was recorded regarding AcroForm cleanup during redaction:
> "The unchanged AcroForm branch in `RedactionResources.prepareForRedaction` collects field dictionaries and the form dictionary, then detaches those identities. It does not collect field value descendants. A proposed fixture has a hidden/NoView field `/V 7 0 R` and catalog `/AlternatePayload 7 0 R`, where object 7 is an indirect private COSString. Removing the form and field dictionaries would leave the catalog alias outside that removal set."

### Empirical Defect Confirmation
A focused regression test was authored in `PrivatePayloadAliasTest`:
1. Catalog contains `/AcroForm << /Fields [4 0 R] /DA (/Helv 12 Tf 0 g) >> /AlternatePayload 5 0 R`.
2. Field `4 0 R` contains `/FT /Tx /T (SecretField) /V 5 0 R /F 2` (hidden text field).
3. Object `5 0 R` is a genuine indirect `COSString("PRIVATE_ACROFORM_VALUE_99214")`.
4. Prior to remediation, `RedactionResources.prepareForRedaction` collected only field dictionary `4 0 R` and the AcroForm root into `removed`. Indirect value descendant `5 0 R` was never collected into `removed`.
5. When `detachReferences(document, removed)` executed, `/AlternatePayload 5 0 R` in the catalog was left untouched.
6. Execution failed baseline validation with `java.lang.AssertionError: Private bytes in serialized PDF` at `PrivatePayloadAliasTest.assertAbsent`. The private secret survived redaction and was reachable in the exported PDF.

---

## 2. Technical Remediation

### Architecture & Boundary Invariants
1. **Separation of Form Geometry vs Form Values:**
   - Visible appearance streams (`/AP -> /N`) are preserved and baked into page content streams/resources by `form.flatten(fields, false)`.
   - The interactive `AcroForm` container is removed from the catalog.
2. **Pre-mutation Hierarchy & Cycle Guard:**
   - Field tree hierarchy depth and node counts are strictly bounded (`<= SecurityLimits.MAX_GRAPH_DEPTH` and `<= SecurityLimits.MAX_GRAPH_NODES`) and cycles in `/Kids` are rejected fail-closed before any flattening or mutation occurs.
3. **Unified Anchors and Retained Element Collection:**
   - Extracted `buildAnchors` and `collectRetained` helpers in `RedactionResources.kt`.
   - `anchors` comprises `document.trailer`, `catalog`, and the entire page hierarchy (`/Pages` tree and `/Page` dictionaries).
   - `retained` comprises all page resources (`page.resources`, including flattened appearance streams and used fonts), page contents, page annotations, and navigation roots (`Outlines`, `Dests`, `Names`, `OpenAction`, `PageLabels`, `OCProperties`).
   - `collectRetained` explicitly excludes form tree nodes (`fieldNodes + form.cosObject`) to prevent form fields referenced by actions (e.g. `ResetForm` in `catalog.OPEN_ACTION`) from being erroneously marked as retained.
4. **Form Value Descendant Collection (`collectFormPayload`):**
   - Recurses through all form dictionary entries (excluding `/Fields` and `/DR`) and all field dictionaries.
   - For all form values and metadata descendants (`/V`, `/DV`, `/RV`, `/TU`, `/TM`, `/T`, `/DS`, `/Opt`, `/I`, `/TI`, `/AA`, `/A`, `/Lock`, `/SV`, and custom entries):
     - If a form payload node references an anchor without being a valid widget page back-reference (`/P`): rejects fail-closed with `IllegalArgumentException("Private form payload references the document hierarchy")`.
     - If a form value descendant aliases retained content or navigation: rejects fail-closed with `IllegalArgumentException("Private form payload aliases retained content or navigation")`.
     - Non-retained, non-anchor `COSDictionary` (including `COSStream`), `COSArray`, and `COSString` objects are added to `removed`.
     - Shared primitive singletons (`COSName`, numbers, booleans) are never added to `removed`.
5. **Graph-Wide Reference Detachment:**
   - `detachReferences(document, removed)` traverses the document graph and scrubs any indirect reference pointing to any detached form value descendant or unflattened hidden appearance stream.
   - `/AlternatePayload` and any arbitrary alias dictionaries pointing to the form value descendant are severed and removed.

---

## 3. Test Coverage & Verification

### Test Cases Added to `PrivatePayloadAliasTest`:
1. `serializedAcroFormValueDescendantAliasIsSanitized`:
   - Validates that an indirect `/V` COSString aliased by catalog `/AlternatePayload` is completely removed, resulting in `assertNull(catalog.AlternatePayload)` and complete absence of private bytes in the serialized PDF output.
2. `acroFormValueAliasingPageResourceRejectsBeforeMutation`:
   - Validates that a hostile field with `/V` pointing to `page.resources` rejects before mutation with `"Private form payload aliases retained content or navigation"`.
3. `acroFormValueAliasingDocumentAnchorRejectsBeforeMutation`:
   - Validates that a hostile field with `/V` pointing to the document catalog rejects before mutation with `"Private form payload references the document hierarchy"`.
4. `acroFormHiddenAppearanceAliasIsSanitized`:
   - Validates that a hidden widget (`/F 2`) with normal appearance stream `/AP -> /N` aliased by catalog `/AlternatePayload` is completely scrubbed and absent in serialized output.

### Regression & Module Suite Verification:
- `:pdf-jail:testDebugUnitTest`: 80 tests completed, 80 passed (including `redactedWidgetDoesNotRetainOriginalValueInDocumentFormTree`, `formFlatteningPreservesAnExistingCustomAppearance`, `xfaFormsAreRejectedBeforeAnyFormMutation`, `cyclicFormHierarchyIsRejectedBeforeFlattening`, and all 16 `PrivatePayloadAliasTest` methods).
- `:app-host:testDebugUnitTest`: 254 unit tests completed, 254 passed (0 failures).
- `:app-host:assembleDebugAndroidTest`: compiled and packaged instrumentation test APK successfully.
- `ReleaseWorkflowRegressionTest`: passed all release gate checks.
