package com.xiaozhi.client.ui.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.client.core.activation.ActivationManager
import com.xiaozhi.client.core.activation.ActivationState
import com.xiaozhi.client.core.activation.WebsocketConfig
import com.xiaozhi.client.core.identity.EfuseStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ActivationViewModel(
    val activationManager: ActivationManager,
    val efuseStore: EfuseStore,
) : ViewModel() {

    private val _macReveal = MutableStateFlow(false)
    val macReveal: StateFlow<Boolean> = _macReveal.asStateFlow()

    fun start() {
        activationManager.start()
    }

    fun retry() {
        activationManager.start()
    }

    fun toggleMacReveal() {
        _macReveal.value = !_macReveal.value
    }
}
