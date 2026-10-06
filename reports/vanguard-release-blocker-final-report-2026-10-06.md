# PDFchemy Android — Vanguard Release Blocker Final Report

**Date:** 2026-10-06
**Priority:** P0
**Result:** Remediation acceptance passed on the final Android main commit.
**Next gate:** Benign PDF compatibility testing; feature development remains frozen.

## 1. Repository and commit state

| Item | Verified value |
|---|---|
| Intended repository | [kiss2oblivion/pdfchemy-android](https://github.com/kiss2oblivion/pdfchemy-android) |
| Workspace | `E:\backup_ext_hdd\android-projects-2026-07-30\Shrinkpdf` |
| Starting Android main | `d77b771704e8ffdc70e1573edc7cfba94564656c` |
| Final tested commit | `939ff3349a8d1b078a405924b11277ef59ea8d2c` |
| Local HEAD at remediation acceptance | `939ff3349a8d1b078a405924b11277ef59ea8d2c` |
| Verified `repo-android/main` at remediation acceptance | `939ff3349a8d1b078a405924b11277ef59ea8d2c` |
| Parked Wave 2 branch | `feature/wave-2-audio-export` |
| Wave 2 local and remote SHA | `aae07e50be33554a8e660de9cc930a153673f1ac` |

The final remediation was pushed explicitly with `git push repo-android main`.
The returned remote main SHA was checked with `git ls-remote` against local HEAD.
Wave 2 was neither merged nor modified.

The remediation commit chain is:

| Commit | Description | Handling |
|---|---|---|
| `757b3f9d3cabd7bb8d355cc91d5662fe481a6557` | `fix(security): implement vanguard run #19/#20 remediation` | Inherited Anti-Gravity commit, originally pushed to the wrong repository. Its attachment fix was corrected before the final tree reached Android main. |
| `67ca9b72a23af0dcae95118d8d96fe51138f241d` | `fix(security): count logical attachments and prove benign sanitizer scope` | Corrected identity/counting, strengthened regressions, documented release compilation evidence. |
| `939ff3349a8d1b078a405924b11277ef59ea8d2c` | `fix(build): complete missing translations for release lint` | Fixed the additional build blocker exposed by CI. Final full matrix passed on this SHA. |

Remote URLs were verified before pushing:

```text
origin       -> https://github.com/kiss2oblivion/pdfchemy.git
repo-android -> https://github.com/kiss2oblivion/pdfchemy-android.git
```

Local main now tracks `repo-android/main`, and `branch.main.pushRemote` is set to
`repo-android`. No remediation push was made to `origin` during this work.

## 2. Complete net change inventory

Compared with starting Android main, the committed remediation changes **27
files: 641 insertions and 31 deletions**. This final report is published in a
subsequent documentation commit and is not part of the tested commit's diff.

| File or group | Change |
|---|---|
| `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/ActiveContentScrubber.kt` | Count canonical FileSpec identities while preserving broad carrier removal. |
| `pdf-jail/src/test/java/com/pdfchemy/app/jail/ActiveContentScrubberTest.kt` | Add identity and empty-carrier regressions; update the annotation fixture to contain an embedded FileSpec. |
| `app-host/src/androidTest/java/com/pdfchemy/app/security/SanitizerScopeSecurityTest.kt` | Correct the stale cleanliness assertion, add explicit threat taxonomy assertions, retain the simple attachment regression introduced by the inherited commit. |
| `app-host/src/androidTest/java/com/pdfchemy/app/security/AttachmentIdentitySecurityTest.kt` | Add four instrumentation tests covering one file, shared references, distinct files, and complete purging. |
| `pdf-jail/src/main/java/com/pdfchemy/app/utils/AppLogger.kt` | Delete the unused class duplicating the host logger's fully qualified name. |
| `app-host/proguard-rules.pro` | Retain the verified optional-JNDI suppression and document its dependency path. |
| Twenty `app-host/src/main/res/values-*/vanguard_release_strings.xml` files | Add the 15 missing existing-label translations per locale configuration: 300 translated strings total. |
| `reports/vanguard-release-blocker-2026-10-06.md` | Record implementation scope, release compilation findings, local verification, the CI-discovered translation blocker, and the compatibility gate. |

## 3. Logical attachment counting

### Defect

The previous compound detection incremented the counter for dictionaries owning
`/EmbeddedFiles`, `/AF`, `/EF`, or `/FS`, and for FileAttachment annotations.
One logical attachment therefore counted both its carrier dictionary and its
FileSpec. Deduplicating the current traversal dictionary by object identity did
not solve this: those dictionaries are different objects.

### Final implementation

`resolveAttachmentIdentity(dict)` returns the dictionary owning `/EF`, or
resolves a FileAttachment annotation's `/FS` to that FileSpec. A separate
identity-backed `logicalAttachments` set increments the count only for a newly
encountered canonical FileSpec.

| Structure | Counting behavior |
|---|---|
| `/EmbeddedFiles` carrier | Does not independently increment the count. |
| `/AF` carrier | Does not independently increment the count. |
| FileSpec containing `/EF` | Counts once by resolved FileSpec identity. |
| FileAttachment annotation `/FS` | Resolves the FileSpec and shares its identity. |
| Multiple references to one FileSpec | Count 1. |
| Two distinct FileSpecs | Count 2, including when they share an EF dictionary. |
| Empty carriers or an annotation without an embedded FileSpec | Count 0; still scrubbed when attachment purging is selected. |

### Purging remains complete

Counting and removal remain separate. With `purgeAttachments = true`, the
scrubber still removes `/EmbeddedFiles`, `/AF`, `/EF`, and `/FS`, and converts
FileAttachment annotations to Text annotations. Children are captured before
links are removed, so orphaned carriers are visited and scrubbed too.

Graph traversal limits and zero-trust quotas are unchanged. The inherited
commit's unrelated deduplication changes for JavaScript, Launch, other active
actions, and untrusted URI counters were removed; their behavior matches the
starting Android main.

## 4. Sanitizer scope correction

After selectively removing JavaScript while retaining an ordinary HTTPS link and
metadata, the expected result is clean. The stale `assertFalse(isClean)` was
changed to `assertTrue(isClean)` and backed by explicit assertions:

```kotlin
assertEquals(0, selectedAudit.threatsFound)
assertEquals(0, selectedAudit.jsCount)
assertEquals(0, selectedAudit.launchActionsCount)
assertEquals(0, selectedAudit.otherActionsCount)
assertEquals(0, selectedAudit.untrustedUriCount)
assertEquals(1, selectedAudit.uriCount)
assertTrue(selectedAudit.hasMetadata)
assertTrue(selectedAudit.isClean)
```

The test also retains its checks that unselected actions and attachments report
zero removals, and that default sanitization re-audits clean.

No change was made to executable-threat policy. Ordinary user-clicked HTTP/HTTPS
links, metadata, destination navigation, and benign attachments remain
nonblocking. JavaScript, Launch, dangerous active or automatic actions,
untrusted automatic URI contexts, and unverifiable parsing/encryption retain
their existing protection behavior.

## 5. Regression coverage

### Instrumentation

| Test | Expected result |
|---|---|
| Existing `PdfSanitizerAndBatesTest.testVanguardEmbeddedBenignFilePasses` | Exactly 1 attachment, zero executable threats, Vanguard allows the document. Original expectations preserved. |
| `SanitizerScopeSecurityTest.attachmentDeduplicationRegressionTest` | One simple embedded FileSpec counts once despite nested carrier dictionaries. |
| `AttachmentIdentitySecurityTest.oneEmbeddedFileCountsOnce` | 1 attachment, clean audit, no executable threats. |
| `AttachmentIdentitySecurityTest.nameTreeAssociatedFileAndAnnotationShareOneIdentity` | A FileSpec simultaneously referenced by EmbeddedFiles, AF, and annotation FS counts 1. |
| `AttachmentIdentitySecurityTest.twoIndependentFileSpecificationsCountTwice` | Two FileSpecs with their own embedded streams count 2. |
| `AttachmentIdentitySecurityTest.purgingAttachmentsSeversAllCarriersAndReauditsToZero` | Reports 2 removals, re-audits to 0, and finds no surviving attachment keys or FileAttachment subtype in reachable or pooled objects. |
| Strengthened sanitizer scope test | JavaScript is removed; retained benign HTTPS link and metadata do not make the audit unclean. |

Five instrumentation tests were added overall: the inherited simple regression
plus the four new cases. The suite grew from 173 to 178 tests.

### Unit tests

- `attachmentIdentityIsTheResolvedFileSpecRatherThanItsCarriers` checks resolved
  indirect references, shared carriers, distinct FileSpecs sharing an EF
  dictionary, removal counts, and re-audit to zero.
- `emptyAttachmentCarriersAreRemovedWithoutCountingAsFiles` checks zero counts
  alongside removal of empty carriers and neutralization of an annotation.
- The existing annotation scrub fixture now contains an EF-owning FileSpec,
  reflecting the logical attachment model instead of counting an empty FS.

## 6. Release compilation fixes and evidence

### Duplicate AppLogger

The deleted jail class was `com.pdfchemy.app.utils.AppLogger`, the same fully
qualified name as the logger in:

```text
app-host/src/main/java/com/pdfchemy/app/utils/AppLogger.kt
```

No jail source references the deleted FQCN. Jail sources use the separate logger
at `pdf-jail/src/main/java/com/pdfchemy/app/jail/AppLogger.kt`. Debug and release
assembly pass with the duplicate removed.

### Optional Bouncy Castle JNDI dependency

The retained rule is:

```proguard
-dontwarn javax.naming.**
```

Removing it reproduced an R8 failure for these eight missing types:

```text
javax.naming.NamingEnumeration
javax.naming.NamingException
javax.naming.directory.Attribute
javax.naming.directory.Attributes
javax.naming.directory.DirContext
javax.naming.directory.InitialDirContext
javax.naming.directory.SearchControls
javax.naming.directory.SearchResult
```

The references originate in Bouncy Castle's `CrlCache` LDAP CRL path,
`X509LDAPCertStoreSpi`, and `LDAPStoreHelper`. Resolved JAR dependencies and source
were inspected. Provider keep rules retain those optional classes, but
application sources do not call LDAP/JNDI/DANE or PKIX network validation.
`AndroidPdfCryptoSigner` uses an in-memory `JcaCertStore`.

The suppression is documented in ProGuard rules and retained for that optional
desktop dependency path. It does not supply JNDI at runtime. A future feature
using LDAP certificate validation must revisit this assumption.

## 7. Additional release lint blocker

[CI run #21](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/37451983389)
passed every security/test job, including 178 tests on each instrumentation
target. Its final build failed on **15 MissingTranslation errors**. Local full
release lint reproduced the same errors.

The following existing keys were translated:

```text
settings_remember_position
settings_remember_position_desc
vanguard_blocked_message_js
vanguard_blocked_message_launch
vanguard_blocked_message_uri
vanguard_blocked_message_other
sanitizer_other_action_threats
vanguard_blocked_message_default
diagnosing_document
diagnosis_failed_title
diagnosis_failed_desc
error_signing_failed
pki_signature_failed_desc
page_render_failed
retry
```

Each locale received a new `vanguard_release_strings.xml` file:

```text
values-ar       values-de       values-es       values-fr
values-hi       values-in       values-it       values-ja
values-ko       values-nl       values-pl       values-pt
values-pt-rBR   values-ro       values-ru       values-th
values-tr       values-vi       values-zh-rCN   values-zh-rTW
```

The correction adds 300 strings in 20 locale configurations. Placeholder `%1`,
security-message meaning, and Android/XML escaping are preserved. English
strings, feature behavior, security classification, and
`app-host/lint-localization-baseline.xml` were not changed.

## 8. Local verification

The final local verification executed:

```powershell
.\gradlew.bat :app-host:lintRelease testDebugUnitTest assembleDebug assembleRelease securityArchitecture :app-host:assembleDebugAndroidTest --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

| Check | Result |
|---|---|
| `testDebugUnitTest` | PASS — 110 tests, 0 failures, 0 errors, 0 skipped. |
| `assembleDebug` | PASS. |
| `assembleRelease` | PASS, including R8. |
| `securityArchitecture` | PASS. |
| `:app-host:lintRelease` | PASS — zero unbaselined errors after the translation correction. |
| `:app-host:assembleDebugAndroidTest` | PASS — instrumentation APK compiled. |
| `git diff --check` | PASS. |
| Local connected instrumentation | Not locally verified: ADB listed no device and the emulator listed no AVDs. Verified through CI instead. |

The local full-history secret scanner could not produce complete scan evidence;
it was not treated as a pass. The authoritative CI secrets job passed on the
final commit. Its scanner gate tests also passed locally.

## 9. Final CI acceptance

**[Android security and build — run #22](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/37458265499)**
**Tested SHA:** `939ff3349a8d1b078a405924b11277ef59ea8d2c`
**Overall conclusion:** SUCCESS

| Required job | Final result | Test evidence |
|---|---|---|
| architecture | PASS | Security audit, architecture checks, architecture tests. |
| dependency-security | PASS | Dependency inventory/security gate. |
| secrets | PASS | Full-history secrets gate. |
| tests | PASS | Android module unit-test jobs. |
| android-instrumented-security (24, x86) | PASS | All 178 tests finished; zero failures. |
| android-instrumented-security (30, x86_64) | PASS | All 178 tests finished; zero failures. |
| android-instrumented-security (36, x86_64) | PASS | All 178 tests finished; zero failures. |
| build | PASS | `:app-host:lintRelease :app-host:assembleDebug`. |

The instrumentation job logs were inspected for completion counts and successful
Gradle results. The final build log confirms both release lint and debug assembly
completed successfully.

CI release lint still reports **603 warnings**, with **38 inherited errors
filtered by the unchanged localization baseline**. A passing matrix does not
mean all historical lint findings have been eliminated.

Final remote verification confirmed local HEAD equals Android main and both
local/remote Wave 2 refs retain their original SHA. There were no uncommitted
tracked source changes at completion. Pre-existing untracked user files were
left intact.

## 10. Acceptance checklist

- [x] Logical embedded attachment counts are correct.
- [x] Carrier dictionaries are not independently counted.
- [x] Multiple references to the same FileSpec count once.
- [x] Distinct FileSpecs count separately.
- [x] Attachment purging removes all relevant carriers.
- [x] Benign embedded attachments do not block Vanguard.
- [x] Benign HTTPS links and metadata do not make the audit unclean.
- [x] Explicit executable-threat taxonomy assertions pass.
- [x] Local unit tests, architecture, debug/release assembly, and release lint pass.
- [x] Final remediation is pushed to the Android repository.
- [x] Local HEAD equals `repo-android/main`.
- [x] API 24, API 30, and API 36 instrumentation all pass.
- [x] Final CI build passes.
- [x] Wave 2 remains untouched.

## 11. Remaining release gate

Feature development remains frozen. The next step is a real benign-PDF
compatibility corpus covering:

- ChatGPT/OpenAI-generated PDFs.
- LibreOffice, Microsoft Word, Google Docs, Adobe Acrobat, and Canva exports.
- Scanner and image-to-PDF outputs.
- C2PA/Content Credentials attachments.
- Ordinary HTTPS links and bookmarks/outlines.
- Document metadata and XMP.
- Destination-form OpenAction and internal navigation.
- Normal forms.

Vanguard must allow these unless genuine executable or automatic active behavior
is present. Every newly discovered benign false positive must become a permanent
regression fixture.

**This report establishes completion of the narrow release-blocker remediation.
It does not establish completion of the broader compatibility release gate or
blanket release/security certification.**
