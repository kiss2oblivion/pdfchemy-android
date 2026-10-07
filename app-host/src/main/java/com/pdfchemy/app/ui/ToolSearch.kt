package com.pdfchemy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.Screen

data class ToolEntry(
    val nameRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector,
    val screen: Screen,
    val aliases: List<String> = emptyList()
)

object ToolRegistry {
    // One entry per destination/format. Keep this catalog in sync with Screen;
    // registry tests enforce coverage without usage-based ordering.
    val allTools = listOf(
        ToolEntry(R.string.images_to_pdf, R.string.subtitle_images_to_pdf, Icons.Rounded.PictureAsPdf, Screen.ImagesToPdf, listOf("jpg to pdf", "png to pdf", "photo to pdf", "picture to pdf")),
        ToolEntry(R.string.text_to_pdf, R.string.subtitle_text_to_pdf, Icons.Rounded.Description, Screen.TextToPdf, listOf("txt to pdf", "notes to pdf")),
        ToolEntry(R.string.text_format_converter, R.string.subtitle_format_converter, Icons.Rounded.SyncAlt, Screen.TextConverter, listOf("txt", "rtf", "html", "convert text format")),
        ToolEntry(R.string.menu_pdf_to_images, R.string.menu_pdf_to_images_desc, Icons.Rounded.Image, Screen.PdfToImages, listOf("pdf to jpg", "pdf to png", "export pages as images")),
        ToolEntry(R.string.menu_ocr_pdf, R.string.menu_ocr_pdf_desc, Icons.Rounded.DocumentScanner, Screen.OcrPdf, listOf("ocr", "text recognition", "searchable pdf", "scan text")),
        ToolEntry(R.string.menu_pdf_to_word, R.string.menu_pdf_to_word_desc, Icons.Rounded.Article, Screen.OfficeExport(com.pdfchemy.app.logic.OfficeFormat.WORD), listOf("pdf to word", "docx")),
        ToolEntry(R.string.menu_pdf_to_excel, R.string.menu_pdf_to_excel_desc, Icons.Rounded.TableChart, Screen.OfficeExport(com.pdfchemy.app.logic.OfficeFormat.EXCEL), listOf("pdf to excel", "xlsx", "spreadsheet")),
        ToolEntry(R.string.menu_pdf_to_pptx, R.string.menu_pdf_to_pptx_desc, Icons.Rounded.Slideshow, Screen.OfficeExport(com.pdfchemy.app.logic.OfficeFormat.POWERPOINT), listOf("pdf to powerpoint", "pptx", "slides")),
        ToolEntry(R.string.menu_extract_images, R.string.menu_extract_images_desc, Icons.Rounded.Image, Screen.ExtractImages, listOf("extract embedded images", "save pictures")),
        ToolEntry(R.string.menu_markdown_studio, R.string.menu_markdown_studio_desc, Icons.Rounded.EditNote, Screen.MarkdownStudio, listOf("markdown to pdf", "md to pdf")),
        ToolEntry(R.string.menu_ebook_suite, R.string.menu_ebook_suite_desc, Icons.Rounded.AutoStories, Screen.EbookConverter, listOf("epub to pdf", "cbz to pdf", "comic", "ebook")),
        ToolEntry(R.string.menu_table_extractor, R.string.menu_table_extractor_desc, Icons.Rounded.TableChart, Screen.TableExtractor, listOf("csv", "extract tables")),
        ToolEntry(R.string.compress_title, R.string.cat_compress_desc, Icons.Rounded.Compress, Screen.CompressPdf, listOf("shrink", "reduce size", "make smaller", "optimize pdf")),
        ToolEntry(R.string.compress_batch, R.string.subtitle_batch_compress, Icons.Rounded.LibraryBooks, Screen.BatchCompressPdf, listOf("batch compression", "compress multiple pdfs")),
        ToolEntry(R.string.menu_compress_image, R.string.menu_compress_image_desc, Icons.Rounded.Image, Screen.ImageCompressor, listOf("shrink photo", "reduce image size", "compress jpg", "compress png")),
        ToolEntry(R.string.menu_grayscale_optimizer, R.string.menu_grayscale_optimizer_desc, Icons.Rounded.Draw, Screen.GrayscaleOptimizer, listOf("black and white", "monochrome", "remove color")),
        ToolEntry(R.string.menu_fast_web_view, R.string.menu_fast_web_view_desc, Icons.Rounded.Dashboard, Screen.LinearizePdf, listOf("linearize", "fast web view", "web streaming")),
        ToolEntry(R.string.menu_edit_pdf, R.string.menu_edit_pdf_desc, Icons.Filled.Edit, Screen.PdfEditor(), listOf("annotate", "highlight", "draw", "add text", "edit pdf")),
        ToolEntry(R.string.menu_quick_fill_sign, R.string.menu_quick_fill_sign_desc, Icons.Rounded.Draw, Screen.QuickFillSign, listOf("quick fill", "check mark", "fill and sign")),
        ToolEntry(R.string.menu_form_builder, R.string.menu_form_builder_desc, Icons.Rounded.EditNote, Screen.FormBuilder, listOf("create form", "add form fields")),
        ToolEntry(R.string.menu_fill_form, R.string.menu_fill_form_desc, Icons.Rounded.DynamicForm, Screen.FillForm, listOf("fill interactive form", "acroform")),
        ToolEntry(R.string.menu_merge, R.string.menu_merge_desc, Icons.Rounded.Merge, Screen.MergePdf, listOf("combine", "join", "append", "add together")),
        ToolEntry(R.string.menu_split, R.string.menu_split_desc, Icons.Rounded.CallSplit, Screen.SplitPdf, listOf("separate", "divide", "extract pages", "break apart")),
        ToolEntry(R.string.menu_delete_pages, R.string.menu_delete_pages_desc, Icons.Rounded.Delete, Screen.DeletePages, listOf("remove pages", "delete pages")),
        ToolEntry(R.string.menu_rotate_pages, R.string.menu_rotate_pages_desc, Icons.Rounded.RotateRight, Screen.RotatePdf, listOf("turn pages", "orientation", "rotate pages")),
        ToolEntry(R.string.menu_reflow_reader, R.string.menu_reflow_reader_desc, Icons.Rounded.MenuBook, Screen.ReflowReader(), listOf("reflow", "text to speech", "tts", "audio export", "wav", "listen", "read aloud")),
        ToolEntry(R.string.menu_page_organizer, R.string.menu_page_organizer_desc, Icons.Rounded.ViewModule, Screen.PageOrganizer, listOf("reorder", "move pages", "page thumbnails", "insert blank page")),
        ToolEntry(R.string.menu_watermark_pdf, R.string.menu_watermark_pdf_desc, Icons.Rounded.BrandingWatermark, Screen.Watermark, listOf("watermark", "add logo")),
        ToolEntry(R.string.menu_page_number_pdf, R.string.menu_page_number_pdf_desc, Icons.Rounded.Numbers, Screen.PageNumber, listOf("page numbers", "pagination")),
        ToolEntry(R.string.menu_page_layout, R.string.menu_page_layout_desc, Icons.Rounded.Dashboard, Screen.PageLayout, listOf("resize paper", "page size", "a4", "letter")),
        ToolEntry(R.string.menu_replace_image, R.string.menu_replace_image_desc, Icons.Rounded.Image, Screen.ImageReplacer, listOf("replace image", "swap picture")),
        ToolEntry(R.string.menu_find_replace, R.string.menu_find_replace_desc, Icons.Rounded.Search, Screen.FindAndReplaceText, listOf("find and replace", "replace words")),
        ToolEntry(R.string.menu_crop_pdf, R.string.menu_crop_pdf_desc, Icons.Rounded.Crop, Screen.CropPdf, listOf("trim margins", "crop pages", "remove borders")),
        ToolEntry(R.string.menu_booklet_creator, R.string.menu_booklet_creator_desc, Icons.Rounded.MenuBook, Screen.Booklet, listOf("booklet", "fold", "imposition")),
        ToolEntry(R.string.menu_header_footer_stamping, R.string.menu_header_footer_stamping_desc, Icons.Rounded.Numbers, Screen.HeaderFooter, listOf("header", "footer")),
        ToolEntry(R.string.menu_bookmark_editor, R.string.menu_bookmark_editor_desc, Icons.Rounded.MenuBook, Screen.BookmarkEditor, listOf("edit bookmarks", "edit table of contents")),
        ToolEntry(R.string.menu_attachment_manager, R.string.menu_attachment_manager_desc, Icons.Rounded.UploadFile, Screen.AttachmentManager, listOf("attachments", "embedded files", "portfolio")),
        ToolEntry(R.string.menu_nup_handouts, R.string.menu_nup_handouts_desc, Icons.Rounded.ViewModule, Screen.NUp, listOf("n-up", "multiple pages per sheet", "handouts")),
        ToolEntry(R.string.menu_deskew, R.string.menu_deskew_desc, Icons.Rounded.RotateRight, Screen.Deskew, listOf("straighten", "fix scan tilt")),
        ToolEntry(R.string.menu_protect_pdf, R.string.menu_protect_pdf_desc, Icons.Default.Lock, Screen.ProtectPdf, listOf("encrypt", "password protect", "lock pdf")),
        ToolEntry(R.string.menu_unlock_pdf, R.string.menu_unlock_pdf_desc, Icons.Default.LockOpen, Screen.UnlockPdf(), listOf("decrypt", "remove password", "unlock pdf")),
        ToolEntry(R.string.menu_inspect_metadata, R.string.menu_inspect_metadata_desc, Icons.Rounded.Edit, Screen.InspectMetadata, listOf("properties", "author", "title", "document info")),
        ToolEntry(R.string.menu_remove_metadata, R.string.menu_remove_metadata_desc, Icons.Rounded.DeleteSweep, Screen.StripMetadata, listOf("remove properties", "clean author", "remove metadata")),
        ToolEntry(R.string.menu_text_cleaner, R.string.menu_text_cleaner_desc, Icons.Rounded.Edit, Screen.TextCleaner, listOf("clean text", "invisible characters")),
        ToolEntry(R.string.menu_extract, R.string.menu_extract_desc, Icons.Rounded.DocumentScanner, Screen.ExtractText, listOf("pdf to text", "extract text", "copy text")),
        ToolEntry(R.string.menu_compare_pdfs, R.string.menu_compare_pdfs_desc, Icons.Rounded.Difference, Screen.ComparePdf, listOf("compare", "differences", "diff")),
        ToolEntry(R.string.menu_metadata_sanitizer, R.string.menu_metadata_sanitizer_desc, Icons.Rounded.CleaningServices, Screen.MetadataSanitizer, listOf("privacy", "scrub metadata", "gps")),
        ToolEntry(R.string.menu_flatten_pdf, R.string.menu_flatten_pdf_desc, Icons.Filled.Lock, Screen.FlattenPdf, listOf("flatten", "bake annotations", "flatten forms")),
        ToolEntry(R.string.menu_repair_pdf, R.string.menu_repair_pdf_desc, Icons.Filled.Build, Screen.RepairPdf, listOf("repair", "damaged pdf", "broken pdf")),
        ToolEntry(R.string.menu_redaction_studio, R.string.menu_redaction_studio_desc, Icons.Filled.Lock, Screen.Redaction, listOf("redact", "hide sensitive text", "black out", "pii")),
        ToolEntry(R.string.menu_pdfa_validator, R.string.menu_pdfa_validator_desc, Icons.Rounded.FactCheck, Screen.PdfAValidator, listOf("pdf/a", "archival", "validate compliance")),
        ToolEntry(R.string.menu_font_inspector, R.string.menu_font_inspector_desc, Icons.Rounded.Edit, Screen.FontInspector, listOf("fonts", "typography", "embedded fonts")),
        ToolEntry(R.string.menu_doc_sanitizer, R.string.menu_doc_sanitizer_desc, Icons.Rounded.VerifiedUser, Screen.DocumentSanitizer, listOf("remove javascript", "remove threats", "sanitize document")),
        ToolEntry(R.string.menu_scan_document, R.string.menu_scan_document_desc, Icons.Rounded.DocumentScanner, Screen.ScanPdf, listOf("camera to pdf", "digitize", "photo scan", "scan paper")),
        ToolEntry(R.string.select_pdf_for_reading, R.string.reflow_reader_subtitle, Icons.Rounded.MenuBook, Screen.PdfReader(), listOf("read pdf", "open pdf", "view pdf", "continuous reading")),
        ToolEntry(R.string.menu_sign_pdf, R.string.menu_sign_pdf_desc, Icons.Rounded.Draw, Screen.SignPdf, listOf("signature", "digital signature", "sign pdf")),
    )

    fun search(query: String, resolveString: (Int) -> String): List<ToolEntry> {
        fun termsOf(text: String) = text.trim().lowercase(java.util.Locale.ROOT)
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
        val terms = termsOf(query)
        if (terms.isEmpty()) return emptyList()
        // Full names/aliases win over incidental words in descriptions, especially
        // directional conversions such as PDF to text versus text to PDF.
        val exact = allTools.filter { tool ->
            (listOf(resolveString(tool.nameRes)) + tool.aliases).any {
                termsOf(it) == terms
            }
        }
        if (exact.isNotEmpty()) return exact
        return allTools.filter { tool ->
            val searchable = (listOf(resolveString(tool.nameRes), resolveString(tool.descriptionRes)) + tool.aliases)
                .joinToString(" ").lowercase(java.util.Locale.ROOT)
            terms.all { searchable.contains(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSearchBar(
    modifier: Modifier = Modifier,
    onToolSelected: (Screen) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    // Read localized resources on composition so locale changes refresh matching.
    val filteredTools = ToolRegistry.search(query) { context.getString(it) }

    SearchBar(
        query = query,
        onQueryChange = { query = it },
        onSearch = { active = false },
        active = active,
        onActiveChange = { active = it },
        placeholder = { Text(stringResource(R.string.tool_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (active) {
                IconButton(onClick = {
                    if (query.isNotEmpty()) {
                        query = ""
                    } else {
                        active = false
                    }
                }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(if (query.isNotEmpty()) R.string.clear else R.string.desc_back))
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (query.isNotBlank() && filteredTools.isEmpty()) {
                item { Text(stringResource(R.string.tool_search_no_results)) }
            }

            items(filteredTools, key = { it.screen.toString() }) { tool ->
                ListItem(
                    headlineContent = { Text(stringResource(id = tool.nameRes), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(id = tool.descriptionRes)) },
                    leadingContent = { Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            active = false
                            query = ""
                            onToolSelected(tool.screen)
                        }
                )
            }
        }
    }
}
