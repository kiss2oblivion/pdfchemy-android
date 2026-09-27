package com.pdfchemy.app.ui.textconverter

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfchemy.app.logic.TextFormatConverter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import androidx.compose.ui.res.stringResource
import com.pdfchemy.app.R

class TextConverterViewModel : ViewModel() {

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _inputFormat = MutableStateFlow(TextFormatConverter.Format.TXT)
    val inputFormat: StateFlow<TextFormatConverter.Format> = _inputFormat.asStateFlow()

    private val _outputFormat = MutableStateFlow(TextFormatConverter.Format.MD)
    val outputFormat: StateFlow<TextFormatConverter.Format> = _outputFormat.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    sealed class UiState {
        object Idle : UiState()
        object Processing : UiState()
        data class Success(val title: String, val message: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    fun setInputText(text: String) {
        _inputText.value = text
    }

    fun setInputFormat(format: TextFormatConverter.Format) {
        _inputFormat.value = format
        // Auto-select a valid output format if needed
        val validOutputs = getValidOutputFormats(format)
        if (!validOutputs.contains(_outputFormat.value)) {
            _outputFormat.value = validOutputs.firstOrNull() ?: TextFormatConverter.Format.TXT
        }
    }

    fun setOutputFormat(format: TextFormatConverter.Format) {
        _outputFormat.value = format
    }

    fun getValidOutputFormats(input: TextFormatConverter.Format): List<TextFormatConverter.Format> {
        return TextFormatConverter.Format.values().filter { it != input }
    }

    fun loadFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.value = UiState.Processing
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val stringBuilder = java.lang.StringBuilder()
                    var line: String?
                    val maxChars = 2_000_000
                    while (reader.readLine().also { line = it } != null) {
                        stringBuilder.append(line).append("\n")
                        if (stringBuilder.length > maxChars) {
                            // truncate large files to avoid OOM
                            com.pdfchemy.app.utils.AppLogger.w("TextConverterViewModel: Input file exceeded size limit ($maxChars chars)")
                            break
                        }
                    }
                    _inputText.value = stringBuilder.toString()
                }
                
                // Auto-detect format based on extension
                val path = uri.path?.lowercase() ?: ""
                when {
                    path.endsWith(".md") -> setInputFormat(TextFormatConverter.Format.MD)
                    path.endsWith(".html") || path.endsWith(".htm") -> setInputFormat(TextFormatConverter.Format.HTML)
                    path.endsWith(".csv") -> setInputFormat(TextFormatConverter.Format.CSV)
                    path.endsWith(".tsv") -> setInputFormat(TextFormatConverter.Format.TSV)
                    path.endsWith(".json") -> setInputFormat(TextFormatConverter.Format.JSON)
                    path.endsWith(".yaml") || path.endsWith(".yml") -> setInputFormat(TextFormatConverter.Format.YAML)
                    path.endsWith(".xml") -> setInputFormat(TextFormatConverter.Format.XML)
                    else -> setInputFormat(TextFormatConverter.Format.TXT)
                }
                
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to read file: ${e.message}")
            }
        }
    }

    fun convertAndSave(context: Context, destUri: Uri) {
        if (_uiState.value is UiState.Processing) return

        viewModelScope.launch {
            _uiState.value = UiState.Processing
            
            val result = TextFormatConverter.convert(
                _inputText.value, 
                _inputFormat.value, 
                _outputFormat.value
            )
            
            result.onSuccess { convertedText ->
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                        outputStream.write(convertedText.toByteArray(Charsets.UTF_8))
                    }
                    _uiState.value = UiState.Success(context.getString(R.string.success_title), context.getString(R.string.success_file_converted))
                } catch (e: Exception) {
                    _uiState.value = UiState.Error("Failed to save file: ${e.message}")
                }
            }.onFailure { error ->
                _uiState.value = UiState.Error(error.message ?: "Conversion failed.")
            }
        }
    }

    fun dismissMessage() {
        _uiState.value = UiState.Idle
    }
}
