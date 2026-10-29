# SAF publication inventory — 2026-10-28

Audit starting source: `71eb0e8c27d57dba07f5f489ac80eeacdd0754b8`.
The table below inventories every production gateway/bridge execution call site, including read-only calls and internal delegation. Regenerated against the correction for reviewable current line numbers.

## Destination provenance and reachable paths

| Destination creation path | Original behavior and reachability | Corrected contract |
| --- | --- | --- |
| Shared `rememberPreferredDocumentCreator` / destination-aware wrapper, no saved folder | `ACTION_CREATE_DOCUMENT`; callback explicitly existing/unknown. Used by Editor, compression/manipulation, metadata, forms, annotations/signatures, repair, sanitize, OCR, Office, ebook, extraction and other tool save callbacks. Existing SAF documents could reach `wt`. | Shared utility chooses a folder, creates a separate unique copy through `OutputPolicy.createCopy`, returns FRESH_COPY. No Gemini-owned screen edits. |
| Shared wrapper, saved folder | `OutputPolicy.createCopy`; proven new URI. Fallback reverted to unknown CreateDocument. | Saved-folder copy retained; fallback also chooses a folder and creates a copy. |
| Deskew | Already folder -> `OutputPolicy.createCopy`, engine rejects unknown destinations. | Preserved. |
| PdfManipulator split, blank/bookmark split, PDF->images | Raw `DocumentFile.createFile`, no freshness registration; provider could return a colliding capability. | `OutputPolicy.createCopy` for all four paths, numbered names, collision check, registered provenance. |
| MainViewModel batch compression/image extraction; image compressor folder workflow | Existing host `OutputPolicy.createCopy`. | Preserved; no MainViewModel or screen edits. |
| Private file temporaries | `JailEngineBridge.bitmap/streams`, image-list/outline/table previews and framed image extraction allocate private host files, worker receives only pipes. | Preserved. These private scratch writes are not claimed to be arbitrary SAF transactions. The stream adapter's final caller-owned stream is outside gateway publication and has no active production UI caller; active ebook UI uses the URI gateway directly. |
| Legacy `PdfJailClient` export/compress | Compatibility APIs use HostOutputTransaction; no current production call sites. | Same fresh SAF guard, snapshot and ownership semantics as gateway. |
| Direct engine API supplied URI | Public core APIs permit callers to pass arbitrary content URIs even without a picker. | Entire target batch checked centrally before first destination open; existing/unknown content URIs fail explicitly. No trusting empty size, CreateDocument, URI grants, worker success or marker strings as proof of freshness. |
| Shared save utility used for audio/direct host writers | Same unknown CreateDocument fallback originally. | Utility now returns only fresh copies. The independent audio publisher API is not a PdfGateway/JailEngineBridge workflow; its direct public API is reported separately in closure limitations unless tested and corrected. |

## Publication boundary

`HostOutputTransaction` still uses bounded worker pipes, EOF readback, host-only snapshots, typed result validation and worker acceptance before publication. Existing documents are rejected before `wt`; the entire batch is checked before its first open. A fresh capability is claimed for one operation from transaction construction through cleanup/publication. Competing transactions and cleanup cannot take it. Successful publication retires freshness, preventing later reuse as a disposable output. Failure deletes only owned fresh copies after writers stop; a provider refusing deletion can leave an unpublished partial copy. This does not establish rollback for arbitrary SAF documents or dishonest providers. Source-alias prevention remains enforced by SourcePreservationPolicy.

The shared UI contract changes from choosing an output document to choosing a folder for a separate copy. Gemini should review wording/translation integration in its own branch. No changes to MainActivity, MainViewModel, OfficeExportScreen or other Gemini-owned screens are required for these callers to obtain the corrected contract.

## Complete call-site inventory

| Production call site | Operation | Destination expression | Classification |
| --- | --- | --- | --- |
| `app-host/src/main/java/com/pdfchemy/app/logic/AcroFormEngine.kt:17` | `"ACRO_FORM"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/AcroFormEngine.kt:28` | `"ACRO_FORM"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/AcroFormEngine.kt:49` | `"ACRO_FORM"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/AcroFormEngine.kt:82` | `"ACRO_FORM"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/ComicBookEngine.kt:17` | `"COMIC_BOOK"` | `destCbzUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/ComicBookEngine.kt:31` | `"COMIC_BOOK"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/ImageCompressor.kt:18` | `"IMAGE_ANALYZE"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/ImageCompressor.kt:26` | `"IMAGE_COMPRESS"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/ImageCompressor.kt:34` | `"IMAGE_TARGET"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/ImageCompressor.kt:44` | `"IMAGE_BOUNDS"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/IsolatedImageDecoder.kt:13` | `"IMAGE_DECODE"` | `(private bitmap temporary)` | Private host temporary / stream adapter |
| `app-host/src/main/java/com/pdfchemy/app/logic/JailEngineBridge.kt:15` | `name` | `dest` | Delegation to the same gateway boundary |
| `app-host/src/main/java/com/pdfchemy/app/logic/JailEngineBridge.kt:16` | `name` | `dest` | Delegation to the same gateway boundary |
| `app-host/src/main/java/com/pdfchemy/app/logic/MarkdownEngine.kt:10` | `"MARKDOWN_TO_PDF"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/MarkdownEngine.kt:16` | `"PDF_TO_MARKDOWN"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/OfficeExportEngine.kt:23` | `"OFFICE_WORD"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/OfficeExportEngine.kt:51` | `"OFFICE_EXCEL"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/OfficeExportEngine.kt:79` | `"OFFICE_PPT"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfArchiveValidatorEngine.kt:35` | `"ARCHIVE_INSPECT"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfBookmarkEngine.kt:23` | `"BOOKMARK_READ"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfBookmarkEngine.kt:41` | `"BOOKMARK_WRITE"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfCompressor.kt:128` | `"COMPRESS"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfCropEngine.kt:94` | `"CROP"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfDeskewEngine.kt:128` | `"DESKEW"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfDiffEngine.kt:34` | `"TEXT_PAGES"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfDiffEngine.kt:35` | `"TEXT_PAGES"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfEditor.kt:29` | `"GET_PAGE_COUNT"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfEditor.kt:65` | `"EDITOR_EXPORT"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfFindAndReplaceEngine.kt:8` | `"FIND_OCCURRENCES"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfFindAndReplaceEngine.kt:25` | `"REPLACE_ALL"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfFlattenEngine.kt:30` | `"FLATTEN_INSPECT"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfFlattenEngine.kt:58` | `"FLATTEN_APPLY"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfFontInspectorEngine.kt:24` | `"FONT_INSPECT"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfGrayscaleEngine.kt:15` | `"GRAYSCALE_PREVIEW"` | `(private bitmap temporary)` | Private host temporary / stream adapter |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfGrayscaleEngine.kt:18` | `"GRAYSCALE_CONVERT"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfHeaderFooterEngine.kt:15` | `"HEADER_FOOTER"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfImageReplacerEngine.kt:16` | `"IMAGE_LIST"` | `Uri.fromFile(file)` | Private host temporary / stream adapter |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfImageReplacerEngine.kt:26` | `"IMAGE_REPLACE"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfLayoutEngine.kt:15` | `"LAYOUT_RESIZE"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfLayoutEngine.kt:17` | `"LAYOUT_NUP"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfLinearizeEngine.kt:23` | `"LINEARIZE_CHECK"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:25` | `"MERGE"` | `listOf(outputUri)` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:68` | `"SPLIT"` | `destUris` | Host-created batch copies |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:97` | `"PLAN_SPLIT_BLANK"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:125` | `"SPLIT"` | `outputUris` | Host-created batch copies |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:148` | `"PLAN_SPLIT_BOOKMARKS"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:176` | `"SPLIT"` | `outputUris` | Host-created batch copies |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:209` | `"DELETE_PAGES"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:228` | `"ROTATE"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:250` | `"PROTECT"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:267` | `"UNLOCK"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:276` | `"CHECK_ENCRYPTION"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:321` | `"PDF_TO_IMAGES"` | `outputUris` | Host-created batch copies |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfManipulator.kt:336` | `"GET_PAGE_COUNT"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfMetadataEngine.kt:28` | `"METADATA_READ"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfMetadataEngine.kt:63` | `"METADATA_WRITE"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfNUpEngine.kt:55` | `"NUP_GENERATE"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfOcrEngine.kt:18` | `OCR_PROCESS` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfOutlineReader.kt:48` | `"OUTLINE_READ"` | `destUri` | Private host temporary / stream adapter |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfPageOrganizer.kt:41` | `"PAGE_ORGANIZE"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfRedactionEngine.kt:36` | `"SEARCH_REDACT"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfRedactionEngine.kt:88` | `"REDACT"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfRepairEngine.kt:30` | `"REPAIR_DIAGNOSE"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfRepairEngine.kt:64` | `"REPAIR_APPLY"` | `destPdfUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfSanitizerEngine.kt:48` | `"SANITIZE_AUDIT"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfSanitizerEngine.kt:127` | `"SANITIZE_CLEAN"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfStampAndNumberEngine.kt:67` | `"WATERMARK"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfStampAndNumberEngine.kt:91` | `"PAGE_NUMBERS"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfStampAndNumberEngine.kt:117` | `"BATES_STAMP"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfTableExtractorEngine.kt:38` | `"TABLE_EXTRACT"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfTextExtractor.kt:13` | `"TEXT_EXTRACT"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfTextExtractor.kt:26` | `"TEXT_EXTRACT"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfToEpubEngine.kt:15` | `"PDF_TO_EPUB"` | `(private temporary, then caller-owned stream)` | Private host temporary / stream adapter |
| `app-host/src/main/java/com/pdfchemy/app/logic/PdfToEpubEngine.kt:18` | `"EPUB_TO_PDF"` | `(private temporary, then caller-owned stream)` | Private host temporary / stream adapter |
| `app-host/src/main/java/com/pdfchemy/app/logic/SignatureEngine.kt:64` | `"IMAGE_DECODE"` | `(private bitmap temporary)` | Private host temporary / stream adapter |
| `app-host/src/main/java/com/pdfchemy/app/logic/SignatureEngine.kt:118` | `"SIGNATURE_APPLY"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/SignatureEngine.kt:220` | `"SIGNATURE_DIGITAL"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/logic/TextFormatConverter.kt:7` | `"TEXT_FORMAT"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/logic/TextToPdfConverter.kt:15` | `"TEXT_TO_PDF"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/ui/AttachmentManagerScreen.kt:58` | `"ATTACHMENT_LIST"` | `null` | No output destination |
| `app-host/src/main/java/com/pdfchemy/app/ui/AttachmentManagerScreen.kt:97` | `"ATTACHMENT_EXTRACT"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/ui/AttachmentManagerScreen.kt:117` | `"ATTACHMENT_EMBED"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/ui/BookletScreen.kt:91` | `"BOOKLET_GENERATE"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/ui/EbookConverterScreen.kt:116` | `"PDF_TO_EPUB"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/ui/EbookConverterScreen.kt:136` | `"EPUB_TO_PDF"` | `destUri` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt:750` | `"IMAGES_TO_PDF"` | `listOf(destUri)` | Caller URI; SAF requires fresh host provenance |
| `app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt:1227` | `"IMAGE_EXTRACT_FRAMED"` | `Uri.fromFile(tempZip)` | Private host temporary / stream adapter |
| `app-host/src/main/java/com/pdfchemy/app/ui/ScanPdfScreen.kt:181` | `"IMAGES_TO_PDF"` | `listOf(destUri)` | Caller URI; SAF requires fresh host provenance |
