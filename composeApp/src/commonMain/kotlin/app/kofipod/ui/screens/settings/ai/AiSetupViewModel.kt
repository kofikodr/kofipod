// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.ai.AiConfigRepository
import app.kofipod.ai.AiError
import app.kofipod.ai.GeminiModel
import app.kofipod.ai.KeyValidator
import app.kofipod.ai.toAiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AiSetupUiState(
    val connected: Boolean = false,
    val model: GeminiModel = GeminiModel.Flash,
    val pasteValue: String = "",
    val verifying: Boolean = false,
    val errorMessage: String? = null,
    val showDisconnectConfirm: Boolean = false,
)

class AiSetupViewModel(
    private val config: AiConfigRepository,
    private val client: KeyValidator,
) : ViewModel() {
    private val pasteValue = MutableStateFlow("")
    private val verifying = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val showDisconnectConfirm = MutableStateFlow(false)

    val state: StateFlow<AiSetupUiState> =
        combine(
            config.isKeyConfigured(),
            config.model(),
            pasteValue,
            verifying,
            errorMessage,
            showDisconnectConfirm,
        ) { values ->
            AiSetupUiState(
                connected = values[0] as Boolean,
                model = values[1] as GeminiModel,
                pasteValue = values[2] as String,
                verifying = values[3] as Boolean,
                errorMessage = values[4] as String?,
                showDisconnectConfirm = values[5] as Boolean,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSetupUiState())

    fun onPasteChange(value: String) {
        pasteValue.value = value
        if (errorMessage.value != null) errorMessage.value = null
    }

    fun setModel(model: GeminiModel) =
        viewModelScope.launch {
            config.setModel(model)
        }

    fun connect() {
        // Guard against double-submission. The Compose UI also gates on `state.verifying`,
        // but two synchronous taps on the Main thread can both observe `verifying = false`
        // before either coroutine reaches its first suspension point — without this guard
        // we'd race on `setKey(raw)` and burn two validation requests.
        if (verifying.value) return
        val raw = pasteValue.value.trim()
        if (raw.isEmpty()) {
            errorMessage.value = "Paste your Gemini API key first."
            return
        }
        viewModelScope.launch {
            verifying.value = true
            errorMessage.value = null
            val result = client.validate(raw, state.value.model)
            verifying.value = false
            result
                .onSuccess {
                    config.setKey(raw)
                    pasteValue.value = ""
                }.onFailure {
                    errorMessage.value = errorCopy(it.toAiError())
                }
        }
    }

    fun requestDisconnect() {
        showDisconnectConfirm.value = true
    }

    fun cancelDisconnect() {
        showDisconnectConfirm.value = false
    }

    fun confirmDisconnect() =
        viewModelScope.launch {
            config.disconnect()
            showDisconnectConfirm.value = false
            pasteValue.value = ""
        }
}

/**
 * Maps [AiError] variants to the user-facing copy shown under the paste field.
 * Lifted out of [AiSetupViewModel] so it's directly unit-testable — the VM
 * itself only needs `errorCopy(error)` and the indirection caused tests to need
 * a full `viewModelScope` to assert one branch. Internal so the test suite can
 * see it without exposing it on the public surface.
 */
internal fun errorCopy(error: AiError): String =
    when (error) {
        AiError.KeyInvalid -> "That key was rejected. Double-check it in Google AI Studio."
        AiError.RateLimited -> "Google rate-limited the validation request. Try again in a minute."
        AiError.Network -> "Couldn't reach Google. Check your connection and retry."
        AiError.NoKey -> "Paste your Gemini API key first."
        AiError.AudioTooLong -> "Unexpected error during validation."
        is AiError.Unknown -> "Validation failed (status ${error.statusCode ?: "unknown"})."
    }
