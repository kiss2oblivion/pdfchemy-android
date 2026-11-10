# Custom Android OCR artifact

`ocr-sources.json` pins the upstream inputs for the standard (non-OpenMP) custom
Tesseract4Android AAR. `ocr-inventory.json` records the resulting AAR, Java and
16 native library hashes, ELF architecture/load alignment and build provenance.
The wrapper retains the repository's `init(byte[], language, mode)` JNI patch.
Its native minimum SDK matches the application's API 24 floor. This enables
Leptonica's POSIX memory-stream backend for JPEG and avoids its temporary-file
fallback when decoding encoded image bytes through JNI.

The security rebuild updates Tesseract 5.5.1 to 5.5.4, Leptonica 1.85.0 to
1.87.0 and libpng 1.6.48 to 1.6.59. IJG libjpeg remains 9f from the pinned wrapper
tree. Its version 10 migration and independent advisory review remain follow-up
gates; the three-component OSV query does not cover it or Android system codecs.
The pinned NDK CPU-feature shim is also retained legacy code, explicitly marked
as such in its upstream headers. Migration to `google/cpu_features` is a separate
compatibility batch; this rebuild records the shim's hashes and BSD license.

## Build

Use JDK 21, the repository Gradle 9.4.1 wrapper, NDK 28.2.13676358 and CMake
3.22.1, targeting API 24. The recipe fetches exact Git revisions, refuses modified existing source
trees, applies the tracked patch and records the preparation inputs. Select a
fresh workspace on a drive with sufficient build/temp space. No signing keys are
needed. Example PowerShell, from the repository root:

```powershell
$nativeWorkspace = 'D:\AndroidEmulator\pdfchemy-fix01\native-ocr-rebuild'
$env:JAVA_HOME = '<JDK 21 directory>'
$env:TEMP = '<existing directory on the build drive>'
$env:TMP = $env:TEMP
python -B tools/prepare_native_ocr.py --workspace $nativeWorkspace --sdk E:/Android_SDK
$env:JITPACK = 'true'
.\gradlew.bat -p "$nativeWorkspace/tesseract4android" :tesseract4android:assembleStandardRelease --no-daemon --no-configuration-cache --max-workers=1
python -B tools/check_native_ocr.py minimums --artifact "$nativeWorkspace/tesseract4android/tesseract4android/build/outputs/aar/tesseract4android-standard-release.aar"
```

The recipe limits Ninja compile/link parallelism and includes the pinned NDK's
CPU feature shim. Codec source settings retain upstream defaults; the API floor
enables the memory-stream support selected by Leptonica's environment header;
the replacement CMake source/include paths select the pinned component trees.
The wrapper uses static libc++; no extra libc++ shared object is shipped.

Review build output before replacing the AAR. Copy to a temporary sibling,
verify its SHA-256, then rename it into place. Update the Gradle pin only after
that verification, and record the new inventory with `record --build-receipt
<workspace>/prepared-ocr.json`. Never regenerate an inventory to silence an
unexplained artifact difference. Timestamped ZIPs may differ between rebuilds;
this recipe records provenance and hashes and does not promise bit-identical ZIPs.

## Checks and coverage

```powershell
python -B -m unittest tools.test_check_native_ocr
python -B tools/check_native_ocr.py check
```

The live gate queries OSV for the exact Tesseract, Leptonica and libpng Git
commits and fails on non-withdrawn findings, pagination or malformed responses.
`--offline` checks artifact integrity only. CI runs both the regression tests
and live gate. A zero-result response is advisory database evidence, not a claim
that a component has no vulnerabilities. Maven inventory remains a separate gate.

Android's 16 KB ELF alignment requirement applies to the 64-bit ABIs. The
original artifact already met that requirement; 4 KB loads on 32-bit ABIs are
accepted. APK ZIP alignment must also be checked independently with `zipalign
-c -P 16 4 <apk>`.

`NativeOcrArtifactCompatibilityTest` exercises native version reporting, invalid
in-memory model initialization and direct JNI PNG/JPEG decoding. It bypasses
the public `ReadFile.readMem` method, which delegates to Android BitmapFactory.
The existing isolated-worker OCR tests verify searchable output and worker
survival. These are compatibility checks, not reproductions of every upstream
advisory. Physical ARM/ARM64 checks remain a release gate.

## Attribution

The wrapper and Tesseract are Apache-2.0; Leptonica uses its BSD-style license;
libpng uses the PNG Reference Library license; IJG libjpeg has its own terms.
The NDK CPU-feature shim uses its BSD-style license.
The original texts are retained under `native/licenses`. The JPEG software is
based in part on the work of the Independent JPEG Group. Modifications comprise
the repository's in-memory JNI patch and the source/toolchain wiring described
above; these are not an upstream release.
