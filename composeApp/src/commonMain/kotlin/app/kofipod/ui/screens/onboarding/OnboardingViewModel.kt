// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.auth.AuthService
import app.kofipod.auth.SignInError
import app.kofipod.config.BuildKonfig
import app.kofipod.data.repo.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val signingIn: Boolean = false,
    val signedInAs: String? = null,
    val error: String? = null,
    val done: Boolean = false,
)

class OnboardingViewModel(
    private val auth: AuthService,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun signIn() {
        if (_state.value.signingIn) return
        _state.value = OnboardingUiState(signingIn = true)
        viewModelScope.launch {
            runCatching {
                auth.signIn(BuildKonfig.GOOGLE_SERVER_CLIENT_ID)
            }.onSuccess { r ->
                settings.put(KEY_GOOGLE_EMAIL, r.email)
                markOnboarded()
                _state.value = OnboardingUiState(signedInAs = r.email, done = true)
            }.onFailure { e ->
                val msg = when (e) {
                    is SignInError.NotConfigured ->
                        "Sign-in is not configured for this build. Add GOOGLE_SERVER_CLIENT_ID to local.properties."
                    is SignInError.Cancelled -> "Sign-in cancelled."
                    else -> e.message ?: "Sign-in failed."
                }
                _state.value = OnboardingUiState(error = msg)
            }
        }
    }

    fun skip() {
        viewModelScope.launch {
            markOnboarded()
            _state.value = _state.value.copy(done = true)
        }
    }

    private fun markOnboarded() {
        settings.put(KEY_ONBOARDED, "true")
    }

    companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_GOOGLE_EMAIL = "google_email"
    }
}
