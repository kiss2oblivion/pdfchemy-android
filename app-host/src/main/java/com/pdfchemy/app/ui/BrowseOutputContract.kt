package com.pdfchemy.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

class BrowseOutputContract : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: Context, input: Uri?): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= 26 && input != null && DocumentsContract.isDocumentUri(context, input))
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
    }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}
