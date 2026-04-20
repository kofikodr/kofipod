// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.kofipod.ui.nav.DeepLinks
import app.kofipod.ui.theme.ThemeSystem

const val EXTRA_OPEN_PLAYER = "app.kofipod.extra.OPEN_PLAYER"

private const val TABLET_SW_DP = 600

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Phones lock to portrait; tablets (sw >= 600dp) stay unspecified so the OS can rotate.
        // Set before super.onCreate so there's no brief landscape flash on first draw.
        requestedOrientation =
            if (resources.configuration.smallestScreenWidthDp < TABLET_SW_DP) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent { App() }
    }

    override fun onStop() {
        super.onStop()
        ThemeSystem.syncPendingMode(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            DeepLinks.requestOpenPlayer()
        }
    }
}
