package com.pdfchemy.app.jail

import com.pdfchemy.app.logic.*
import com.pdfchemy.app.security.SecurityLimits
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Universal, fail-closed worker response validator.
 * Enforces transport framing, maximum depth, node budgets, single root value,
 * zero trailing non-whitespace, and strict operation-specific typed schemas.
 */
object WorkerResponseValidator {

    val KNOWN_OPERATIONS: Set<String> = setOf(
        "DEBUG_IDENTITY", "DEBUG_SCRATCH", "METADATA_READ", "GET_PAGE_COUNT",
        "SANITIZE_AUDIT", "SANITIZE_CLEAN", "FLATTEN_INSPECT", "CHECK_ENCRYPTION",
        "LINEARIZE_CHECK", "ARCHIVE_INSPECT", "FONT_INSPECT", "BOOKMARK_READ",
        "ATTACHMENT_LIST", "SEARCH_REDACT", "PLAN_SPLIT_BLANK", "PLAN_SPLIT_BOOKMARKS",
        "ACRO_FORM", "REPAIR_DIAGNOSE", "REPAIR_APPLY", "DESKEW", "IMAGE_ANALYZE",
        "IMAGE_BOUNDS", "IMAGE_COMPRESS", "IMAGE_TARGET", "IMAGE_LIST", "TEXT_PAGES",
        "FIND_OCCURRENCES", "REPLACE_ALL", "TEXT_FORMAT", "PDF_TO_MARKDOWN",
        "COMPRESS", "ANALYZE_PDF", "OFFICE_WORD", "OFFICE_EXCEL", "OFFICE_PPT",
        "METADATA_WRITE", "DELETE_PAGES", "ROTATE", "PROTECT", "UNLOCK",
        "BOOKMARK_WRITE", "TEXT_EXTRACT", "IMAGE_EXTRACT_FRAMED", "IMAGE_EXTRACT",
        "ARCHIVE_CONVERT", "LINEARIZE_OPTIMIZE", "PAGE_ORGANIZE", "COMIC_BOOK",
        "CROP", "FLATTEN_APPLY", "REDACT",
        "ATTACHMENT_EXTRACT", "ATTACHMENT_REMOVE", "ATTACHMENT_EMBED", "BOOKLET_GENERATE",
        "TABLE_EXTRACT", "WATERMARK", "PAGE_NUMBERS", "BATES_STAMP",
        "NUP_GENERATE", "OCR_PROCESS",
        "SIGNATURE_DIGITAL", "PDF_TO_EPUB", "EPUB_TO_PDF", "EDITOR_EXPORT",
        "SPLIT", "MERGE", "IMAGES_TO_PDF", "PDF_TO_IMAGES", "LAYOUT_RESIZE",
        "LAYOUT_NUP", "HEADER_FOOTER", "GRAYSCALE_CONVERT", "GRAYSCALE_PREVIEW",
        "TEXT_TO_PDF", "MARKDOWN_TO_PDF", "IMAGE_DECODE", "IMAGE_REPLACE",
        "OUTLINE_READ",
        "DEBUG_OUTPUT_BLOCK", "DEBUG_RETAIN_OUTPUT", "DEBUG_DUPLICATE_OUTPUT_SUCCESS",
        "DEBUG_OUTPUT_OVERFLOW", "DEBUG_UNTRUSTED_OUTPUT_OVERFLOW", "DEBUG_FAIL", "DEBUG_THROW_FATAL", "DEBUG_BLOCK", "DEBUG_EARLY_SUCCESS", "SIGNATURE_APPLY"
    )

    fun validateOperation(operation: String) {
        if (!KNOWN_OPERATIONS.contains(operation)) {
            throw SecurityException("Unknown or unsupported worker operation: $operation")
        }
    }

    private const val MAX_FILE_RESULT_BYTES = 2 * 1024 * 1024L // 2 MiB for result files
    private const val MAX_TOTAL_NODES = 5000
    private const val MAX_STRING_LENGTH = 1024
    private const val MAX_LONG_STRING_LENGTH = 1_000_000

    fun validate(operation: String, rawJson: String, targetCount: Int = 0): OperationContract {
        validateOperation(operation)
        SecurityLimits.enforceResultSize(rawJson)

        // 1. Structural check: balanced quotes, braces, brackets, and nesting depth
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in rawJson) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    require(depth <= SecurityLimits.MAX_RESPONSE_DEPTH) { "Result nesting quota exceeded" }
                }
                '}', ']' -> {
                    depth--
                    require(depth >= 0) { "Invalid worker result: unbalanced closing delimiter" }
                }
            }
        }
        require(depth == 0 && !quoted) { "Invalid worker result: unclosed delimiter" }

        // 2. Parse root value and reject trailing non-whitespace
        val tokener = JSONTokener(rawJson)
        val root = tokener.nextValue()
        val trailing = tokener.nextClean()
        require(trailing == '\u0000') { "Trailing non-whitespace after JSON root: '$trailing'" }

        // 3. Check error presence if root is JSONObject
        if (root is JSONObject) {
            require(!root.has("error")) { "Worker reported error: ${root.optString("error")}" }
            if (root.has("success")) {
                require(root.getBoolean("success")) { "Worker reported failure: success=false" }
            }
            if (root.has("isSuccess")) {
                require(root.getBoolean("isSuccess")) { "Worker reported failure: isSuccess=false" }
            }
        }

        // 4. Node count budget enforcement
        var nodeCount = 0
        fun countNodes(value: Any?) {
            nodeCount++
            require(nodeCount <= MAX_TOTAL_NODES) { "Worker result exceeded node limit of $MAX_TOTAL_NODES" }
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        countNodes(value.get(k))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        countNodes(value.get(i))
                    }
                }
            }
        }
        countNodes(root)

        // 5. Exhaustive operation validation (fail-closed for unknown operations)
        return when (operation) {
            "DEBUG_IDENTITY" -> parseDebugIdentity(root)
            "DEBUG_SCRATCH" -> parseDebugScratch(root)
            "METADATA_READ" -> parseMetadataRead(root)
            "GET_PAGE_COUNT" -> parsePageCount(root)
            "SANITIZE_AUDIT" -> parseSanitizeAudit(root)
            "SANITIZE_CLEAN" -> parseSanitizeClean(root)
            "FLATTEN_INSPECT" -> parseFlattenInspect(root)
            "CHECK_ENCRYPTION" -> parseCheckEncryption(root)
            "LINEARIZE_CHECK" -> parseLinearizeCheck(root)
            "ARCHIVE_INSPECT" -> parseArchiveInspect(root)
            "FONT_INSPECT" -> parseFontInspect(root)
            "BOOKMARK_READ" -> parseBookmarkRead(root)
            "ATTACHMENT_LIST" -> parseAttachmentList(root)
            "SEARCH_REDACT" -> parseSearchRedact(root)
            "PLAN_SPLIT_BLANK" -> parsePlanSplitBlank(root)
            "PLAN_SPLIT_BOOKMARKS" -> parsePlanSplitBookmarks(root)
            "ACRO_FORM" -> parseAcroForm(root)
            "REPAIR_DIAGNOSE" -> parseRepairDiagnose(root)
            "REPAIR_APPLY" -> parseRepairApply(root)
            "DESKEW" -> parseDeskew(root)
            "IMAGE_ANALYZE" -> parseImageAnalyze(root)
            "IMAGE_BOUNDS" -> parseImageBounds(root)
            "IMAGE_COMPRESS", "IMAGE_TARGET" -> parseImageCompression(root)
            "IMAGE_LIST" -> parseImageList(root)
            "TEXT_PAGES" -> parseTextPages(root)
            "FIND_OCCURRENCES" -> parseFindOccurrences(root)
            "REPLACE_ALL" -> parseReplaceAll(root)
            "TEXT_FORMAT" -> parseTextFormat(root)
            "PDF_TO_MARKDOWN" -> parseMarkdown(root)
            "COMPRESS" -> parseCompress(root)
            "ANALYZE_PDF" -> parsePdfAnalysis(root)
            "OFFICE_WORD", "OFFICE_EXCEL", "OFFICE_PPT" -> parseOfficeExport(root)

            // Standard output / status operations
            "METADATA_WRITE", "DELETE_PAGES", "ROTATE", "PROTECT", "UNLOCK",
            "BOOKMARK_WRITE", "TEXT_EXTRACT", "IMAGE_EXTRACT_FRAMED", "IMAGE_EXTRACT",
            "ARCHIVE_CONVERT", "LINEARIZE_OPTIMIZE", "PAGE_ORGANIZE", "COMIC_BOOK",
            "CROP", "FLATTEN_APPLY", "REDACT",
            "ATTACHMENT_EXTRACT", "ATTACHMENT_REMOVE", "ATTACHMENT_EMBED", "BOOKLET_GENERATE",
            "TABLE_EXTRACT", "WATERMARK", "PAGE_NUMBERS", "BATES_STAMP",
            "NUP_GENERATE", "OCR_PROCESS",
            "SIGNATURE_DIGITAL", "PDF_TO_EPUB", "EPUB_TO_PDF", "EDITOR_EXPORT",
            "SPLIT", "MERGE", "IMAGES_TO_PDF", "PDF_TO_IMAGES", "LAYOUT_RESIZE",
            "LAYOUT_NUP", "HEADER_FOOTER", "GRAYSCALE_CONVERT", "GRAYSCALE_PREVIEW",
            "TEXT_TO_PDF", "MARKDOWN_TO_PDF", "IMAGE_DECODE", "IMAGE_REPLACE",
            "OUTLINE_READ",
            "DEBUG_OUTPUT_BLOCK", "DEBUG_RETAIN_OUTPUT", "DEBUG_DUPLICATE_OUTPUT_SUCCESS",
            "DEBUG_OUTPUT_OVERFLOW", "DEBUG_UNTRUSTED_OUTPUT_OVERFLOW", "DEBUG_FAIL", "DEBUG_THROW_FATAL", "DEBUG_BLOCK", "DEBUG_EARLY_SUCCESS", "SIGNATURE_APPLY" -> parseStandardOutput(root, targetCount)

            else -> throw SecurityException("Unknown or unsupported worker operation: $operation")
        }
    }

    // --- Operation Parsers ---

    private fun parseDebugIdentity(root: Any?): DebugIdentityContract {
        val obj = requireObject(root)
        val pid = obj.requireInt("pid", min = 1)
        val uid = obj.requireInt("uid", min = 1)
        return DebugIdentityContract(pid = pid, uid = uid)
    }

    private fun parseDebugScratch(root: Any?): DebugScratchContract {
        val obj = requireObject(root)
        val capability = obj.requireBoolean("scratchCapability")
        require(capability) { "scratchCapability must be true" }
        return DebugScratchContract(scratchCapability = true)
    }

    private fun parseMetadataRead(root: Any?): MetadataReadContract {
        val obj = requireObject(root)
        require(obj.length() > 0) { "Empty METADATA_READ response is invalid" }
        val title = obj.requireString("title")
        val author = obj.requireString("author")
        val subject = obj.requireString("subject")
        val keywords = obj.requireString("keywords")
        val creator = obj.requireString("creator")
        val producer = obj.requireString("producer")
        val creationDate = obj.requireString("creationDate")
        val modificationDate = obj.requireString("modificationDate")
        val pageCount = obj.requireInt("pageCount", min = 0, max = SecurityLimits.MAX_PAGE_COUNT)
        val hasXmpMetadata = obj.requireBoolean("hasXmpMetadata")
        val isEncrypted = obj.requireBoolean("isEncrypted")
        return MetadataReadContract(
            title = title,
            author = author,
            subject = subject,
            keywords = keywords,
            creator = creator,
            producer = producer,
            creationDate = creationDate,
            modificationDate = modificationDate,
            pageCount = pageCount,
            hasXmpMetadata = hasXmpMetadata,
            isEncrypted = isEncrypted
        )
    }

    private fun parsePageCount(root: Any?): PageCountContract {
        val obj = requireObject(root)
        val count = obj.requireInt("pageCount", min = 1, max = SecurityLimits.MAX_PAGE_COUNT)
        return PageCountContract(pageCount = count)
    }

    private fun parseSanitizeAudit(root: Any?): SanitizeAuditContract {
        val obj = requireObject(root)
        require(obj.length() > 0) { "Empty SANITIZE_AUDIT response is invalid" }
        val threatsFound = obj.requireInt("threatsFound", min = 0)
        val isClean = obj.requireBoolean("isClean")
        val isEncrypted = obj.requireBoolean("isEncrypted")
        val parseFailed = obj.requireBoolean("parseFailed")

        if (parseFailed) {
            require(!isClean) { "parseFailed=true must not report isClean=true" }
            return SanitizeAuditContract(
                threatsFound = threatsFound,
                isClean = false,
                jsCount = 0,
                launchActionsCount = 0,
                attachmentCount = 0,
                uriCount = 0,
                hasMetadata = false,
                isEncrypted = isEncrypted,
                parseFailed = true
            )
        }

        // When parseFailed is false, full detailed counts are mandatory
        val jsCount = obj.requireInt("jsCount", min = 0)
        val launchActionsCount = obj.requireInt("launchActionsCount", min = 0)
        val attachmentCount = obj.requireInt("attachmentCount", min = 0)
        val uriCount = obj.requireInt("uriCount", min = 0)
        val hasMetadata = obj.requireBoolean("hasMetadata")

        if (threatsFound > 0) {
            require(!isClean) { "Audit reporting threatsFound=$threatsFound cannot report isClean=true" }
        } else {
            require(isClean) { "Audit reporting zero threats must report isClean=true" }
        }

        return SanitizeAuditContract(
            threatsFound = threatsFound,
            isClean = isClean,
            jsCount = jsCount,
            launchActionsCount = launchActionsCount,
            attachmentCount = attachmentCount,
            uriCount = uriCount,
            hasMetadata = hasMetadata,
            isEncrypted = isEncrypted,
            parseFailed = false
        )
    }

    private fun parseSanitizeClean(root: Any?): SanitizerCleanContract {
        val obj = requireObject(root)
        val isSuccess = obj.requireBoolean("isSuccess")
        require(isSuccess) { "Worker reported failure: isSuccess=false" }
        return SanitizerCleanContract(
            isSuccess = true,
            threatsRemoved = obj.requireInt("threatsRemoved", min = 0),
            jsRemoved = obj.requireInt("jsRemoved", min = 0),
            actionsRemoved = obj.requireInt("actionsRemoved", min = 0),
            metadataRemoved = obj.requireBoolean("metadataRemoved"),
            attachmentsRemoved = obj.requireInt("attachmentsRemoved", min = 0)
        )
    }

    private fun parseFlattenInspect(root: Any?): FlattenInspectContract {
        val obj = requireObject(root)
        val fieldCount = obj.requireInt("fieldCount", min = 0)
        val annotationCount = obj.requireInt("annotationCount", min = 0)
        val hasSignatures = obj.requireBoolean("hasSignatures")
        return FlattenInspectContract(
            fieldCount = fieldCount,
            annotationCount = annotationCount,
            hasSignatures = hasSignatures
        )
    }

    private fun parseCheckEncryption(root: Any?): CheckEncryptionContract {
        val obj = requireObject(root)
        val isEncrypted = obj.requireBoolean("isEncrypted")
        return CheckEncryptionContract(isEncrypted = isEncrypted)
    }

    private fun parseLinearizeCheck(root: Any?): LinearizeCheckContract {
        val obj = requireObject(root)
        val isLinearized = obj.requireBoolean("isLinearized")
        val pageCount = obj.requireInt("pageCount", min = 0, max = SecurityLimits.MAX_PAGE_COUNT)
        return LinearizeCheckContract(isLinearized = isLinearized, pageCount = pageCount)
    }

    private fun parseArchiveInspect(root: Any?): ArchiveInspectContract {
        val obj = requireObject(root)
        val version = obj.requireString("pdfaVersionDetected", maxLength = 64)
        val score = obj.requireInt("complianceScore", min = 0, max = 100)
        val isCompliant = obj.requireBoolean("isCompliant")
        val checksArray = obj.requireArray("checks", maxItems = 100)
        val checks = mutableListOf<ArchiveCheckItemContract>()
        for (i in 0 until checksArray.length()) {
            val c = checksArray.getJSONObject(i)
            val rule = c.requireString("rule", maxLength = 128)
            val isPassed = c.requireBoolean("isPassed")
            val details = c.requireString("details", maxLength = 512)
            val severity = c.requireString("severity", maxLength = 16)
            require(severity in listOf("INFO", "WARNING", "ERROR")) { "Invalid check severity: $severity" }
            checks.add(ArchiveCheckItemContract(rule = rule, isPassed = isPassed, details = details, severity = severity))
        }
        return ArchiveInspectContract(
            pdfaVersionDetected = version,
            complianceScore = score,
            checks = checks,
            isCompliant = isCompliant
        )
    }

    private fun parseFontInspect(root: Any?): FontInspectContract {
        val arr = requireArray(root, maxItems = 500)
        val fonts = mutableListOf<FontInfoContract>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            fonts.add(FontInfoContract(
                postscriptName = obj.requireString("postscriptName", maxLength = 256),
                familyName = obj.requireString("familyName", maxLength = 256),
                formatType = obj.requireString("formatType", maxLength = 256),
                isEmbedded = obj.requireBoolean("isEmbedded"),
                isSubset = obj.requireBoolean("isSubset"),
                encoding = obj.requireString("encoding", maxLength = 256),
                pageCountUsed = obj.requireInt("pageCountUsed", min = 0)
            ))
        }
        return FontInspectContract(fonts = fonts)
    }

    private fun parseBookmarkRead(root: Any?): BookmarkReadContract {
        val arr = requireArray(root, maxItems = 500)
        val bookmarks = mutableListOf<BookmarkItemContract>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            bookmarks.add(BookmarkItemContract(
                title = obj.requireString("title", maxLength = 256),
                pageIndex = obj.requireInt("pageIndex", min = 0, max = SecurityLimits.MAX_PAGE_COUNT)
            ))
        }
        return BookmarkReadContract(bookmarks = bookmarks)
    }

    private fun parseAttachmentList(root: Any?): AttachmentListContract {
        val arr = requireArray(root, maxItems = 500)
        val list = mutableListOf<AttachmentItemContract>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(AttachmentItemContract(
                name = obj.requireString("name", maxLength = 256),
                sizeBytes = obj.requireLong("sizeBytes", min = 0, max = SecurityLimits.MAX_OUTPUT_BYTES),
                mimeType = obj.requireString("mimeType", maxLength = 128)
            ))
        }
        return AttachmentListContract(attachments = list)
    }

    private fun parseSearchRedact(root: Any?): SearchRedactContract {
        val obj = requireObject(root)
        val arr = obj.requireArray("boxes", maxItems = 5000)
        val boxes = mutableListOf<RedactionBox>()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val page = b.requireInt("pageIndex", min = 0, max = SecurityLimits.MAX_PAGE_COUNT)
            val left = b.requireDouble("left", min = 0.0, max = 1.0).toFloat()
            val top = b.requireDouble("top", min = 0.0, max = 1.0).toFloat()
            val right = b.requireDouble("right", min = 0.0, max = 1.0).toFloat()
            val bottom = b.requireDouble("bottom", min = 0.0, max = 1.0).toFloat()
            require(right >= left) { "Invalid box bounds: right < left" }
            require(bottom >= top) { "Invalid box bounds: bottom < top" }
            val label = b.optString("overlayLabel", "REDACTED")
            require(label.length <= 256) { "Label exceeds 256 chars" }
            boxes.add(RedactionBox(
                pageIndex = page,
                xRatio = left,
                yRatio = top,
                widthRatio = right - left,
                heightRatio = bottom - top,
                overlayLabel = label
            ))
        }
        return SearchRedactContract(boxes = boxes)
    }

    private fun parsePlanSplitBlank(root: Any?): PlanSplitBlankContract {
        val obj = requireObject(root)
        require(obj.requireBoolean("success")) { "PLAN_SPLIT_BLANK reported failure" }
        val groupsArr = obj.requireArray("groups", maxItems = SecurityLimits.MAX_OUTPUT_FILES)
        val groups = parseGroupsArray(groupsArr)
        return PlanSplitBlankContract(success = true, groups = groups)
    }

    private fun parsePlanSplitBookmarks(root: Any?): PlanSplitBookmarksContract {
        val obj = requireObject(root)
        require(obj.requireBoolean("success")) { "PLAN_SPLIT_BOOKMARKS reported failure" }
        val groupsArr = obj.requireArray("groups", maxItems = SecurityLimits.MAX_OUTPUT_FILES)
        val groups = parseGroupsArray(groupsArr)
        return PlanSplitBookmarksContract(success = true, groups = groups)
    }

    private fun parseGroupsArray(groupsArr: JSONArray): List<List<String>> {
        val groups = mutableListOf<List<String>>()
        for (i in 0 until groupsArr.length()) {
            val item = groupsArr.get(i)
            if (item is JSONArray) {
                require(item.length() <= 100) { "Group range item count exceeded" }
                val inner = mutableListOf<String>()
                for (j in 0 until item.length()) {
                    val s = item.getString(j)
                    require(s.length <= 64) { "Range string exceeds 64 chars" }
                    inner.add(s)
                }
                groups.add(inner)
            } else if (item is String) {
                require(item.length <= 64) { "Range string exceeds 64 chars" }
                groups.add(listOf(item))
            } else {
                throw IllegalArgumentException("Invalid group element type: ${item?.javaClass?.name}")
            }
        }
        return groups
    }

    private fun parseAcroForm(root: Any?): OperationContract {
        val obj = requireObject(root)
        if (obj.has("hasAcroForm")) {
            return AcroFormHasFormContract(hasAcroForm = obj.requireBoolean("hasAcroForm"))
        }
        if (obj.has("fields")) {
            val arr = obj.requireArray("fields", maxItems = 1000)
            val fields = mutableListOf<FormFieldInfo>()
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val typeName = f.requireString("type", maxLength = 32)
                val type = try { FormFieldType.valueOf(typeName) } catch (_: Exception) { FormFieldType.OTHER }
                val opts = mutableListOf<String>()
                if (f.has("possibleOptions") && !f.isNull("possibleOptions")) {
                    val oArr = f.getJSONArray("possibleOptions")
                    require(oArr.length() <= 256) { "Too many options" }
                    for (j in 0 until oArr.length()) {
                        val s = oArr.getString(j)
                        require(s.length <= 256) { "Option string exceeds 256 chars" }
                        opts.add(s)
                    }
                }
                fields.add(FormFieldInfo(
                    name = f.requireString("name", maxLength = 256),
                    fullyQualifiedName = f.requireString("fullyQualifiedName", maxLength = 512),
                    type = type,
                    value = f.requireString("value", maxLength = 4096),
                    possibleOptions = opts,
                    isReadOnly = f.optBoolean("isReadOnly", false),
                    isRequired = f.optBoolean("isRequired", false)
                ))
            }
            return AcroFormFieldsContract(fields = fields)
        }
        // Fill or create returns success/size
        return parseStandardOutput(root, 0)
    }

    private fun parseRepairDiagnose(root: Any?): RepairDiagnoseContract {
        val obj = requireObject(root)
        return RepairDiagnoseContract(
            hasValidHeader = obj.requireBoolean("hasValidHeader"),
            hasValidEof = obj.requireBoolean("hasValidEof"),
            recoveredPages = obj.requireInt("recoveredPages", min = 0, max = SecurityLimits.MAX_PAGE_COUNT),
            isEncrypted = obj.requireBoolean("isEncrypted"),
            issueSummary = obj.requireString("issueSummary", maxLength = 1024)
        )
    }

    private fun parseRepairApply(root: Any?): RepairApplyContract {
        val obj = requireObject(root)
        require(obj.requireBoolean("success")) { "REPAIR_APPLY reported failure: success=false" }
        val pages = obj.requireInt("recoveredPages", min = 0, max = SecurityLimits.MAX_PAGE_COUNT)
        return RepairApplyContract(success = true, recoveredPages = pages)
    }

    private fun parseDeskew(root: Any?): DeskewContract {
        val obj = requireObject(root)
        val count = obj.requireInt("straightenedCount", min = 0, max = SecurityLimits.MAX_PAGE_COUNT)
        return DeskewContract(success = true, straightenedCount = count)
    }

    private fun parseOfficeExport(root: Any?): OfficeExportContract {
        val obj = requireObject(root)
        require(obj.requireBoolean("success")) { "Office export reported failure: success=false" }
        return OfficeExportContract(
            success = true,
            pageCount = obj.requireInt("pageCount", min = 0, max = SecurityLimits.MAX_PAGE_COUNT),
            outputSizeBytes = obj.requireLong("outputSizeBytes", min = 0),
            itemsExtracted = obj.requireInt("itemsExtracted", min = 0)
        )
    }

    private fun parseImageAnalyze(root: Any?): ImageAnalysisContract {
        val obj = requireObject(root)
        val width = obj.requireInt("width", min = 1, max = 8192)
        val height = obj.requireInt("height", min = 1, max = 8192)
        val size = obj.requireLong("originalSizeBytes", min = 0)
        val mime = obj.requireString("mimeType", maxLength = 128)
        val formatName = obj.requireString("formatName", maxLength = 64)
        val isSupported = obj.requireBoolean("isSupported")
        val statusName = obj.requireString("validationStatus", maxLength = 64)
        val status = ImageValidationStatus.valueOf(statusName)
        val valMsg = if (obj.has("validationMessage") && !obj.isNull("validationMessage")) obj.requireString("validationMessage", maxLength = 256) else null
        val altSug = if (obj.has("alternativeSuggestion") && !obj.isNull("alternativeSuggestion")) obj.requireString("alternativeSuggestion", maxLength = 256) else null
        val estBytes = obj.requireLong("estimatedCompressedBytes", min = 0)
        val estSavings = obj.requireInt("estimatedSavingsPercent", min = 0, max = 100)
        val qlObj = obj.requireObject("qualityLoss")
        val qlLevel = qlObj.requireString("level", maxLength = 64)
        val qlStars = qlObj.requireInt("stars", min = 1, max = 5)
        val qualityLoss = PerceivedQualityLoss.values().find { it.level == qlLevel } ?: PerceivedQualityLoss.MODERATE

        val analysis = ImageAnalysis(
            width = width,
            height = height,
            originalSizeBytes = size,
            mimeType = mime,
            formatName = formatName,
            isSupported = isSupported,
            validationStatus = status,
            validationMessage = valMsg,
            alternativeSuggestion = altSug,
            estimatedCompressedBytes = estBytes,
            estimatedSavingsPercent = estSavings,
            qualityLoss = qualityLoss
        )
        return ImageAnalysisContract(analysis = analysis)
    }

    private fun parseImageBounds(root: Any?): ImageBoundsContract {
        val obj = requireObject(root)
        val w = obj.requireInt("outWidth", min = 1, max = 8192)
        val h = obj.requireInt("outHeight", min = 1, max = 8192)
        return ImageBoundsContract(outWidth = w, outHeight = h)
    }

    private fun parseImageCompression(root: Any?): ImageCompressionResultContract {
        val obj = requireObject(root)
        val success = obj.requireBoolean("success")
        require(success) { "Image compression reported failure" }
        val orig = obj.requireLong("originalSize", min = 0)
        val comp = obj.requireLong("compressedSize", min = 0)
        val w = obj.requireInt("width", min = 0, max = 8192)
        val h = obj.requireInt("height", min = 0, max = 8192)
        val format = obj.requireString("format", maxLength = 32)
        val error = if (obj.has("error") && !obj.isNull("error")) obj.requireString("error", maxLength = 256) else null
        return ImageCompressionResultContract(
            result = ImageCompressionResult(
                success = true,
                originalSize = orig,
                compressedSize = comp,
                width = w,
                height = h,
                format = format,
                error = error
            )
        )
    }

    private fun parseImageList(root: Any?): ImageListContract {
        val arr = requireArray(root, maxItems = 500)
        val list = mutableListOf<EmbeddedImageInfo>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(EmbeddedImageInfo(
                id = obj.requireString("id", maxLength = 128),
                pageIndex = obj.requireInt("pageIndex", min = 0, max = SecurityLimits.MAX_PAGE_COUNT),
                resourceName = obj.requireString("resourceName", maxLength = 128),
                width = obj.requireInt("width", min = 1, max = 8192),
                height = obj.requireInt("height", min = 1, max = 8192),
                format = obj.requireString("format", maxLength = 32),
                thumbnailBitmap = null
            ))
        }
        return ImageListContract(images = list)
    }

    private fun parseTextPages(root: Any?): TextPagesContract {
        val arr = requireArray(root, maxItems = SecurityLimits.MAX_PAGE_COUNT)
        val pages = mutableListOf<String>()
        var totalChars = 0L
        for (i in 0 until arr.length()) {
            val s = arr.getString(i)
            require(s.length <= MAX_LONG_STRING_LENGTH) { "Page text exceeds maximum length" }
            totalChars += s.length
            require(totalChars <= SecurityLimits.MAX_OUTPUT_BYTES) { "Aggregate extracted text exceeds limit" }
            pages.add(s)
        }
        return TextPagesContract(pages = pages)
    }

    private fun parseFindOccurrences(root: Any?): FindOccurrencesContract {
        val obj = requireObject(root)
        val total = obj.requireInt("totalMatches", min = 0)
        val affected = obj.requireInt("pagesAffected", min = 0, max = SecurityLimits.MAX_PAGE_COUNT)
        val occArr = obj.requireArray("occurrences", maxItems = 5000)
        val occList = mutableListOf<TextMatchOccurrence>()
        for (i in 0 until occArr.length()) {
            val o = occArr.getJSONObject(i)
            val b = o.requireObject("bounds")
            val left = b.requireDouble("left").toFloat()
            val top = b.requireDouble("top").toFloat()
            val right = b.requireDouble("right").toFloat()
            val bottom = b.requireDouble("bottom").toFloat()
            occList.add(TextMatchOccurrence(
                pageIndex = o.requireInt("pageIndex", min = 0, max = SecurityLimits.MAX_PAGE_COUNT),
                matchedText = o.requireString("matchedText", maxLength = 256),
                snippet = o.requireString("snippet", maxLength = 512),
                bounds = android.graphics.RectF(left, top, right, bottom),
                fontSize = o.requireDouble("fontSize", min = 0.0).toFloat()
            ))
        }
        return FindOccurrencesContract(summary = FindReplaceSummary(
            totalMatches = total,
            pagesAffected = affected,
            occurrences = occList
        ))
    }

    private fun parseReplaceAll(root: Any?): ReplaceAllContract {
        val count = when (root) {
            is Number -> root.toInt()
            is JSONObject -> root.requireInt("count", min = 0)
            else -> throw IllegalArgumentException("Expected integer for REPLACE_ALL result")
        }
        require(count >= 0) { "Replacement count must be non-negative" }
        return ReplaceAllContract(replacementsCount = count)
    }

    private fun parseTextFormat(root: Any?): TextFormatContract {
        val text = when (root) {
            is String -> root
            is JSONObject -> root.requireString("text", maxLength = MAX_LONG_STRING_LENGTH)
            else -> throw IllegalArgumentException("Expected string for TEXT_FORMAT result")
        }
        require(text.length <= MAX_LONG_STRING_LENGTH) { "Formatted text exceeds length limit" }
        return TextFormatContract(convertedText = text)
    }

    private fun parseMarkdown(root: Any?): MarkdownContract {
        val text = when (root) {
            is String -> root
            is JSONObject -> root.requireString("text", maxLength = MAX_LONG_STRING_LENGTH)
            else -> throw IllegalArgumentException("Expected string for MARKDOWN result")
        }
        require(text.length <= MAX_LONG_STRING_LENGTH) { "Markdown text exceeds length limit" }
        return MarkdownContract(markdownText = text)
    }

    private fun parseCompress(root: Any?): CompressContract {
        val obj = requireObject(root)
        val size = obj.requireLong("size", min = 0)
        val images = obj.requireInt("imagesProcessed", min = 0)
        val signatures = obj.requireBoolean("hasSignatures")
        return CompressContract(
            size = size,
            imagesProcessed = images,
            hasSignatures = signatures
        )
    }

    private fun parsePdfAnalysis(root: Any?): PdfAnalysisContract {
        val obj = requireObject(root)
        val pageCount = obj.requireInt("pageCount", min = 1, max = SecurityLimits.MAX_PAGE_COUNT)
        val imageCount = obj.requireInt("imageCount", min = 0)
        val hasSignatures = obj.requireBoolean("hasSignatures")
        val scenario = obj.requireString("scenario", maxLength = 64)
        require(scenario in listOf("SIGNED_OFFICIAL", "TEXT_VECTOR", "SCANNED_IMAGE_HEAVY", "MIXED")) {
            "Invalid analysis scenario: $scenario"
        }
        val quality = obj.requireDouble("recommendedQuality", min = 0.0, max = 1.0).toFloat()
        val reason = obj.requireString("recommendationReason", maxLength = 256)
        return PdfAnalysisContract(
            pageCount = pageCount,
            imageCount = imageCount,
            hasSignatures = hasSignatures,
            scenario = scenario,
            recommendedQuality = quality,
            recommendationReason = reason
        )
    }

    private fun parseStandardOutput(root: Any?, targetCount: Int): StandardOutputContract {
        when (root) {
            is JSONObject -> {
                require(!root.has("error")) { "Worker reported error" }
                if (root.has("renderedCount")) {
                    val count = root.getInt("renderedCount")
                    require(count == targetCount) { "Output count mismatch: expected $targetCount, got $count" }
                }
                val size = root.optLong("size", root.optLong("outBytes", 0L))
                return StandardOutputContract(success = true, size = size)
            }
            is Boolean -> {
                require(root) { "Worker reported boolean false" }
                return StandardOutputContract(success = true)
            }
            is Number -> {
                return StandardOutputContract(success = true, size = root.toLong())
            }
            else -> return StandardOutputContract(success = true)
        }
    }

    // --- Result File Transports (F02) ---

    fun parseOutlineFile(file: File): ReflowDocumentContract {
        require(file.exists()) { "Outline file does not exist" }
        val length = file.length()
        require(length <= MAX_FILE_RESULT_BYTES) { "Outline file exceeds limit: $length bytes" }
        val text = file.readText(Charsets.UTF_8)
        return parseOutlineJson(text)
    }

    fun parseOutlineJson(text: String): ReflowDocumentContract {
        require(text.length <= MAX_FILE_RESULT_BYTES) { "Outline JSON string exceeds limit" }

        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    require(depth <= SecurityLimits.MAX_RESPONSE_DEPTH) { "Outline JSON nesting exceeds limit" }
                }
                '}', ']' -> {
                    depth--
                    require(depth >= 0) { "Unbalanced delimiters in outline JSON" }
                }
            }
        }
        require(depth == 0 && !quoted) { "Unclosed delimiter in outline JSON" }

        val tokener = JSONTokener(text)
        val root = tokener.nextValue()
        val trailing = tokener.nextClean()
        require(trailing == '\u0000') { "Trailing non-whitespace in outline JSON: '$trailing'" }

        val obj = requireObject(root)
        require(!obj.has("error")) { "Worker reported error in outline file" }

        val sectionsArr = obj.requireArray("sections", maxItems = 2000)
        val sections = mutableListOf<ReflowSectionContract>()
        var totalParagraphs = 0
        for (i in 0 until sectionsArr.length()) {
            val secObj = sectionsArr.getJSONObject(i)
            val pageNum = secObj.requireInt("pageNumber", min = 0, max = SecurityLimits.MAX_PAGE_COUNT)
            val title = if (secObj.has("title") && !secObj.isNull("title")) secObj.requireString("title", maxLength = 256) else null
            val pArr = secObj.requireArray("paragraphs", maxItems = 500)
            val pars = mutableListOf<String>()
            for (j in 0 until pArr.length()) {
                totalParagraphs++
                require(totalParagraphs <= 10000) { "Total reflow paragraphs exceed 10000" }
                val p = pArr.getString(j)
                require(p.length <= 8192) { "Paragraph text exceeds 8192 chars" }
                pars.add(p)
            }
            sections.add(ReflowSectionContract(pageNumber = pageNum, title = title, paragraphs = pars))
        }

        val bookmarksArr = obj.requireArray("bookmarks", maxItems = 1000)
        var totalBookmarks = 0
        fun parseBookmarks(arr: JSONArray, currentDepth: Int): List<OutlineBookmarkContract> {
            require(currentDepth <= 8) { "Bookmark tree depth exceeds 8" }
            val list = mutableListOf<OutlineBookmarkContract>()
            for (i in 0 until arr.length()) {
                totalBookmarks++
                require(totalBookmarks <= 1000) { "Total bookmarks exceed 1000" }
                val b = arr.getJSONObject(i)
                val title = b.requireString("title", maxLength = 256)
                val page = b.requireInt("pageNumber", min = 0, max = SecurityLimits.MAX_PAGE_COUNT)
                val children = if (b.has("children") && !b.isNull("children")) {
                    parseBookmarks(b.getJSONArray("children"), currentDepth + 1)
                } else emptyList()
                list.add(OutlineBookmarkContract(title = title, pageNumber = page, children = children))
            }
            return list
        }
        val bookmarks = parseBookmarks(bookmarksArr, 0)

        val isScannedOnly = obj.requireBoolean("isScannedOnly")
        val totalPages = obj.requireInt("totalPages", min = 0, max = SecurityLimits.MAX_PAGE_COUNT)

        return ReflowDocumentContract(
            sections = sections,
            bookmarks = bookmarks,
            isScannedOnly = isScannedOnly,
            totalPages = totalPages
        )
    }

    fun parseCsvTableFile(file: File): String {
        require(file.exists()) { "CSV table file does not exist" }
        val length = file.length()
        require(length <= MAX_FILE_RESULT_BYTES) { "CSV file exceeds limit: $length bytes" }
        val text = file.readText(Charsets.UTF_8)
        return validateCsvTable(text)
    }

    fun validateCsvTable(csvText: String): String {
        require(csvText.length <= MAX_FILE_RESULT_BYTES) { "CSV text exceeds limit" }
        var rowCount = 0
        var totalCells = 0
        csvText.lineSequence().forEach { line ->
            if (line.isNotBlank()) {
                rowCount++
                require(rowCount <= 5000) { "CSV row limit exceeded (max 5000)" }
                var inQuotes = false
                var colCount = 0
                var cellCharCount = 0
                for (ch in line) {
                    if (ch == '\"') {
                        inQuotes = !inQuotes
                    } else if (ch == ',' && !inQuotes) {
                        colCount++
                        totalCells++
                        require(colCount <= 128) { "CSV column limit exceeded (max 128)" }
                        require(cellCharCount <= 1024) { "CSV cell character limit exceeded (max 1024)" }
                        cellCharCount = 0
                    } else {
                        cellCharCount++
                    }
                }
                colCount++
                totalCells++
                require(colCount <= 128) { "CSV column limit exceeded (max 128)" }
                require(cellCharCount <= 1024) { "CSV cell character limit exceeded (max 1024)" }
                require(totalCells <= 20000) { "CSV total cells limit exceeded (max 20000)" }
            }
        }
        return csvText
    }

    // --- JSON Helper Extensions ---

    private fun requireObject(root: Any?): JSONObject {
        require(root is JSONObject) { "Expected JSONObject root, got ${root?.javaClass?.name ?: "null"}" }
        return root
    }

    private fun requireArray(root: Any?, maxItems: Int): JSONArray {
        require(root is JSONArray) { "Expected JSONArray root, got ${root?.javaClass?.name ?: "null"}" }
        require(root.length() <= maxItems) { "Array length ${root.length()} exceeds limit of $maxItems" }
        return root
    }

    private fun JSONObject.requireString(key: String, maxLength: Int = MAX_STRING_LENGTH): String {
        require(has(key) && !isNull(key)) { "Missing required string property '$key'" }
        val v = get(key)
        require(v is String) { "Property '$key' must be string, got ${v.javaClass.name}" }
        require(v.length <= maxLength) { "Property '$key' exceeds max length of $maxLength" }
        return v
    }

    private fun JSONObject.requireInt(key: String, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int {
        require(has(key) && !isNull(key)) { "Missing required int property '$key'" }
        val v = get(key)
        require(v is Number) { "Property '$key' must be number, got ${v.javaClass.name}" }
        val intVal = v.toInt()
        require(intVal in min..max) { "Property '$key' with value $intVal out of range $min..$max" }
        return intVal
    }

    private fun JSONObject.requireLong(key: String, min: Long = Long.MIN_VALUE, max: Long = Long.MAX_VALUE): Long {
        require(has(key) && !isNull(key)) { "Missing required long property '$key'" }
        val v = get(key)
        require(v is Number) { "Property '$key' must be number, got ${v.javaClass.name}" }
        val longVal = v.toLong()
        require(longVal in min..max) { "Property '$key' with value $longVal out of range $min..$max" }
        return longVal
    }

    private fun JSONObject.requireDouble(key: String, min: Double = Double.NEGATIVE_INFINITY, max: Double = Double.POSITIVE_INFINITY): Double {
        require(has(key) && !isNull(key)) { "Missing required double property '$key'" }
        val v = get(key)
        require(v is Number) { "Property '$key' must be number, got ${v.javaClass.name}" }
        val d = v.toDouble()
        require(d.isFinite() && d in min..max) { "Property '$key' with value $d out of range $min..$max" }
        return d
    }

    private fun JSONObject.requireBoolean(key: String): Boolean {
        require(has(key) && !isNull(key)) { "Missing required boolean property '$key'" }
        val v = get(key)
        require(v is Boolean) { "Property '$key' must be boolean, got ${v.javaClass.name}" }
        return v
    }

    private fun JSONObject.requireArray(key: String, maxItems: Int): JSONArray {
        require(has(key) && !isNull(key)) { "Missing required array property '$key'" }
        val v = get(key)
        require(v is JSONArray) { "Property '$key' must be JSONArray, got ${v.javaClass.name}" }
        require(v.length() <= maxItems) { "Array '$key' length ${v.length()} exceeds limit of $maxItems" }
        return v
    }

    private fun JSONObject.requireObject(key: String): JSONObject {
        require(has(key) && !isNull(key)) { "Missing required object property '$key'" }
        val v = get(key)
        require(v is JSONObject) { "Property '$key' must be JSONObject, got ${v.javaClass.name}" }
        return v
    }
}
