// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.settings.podcastindex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.data.api.PodcastIndexConfigRepository
import com.kofikodr.kofipod.data.api.PodcastIndexCreds
import com.kofikodr.kofipod.data.api.PodcastIndexValidation
import com.kofikodr.kofipod.data.api.PodcastIndexValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PodcastIndexSetupUiState(
    val connected: Boolean = false,
    val keyValue: String = "",
    val secretValue: String = "",
    val verifying: Boolean = false,
    val errorMessage: String? = null,
    val showDisconnectConfirm: Boolean = false,
)

class PodcastIndexSetupViewModel(
    private val config: PodcastIndexConfigRepository,
    private val validator: PodcastIndexValidator,
) : ViewModel() {
    private val keyValue = MutableStateFlow("")
    private val secretValue = MutableStateFlow("")
    private val verifying = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val showDisconnectConfirm = MutableStateFlow(false)

    val state: StateFlow<PodcastIndexSetupUiState> =
        combine(
            config.isConfigured(),
            keyValue,
            secretValue,
            verifying,
            errorMessage,
            showDisconnectConfirm,
        ) { values ->
            PodcastIndexSetupUiState(
                connected = values[0] as Boolean,
                keyValue = values[1] as String,
                secretValue = values[2] as String,
                verifying = values[3] as Boolean,
                errorMessage = values[4] as String?,
                showDisconnectConfirm = values[5] as Boolean,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastIndexSetupUiState())

    fun onKeyChange(value: String) {
        keyValue.value = value
        if (errorMessage.value != null) errorMessage.value = null
    }

    fun onSecretChange(value: String) {
        secretValue.value = value
        if (errorMessage.value != null) errorMessage.value = null
    }

    fun connect() {
        // Guard against double-submission — two synchronous taps can both observe
        // verifying = false before either coroutine reaches its first suspension point.
        if (verifying.value) return
        val creds = PodcastIndexCreds(keyValue.value.trim(), secretValue.value.trim())
        if (!creds.isUsable) {
            errorMessage.value = missingFieldsCopy()
            return
        }
        viewModelScope.launch {
            verifying.value = true
            errorMessage.value = null
            val result = validator.validate(creds)
            verifying.value = false
            when (result) {
                PodcastIndexValidation.Valid -> {
                    config.setCredentials(creds)
                    keyValue.value = ""
                    secretValue.value = ""
                }
                PodcastIndexValidation.Invalid -> errorMessage.value = invalidCredsCopy()
                PodcastIndexValidation.NetworkError -> errorMessage.value = networkErrorCopy()
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
            keyValue.value = ""
            secretValue.value = ""
        }
}

/**
 * User-facing copy when both key and secret are not provided.
 * Lifted out for direct unit-testability — same pattern as [AiSetupViewModel]'s errorCopy().
 */
internal fun missingFieldsCopy(): String = "Enter both your Podcast Index key and secret."

/** User-facing copy when Podcast Index rejects the credentials (HTTP 401/403). */
internal fun invalidCredsCopy(): String = "That key or secret was rejected. Double-check both values."

/** User-facing copy when the network is unreachable during validation. */
internal fun networkErrorCopy(): String = "Couldn't reach Podcast Index. Check your connection and try again."
