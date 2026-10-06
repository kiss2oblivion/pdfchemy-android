package com.pdfchemy.app.logic

/**
 * Universal marker interface for all typed, validated worker response contracts.
 * Untrusted JSON from worker processes must be converted into these typed contracts
 * in the IPC layer before being returned to Host callers.
 */
sealed interface OperationContract

/**
 * Standard contract for output-producing operations or operations reporting simple status.
 */
data class StandardOutputContract(
    val success: Boolean = true,
    val size: Long = 0L,
    val count: Int = 1
) : OperationContract

/**
 * Metadata read contract for METADATA_READ.
 */
data class MetadataReadContract(
    val title: String,
    val author: String,
    val subject: String,
    val keywords: String,
    val creator: String,
    val producer: String,
    val creationDate: String,
    val modificationDate: String,
    val pageCount: Int,
    val hasXmpMetadata: Boolean,
    val isEncrypted: Boolean
) : OperationContract

/**
 * Page count contract for GET_PAGE_COUNT.
 */
data class PageCountContract(
    val pageCount: Int
) : OperationContract

/**
 * Sanitizer audit contract for SANITIZE_AUDIT.
 * Fail-closed: threatsFound > 0 requires isClean == false.
 */
data class SanitizeAuditContract(
    val threatsFound: Int,
    val isClean: Boolean,
    val jsCount: Int,
    val launchActionsCount: Int,
    val otherActionsCount: Int,
    val untrustedUriCount: Int,
    val attachmentCount: Int,
    val uriCount: Int,
    val hasMetadata: Boolean,
    val isEncrypted: Boolean,
    val parseFailed: Boolean
) : OperationContract

/**
 * Sanitizer clean contract for SANITIZE_CLEAN.
 */
data class SanitizerCleanContract(
    val isSuccess: Boolean,
    val threatsRemoved: Int,
    val jsRemoved: Int,
    val actionsRemoved: Int,
    val metadataRemoved: Boolean,
    val attachmentsRemoved: Int
) : OperationContract

/**
 * Flatten inspection contract for FLATTEN_INSPECT.
 */
data class FlattenInspectContract(
    val fieldCount: Int,
    val annotationCount: Int,
    val hasSignatures: Boolean
) : OperationContract

/**
 * Encryption inspection contract for CHECK_ENCRYPTION.
 */
data class CheckEncryptionContract(
    val isEncrypted: Boolean
) : OperationContract

/**
 * Linearization status contract for LINEARIZE_CHECK.
 */
data class LinearizeCheckContract(
    val isLinearized: Boolean,
    val pageCount: Int
) : OperationContract

/**
 * Individual compliance check item for ARCHIVE_INSPECT.
 */
data class ArchiveCheckItemContract(
    val rule: String,
    val isPassed: Boolean,
    val details: String,
    val severity: String
)

/**
 * Archive compliance inspection report for ARCHIVE_INSPECT.
 */
data class ArchiveInspectContract(
    val pdfaVersionDetected: String,
    val complianceScore: Int,
    val checks: List<ArchiveCheckItemContract>,
    val isCompliant: Boolean
) : OperationContract

/**
 * Font descriptor for FONT_INSPECT.
 */
data class FontInfoContract(
    val postscriptName: String,
    val familyName: String,
    val formatType: String,
    val isEmbedded: Boolean,
    val isSubset: Boolean,
    val encoding: String,
    val pageCountUsed: Int
)

/**
 * Font inspection report for FONT_INSPECT.
 */
data class FontInspectContract(
    val fonts: List<FontInfoContract>
) : OperationContract

/**
 * Bookmark item for BOOKMARK_READ.
 */
data class BookmarkItemContract(
    val title: String,
    val pageIndex: Int
)

/**
 * Bookmark list report for BOOKMARK_READ.
 */
data class BookmarkReadContract(
    val bookmarks: List<BookmarkItemContract>
) : OperationContract

/**
 * Attachment item for ATTACHMENT_LIST.
 */
data class AttachmentItemContract(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String
)

/**
 * Attachment list report for ATTACHMENT_LIST.
 */
data class AttachmentListContract(
    val attachments: List<AttachmentItemContract>
) : OperationContract

/**
 * Redaction target box contract for SEARCH_REDACT.
 */
data class SearchRedactContract(
    val boxes: List<RedactionBox>
) : OperationContract

/**
 * Blank page split plan for PLAN_SPLIT_BLANK.
 */
data class PlanSplitBlankContract(
    val success: Boolean,
    val groups: List<List<String>>
) : OperationContract

/**
 * Bookmark split plan for PLAN_SPLIT_BOOKMARKS.
 */
data class PlanSplitBookmarksContract(
    val success: Boolean,
    val groups: List<List<String>>
) : OperationContract

/**
 * AcroForm presence check contract.
 */
data class AcroFormHasFormContract(
    val hasAcroForm: Boolean
) : OperationContract

/**
 * AcroForm fields extraction contract.
 */
data class AcroFormFieldsContract(
    val fields: List<FormFieldInfo>
) : OperationContract

/**
 * PDF repair diagnostic contract for REPAIR_DIAGNOSE.
 */
data class RepairDiagnoseContract(
    val hasValidHeader: Boolean,
    val hasValidEof: Boolean,
    val recoveredPages: Int,
    val isEncrypted: Boolean,
    val issueSummary: String
) : OperationContract

/**
 * PDF repair application contract for REPAIR_APPLY.
 */
data class RepairApplyContract(
    val success: Boolean = true,
    val recoveredPages: Int
) : OperationContract

/**
 * Deskew contract for DESKEW.
 */
data class DeskewContract(
    val success: Boolean = true,
    val straightenedCount: Int
) : OperationContract

/**
 * Office export contract for OFFICE_WORD, OFFICE_EXCEL, and OFFICE_PPT.
 */
data class OfficeExportContract(
    val success: Boolean = true,
    val pageCount: Int,
    val outputSizeBytes: Long,
    val itemsExtracted: Int
) : OperationContract

/**
 * Image analysis contract for IMAGE_ANALYZE.
 */
data class ImageAnalysisContract(
    val analysis: ImageAnalysis
) : OperationContract

/**
 * Image compression result contract for IMAGE_COMPRESS and IMAGE_TARGET.
 */
data class ImageCompressionResultContract(
    val result: ImageCompressionResult
) : OperationContract

/**
 * Image bounds contract for IMAGE_BOUNDS.
 */
data class ImageBoundsContract(
    val outWidth: Int,
    val outHeight: Int
) : OperationContract

/**
 * Embedded images list contract for IMAGE_LIST.
 */
data class ImageListContract(
    val images: List<EmbeddedImageInfo>
) : OperationContract

/**
 * Extracted text pages contract for TEXT_PAGES.
 */
data class TextPagesContract(
    val pages: List<String>
) : OperationContract

/**
 * Find occurrences contract for FIND_OCCURRENCES.
 */
data class FindOccurrencesContract(
    val summary: FindReplaceSummary
) : OperationContract

/**
 * Replace all contract for REPLACE_ALL.
 */
data class ReplaceAllContract(
    val replacementsCount: Int
) : OperationContract

/**
 * Text format conversion contract for TEXT_FORMAT.
 */
data class TextFormatContract(
    val convertedText: String
) : OperationContract

/**
 * Markdown conversion contract for PDF_TO_MARKDOWN.
 */
data class MarkdownContract(
    val markdownText: String
) : OperationContract

/**
 * Compression result contract for COMPRESS.
 */
data class CompressContract(
    val size: Long,
    val imagesProcessed: Int,
    val hasSignatures: Boolean
) : OperationContract

/**
 * PDF analysis contract for ANALYZE_PDF.
 */
data class PdfAnalysisContract(
    val pageCount: Int,
    val imageCount: Int,
    val hasSignatures: Boolean,
    val scenario: String,
    val recommendedQuality: Float,
    val recommendationReason: String
) : OperationContract

/**
 * Debug identity contract for DEBUG_IDENTITY.
 */
data class DebugIdentityContract(
    val pid: Int,
    val uid: Int
) : OperationContract

/**
 * Debug scratch capability contract for DEBUG_SCRATCH.
 */
data class DebugScratchContract(
    val scratchCapability: Boolean
) : OperationContract

/**
 * Reflow section contract for OUTLINE_READ.
 */
data class ReflowSectionContract(
    val pageNumber: Int,
    val title: String?,
    val paragraphs: List<String>
)

/**
 * Outline hierarchical bookmark contract for OUTLINE_READ.
 */
data class OutlineBookmarkContract(
    val title: String,
    val pageNumber: Int,
    val children: List<OutlineBookmarkContract>
)

/**
 * Reflow document data contract for OUTLINE_READ.
 */
data class ReflowDocumentContract(
    val sections: List<ReflowSectionContract>,
    val bookmarks: List<OutlineBookmarkContract>,
    val isScannedOnly: Boolean,
    val totalPages: Int
) : OperationContract
