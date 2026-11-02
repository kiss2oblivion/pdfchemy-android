# Reviewed source excerpts

Selected current source ranges; this is not a claim of a line-by-line review of every production file.

## pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/RedactionResources.kt

```text
29:     fun rasterizationPlan(document: PDDocument, redacted: Set<Int>, deleted: Set<Int>): RasterizationPlan {
30:         // Remove unused inherited resources first: sharing an unused resource does
31:         // not require destroying an otherwise independent page's vector content.
32:         pruneUnused(document)
33:         val observed = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
34:         var streamReferences = 0
35:         val streams = document.pages.map { page ->
36:             Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>()).apply {
37:                 visitGraph(page.cosObject, page.cosObject) {
38:                     observed.add(it)
39:                     require(observed.size <= SecurityLimits.MAX_GRAPH_NODES) { "Excessive page resource graph" }
40:                     if (it is COSStream) add(it)
41:                 }
42:                 streamReferences += size
43:                 require(streamReferences <= SecurityLimits.MAX_GRAPH_NODES) { "Excessive shared stream graph" }
44:             }
45:         }
46:         val selected = redacted.filterTo(mutableSetOf()) { it in streams.indices && it !in deleted }
47:         val originals = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())
48:         selected.forEach { originals.addAll(streams[it]) }
49:         // Expand to a fixed point: rasterizing a dependent page also replaces its
50:         // other original streams, which must not survive through a third page.
51:         do {
52:             var changed = false
53:             for (index in streams.indices) {
54:                 if (index !in selected && index !in deleted && streams[index].any { it in originals }) {
55:                     selected.add(index)
56:                     originals.addAll(streams[index])
57:                     changed = true
58:                 }
59:             }
60:         } while (changed)
61:         return RasterizationPlan(selected, originals)
...
138:     private fun removeDocumentCarriers(document: PDDocument) {
139:         val removed = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
140:         fun record(raw: COSBase?) {
141:             val value = dereference(raw)
142:             // Never record shared primitive singletons (null, names, numbers).
143:             if (value is COSDictionary || value is COSArray || value is COSString) removed.add(value)
144:         }
145:         val catalog = document.documentCatalog.cosObject
146:         for (key in listOf("StructTreeRoot", "MarkInfo")) {
147:             val name = COSName.getPDFName(key)
148:             record(catalog.getItem(name))
149:             catalog.removeItem(name)
150:         }
151:         record(document.document.trailer.getItem(COSName.INFO))
152:         document.documentInformation = PDDocumentInformation()
153:         // Record identities before scrubbing severs standard links. Alternate
154:         // references must not retain XMP, private application data or attachments.
155:         visitGraph(document.document.trailer) { node ->
156:             if (node is COSDictionary) {
157:                 // Outlines can retain accessibility elements through SE even
158:                 // after the main StructTreeRoot is detached. Keep their Dest.
159:                 for (key in listOf("Metadata", "PieceInfo", "EmbeddedFiles", "AF", "EF", "FS", "SE")) {
160:                     record(node.getItem(COSName.getPDFName(key)))
161:                 }
162:                 if (node.containsKey(COSName.getPDFName("EF"))) record(node)
163:                 if (node is COSStream && node.getCOSName(COSName.TYPE) == COSName.getPDFName("EmbeddedFile")) record(node)
164:                 node.removeItem(COSName.getPDFName("StructParents"))
165:                 node.removeItem(COSName.getPDFName("StructParent"))
166:             }
167:         }
168:         ActiveContentScrubber.inspect(document, scrub = true, purgeJs = false,
169:             purgeActions = false, purgeAttachments = true)
170:         detachReferences(document, removed)
171:     }
172: 
173:     private fun detachReferences(document: PDDocument, removed: Set<COSBase>) {
174:         visitGraph(document.document.trailer) { node ->
175:             when (node) {
176:                 is COSDictionary -> for (key in node.keySet().toList()) {
177:                     val child = dereference(node.getItem(key))
178:                     if (child != null && child in removed) node.removeItem(key)
179:                 }
180:                 is COSArray -> for (index in node.size() - 1 downTo 0) {
181:                     val child = dereference(node.get(index))
182:                     if (child != null && child in removed) node.remove(index)
183:                 }
184:             }
185:         }
186:     }
187: 
188:     private fun dereference(raw: COSBase?): COSBase? {
...
```

## app-host/src/main/java/com/pdfchemy/app/MainActivity.kt

```text
408:     private fun cleanupOrphanedCacheFiles(context: Context) {
409:         try {
410:             val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000L)
411:             fun cleanDir(dir: File?) {
412:                 if (dir == null || !dir.exists()) return
413:                 val files = dir.listFiles() ?: return
414:                 for (file in files) {
415:                     if (file.isDirectory) {
416:                         cleanDir(file)
417:                     } else if (file.isFile && file.lastModified() < oneHourAgo) {
418:                         val name = file.name.lowercase()
419:                         if (name.endsWith(".pdf") || name.endsWith(".epub") || name.endsWith(".cbz") ||
420:                             name.endsWith(".csv") || name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx") ||
421:                             name.startsWith("temp_") || name.startsWith("pdf_seekable_") || name.startsWith("scan_")) {
422:                             try { file.delete() } catch (_: Throwable) {}
423:                         }
424:                     }
425:                 }
426:             }
427:             cleanDir(context.cacheDir)
428:             // Saved scans are user documents, not disposable cache files.
429:         } catch (e: Throwable) {
430:             AppLogger.w("Failed to clean orphaned cache files: ${e.message}")
431:         }
432:     }
433: 
434:     @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
435:     override fun onCreate(savedInstanceState: Bundle?) {
436:         installSplashScreen()
437:         super.onCreate(savedInstanceState)
438:         lifecycleScope.launch(Dispatchers.IO) {
439:             cleanupOrphanedCacheFiles(applicationContext)
440:         }
...
```

## app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt

```text
171:             isVanguardScanning = false
172:         }) {
173:             val docId = withContext(Dispatchers.IO) { com.pdfchemy.app.utils.DocumentIdentity.computeStableId(context, initialUri) }
174:             val stagedLease = withContext(Dispatchers.IO) { DocumentStager.stageDocumentCancellableWithLease(context, initialUri) }
175:             val staged = stagedLease.document
176:             val stagedUri = staged.uri
177:             val newLease = stagedLease.lease
178:             val oldLease = activeSessionLease
179:             val oldUri = selectedPdfUri
180:             activeSessionLease = newLease
181:             selectedPdfUri = stagedUri
182:             oldLease?.close()
183:             if (oldUri != null && oldUri != stagedUri && viewModel.continuityDocumentUri.value != oldUri) {
184:                 withContext(Dispatchers.IO) { DocumentStager.release(oldUri) }
185:             }
186:             documentId = docId
187:             personalBookmarks = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
188:                 .getStringSet("bookmarks_$docId", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
189:             
...
230:                 val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
231:                 val sessionPosition = viewModel.readerPositions[initialUri.toString()]
232:                 val savedPage = sessionPosition?.page ?: if (isRememberPositionEnabled) prefs.getInt("page_$docId", 0) else 0
233:                 val offset = sessionPosition?.offset ?: if (isRememberPositionEnabled) prefs.getInt("offset_$docId", 0) else 0
234:                 listState.scrollToItem(savedPage.coerceIn(0, totalPages - 1), offset.coerceAtLeast(0))
235:             }
236:         }
237:     }
238:     
239:     LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, totalPages, loading) {
240:         if (!loading && totalPages > 0) {
241:             val page = listState.firstVisibleItemIndex
242:             val offset = listState.firstVisibleItemScrollOffset
243:             viewModel.readerPositions[initialUri.toString()] = MainViewModel.ReaderPosition(page, offset)
244:             if (isRememberPositionEnabled && documentId.isNotEmpty()) {
245:                 context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE).edit()
246:                     .putInt("page_$documentId", page).putInt("offset_$documentId", offset).apply()
247:             }
...
414:                                 val page = listState.firstVisibleItemIndex
415:                                 personalBookmarks = if (page in personalBookmarks) personalBookmarks - page else personalBookmarks + page
416:                                 context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE).edit().putStringSet("bookmarks_$documentId", personalBookmarks.map { it.toString() }.toSet()).apply()
417:                                 showMore = false
418:                             }, enabled = totalPages > 0)
419:                             DropdownMenuItem(text = { Text("Share") }, onClick = {
420:                                 runCatching { com.pdfchemy.app.logic.DocumentActions.share(context, selectedPdfUri!!) }.onFailure { viewModel.notifyError("No sharing app is available. Save a copy and try again.") }; showMore = false
421:                             }, enabled = totalPages > 0)
422:                             DropdownMenuItem(text = { Text("Print") }, onClick = {
423:                                 runCatching { com.pdfchemy.app.logic.DocumentActions.print(context, selectedPdfUri!!, fileName) }.onFailure { viewModel.notifyError("Printing is unavailable. Check your print service and try again.") }; showMore = false
424:                             }, enabled = totalPages > 0)
425:                         }
...
```

## app-host/src/main/java/com/pdfchemy/app/logic/DocumentActions.kt

```text
26:     fun share(context: Context, uri: Uri) {
27:         val shareUri = if (uri.scheme == "file") FileProvider.getUriForFile(
28:             context, "${context.packageName}.fileprovider", File(requireNotNull(uri.path))
29:         ) else uri
30:         ShareUtil.shareFiles(context, listOf(shareUri))
31:     }
...
```

## app-host/src/main/res/xml/file_paths.xml

```text
1: <?xml version="1.0" encoding="utf-8"?>
2: <paths>
3:     <cache-path name="camera_scans" path="scans/" />
4:     <cache-path name="shared_exports" path="exports/" />
5:     <files-path name="internal_scans" path="scans/" />
6: </paths>
7: 
...
```

## app-host/src/main/java/com/pdfchemy/app/utils/DocumentIdentity.kt

```text
15:     fun computeStableId(context: Context, uri: Uri): String {
16:         var name = ""
17:         var size = 0L
18:         
19:         if (uri.scheme == "content") {
20:             try {
21:                 context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
22:                     if (cursor.moveToFirst()) {
23:                         val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
24:                         val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
25:                         if (nameIndex != -1) name = cursor.getString(nameIndex) ?: ""
26:                         if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
27:                     }
28:                 }
29:             } catch (e: Exception) {
30:                 // Ignore
31:             }
32:         } else if (uri.scheme == "file") {
33:             name = com.pdfchemy.app.utils.FileUtils.getFileName(context, uri) ?: ""
34:             size = java.io.File(uri.path ?: "").length()
35:         }
36:         
37:         if (name.isEmpty()) {
38:             name = uri.lastPathSegment ?: uri.toString()
39:         }
40:         
41:         val rawInput = "$name::$size"
42:         return hashString(rawInput)
43:     }
44:     
45:     private fun hashString(input: String): String {
46:         val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
47:         return bytes.joinToString("") { "%02x".format(it) }
...
```

## pdf-ipc/src/main/java/com/pdfchemy/app/jail/HostOutputTransaction.kt

```text
122:         currentCoroutineContext().ensureActive()
123:         commitStarted = true
124:         val buffer = ByteArray(64 * 1024)
125:         var committed = 0L
126:         for (index in targets.indices) {
127:             currentCoroutineContext().ensureActive()
128:             // SAF may supply a pipe. No seek/truncate operation is performed on
129:             // its FD, and no real provider capability crosses the worker boundary.
130:             requireNotNull(context.contentResolver.openOutputStream(targets[index], "wt")).use { output ->
131:                 var offset = 0L
132:                 while (offset < sizes[index]) {
133:                     currentCoroutineContext().ensureActive()
134:                     val count = Os.pread(snapshots[index].fileDescriptor, buffer, 0,
135:                         minOf(buffer.size.toLong(), sizes[index] - offset).toInt(), offset)
136:                     if (count <= 0) throw IOException("Incomplete commit snapshot")
137:                     require(committed <= SecurityLimits.MAX_OUTPUT_BYTES - count) { "Commit quota exceeded" }
138:                     output.write(buffer, 0, count)
139:                     offset += count; committed += count
140:                 }
141:             }
142:         }
143:     }
144: 
...
```

## app-host/src/main/java/com/pdfchemy/app/logic/PdfGateway.kt

```text
126:             // For output operations: validate and snapshot into private FDs, validate typed response, then ACK before committing to SAF.
127:             if (targets.isNotEmpty()) {
128:                 outputTransaction.validateAndSnapshot(rawResult)
129:                 val contract = WorkerResponseValidator.validate(operation, rawResult, targets.size)
130:                 check(jail.completeOperation(operationToken)) { "Worker rejected Host acceptance handshake" }
131:                 operationToken = 0L // Successfully completed/released
132:                 outputTransaction.commit()
133:                 contract
134:             } else {
135:                 // Non-output operation: Host validates typed response before accepting result
136:                 val contract = WorkerResponseValidator.validate(operation, rawResult, targets.size)
137:                 check(jail.completeOperation(operationToken)) { "Worker rejected Host acceptance handshake" }
138:                 operationToken = 0L // Successfully completed/released
139:                 contract
140:             }
141:         } catch (error: Throwable) {
142:             // Terminate worker ONLY if this operation holds a valid, active token.
143:             // Never abort if token == 0L (e.g. BUSY or pre-admission failure).
144:             if (operationToken > 0L) {
145:                 runCatching {
...
```

## app-host/src/main/java/com/pdfchemy/app/logic/audio/AudioOutputPublisher.kt

```text
15:         context: Context,
16:         wav: File,
17:         destination: Uri,
18:         isFreshCopy: Boolean = OutputPolicy.isFreshCopy(destination)
19:     ) {
20:         copy(
21:             wav,
22:             { context.contentResolver.openOutputStream(destination, "wt") },
23:             { deletePartial(context, destination, isFreshCopy) }
24:         )
25:         OutputPolicy.markPublished(destination)
26:     }
27: 
28:     fun deletePartial(
29:         context: Context,
30:         destination: Uri,
31:         isFreshCopy: Boolean = OutputPolicy.isFreshCopy(destination)
32:     ) {
33:         if (!isFreshCopy) {
34:             // Existing or unknown destinations must not be deleted during pre-publication failure cleanup
35:             return
36:         }
37:         OutputPolicy.unregisterFreshCopy(destination)
38:         runCatching { DocumentFile.fromSingleUri(context, destination)?.delete() }
39:     }
40: 
41:     // Small separate abstraction: HostOutputTransaction is a PDF-worker FD/JSON validator
42:     // with PDF security quotas, unsuitable for already validated host-generated audio.
43:     internal suspend fun copy(wav: File, open: () -> OutputStream?, deletePartial: () -> Unit) {
44:         try {
45:             requireNotNull(open()) { "Unable to open audio destination" }.use { output ->
46:                 wav.inputStream().use { input ->
47:                     val buffer = ByteArray(64 * 1024)
48:                     while (true) {
49:                         currentCoroutineContext().ensureActive()
50:                         val count = input.read(buffer)
51:                         if (count < 0) break
52:                         output.write(buffer, 0, count)
53:                     }
54:                     currentCoroutineContext().ensureActive()
55:                 }
56:             }
57:         } catch (error: Throwable) {
58:             runCatching { deletePartial() }
59:             throw error
60:         }
61:     }
62: }
...
```

## app-host/src/main/java/com/pdfchemy/app/ui/PdfEditorScreen.kt

```text
348:                 documentId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
349:                     com.pdfchemy.app.utils.DocumentIdentity.computeStableId(context, viewModel.editorSourceUri ?: uri)
350:                 }
351:                 if (rememberPosition) currentPageIndex = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
352:                     .getInt("editor_page_$documentId", currentPageIndex).coerceAtLeast(0)
353:                 if (isVanguardEnabled) {
...
394:     LaunchedEffect(currentPageIndex, documentLoading, rememberPosition) {
395:         if (!documentLoading && totalPages > 0 && rememberPosition && documentId.isNotEmpty()) {
396:             context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE).edit().putInt("editor_page_$documentId", currentPageIndex).apply()
397:         }
...
```

## app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt

```text
146:     fun cancelOperation(context: android.content.Context) {
147:         val opId = currentOperationId
148:         val opRecord = if (opId != 0L) activeOperations.remove(opId) else null
149:         val cancelledJob = opRecord?.job ?: activeJob
150:         activeJob = null
151:         _canCancelOperation.value = false
152:         currentOperationId = 0L
153:         _uiState.value = UiState.Idle
154: 
155:         opRecord?.outputs?.forEach { it.publicationState = com.pdfchemy.app.logic.PublicationState.CANCELLED }
156:         cancelledJob?.cancel()
157: 
158:         viewModelScope.launch(Dispatchers.IO) {
159:             // A provider write must finish/abort before its document is deleted.
160:             cancelledJob?.join()
161:             // Only proven fresh incomplete destinations may be deleted; existing/unknown destinations remain intact.
162:             opRecord?.outputs?.forEach { tracked ->
163:                 if (tracked.isFreshCopy && tracked.publicationState != com.pdfchemy.app.logic.PublicationState.PUBLISHED) {
164:                     val claimedByNewerOp = activeOperations.values.any { other ->
165:                         other.operationId > opId && other.outputs.any { it.uri == tracked.uri }
166:                     }
167:                     if (!claimedByNewerOp) {
168:                         com.pdfchemy.app.logic.OutputPolicy.unregisterFreshCopy(tracked.uri)
169:                         runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, tracked.uri)?.delete() }
170:                     }
171:                 }
172:             }
```
