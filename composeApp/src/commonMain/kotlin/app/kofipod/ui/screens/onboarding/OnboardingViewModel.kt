// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.auth.AuthService
import app.kofipod.auth.AuthorizationError
import app.kofipod.auth.SignInError
import app.kofipod.config.BuildKonfig
import app.kofipod.data.repo.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val signingIn: Boolean = false,
    val error: String? = null,
)

class OnboardingViewModel(
    private val settings: SettingsRepository,
    private val auth: AuthService,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun onContinueWithGoogle(onDone: () -> Unit) {
        _state.value = _state.value.copy(signingIn = true, error = null)
        viewModelScope.launch {
            val clientId = BuildKonfig.GOOGLE_SERVER_CLIENT_ID
            runCatching {
                val who = auth.signIn(clientId)
                val drive = auth.authorizeDrive(clientId)
                who to drive
            }
                .onSuccess { (who, drive) ->
                    settings.setGoogleEmail(who.email)
                    settings.setDriveAccessToken(drive.accessToken)
                    settings.setBackupEnabled(true)
                    settings.setOnboarded(true)
                    _state.value = _state.value.copy(signingIn = false)
                    onDone()
                }
                .onFailure { e ->
                    val message = when (e) {
                        is SignInError.NotConfigured -> "Sign-in not configured yet. Tap Skip to use locally."
                        is SignInError.Cancelled -> null
                        is AuthorizationError.Cancelled -> "Drive access was not granted."
                        is SignInError.Failed -> e.message
                        else -> e.message ?: "Sign-in failed"
                    }
                    _state.value = _state.value.copy(signingIn = false, error = message)
                }
        }
    }

    fun onSkip(onDone: () -> Unit) {
        settings.setOnboarded(true)
        onDone()
    }
}
