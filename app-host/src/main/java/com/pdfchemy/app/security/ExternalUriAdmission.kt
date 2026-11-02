package com.pdfchemy.app.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.DocumentStager
import java.io.File

/**
 * Enforces external URI admission policy for VIEW/SEND intents (FOLLOW-03).
 * Strictly distinguishes external untrusted URIs from trusted internal staged capabilities,
 * rejecting private application paths, invalid schemes, and unauthorized capability claims.
 */
object ExternalUriAdmission {

    sealed class AdmissionResult {
        data class Admitted(val uri: Uri) : AdmissionResult()
        data class Rejected(val reason: String, val rejectedUri: Uri? = null) : AdmissionResult()
    }

    private val ADMITTED_ACTIONS = setOf(
        Intent.ACTION_VIEW,
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE
    )

    private val FORBIDDEN_SCHEMES = setOf(
        "http", "https", "ftp", "javascript", "data", "android.resource", "about", "ws", "wss"
    )

    fun admit(context: Context, intent: Intent?): AdmissionResult {
        if (intent == null) {
            return AdmissionResult.Rejected("Intent is null")
        }

        val action = intent.action
        if (action !in ADMITTED_ACTIONS) {
            return AdmissionResult.Rejected("Unsupported external intent action: $action")
        }

        val candidateUri = extractCandidateUri(intent)
            ?: return AdmissionResult.Rejected("No candidate URI found in intent")

        return admitUri(context, candidateUri)
    }

    fun admitAll(context: Context, intent: Intent?): List<AdmissionResult> {
        if (intent == null) {
            return listOf(AdmissionResult.Rejected("Intent is null"))
        }

        val action = intent.action
        if (action !in ADMITTED_ACTIONS) {
            return listOf(AdmissionResult.Rejected("Unsupported external intent action: $action"))
        }

        val candidateUris = extractCandidateUris(intent)
        if (candidateUris.isEmpty()) {
            return listOf(AdmissionResult.Rejected("No candidate URI found in intent"))
        }

        return candidateUris.map { admitUri(context, it) }
    }

    fun extractCandidateUri(intent: Intent): Uri? {
        val action = intent.action
        return when (action) {
            Intent.ACTION_VIEW -> {
                intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            Intent.ACTION_SEND -> {
                val streamUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                streamUri ?: intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                list?.firstOrNull() ?: intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> null
        }
    }

    fun extractCandidateUris(intent: Intent): List<Uri> {
        val action = intent.action
        val results = mutableListOf<Uri>()
        when (action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { results.add(it) }
                intent.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i)?.uri?.let { if (it !in results) results.add(it) }
                    }
                }
            }
            Intent.ACTION_SEND -> {
                val streamUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                streamUri?.let { results.add(it) }
                intent.data?.let { if (it !in results) results.add(it) }
                intent.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i)?.uri?.let { if (it !in results) results.add(it) }
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                list?.forEach { if (it !in results) results.add(it) }
                intent.data?.let { if (it !in results) results.add(it) }
                intent.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i)?.uri?.let { if (it !in results) results.add(it) }
                    }
                }
            }
        }
        return results
    }

    fun admitUri(context: Context, uri: Uri?): AdmissionResult {
        if (uri == null) {
            return AdmissionResult.Rejected("URI is null")
        }

        val uriString = uri.toString()
        if (uriString.isBlank()) {
            return AdmissionResult.Rejected("URI is empty or blank", uri)
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme == null) {
            return AdmissionResult.Rejected("URI lacks a scheme: $uri", uri)
        }

        if (scheme in FORBIDDEN_SCHEMES) {
            return AdmissionResult.Rejected("URI scheme '$scheme' is forbidden for external admission: $uri", uri)
        }

        if (scheme != "content" && scheme != "file") {
            return AdmissionResult.Rejected("Unsupported URI scheme '$scheme': $uri", uri)
        }

        // Scheme: file
        if (scheme == "file") {
            val path = uri.path
            if (path.isNullOrBlank()) {
                return AdmissionResult.Rejected("File URI has null or empty path: $uri", uri)
            }

            // Path traversal patterns
            if (path.contains("..") || path.contains("%2e%2e", ignoreCase = true)) {
                return AdmissionResult.Rejected("Path traversal pattern rejected in file URI: $path", uri)
            }

            val file = File(path)

            // Strictly reject external attempt to claim an internal staged capability
            if (DocumentStager.isTrustedStagedCapability(context, uri)) {
                return AdmissionResult.Rejected("External intent cannot claim internal staged capability: $uri", uri)
            }

            // Staged directory name pattern guard
            if (file.name.startsWith("snapshot_") && file.parentFile?.name == "staged") {
                return AdmissionResult.Rejected("External intent cannot reference staged cache directory: $uri", uri)
            }

            // Strictly reject access to any private app directory
            if (DocumentStager.isPrivateAppPath(context, file, includeCacheAndFiles = true)) {
                return AdmissionResult.Rejected("External intent cannot access private application storage: $path", uri)
            }

            return AdmissionResult.Admitted(uri)
        }

        // Scheme: content
        if (scheme == "content") {
            val authority = uri.authority
            if (authority.isNullOrBlank()) {
                return AdmissionResult.Rejected("Content URI has null or empty authority: $uri", uri)
            }

            // Authority traversal check
            if (authority.contains("..") || authority.contains("/") || authority.contains("\\")) {
                return AdmissionResult.Rejected("Malicious authority syntax in content URI: $authority", uri)
            }

            // The exported Activity opens inputs with our UID. URI text and Intent
            // grant flags cannot authorize a foreign caller to claim our providers.
            // ContentResolver also accepts encoded user-qualified authorities, so
            // compare the installed provider identity after removing that qualifier.
            if (isAppOwnedContentUri(context, uri)) {
                return AdmissionResult.Rejected("External intent cannot claim an app-owned provider: $authority", uri)
            }

            val path = uri.path
            if (path != null && (path.contains("..") || path.contains("%2e%2e", ignoreCase = true) || path.contains("\\"))) {
                return AdmissionResult.Rejected("Path traversal pattern rejected in content URI: $path", uri)
            }

            val raw = uri.toString()
            if (raw.contains("\\") || raw.contains("%5c", ignoreCase = true)) {
                return AdmissionResult.Rejected("Backslash separator rejected in content URI: $uri", uri)
            }

            return AdmissionResult.Admitted(uri)
        }

        return AdmissionResult.Rejected("Unhandled admission state for URI: $uri", uri)
    }

    internal fun isAppOwnedContentUri(context: Context, uri: Uri): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        val authority = uri.authority?.substringAfterLast('@') ?: return false
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        return provider?.applicationInfo?.uid == context.applicationInfo.uid
    }
}
