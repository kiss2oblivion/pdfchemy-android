# SCAN-04: native OCR dependency remediation

Status: implemented and locally verified; commit, push and same-source CI are
delivery gates. Physical ARM/ARM64, signed artifact and integration gates remain
separate. This receipt does not authorize a release.

## Change

The custom Tesseract4Android 4.9.0 AAR is rebuilt from pinned Git inputs with
Tesseract 5.5.4, Leptonica 1.87.0 and libpng 1.6.59, preserving the tracked
in-memory model initialization patch. The original artifact contained Tesseract
5.5.1, Leptonica 1.85.0 and libpng 1.6.48. IJG libjpeg remains 9f. Independent
review and a version 10 compatibility migration remain explicitly tracked.

The native wrapper now targets the app's existing API 24 minimum. The first
replacement candidate retained the wrapper's API 21 floor: 13 of 14 API 24
native checks passed, but direct JNI JPEG decoding returned no Pix. The wrapper
targets below API 23 disable Leptonica's `fmemopen` backend; its memory JPEG
reader then falls back to `tmpfile`. The API 24 rebuild enables the upstream
memory backend without changing the application's supported API range. The
native API floor is an enforced artifact-policy input.

The source recipe, exact revisions, toolchain, licenses and native inventory
live under `native/` and `tools/prepare_native_ocr.py`. The artifact gate checks
four ABIs, the Java byte-buffer overloads and JNI export, all 16 native library
hashes, architecture and 64-bit ELF load alignment. Its build receipt must match
the tracked recipe, patch and source/toolchain pins. CI adds artifact-policy
regressions and live Git-revision advisory checks alongside Maven scanning.

## Advisory disposition and limits

- [Tesseract 5.5.4](https://github.com/tesseract-ocr/tesseract/releases/tag/5.5.4)
  reports fixes for 17 security advisories. Updating the source includes those
  upstream changes. This batch does not reproduce all advisories or claim every
  vulnerable parser path was reachable from document images. Production models
  are separately pinned assets, supplied through the byte-buffer JNI contract.
- [libpng GHSA-qvg3-h654-xq3j](https://github.com/pnggroup/libpng/security/advisories/GHSA-qvg3-h654-xq3j)
  is fixed in 1.6.59. The advisory's early `png_read_end` sequence is not how
  Leptonica's reviewed PNG reader operates: old and replacement `pngio.c` use
  `png_read_png`, which upstream excludes from this advisory's affected sequence.
  The library update also incorporates intervening upstream fixes. No specific
  app exploit is asserted from a version match.
- Live OSV queries on 2026-11-10 returned zero non-withdrawn findings for the
  exact replacement Tesseract, Leptonica and libpng Git revisions. This is not
  complete native coverage: IJG libjpeg and Android system codecs are outside
  those three queries. Pagination, malformed responses and non-withdrawn
  findings fail the gate rather than silently passing.
- The NDK `android/cpufeatures` shim is explicitly marked as legacy support in
  its source headers. The rebuild records its exact NDK source hashes and BSD
  notice; migration to `google/cpu_features` remains a compatibility follow-up.
  The external wrapper also emits Gradle 10 removal warnings for Groovy space
  assignment. That syntax migration is tracked before changing the pinned
  Gradle 9.4.1 toolchain.
- The original artifact's 64-bit ELF loads already met Android's 16 KB
  requirement. The first checker attempt incorrectly rejected normal 32-bit
  4 KB loads. That checker error was corrected and regression-tested before
  accepting a replacement. It is not reported as an artifact alignment fix.

## Verification record

- Four-ABI standard AAR build succeeded using NDK 28.2.13676358, CMake 3.22.1,
  AGP 9.2.1, Gradle 9.4.1 and JDK 21. The x86_64 first build took 5m 36s; the
  resumed four-ABI task completed successfully. The recipe's Ninja job pools
  bound compile/link parallelism. Upstream Leptonica ignored-return warnings
  remain in native build output and are not claimed as fixed vulnerabilities.
- All prepared changed-source hashes match the build receipt and current
  recipe/patch pins. The rebuilt artifact passes minimum ABI/JNI/version policy.
- Twenty-four artifact-policy regressions passed against the first replacement AAR.
  Mock advisory responses used by those tests are not live scan evidence.
- The corrected baseline gate rejects the original artifact's old libpng and
  Tesseract versions. Source/build logs are `scan04-*.log` and runtime evidence
  is under `.scan04-evidence/`.
- The final API 24 native rebuild passed in 16m 5s. All 26 artifact-policy
  regressions pass against it, including API-floor, memory-backend and portable
  LF/CRLF provenance regressions. The native inventory and live advisory gate
  pass. Final AAR SHA-256:
  `ef7a618021f07d98bbebdc99884ffd7c0458e4b89b277f34227c26ab2b00909e`.
- The final application verification passed in 3m 13s: debug and instrumentation
  APKs, release lint (zero errors, 622 warnings), security audit and architecture
  checks. Unit tasks are green with 577 host, 115 IPC and 136 worker results
  (zero failures/errors/skips); host/IPC tasks reused unchanged results, worker
  tests executed during the first application check and were cached in the final
  check. These are not 828 newly executed tests against the final native build.
- All 14 OCR methods pass on both API 24 x86 and API 36 x86_64 (28 executions).
  The four new methods cover runtime version, invalid in-memory model, direct
  native PNG/JPEG decoding and recovery after truncated PNG. The ten existing
  methods cover isolated-worker OCR, searchability, geometry, cancellation,
  progress, failure handling and repeated worker survival. The formerly failing
  JPEG assertion is unchanged. Both runs have strict terminal-result validation.
- All 16 OCR shared libraries in the final debug APK match the reviewed native
  inventory. `zipalign -c -P 16 4` passes. The local API 36 emulator reports 4 KB
  pages; this is ELF/ZIP alignment evidence, not a 16 KB runtime test.
- Final app APK SHA-256:
  `e5441671bce203b8a326b07a3613801b9c193c5b02e1966a63c3ff49676c81ce`.
  Test APK SHA-256:
  `cbdff09f5a24cd07a1c7d42026ddb4388f15509af57414700d01470f9c55b26c`.
  Raw final logs are `scan04-api24-full-verification.log`,
  `scan04-policy-unit-api24.log`, `scan04-live-native-api24.log` and
  `scan04-native-api24-emulator-5580.log` / `5582.log`. The failed first candidate
  remains separately recorded in `scan04-native-emulator-5582.log`.

## Workspace recovery

C: ran out of space during the initial artifact replacement, truncating that
copy in the isolated worktree. No application build used the partial copy. The
verified original was restored from D: (SHA-256
`d8e6197e73ee8cb7f98079d1ae85e6b6dbff826f2011af84e2b493549bcf62fa`).
The installer now stages and hashes a temporary sibling before replacing the
artifact and requires disk headroom. Original E: checkouts remain untouched.
Verified generated Gradle transform entries were relocated to D: individually,
with junctions retaining their existing paths, recovering 3 GB headroom. Locked
entries were skipped, and generated cache data was retained on D:. The complete
cache-root move was refused by Windows and did not change that root path.

## Preceding speech batch CI closure

The speech fixture repair `8a4e787d749e42838b8022c8184ec8374b4ddb7d` passes all
nine jobs in [CI 42787794110](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/42787794110),
including 532 native methods on each API 24/30/34/36 (2,128 total). This closes
the earlier CI Looper fixture failure. The two incomplete local API 34 broader
selections remain recorded as host emulator crashes, not passes.
