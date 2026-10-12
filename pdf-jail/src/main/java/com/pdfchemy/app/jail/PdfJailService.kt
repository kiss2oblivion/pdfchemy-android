package com.pdfchemy.app.jail

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.pdfchemy.app.security.*
import com.pdfchemy.app.jail.engines.*
import com.pdfchemy.app.logic.PageModification
import com.pdfchemy.app.jail.boundedFileOutput
import com.pdfchemy.app.jail.capabilityInput
import kotlinx.coroutines.*

class PdfJailService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gate = WorkerGate { Process.killProcess(Process.myPid()) }
    @Volatile private var debugOutput: ParcelFileDescriptor? = null

    private fun submit(
        operationId: Long,
        sources: List<ParcelFileDescriptor>,
        targets: List<ParcelFileDescriptor>,
        hashes: List<String>,
        sizes: List<Long>,
        params: String,
        callback: IPdfJailStringCallback,
        scratchBinder: IBinder?,
        validateMetadata: () -> Unit = {},
        task: suspend () -> String
    ) {
        val all = sources + targets
        if (!gate.startExecution(operationId)) {
            all.forEach { runCatching { it.close() } }
            val errorCode = if (gate.isBusy()) 429 else 400
            val errorMsg = if (errorCode == 429) "BUSY" else "Invalid or stale operation token"
            runCatching { callback.onFailure(errorCode, errorMsg) }
            return
        }
        val death = IBinder.DeathRecipient {
            gate.poison(operationId)
            Process.killProcess(Process.myPid())
        }
        serviceScope.launch {
            var result: String? = null
            var failure: Throwable? = null
            val owner = scratchBinder ?: callback.asBinder()
            try {
                owner.linkToDeath(death, 0)
                validateMetadata()
                require(sources.size <= SecurityLimits.MAX_BATCH_FDS && targets.size <= SecurityLimits.MAX_BATCH_FDS)
                require(hashes.size == sources.size && sizes.size == sources.size)
                require(sizes.sum() <= SecurityLimits.MAX_BATCH_INPUT_BYTES)
                RequestValidator.validate(params)
                JailScratch.begin(scratchBinder, targets)
                sources.indices.forEach { StagedIdentity.verifyAndRewind(sources[it], hashes[it], sizes[it]) }
                result = SecurityLimits.enforceResultSize(task())
                require(targets.sumOf { it.statSize.coerceAtLeast(0) } <= SecurityLimits.MAX_OUTPUT_BYTES) { "Aggregate output storage quota exceeded" }
                if (result.trimStart().startsWith("{")) {
                    val response = org.json.JSONObject(result)
                    check(!response.has("error") && (!response.has("success") || response.optBoolean("success")) && (!response.has("isSuccess") || response.optBoolean("isSuccess"))) { response.optString("error", "Operation failed") }
                }
                check(gate.markAwaitingHostAccept(operationId)) { "Failed to transition to AWAITING_HOST_ACCEPT" }
            } catch (t: Throwable) {
                failure = t
                gate.poison(operationId)
                // Best effort truncation for seekable destinations; a provider may prohibit it.
                targets.forEach { runCatching { android.system.Os.ftruncate(it.fileDescriptor, 0) } }
                if (t !is Exception) {
                    all.forEach { runCatching { it.close() } }
                    JailScratch.close()
                    Process.killProcess(Process.myPid())
                    return@launch
                }
            } finally {
                runCatching { owner.unlinkToDeath(death, 0) }
                all.forEach { runCatching { it.close() } }
                JailScratch.close()
            }
            runCatching {
                if (failure != null) callback.onFailure(400, failure.message ?: "Worker failed")
                else {
                    callback.onSuccess(requireNotNull(result))
                    if (com.pdfchemy.pdfjail.BuildConfig.DEBUG && result.contains("\"duplicateSuccess\":true")) {
                        callback.onSuccess(requireNotNull(result))
                    }
                }
            }
        }
    }

    private fun numeric(operationId: Long, callback: IPdfJailCallback?) = object : IPdfJailStringCallback.Stub() {
        override fun onSuccess(resultJson: String) {
            try {
                val size = org.json.JSONObject(resultJson).optLong("size", 0L)
                callback?.onSuccess(size)
            } catch (t: Throwable) {
                gate.poison(operationId)
                callback?.onFailure(400, "Protocol error parsing numeric result: ${t.message}")
            }
        }
        override fun onFailure(errorCode: Int, errorMessage: String) {
            callback?.onFailure(errorCode, errorMessage)
        }
    }

    private val binder = object : IPdfJailService.Stub() {
        override fun beginOperation(ownerBinder: IBinder?): Long = gate.begin(ownerBinder)

        override fun completeOperation(operationId: Long): Boolean = gate.complete(operationId)

        override fun abortOperation(operationId: Long) {
            gate.terminateIfOwner(operationId)
        }

        override fun abortWorker() {
            Process.killProcess(Process.myPid())
        }

        override fun debugOutputProbe(rewrite: Boolean, callback: IPdfJailCallback) {
            check(com.pdfchemy.pdfjail.BuildConfig.DEBUG)
            val fd = debugOutput
            if (fd == null) { callback.onSuccess(0); return }
            if (rewrite) {
                android.system.Os.ftruncate(fd.fileDescriptor, 0)
                android.system.Os.lseek(fd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                val bytes = "late worker mutation".toByteArray()
                android.system.Os.write(fd.fileDescriptor, bytes, 0, bytes.size)
            }
            callback.onSuccess(fd.statSize)
        }

        override fun compressPdf(
            operationId: Long,
            sourceFd: ParcelFileDescriptor?,
            targetFd: ParcelFileDescriptor?,
            targetDpi: Float,
            quality: Float,
            rasterizePages: Boolean,
            expectedSha256: String,
            expectedSize: Long,
            scratchBinder: IBinder?,
            callback: IPdfJailCallback?
        ) {
            submit(operationId, listOfNotNull(sourceFd), listOfNotNull(targetFd), listOf(expectedSha256), listOf(expectedSize), "{}", numeric(operationId, callback), scratchBinder) {
                PdfServiceEngineWorker.compress(sourceFd, targetFd, targetDpi, quality, rasterizePages)
            }
        }

        override fun analyzePdf(
            operationId: Long,
            sourceFd: ParcelFileDescriptor?,
            expectedSha256: String,
            expectedSize: Long,
            scratchBinder: IBinder?,
            callback: IPdfJailStringCallback?
        ) {
            if (callback == null) { sourceFd?.close(); return }
            submit(operationId, listOfNotNull(sourceFd), emptyList(), listOf(expectedSha256), listOf(expectedSize), "{}", callback, scratchBinder) {
                PdfServiceEngineWorker.analyze(sourceFd)
            }
        }

        override fun exportModifiedPdf(
            operationId: Long,
            sourceFd: ParcelFileDescriptor?,
            targetFd: ParcelFileDescriptor?,
            modificationsJson: String?,
            expectedSha256: String,
            expectedSize: Long,
            scratchBinder: IBinder?,
            callback: IPdfJailCallback?
        ) {
            submit(operationId, listOfNotNull(sourceFd), listOfNotNull(targetFd), listOf(expectedSha256), listOf(expectedSize), modificationsJson ?: "{}", numeric(operationId, callback), scratchBinder) {
                require(sourceFd != null && targetFd != null && modificationsJson != null && callback != null)
                val type = object : com.google.gson.reflect.TypeToken<Map<Int, PageModification>>() {}.type
                val modifications = com.google.gson.Gson().fromJson<Map<Int, PageModification>>(modificationsJson, type) ?: emptyMap()
                check(PdfEditorWorker.exportModifiedPdf(this@PdfJailService, sourceFd, targetFd, modifications))
                org.json.JSONObject().put("size", targetFd.statSize).toString()
            }
        }

        override fun executeEngine(
            operationId: Long,
            engineName: String,
            sourceFd: ParcelFileDescriptor?,
            targetFd: ParcelFileDescriptor?,
            paramsJson: String,
            rendererBinder: IBinder?,
            expectedSha256: String,
            expectedSize: Long,
            scratchBinder: IBinder?,
            callback: IPdfJailStringCallback
        ) {
            submit(operationId, listOfNotNull(sourceFd), listOfNotNull(targetFd), if (sourceFd == null) emptyList() else listOf(expectedSha256), if (sourceFd == null) emptyList() else listOf(expectedSize), paramsJson, callback, scratchBinder) {
                if (com.pdfchemy.pdfjail.BuildConfig.DEBUG && engineName == "DEBUG_DUPLICATE_OUTPUT_SUCCESS") {
                    boundedFileOutput(requireNotNull(targetFd).fileDescriptor).use { it.write("original worker bytes".toByteArray()) }
                    return@submit "{\"success\":true,\"duplicateSuccess\":true}"
                }
                if (com.pdfchemy.pdfjail.BuildConfig.DEBUG && engineName == "DEBUG_UNTRUSTED_OUTPUT_OVERFLOW") {
                    android.system.Os.ftruncate(requireNotNull(targetFd).fileDescriptor, SecurityLimits.MAX_OUTPUT_BYTES + 1)
                    callback.onSuccess("{\"success\":true}")
                    while (true) Thread.sleep(1000)
                }
                if (com.pdfchemy.pdfjail.BuildConfig.DEBUG && engineName == "DEBUG_THROW_FATAL") {
                    throw AssertionError("Deliberate fatal Error in worker")
                }
                if (com.pdfchemy.pdfjail.BuildConfig.DEBUG && engineName == "DEBUG_EARLY_SUCCESS") {
                    callback.onSuccess("{\"success\":true}")
                    while (true) Thread.sleep(1000)
                }
                dispatch(engineName, sourceFd, targetFd, paramsJson, rendererBinder)
            }
        }

        override fun executeOcr(operationId: Long, sourceFd: ParcelFileDescriptor?, targetFd: ParcelFileDescriptor?,
            expectedSha256: String, expectedSize: Long, scratchBinder: IBinder?,
            progress: IPdfOcrProgressCallback?, callback: IPdfJailStringCallback) {
            submit(operationId, listOfNotNull(sourceFd), listOfNotNull(targetFd), listOf(expectedSha256),
                listOf(expectedSize), "{}", callback, scratchBinder) {
                require(sourceFd != null && targetFd != null && progress != null)
                PdfOcrEngineWorker.createSearchablePdf(this@PdfJailService, sourceFd, targetFd) { completed, total, saving ->
                    progress.onProgress(operationId, completed, total, saving)
                }
            }
        }

        override fun executeEngineExtra(
            operationId: Long,
            engineName: String,
            sourceFd: ParcelFileDescriptor?,
            targetFd: ParcelFileDescriptor?,
            extraFd: ParcelFileDescriptor?,
            paramsJson: String,
            rendererBinder: IBinder?,
            expectedSha256: String,
            expectedSize: Long,
            extraExpectedSha256: String,
            extraExpectedSize: Long,
            scratchBinder: IBinder?,
            callback: IPdfJailStringCallback
        ) {
            submit(operationId, listOfNotNull(sourceFd, extraFd), listOfNotNull(targetFd), listOf(expectedSha256, extraExpectedSha256), listOf(expectedSize, extraExpectedSize), paramsJson, callback, scratchBinder) {
                require(sourceFd != null && extraFd != null && targetFd != null)
                if (engineName == "SIGNATURE_APPLY") SignatureEngineWorker.applySignatures(sourceFd, targetFd, extraFd, paramsJson)
                else if (engineName == "ATTACHMENT_EMBED") PdfAttachmentEngineWorker.embedAttachment(sourceFd, targetFd, extraFd, paramsJson)
                else MigratedEngineDispatch.execute(this@PdfJailService, engineName, sourceFd, targetFd, extraFd, paramsJson)
            }
        }

        override fun executeEngineBatch(
            operationId: Long,
            engineName: String,
            sourceFds: Array<out ParcelFileDescriptor>?,
            targetFds: Array<out ParcelFileDescriptor>?,
            paramsJson: String?,
            rendererBinder: IBinder?,
            expectedSha256s: Array<String>?,
            expectedSizes: LongArray?,
            scratchBinder: IBinder?,
            callback: IPdfJailStringCallback
        ) {
            val sources = sourceFds?.toList().orEmpty()
            val targets = targetFds?.toList().orEmpty()
            submit(operationId, sources, targets, expectedSha256s?.toList().orEmpty(), expectedSizes?.toList().orEmpty(), paramsJson ?: "{}", callback, scratchBinder,
                validateMetadata = { SecurityLimits.requireBatch(sources.size, targets.size, expectedSha256s, expectedSizes) }) {
                when (engineName) {
                    "IMAGES_TO_PDF" -> ImagePdfWorker.convert(sources, targets.single())
                    "MERGE" -> PdfManipulatorWorker.mergePdfs(this@PdfJailService, sourceFds!!, targets.first())
                    "SPLIT" -> PdfManipulatorWorker.splitPdf(this@PdfJailService, sources.first(), targetFds!!, paramsJson ?: "{}")
                    "PDF_TO_IMAGES" -> PdfManipulatorWorker.pdfToImages(this@PdfJailService, sources.first(), targetFds!!, paramsJson ?: "{}")
                    else -> error("Unknown batch engine")
                }
            }
        }
    }

    private suspend fun dispatch(engineName: String, sourceFd: ParcelFileDescriptor?, targetFd: ParcelFileDescriptor?, paramsJson: String, rendererBinder: IBinder?): String {
        return when (engineName) {
            "COMPRESS" -> JailCompressionWorker.compress(requireNotNull(sourceFd), requireNotNull(targetFd), org.json.JSONObject(paramsJson))
            "EDITOR_EXPORT" -> {
                val type = object : com.google.gson.reflect.TypeToken<Map<Int, PageModification>>() {}.type
                val modifications = com.google.gson.Gson().fromJson<Map<Int, PageModification>>(paramsJson, type)
                check(PdfEditorWorker.exportModifiedPdf(this@PdfJailService, requireNotNull(sourceFd), requireNotNull(targetFd), modifications))
                org.json.JSONObject().put("size", requireNotNull(targetFd).statSize).toString()
            }
            "DEBUG_IDENTITY" -> { check(com.pdfchemy.pdfjail.BuildConfig.DEBUG); org.json.JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid()).toString() }
            "DEBUG_FAIL" -> { check(com.pdfchemy.pdfjail.BuildConfig.DEBUG); error("Deliberate admitted engine failure") }
            "DEBUG_THROW_FATAL" -> { check(com.pdfchemy.pdfjail.BuildConfig.DEBUG); throw LinkageError("Deliberate fatal worker LinkageError") }
            "DEBUG_BLOCK" -> { check(com.pdfchemy.pdfjail.BuildConfig.DEBUG); while (true) Thread.sleep(1000); error("unreachable") }
            "DEBUG_OUTPUT_BLOCK" -> {
                check(com.pdfchemy.pdfjail.BuildConfig.DEBUG)
                boundedFileOutput(requireNotNull(targetFd).fileDescriptor).use { it.write("partial".toByteArray()) }
                debugOutput?.close(); debugOutput = targetFd.dup()
                while (true) Thread.sleep(1000)
                error("unreachable")
            }
            "DEBUG_RETAIN_OUTPUT" -> {
                check(com.pdfchemy.pdfjail.BuildConfig.DEBUG)
                boundedFileOutput(requireNotNull(targetFd).fileDescriptor).use { it.write("original worker bytes".toByteArray()) }
                debugOutput?.close(); debugOutput = targetFd.dup()
                "{\"success\":true}"
            }
            "DEBUG_OUTPUT_OVERFLOW" -> {
                check(com.pdfchemy.pdfjail.BuildConfig.DEBUG)
                boundedFileOutput(requireNotNull(targetFd).fileDescriptor).use { output ->
                    val bytes = ByteArray(64 * 1024)
                    repeat((SecurityLimits.MAX_OUTPUT_BYTES / bytes.size).toInt()) { output.write(bytes) }
                    output.write(0)
                }
                error("Output quota was bypassed")
            }
            "DEBUG_SCRATCH" -> {
                check(com.pdfchemy.pdfjail.BuildConfig.DEBUG)
                val file = JailScratch.createTempFile("probe_", ".tmp")
                com.pdfchemy.app.jail.CapabilityIo.fd(file).use { fd ->
                    com.pdfchemy.app.jail.boundedFileOutput(fd.fileDescriptor).use { it.write("scratch".toByteArray()) }
                    android.system.Os.fsync(fd.fileDescriptor)
                    android.system.Os.lseek(fd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                    val read = ByteArray(7)
                    check(android.system.Os.read(fd.fileDescriptor, read, 0, 7) == 7 && String(read) == "scratch")
                }
                org.json.JSONObject().put("scratchCapability", true).toString()
            }

            "METADATA_READ" -> com.pdfchemy.app.jail.engines.PdfMetadataEngineWorker.readMetadata(sourceFd!!)
            "METADATA_WRITE" -> com.pdfchemy.app.jail.engines.PdfMetadataEngineWorker.writeOrSanitizeMetadata(sourceFd!!, targetFd!!, paramsJson)
            "DELETE_PAGES" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.deletePages(this@PdfJailService, sourceFd, targetFd, paramsJson)
            "ROTATE" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.rotatePdf(this@PdfJailService, sourceFd, targetFd, paramsJson)
            "PROTECT" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.protectPdf(this@PdfJailService, sourceFd, targetFd, paramsJson)
            "UNLOCK" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.unlockPdf(this@PdfJailService, sourceFd, targetFd, paramsJson)
            "CHECK_ENCRYPTION" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.checkEncryption(this@PdfJailService, sourceFd)
            "GET_PAGE_COUNT" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.getPageCount(this@PdfJailService, sourceFd!!)
            "PLAN_SPLIT_BLANK" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.planSplitByBlankPages(this@PdfJailService, sourceFd, paramsJson)
            "PLAN_SPLIT_BOOKMARKS" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.planSplitByBookmarks(this@PdfJailService, sourceFd)
            "BOOKMARK_READ" -> com.pdfchemy.app.jail.engines.PdfBookmarkEngineWorker.readBookmarks(sourceFd!!)
            "BOOKMARK_WRITE" -> com.pdfchemy.app.jail.engines.PdfBookmarkEngineWorker.writeBookmarks(sourceFd!!, targetFd!!, paramsJson)
            "TEXT_EXTRACT" -> com.pdfchemy.app.jail.engines.PdfTextExtractorWorker.extractText(this@PdfJailService, sourceFd!!, targetFd!!, paramsJson)
            "IMAGE_EXTRACT_FRAMED" -> com.pdfchemy.app.jail.engines.PdfImageExtractorEngine.extractImagesFramed(sourceFd!!, targetFd!!)
            "IMAGE_EXTRACT" -> com.pdfchemy.app.jail.engines.PdfImageExtractorEngine.extractImagesToZip(sourceFd!!, targetFd!!)
            "FONT_INSPECT" -> com.pdfchemy.app.jail.engines.PdfFontInspectorEngineWorker.inspectFonts(sourceFd!!)
            "ARCHIVE_INSPECT" -> com.pdfchemy.app.jail.engines.PdfArchiveValidatorEngineWorker.inspectPdfACompliance(sourceFd!!)
            "ARCHIVE_CONVERT" -> com.pdfchemy.app.jail.engines.PdfArchiveValidatorEngineWorker.convertToPdfA(sourceFd!!, targetFd!!)
            "LINEARIZE_CHECK" -> com.pdfchemy.app.jail.engines.PdfLinearizeEngineWorker.checkLinearized(sourceFd!!)
            "LINEARIZE_OPTIMIZE" -> com.pdfchemy.app.jail.engines.PdfLinearizeEngineWorker.optimizeFastWebView(sourceFd!!, targetFd!!)
            "OUTLINE_READ" -> com.pdfchemy.app.jail.engines.PdfOutlineReaderWorker.readOutline(this@PdfJailService, sourceFd!!, targetFd, paramsJson)
            "PAGE_ORGANIZE" -> com.pdfchemy.app.jail.engines.PdfPageOrganizerWorker.reorganizePages(sourceFd!!, targetFd!!, paramsJson)
            "COMIC_BOOK" -> com.pdfchemy.app.jail.engines.ComicBookEngineWorker.execute(this@PdfJailService, sourceFd, targetFd, paramsJson)
            "ACRO_FORM" -> com.pdfchemy.app.jail.engines.AcroFormEngineWorker.execute(this@PdfJailService, sourceFd, targetFd, paramsJson)
            "CROP" -> com.pdfchemy.app.jail.engines.PdfCropEngineWorker.execute(this@PdfJailService, sourceFd, targetFd, paramsJson)
            "FLATTEN_INSPECT" -> com.pdfchemy.app.jail.engines.PdfFlattenEngineWorker.inspect(this@PdfJailService, sourceFd!!)
            "FLATTEN_APPLY" -> com.pdfchemy.app.jail.engines.PdfFlattenEngineWorker.flatten(this@PdfJailService, sourceFd!!, targetFd!!, paramsJson)
            "REDACT" -> com.pdfchemy.app.jail.engines.PdfRedactionEngineWorker.execute(this@PdfJailService, sourceFd, targetFd, paramsJson, rendererBinder)
            "SEARCH_REDACT" -> com.pdfchemy.app.jail.engines.PdfRedactionEngineWorker.searchTargets(this@PdfJailService, sourceFd!!, paramsJson)
            "OFFICE_WORD" -> com.pdfchemy.app.jail.engines.OfficeExportEngineWorker.exportToWord(this@PdfJailService, sourceFd!!, targetFd!!)
            "OFFICE_EXCEL" -> com.pdfchemy.app.jail.engines.OfficeExportEngineWorker.exportToExcel(this@PdfJailService, sourceFd!!, targetFd!!)
            "OFFICE_PPT" -> com.pdfchemy.app.jail.engines.OfficeExportEngineWorker.exportToPowerPoint(this@PdfJailService, sourceFd!!, targetFd!!, rendererBinder)
            "ATTACHMENT_LIST" -> com.pdfchemy.app.jail.engines.PdfAttachmentEngineWorker.listAttachments(sourceFd!!)
            "ATTACHMENT_EXTRACT" -> com.pdfchemy.app.jail.engines.PdfAttachmentEngineWorker.extractAttachment(sourceFd!!, targetFd!!, paramsJson)
            "ATTACHMENT_REMOVE" -> com.pdfchemy.app.jail.engines.PdfAttachmentEngineWorker.removeAttachment(sourceFd!!, targetFd!!, paramsJson)
            "BOOKLET_GENERATE" -> com.pdfchemy.app.jail.engines.PdfBookletEngineWorker.generateBooklet(sourceFd!!, targetFd!!, paramsJson)
            "DESKEW" -> com.pdfchemy.app.jail.engines.PdfDeskewEngineWorker.deskew(sourceFd!!, targetFd!!, paramsJson)
            "TABLE_EXTRACT" -> com.pdfchemy.app.jail.engines.PdfTableExtractorWorker.extractText(sourceFd!!, targetFd, paramsJson)
            "WATERMARK" -> com.pdfchemy.app.jail.engines.PdfStampAndNumberWorker.applyWatermark(sourceFd!!, targetFd!!, paramsJson)
            "PAGE_NUMBERS" -> com.pdfchemy.app.jail.engines.PdfStampAndNumberWorker.applyPageNumbers(sourceFd!!, targetFd!!, paramsJson)
            "BATES_STAMP" -> com.pdfchemy.app.jail.engines.PdfStampAndNumberWorker.applyBatesStamping(sourceFd!!, targetFd!!, paramsJson)
            "SANITIZE_AUDIT" -> com.pdfchemy.app.jail.engines.PdfSanitizerEngineWorker.audit(sourceFd!!)
            "SANITIZE_CLEAN" -> com.pdfchemy.app.jail.engines.PdfSanitizerEngineWorker.sanitize(sourceFd!!, targetFd!!, paramsJson)
            "REPAIR_DIAGNOSE" -> com.pdfchemy.app.jail.engines.PdfRepairEngineWorker.diagnose(sourceFd!!)
            "REPAIR_APPLY" -> com.pdfchemy.app.jail.engines.PdfRepairEngineWorker.repair(sourceFd!!, targetFd!!)
            "NUP_GENERATE" -> com.pdfchemy.app.jail.engines.PdfNUpEngineWorker.generateNUpPdf(sourceFd!!, targetFd!!, paramsJson)
            "OCR_PROCESS" -> com.pdfchemy.app.jail.engines.PdfOcrEngineWorker.createSearchablePdf(this@PdfJailService, sourceFd!!, targetFd!!)
            "SIGNATURE_APPLY" -> error("Signature images require a verified extra descriptor")
            "SIGNATURE_DIGITAL" -> com.pdfchemy.app.jail.engines.SignatureEngineWorker.applyDigitalSignature(sourceFd!!, targetFd!!, paramsJson)
            "PDF_TO_EPUB" -> {
                val json = org.json.JSONObject(paramsJson)
                val bookTitle = json.optString("bookTitle", "Untitled E-Book")
                val authorName = json.optString("authorName", "Unknown Author")
                val success = com.pdfchemy.app.jail.engines.PdfToEpubEngine.pdfToEpub(this@PdfJailService, com.pdfchemy.app.jail.capabilityInput(sourceFd!!.fileDescriptor), com.pdfchemy.app.jail.boundedFileOutput(targetFd!!.fileDescriptor), bookTitle, authorName).getOrThrow()
                org.json.JSONObject().put("isSuccess", success).toString()
            }
            "EPUB_TO_PDF" -> {
                val success = com.pdfchemy.app.jail.engines.PdfToEpubEngine.epubToPdf(this@PdfJailService, com.pdfchemy.app.jail.capabilityInput(sourceFd!!.fileDescriptor), com.pdfchemy.app.jail.boundedFileOutput(targetFd!!.fileDescriptor)).getOrThrow()
                org.json.JSONObject().put("isSuccess", success).toString()
            }
            else -> com.pdfchemy.app.jail.engines.MigratedEngineDispatch.execute(this@PdfJailService, engineName, sourceFd, targetFd, null, paramsJson)
        }
    }
    override fun onCreate() { super.onCreate(); com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this) }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { debugOutput?.close(); serviceScope.cancel(); gate.close(); super.onDestroy() }
}
