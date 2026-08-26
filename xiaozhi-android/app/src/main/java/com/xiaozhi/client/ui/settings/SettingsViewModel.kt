package com.xiaozhi.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.client.AppContainer
import com.xiaozhi.client.core.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _draft = MutableStateFlow<AppSettings?>(null)
    val draft: StateFlow<AppSettings?> = _draft.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun loadDraft() {
        viewModelScope.launch {
            _draft.value = container.settingsStore.first()
        }
    }

    fun save(draft: AppSettings) {
        viewModelScope.launch {
            container.settingsStore.update { draft }
            _message.value = "Saved"
        }
    }

    fun setTheme(theme: com.xiaozhi.client.ui.theme.ThemeId) {
        _draft.value = _draft.value?.copy(theme = theme)
    }

    fun setField(transform: (AppSettings) -> AppSettings) {
        _draft.value = _draft.value?.let(transform)
    }

    fun clearMessage() {
        _message.value = null
    }
}
