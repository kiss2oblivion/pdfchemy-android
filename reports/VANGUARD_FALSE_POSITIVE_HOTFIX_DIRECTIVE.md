# PDFchemy Vanguard False-Positive Hotfix Directive

## Objective

Fix Vanguard so that ordinary, standards-compliant PDFs are not blocked merely because they contain hyperlinks, metadata, benign embedded files, or a non-executable `/OpenAction` destination.

The security posture must remain fail-closed for genuine executable or automatic-action threats.

## Confirmed regression case

The PDF `Raport_oficial_laborator_psihologia_transporturilor_2026_REVIZUIT.pdf` is a legitimate LibreOffice-generated report.

Observed structure:

- 11 pages.
- No encryption.
- No JavaScript detected.
- No AcroForm.
- No executable launch action detected by independent inspection.
- 10 ordinary `/URI` link annotations to official websites.
- `/OpenAction[1 0 R /XYZ null null 0]`, which is a document-view destination, not an executable action.
- An `/EmbeddedFiles` entry named `Content Credentials`.
- Standard document metadata.

Vanguard currently blocks this file.

## Confirmed root cause in current `main`

Relevant files:

- `pdfjail/src/main/java/com/pdfchemy/app/logic/PdfSanitizerEngine.kt`
- `pdfjail/src/main/java/com/pdfchemy/app/jail/engines/PdfSanitizerEngineWorker.kt`
- `app/src/main/java/com/pdfchemy/app/ui/VanguardPicker.kt`

Current blocker logic incorrectly treats these as executable threats:

```kotlin
report.attachmentCount > 0
report.uriCount > 0
```

`PdfSanitizerEngineWorker.audit()` also includes URI links, attachments, and metadata in `totalThreats`, which makes ordinary PDF features look like security threats.

This classification is wrong.

## Required behavior

### BLOCK document opening

Vanguard must block when the document contains genuine executable or automatic-action risk, including:

- JavaScript action dictionaries: `/S /JavaScript`.
- JavaScript name trees.
- `/Launch`.
- Executable or network-capable actions reached from `/OpenAction` or `/AA`, including `SubmitForm`, `ImportData`, `GoToE`, `GoToR`, `Sound`, `Movie`, or equivalent automatic actions.
- Malformed action structures that cannot be safely classified.
- Parser failure in the security scanner.
- Encrypted PDFs that cannot be audited, using the existing unlock flow.

### DO NOT BLOCK document opening

The following are not sufficient grounds to block a PDF:

- Ordinary `/URI` annotations activated only by an explicit user click.
- Standard metadata.
- XMP metadata.
- Bookmarks/outlines.
- A destination-form `/OpenAction`, for example:
  `/OpenAction [pageRef /XYZ ...]`
- Ordinary internal `GoTo` navigation.
- Embedded files merely existing in the document.
- Content Credentials / C2PA-style associated files.
- Fonts, images, ICC profiles, thumbnails, structure trees, tagged-PDF data, or accessibility metadata.

### Embedded files

Embedded files are content, not automatically executable code.

Rules:

1. The PDF may open.
2. Never auto-open or auto-extract an embedded file.
3. Require an explicit user action before extraction/opening.
4. Treat executable or script-like attachment types as high risk.
5. If an automatic action references an embedded file, block that action/document according to the executable-action policy.
6. `Content Credentials` must not by itself cause a Vanguard block.

### URI actions

A URI action attached to a normal link annotation must not block the PDF.

When the user activates the link:

1. Parse the URI.
2. Allow only explicitly supported schemes.
3. Prefer `https` and `http`.
4. Reject or require a stronger warning for `file:`, `intent:`, `javascript:`, `data:`, shell-like or unknown custom schemes.
5. Never launch a URI automatically during document parsing or rendering.

A URI reached through `/OpenAction` or `/AA` is different: it is an automatic-action context and must be treated as potentially dangerous.

## Required implementation change

Do not use a single undifferentiated `processAction()` for all action contexts.

The audit must preserve action context.

Recommended model:

```kotlin
enum class ActionContext {
    OPEN_ACTION,
    ADDITIONAL_ACTION,
    ANNOTATION_CLICK,
    OUTLINE,
    OTHER
}
```

Classify each action as one of:

```kotlin
enum class VanguardRisk {
    BLOCK,
    WARN,
    INFORMATIONAL,
    SAFE
}
```

Minimum policy:

| Feature | Context | Result |
|---|---|---|
| JavaScript | any | BLOCK |
| Launch | any | BLOCK |
| URI | normal annotation click | SAFE/WARN |
| URI | OpenAction or AA | BLOCK |
| SubmitForm / ImportData | annotation click | WARN or disabled |
| SubmitForm / ImportData | OpenAction or AA | BLOCK |
| GoTo | any | SAFE |
| destination-array OpenAction | document open | SAFE |
| GoToR / GoToE | annotation click | WARN |
| GoToR / GoToE | OpenAction or AA | BLOCK |
| EmbeddedFiles present | no automatic reference | INFORMATIONAL |
| metadata/XMP | any | SAFE |

## Immediate hotfix

Until the context-aware classifier is implemented, the release-blocking false positive must be removed by changing Vanguard's blocking condition from:

```kotlin
report.jsCount > 0 ||
report.launchActionsCount > 0 ||
report.attachmentCount > 0 ||
report.uriCount > 0
```

to:

```kotlin
report.jsCount > 0 ||
report.launchActionsCount > 0
```

Apply the same correction to `hasExecutableThreats()`.

In `PdfSanitizerEngineWorker.audit()`, `threatsFound` and `isClean` must represent executable threats, not benign document features.

For the hotfix:

```kotlin
val executableThreats = jsCount + actionCount
```

Use that for:

```kotlin
put("threatsFound", executableThreats)
put("isClean", executableThreats == 0)
```

Keep `attachmentCount`, `uriCount`, and `hasMetadata` as informational audit fields.

## Required tests

Add regression tests before release.

### 1. Ordinary hyperlink PDF

Create a PDF containing one normal link annotation with `/S /URI`.

Expected:

```text
VanguardThreatResult.Clean
```

### 2. Destination-form OpenAction

Create:

```text
/OpenAction [pageRef /XYZ null null 0]
```

Expected:

```text
Clean
```

### 3. Metadata-only PDF

Author/title/XMP present.

Expected:

```text
Clean
```

### 4. Embedded benign file

PDF contains an embedded text file or a `Content Credentials` associated file with no auto-run action.

Expected:

```text
Clean
```

### 5. JavaScript action

Expected:

```text
ExecutableThreat
```

### 6. Launch action

Expected:

```text
ExecutableThreat
```

### 7. JavaScript in Additional Actions (`/AA`)

Expected:

```text
ExecutableThreat
```

### 8. Automatic URI

URI action reachable through `/OpenAction` or `/AA`.

Expected after context-aware classifier:

```text
ExecutableThreat
```

### 9. Malformed action graph

Cycle or malformed action structure.

Expected:

- no infinite recursion;
- fail closed when classification is impossible;
- bounded traversal.

### 10. Real regression fixture

Add the original legitimate report that triggered this bug to a benign regression corpus, or add a structurally equivalent generated fixture containing:

- normal URI annotations;
- metadata;
- destination-form OpenAction;
- benign EmbeddedFiles/Content Credentials.

Expected:

```text
Clean
```

## Acceptance criteria

The fix is complete only when all of the following are true:

- The regression PDF opens with Vanguard enabled.
- Normal hyperlink PDFs open.
- PDFs containing metadata open.
- PDFs containing benign embedded files open.
- JavaScript PDFs remain blocked.
- Launch-action PDFs remain blocked.
- Encrypted unverified PDFs keep the existing unlock flow.
- Parser failures remain fail-closed.
- Vanguard never executes actions during audit.
- No host-process PDF parsing is introduced.
- All scanning remains inside the PDF jail boundary.
- Existing staging/hash verification remains intact.
- Tests cover benign and malicious fixtures.
- Security messaging describes the actual detected threat category rather than claiming every blocked file contains scripts.

## UI correction

The current blocked message says:

> This PDF contains embedded scripts or automated execution hooks.

Only show that message when such a condition was actually detected.

Prefer reason-specific messages, for example:

- `JavaScript action detected`
- `External process launch action detected`
- `Automatic external action detected`
- `Document could not be safely parsed`
- `Encrypted document cannot be audited until unlocked`

Do not describe hyperlinks, metadata, or benign attachments as executable code.

## Non-goals

Do not solve the false positive by:

- disabling Vanguard;
- bypassing staging;
- moving PDF parsing back into the host process;
- allowing JavaScript;
- silently ignoring parser failures;
- automatically opening embedded files;
- globally permitting every URI scheme;
- weakening the jail boundary.

The correct fix is classification, not removal of security.
