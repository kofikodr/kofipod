// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import app.kofipod.data.repo.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OnboardingUiState(
    val signingIn: Boolean = false,
    val error: String? = null,
)

class OnboardingViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun onContinue(onDone: () -> Unit) {
        settings.setOnboarded(true)
        onDone()
    }
}
