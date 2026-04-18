// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.splash

import androidx.lifecycle.ViewModel
import app.kofipod.data.repo.SettingsRepository

class SplashViewModel(settings: SettingsRepository) : ViewModel() {
    val needsOnboarding: Boolean = !settings.onboardedNow()
}
