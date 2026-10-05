import sys
with open('app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt', 'r', encoding='utf-8') as f:
    text = f.read()

target = """    fun setHistoryEnabled(enabled: Boolean) {
        _isHistoryEnabled.value = enabled
        prefs.edit().putBoolean("history_enabled", enabled).apply()
        if (!enabled) {
            clearHistory()
        }
    }"""

replacement = target + """

    private val _isRememberPositionEnabled = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean("remember_position", true))
    val isRememberPositionEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> = _isRememberPositionEnabled.asStateFlow()

    fun setRememberPositionEnabled(enabled: Boolean) {
        _isRememberPositionEnabled.value = enabled
        prefs.edit().putBoolean("remember_position", enabled).apply()
    }"""

text = text.replace(target, replacement)
with open('app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt', 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)
