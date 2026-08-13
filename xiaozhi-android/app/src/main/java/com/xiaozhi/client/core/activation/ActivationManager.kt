package com.xiaozhi.client.core.activation

import com.xiaozhi.client.core.identity.EfuseStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Drives the v2 activation flow:
 *
 *  1. POST /xiaozhi/ota/
 *  2. If response contains `activation.challenge`:
 *       a. surface the verification code + challenge to the UI
 *       b. POST /xiaozhi/ota/activate with HMAC-signed payload
 *       c. if HTTP 202 — retry every 5 s up to 60 times while user enters code
 *       d. on 200 — flip `efuse.isActivated = true`
 *  3. If response has no `activation` block — device is already activated on
 *     this account, just persist the websocket config.
 *
 * Surfaces its state as a [StateFlow] observed by the UI.
 */
class ActivationManager(
    private val otaClient: OtaClient,
    private val efuse: EfuseStore,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ActivationState>(ActivationState.Idle)
    val state: StateFlow<ActivationState> = _state.asStateFlow()

    /** Websocket info discovered during OTA. Populated after a successful OTA POST. */
    private val _websocketConfig = MutableStateFlow<WebsocketConfig?>(null)
    val websocketConfig: StateFlow<WebsocketConfig?> = _websocketConfig.asStateFlow()

    private var retryJob: Job? = null

    /**
     * Kick off the OTA handshake. Safe to call repeatedly — resets state.
     * If the device is already marked activated locally, OTA will still run
     * to refresh the websocket URL/token.
     */
    fun start(
        otaUrl: String = OtaClient.DEFAULT_OTA_URL,
        authUrl: String = OtaClient.DEFAULT_AUTH_URL,
    ) {
        retryJob?.cancel()
        retryJob = scope.launch {
            _state.value = ActivationState.Connecting
            try {
                val resp = otaClient.ota(otaUrl, efuse)
                resp.websocket?.let { _websocketConfig.value = it.toConfig() }

                val activation = resp.activation
                if (activation == null) {
                    // Already activated.
                    efuse.isActivated = true
                    _state.value = ActivationState.Activated
                    return@launch
                }

                // Pending — surface the code + drive the challenge loop.
                _state.value = ActivationState.Pending(
                    code = activation.code,
                    challenge = activation.challenge,
                    message = activation.message,
                    authUrl = authUrl,
                )
                driveActivationLoop(otaUrl, activation.challenge)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.value = ActivationState.Failed(t.message ?: t::class.java.simpleName)
            }
        }
    }

    fun cancel() {
        retryJob?.cancel()
        retryJob = null
        if (_state.value !is ActivationState.Activated) {
            _state.value = ActivationState.Idle
        }
    }

    /**
     * Repeatedly POST /activate with the same HMAC-signed payload until the
     * server confirms activation or the retry budget runs out.
     */
    private suspend fun driveActivationLoop(otaUrl: String, challenge: String) {
        val activateUrl = otaUrl.removeSuffix("/") + "/activate"
        repeat(MAX_RETRIES) { attempt ->
            try {
                val result = otaClient.activate(activateUrl, efuse, challenge)
                if (result.activated) {
                    efuse.isActivated = true
                    _state.value = ActivationState.Activated
                    return
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (io: IOException) {
                // Network blips are retried too — keep the loop alive.
                _state.value = (_state.value as? ActivationState.Pending)?.copy(
                    message = "Network error: ${io.message}, retrying…",
                ) ?: _state.value
            } catch (t: Throwable) {
                _state.value = ActivationState.Failed(t.message ?: t::class.java.simpleName)
                return
            }
            delay(RETRY_INTERVAL_MS)
        }
        _state.value = ActivationState.Failed(
            "Timed out waiting for activation. Please try again.",
        )
    }

    private fun OtaWebSocket.toConfig() = WebsocketConfig(url = url, token = token)

    companion object {
        const val MAX_RETRIES = 60
        const val RETRY_INTERVAL_MS = 5_000L
    }
}

sealed interface ActivationState {
    /** Initial / not started. */
    data object Idle : ActivationState
    /** OTA in flight. */
    data object Connecting : ActivationState
    /** OTA returned a code; waiting for the user to type it at xiaozhi.me. */
    data class Pending(
        val code: String,
        val challenge: String,
        val message: String?,
        val authUrl: String,
    ) : ActivationState
    /** Confirmed activated. */
    data object Activated : ActivationState
    /** Failed (terminal — call [ActivationManager.start] to retry). */
    data class Failed(val reason: String) : ActivationState
}

data class WebsocketConfig(val url: String, val token: String)
