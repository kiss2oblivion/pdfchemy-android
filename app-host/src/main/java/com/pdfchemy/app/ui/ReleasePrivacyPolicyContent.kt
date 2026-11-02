package com.pdfchemy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R

/** Bundled policy remains readable without a browser, network or WebView. */
@Composable
internal fun ReleasePrivacyPolicyContent(modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val paragraphs = remember(resources) {
        resources.openRawResource(R.raw.integrity_privacy_policy).bufferedReader(Charsets.UTF_8)
            .use { it.readText() }.replace("\r\n", "\n").trim().split("\n\n")
    }
    Column(
        modifier = modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        paragraphs.forEach { paragraph ->
            val isHeading = paragraph.startsWith("# ")
                || paragraph.startsWith("## ")
            Text(
                text = paragraph.removePrefix("## ").removePrefix("# "),
                style = if (isHeading) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodyMedium,
                modifier = if (isHeading) Modifier.semantics { heading() } else Modifier
            )
        }
    }
}
