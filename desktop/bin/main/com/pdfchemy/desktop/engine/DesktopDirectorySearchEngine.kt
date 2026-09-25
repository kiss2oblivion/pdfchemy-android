// =================================================================================================
// [FEATURE: Directory Spotlight Search] (FEATURES_REGISTRY Desktop Edition: Spotlight Search)
// Multi-file directory keyword search across hundreds of PDFs with line snippet extraction and
// instant 1-click page jump to Reader. 100% offline, local-first.
// =================================================================================================

package com.pdfchemy.desktop.engine

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean







object DesktopDirectorySearchEngine {

    fun searchDirectory(
        directory: File,
        query: String,
        matchCase: Boolean = false,
        recursive: Boolean = true,
        cancelFlag: AtomicBoolean = AtomicBoolean(false),
        onProgress: (DirectorySearchProgress) -> Unit
    ) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty() || !directory.exists() || !directory.isDirectory) {
            onProgress(DirectorySearchProgress(0, 0, emptyList(), isComplete = true))
            return
        }

        // Collect all PDF files in folder
        val pdfFiles = mutableListOf<File>()
        if (recursive) {
            directory.walkTopDown().filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }.forEach {
                pdfFiles.add(it)
            }
        } else {
            directory.listFiles { f -> f.isFile && f.extension.equals("pdf", ignoreCase = true) }?.let {
                pdfFiles.addAll(it)
            }
        }

        val totalFiles = pdfFiles.size
        if (totalFiles == 0) {
            onProgress(DirectorySearchProgress(0, 0, emptyList(), isComplete = true))
            return
        }

        val results = mutableListOf<FileSearchResult>()
        var scanned = 0
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val executor = Executors.newFixedThreadPool(cpuCores)

        try {
            val futures = pdfFiles.map { file ->
                executor.submit<FileSearchResult?> {
                    if (cancelFlag.get()) return@submit null
                    searchSingleFile(file, trimmedQuery, matchCase, cancelFlag)
                }
            }

            for (future in futures) {
                if (cancelFlag.get()) break
                val res = try { future.get() } catch (_: Exception) { null }
                scanned++
                if (res != null && res.totalMatches > 0) {
                    synchronized(results) {
                        results.add(res)
                    }
                }
                if (scanned % 3 == 0 || scanned == totalFiles) {
                    onProgress(
                        DirectorySearchProgress(
                            filesScanned = scanned,
                            totalFiles = totalFiles,
                            results = synchronized(results) { results.sortedByDescending { it.totalMatches } },
                            isComplete = (scanned == totalFiles)
                        )
                    )
                }
            }
        } finally {
            executor.shutdownNow()
        }

        onProgress(
            DirectorySearchProgress(
                filesScanned = scanned,
                totalFiles = totalFiles,
                results = synchronized(results) { results.sortedByDescending { it.totalMatches } },
                isComplete = true,
                isCancelled = cancelFlag.get()
            )
        )
    }

    private fun searchSingleFile(
        file: File,
        query: String,
        matchCase: Boolean,
        cancelFlag: AtomicBoolean
    ): FileSearchResult? {
        try {
            val pagesText = DesktopPdfEngine.extractAllPagesText(file)
            if (pagesText.isEmpty()) return null

            val snippets = mutableListOf<SearchMatchSnippet>()
            var matchCount = 0

            pagesText.forEachIndexed { index, pageText ->
                if (cancelFlag.get()) {
                    throw InterruptedException("Search cancelled")
                }
                if (pageText.isBlank()) return@forEachIndexed

                val p = index + 1
                val lines = pageText.lines()
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isEmpty()) continue

                    val idx = if (matchCase) {
                        trimmedLine.indexOf(query)
                    } else {
                        trimmedLine.indexOf(query, ignoreCase = true)
                    }

                    if (idx >= 0) {
                        matchCount++
                        if (snippets.size < 12) {
                            val start = (idx - 30).coerceAtLeast(0)
                            val end = (idx + query.length + 30).coerceAtMost(trimmedLine.length)
                            val snippetText = (if (start > 0) "..." else "") +
                                    trimmedLine.substring(start, end).trim() +
                                    (if (end < trimmedLine.length) "..." else "")

                            snippets.add(
                                SearchMatchSnippet(
                                    pageNumber = p,
                                    lineSnippet = snippetText,
                                    matchStart = idx,
                                    matchEnd = idx + query.length
                                )
                            )
                        }
                    }
                }
            }

            if (matchCount > 0) {
                return FileSearchResult(
                    file = file,
                    totalMatches = matchCount,
                    snippets = snippets
                )
            }
        } catch (_: Exception) {}
        return null
    }
}
