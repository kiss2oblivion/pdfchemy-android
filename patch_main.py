import sys

with open('app-host/src/main/java/com/pdfchemy/app/MainActivity.kt', 'r', encoding='utf-8') as f:
    text = f.read()

target1 = """    val isHistoryEnabled by viewModel.isHistoryEnabled.collectAsState()"""
replacement1 = target1 + """
    val isRememberPositionEnabled by viewModel.isRememberPositionEnabled.collectAsState()"""

target2 = """                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.settings_history), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_history_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = isHistoryEnabled,
                                    onCheckedChange = { viewModel.setHistoryEnabled(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                                )
                            }"""
replacement2 = target2 + """
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(stringResource(R.string.settings_remember_position), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.settings_remember_position_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = isRememberPositionEnabled,
                                    onCheckedChange = { viewModel.setRememberPositionEnabled(it) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                                )
                            }"""

text = text.replace(target1, replacement1)
text = text.replace(target2, replacement2)

with open('app-host/src/main/java/com/pdfchemy/app/MainActivity.kt', 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)
