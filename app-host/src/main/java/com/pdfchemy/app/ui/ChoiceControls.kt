package com.pdfchemy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R

@Composable
fun ThemeChoiceDialog(currentMode: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column {
                listOf("SYSTEM" to R.string.theme_system, "LIGHT" to R.string.theme_light,
                    "DARK" to R.string.theme_dark).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = currentMode == mode, role = Role.RadioButton, onClick = { onSelect(mode) }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentMode == mode, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun ReaderThemeSwatch(theme: ReaderTheme, selected: Boolean, onSelect: () -> Unit) {
    val label = when (theme) {
        ReaderTheme.LIGHT -> stringResource(R.string.theme_light)
        ReaderTheme.DARK -> stringResource(R.string.theme_dark)
        else -> theme.label
    }
    Box(
        modifier = Modifier.size(48.dp).semantics { contentDescription = label }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(28.dp).background(theme.bg, CircleShape)
                .border(if (selected) 2.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(Icons.Default.Check, contentDescription = null,
                modifier = Modifier.size(16.dp), tint = theme.text)
        }
    }
}
