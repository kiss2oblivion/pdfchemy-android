# Vanguard release blocker remediation

Scope: Android main based on `d77b771704e8ffdc70e1573edc7cfba94564656c`.
Wave 2 remains parked at `aae07e50be33554a8e660de9cc930a153673f1ac`.

## Changes

- Count resolved FileSpec dictionaries owning `/EF`, rather than `/EmbeddedFiles`,
  `/AF`, or annotation carrier dictionaries. Shared references count once;
  distinct FileSpecs count separately.
- Preserve removal of `/EmbeddedFiles`, `/AF`, `/EF`, `/FS`, and conversion of
  FileAttachment annotations, including orphaned and empty carriers.
- Assert the full executable-threat taxonomy after selective JavaScript removal:
  HTTPS links and metadata remain present while the document is clean.
- Keep action classification, automatic-context traversal, fail-closed handling,
  graph limits, reader behavior, and feature code unchanged from Android main.
  The unrelated action-counter deduplication from `757b3f9` was removed.

## Release compilation findings

The deleted jail `com.pdfchemy.app.utils.AppLogger` duplicated the host class with
the same fully qualified name. No jail source references that FQCN; jail code uses
`com.pdfchemy.app.jail.AppLogger`. Debug and release assembly pass after removal.

Removing `-dontwarn javax.naming.**` reproduces an R8 failure for eight missing
JNDI types. The referencing classes are Bouncy Castle `CrlCache` (LDAP CRL fetch),
`X509LDAPCertStoreSpi`, and `LDAPStoreHelper`. Their resolved source/JARs confirm
the LDAP implementation. The provider keep rules retain these optional classes.
Application sources contain no LDAP/JNDI/DANE/PKIX-network validation calls;
`AndroidPdfCryptoSigner` uses `JcaCertStore` with in-memory certificates. The rule
is retained for this optional desktop dependency path, not to supply an Android
runtime dependency. Any future LDAP validation feature must revisit this rule.

## Verification and release gate

Local `testDebugUnitTest`: 110 tests, zero failures/errors/skips.
`assembleDebug`, `assembleRelease`, `securityArchitecture`, and
`:app-host:assembleDebugAndroidTest` pass. Instrumentation is not locally verified:
ADB reports no device and the local emulator lists no AVDs.

Instrumentation regressions cover one embedded file, one FileSpec shared by a
name tree/AF/annotation, two independent files, and purge/re-audit with graph-wide
carrier checks. Existing benign attachment expectations remain 1 attachment and
zero executable threats.

Release acceptance requires the Android repository's full CI matrix, including
API 24/x86, API 30/x86_64, API 36/x86_64, and the final build. No release
certification is implied by local builds. After CI passes, feature work stays
frozen for the benign PDF compatibility gate: OpenAI/ChatGPT, LibreOffice, Word,
Google Docs, Acrobat, Canva, scanners, image-to-PDF, C2PA attachments, HTTPS links,
bookmarks/outlines, metadata/XMP, destination OpenAction, and normal forms.
Every discovered benign false positive must become a permanent fixture.
