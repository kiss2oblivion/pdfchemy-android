# Upload signing: P1 registration gate remains open

`app-host/build.gradle.kts` selects `app-host/release.jks`, alias `pdfchemy-release-key`, using environment values or untracked `keystore.properties`. No private key/password was changed. Release APK and AAB were built locally with shrinking enabled. `apksigner verify --print-certs` verified the APK; `keytool -printcert -jarfile` identified the signed AAB certificate. Both contain the same certificate.

| Certificate | SHA-256 | SHA-1 |
|---|---|---|
| Current signed APK/AAB | `f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b` | `b8eacf4cbb98b1b0ee801a641bafab6dabe627f5` |
| Existing root `upload_certificate.pem` | `b12a125df566a5b578966f94dd8738decf1b12ff3825373c7e3fe0f0058a540a` | `7571cdd5f4ad084a66de65a8f0d2aff317e41329` |
| Existing ignored `app-host/upload_cert_for_play_console.pem` | `f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b` | `b8eacf4cbb98b1b0ee801a641bafab6dabe627f5` |

`current-upload-certificate.pem` is the public certificate extracted from the signed AAB, not a private key. The existing root PEM was preserved. CI deliberately uses `-PunsignedReleaseVerification=true`; its green release build validates R8/configuration, not Play upload signing.

The signed AAB also passed embedded bundletool 1.18.3 structural validation. Reproduce after `bundleRelease` with `gradlew -I scripts/validate_release_bundle.init.gradle :app-host:validateSignedBundleForRelease`. `jarsigner -verify` reported `jar verified`; it also warned about the self-signed certificate/no timestamp and JarInputStream ordering because Gradle places META-INF/MANIFEST.MF last. The ZIP has no duplicate entries and passed CRC checks. These local checks do not establish Play acceptance.

Required external evidence: **Play Console → App integrity → Upload key certificate → SHA-256** for `com.pdfchemy.app`. Google's app-signing certificate can legitimately differ from the upload certificate. Local files cannot establish which upload fingerprint is registered. A full workspace search, including ignored certificates, found the current key's matching `app-host/upload_cert_for_play_console.pem`, created alongside `release.jks` on 2026-09-27. This establishes intended upload material, not server registration. On 2026-10-07 Antigravity answered the read-only follow-up **UNVERIFIED**: no dated Console receipt/API export/dashboard registration record in its existing context. It also could not establish the original incident/ten-file corpus provenance. No signing material was changed.

- If Play's upload SHA-256 is `f7b12a17…653b41b`, the current signing key matches; the root PEM is stale and can be replaced with the extracted public certificate after confirmation.
- If it is `b12a125d…58a540a`, select the corresponding private upload key if available, or deliberately request an upload-key reset to the current certificate. Renaming a certificate or replacing a PEM does not change the private signing key.
- If it is another value, identify the matching private upload key or perform the supported reset. Do not upload a bundle until this is reconciled.

The package version remains 2.0.6 / code 13. Confirm code 13 is unused for this Play application before upload; increment deliberately if Play already contains it.

Google distinguishes upload and app-signing keys and documents upload-key resets in [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en-GB) and [Android app signing](https://developer.android.com/studio/publish/app-signing).
