package com.pdfchemy.desktop.engine

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

interface UpdateHttpClient {
    fun getText(url: String, timeoutMs: Int): String
    fun download(
        url: String, 
        destination: File, 
        maxBytes: Long, 
        onProgress: (Long, Long) -> Unit, 
        isCancelled: () -> Boolean
    ): Result<File>
}

object DefaultUpdateHttpClient : UpdateHttpClient {
    override fun getText(url: String, timeoutMs: Int): String {
        var currentUrl = url
        var redirects = 0
        while (redirects < 5) {
            val uri = URI(currentUrl)
            DesktopUpdateManager.validateSecureGitHubUri(uri)

            val conn = uri.toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "PDFchemy-Desktop/${DesktopUpdateManager.CURRENT_VERSION}")

            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect without Location header")
                currentUrl = location
                redirects++
                continue
            }

            if (code !in 200..299) {
                throw IllegalStateException("Failed to download from $url: HTTP $code")
            }

            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        throw IllegalStateException("Too many redirects for $url")
    }

    override fun download(
        url: String,
        destination: File,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ): Result<File> {
        try {
            var currentUrl = url
            var redirects = 0

            while (redirects < 5) {
                if (isCancelled()) throw CancellationException("Update download was cancelled by user.")

                val uri = URI(currentUrl)
                DesktopUpdateManager.validateSecureGitHubUri(uri)

                val conn = uri.toURL().openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "PDFchemy-Desktop/${DesktopUpdateManager.CURRENT_VERSION}")

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: return Result.failure(IllegalStateException("Redirect without Location header"))
                    currentUrl = location
                    redirects++
                    continue
                }

                if (code !in 200..299) {
                    return Result.failure(IllegalStateException("Download failed with HTTP $code"))
                }

                val contentLength = conn.contentLengthLong
                var downloadedBytes = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isCancelled()) {
                                throw CancellationException("Update download was cancelled by user.")
                            }
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (downloadedBytes > maxBytes) {
                                throw SecurityException("Download exceeded maximum allowed size of ${maxBytes / (1024 * 1024)} MB")
                            }
                            onProgress(downloadedBytes, contentLength)
                        }
                        output.flush()
                    }
                }
                break
            }
            return Result.success(destination)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
}
