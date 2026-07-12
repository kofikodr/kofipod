// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kofikodr.kofipod.background.Notifier
import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.ui.ActivityHolder
import com.kofikodr.kofipod.ui.nav.DeepLinks
import com.kofikodr.kofipod.ui.theme.ThemeSystem
import org.koin.android.ext.android.inject

const val EXTRA_OPEN_PLAYER = "com.kofikodr.kofipod.extra.OPEN_PLAYER"
const val EXTRA_OPEN_EPISODE_ID = "com.kofikodr.kofipod.extra.OPEN_EPISODE_ID"
const val EXTRA_OPEN_LIBRARY = "com.kofikodr.kofipod.extra.OPEN_LIBRARY"

private const val TABLET_SW_DP = 600

class MainActivity : ComponentActivity() {
    private val activityHolder: ActivityHolder by inject()
    private val downloads: DownloadRepository by inject()

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

    override fun onResume() {
        super.onResume()
        activityHolder.set(this)
        // Deferred downloads have no organic retry signal when the network gate is
        // already open (see DownloadRepository.retryDeferredDownloads). Foreground is
        // the one moment an FGS start is always allowed, so re-drive them here.
        downloads.retryDeferredDownloads()
    }

    override fun onPause() {
        activityHolder.set(null)
        super.onPause()
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
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            DeepLinks.requestOpenPlayer()
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false)) {
            DeepLinks.requestOpenLibrary()
        }
        intent.getStringExtra(EXTRA_OPEN_EPISODE_ID)?.takeIf { it.isNotBlank() }?.let {
            DeepLinks.requestOpenEpisode(it)
        }
        if (intent.getBooleanExtra(Notifier.EXTRA_OPEN_SETTINGS_FOR_UPDATE, false)) {
            DeepLinks.requestOpenSettings()
        }
    }
}
