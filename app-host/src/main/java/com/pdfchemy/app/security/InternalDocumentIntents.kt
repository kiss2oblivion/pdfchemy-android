package com.pdfchemy.app.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Android enforces the alias's exported=false boundary before delivering its Intent. */
internal object InternalDocumentIntents {
    const val ALIAS_CLASS_NAME = "com.pdfchemy.app.InternalDocumentOpen"

    fun create(context: Context, uri: Uri): Intent {
        require(ExternalUriAdmission.isAppOwnedContentUri(context, uri)) { "Internal open requires an app-owned provider" }
        return Intent(Intent.ACTION_VIEW).setComponent(ComponentName(context.packageName, ALIAS_CLASS_NAME))
            .setDataAndType(uri, com.pdfchemy.app.utils.FileUtils.getMimeType(context, uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    fun extractTrustedUri(context: Context, intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_VIEW ||
            intent.component != ComponentName(context.packageName, ALIAS_CLASS_NAME)) return null
        return intent.data?.takeIf { ExternalUriAdmission.isAppOwnedContentUri(context, it) }
    }
}
