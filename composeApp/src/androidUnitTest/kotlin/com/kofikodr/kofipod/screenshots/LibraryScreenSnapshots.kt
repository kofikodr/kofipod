// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.kofikodr.kofipod.ui.screens.library.LibraryContent
import com.kofikodr.kofipod.ui.screens.library.LibraryUiState
import com.kofikodr.kofipod.ui.theme.KofipodTheme
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.LocalKofipodColors
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi baseline for [LibraryContent] phone layout (`size == null`). Locks the
 * current phone rendering before Tasks 2.2 / 2.3 introduce tablet-size branches.
 *
 * Coverage: empty-state at phone resolution (412×892 dp, MDPI per spec §10).
 * Populated-state coverage is intentionally deferred — building a realistic
 * `LibraryUiState` fixture with podcasts, lists, and smart playlists pulls in
 * SQLDelight-generated row types and is more brittle than this regression guard
 * needs to be for Task 2.1's "byte-identical post-refactor" acceptance.
 */
class LibraryScreenSnapshots {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 412,
                    screenHeight = 892,
                    xdpi = 160,
                    ydpi = 160,
                    density = Density.MEDIUM,
                ),
            theme = "android:Theme.Material.Light.NoActionBar",
            useDeviceResolution = true,
        )

    @Test
    fun libraryEmptyState_phone_light() =
        paparazzi.snapshot {
            LibraryEmptyStateHarness()
        }
}

@Composable
private fun LibraryEmptyStateHarness() {
    KofipodTheme(KofipodThemeMode.Light) {
        val c = LocalKofipodColors.current
        Box(modifier = Modifier.fillMaxSize().background(c.bg)) {
            LibraryContent(
                state = LibraryUiState(),
                onOpenPodcast = {},
                onOpenList = {},
                onOpenSearch = {},
                onOpenStarterPack = {},
                onOpenBookmarks = {},
                onOpenStats = {},
                onOpenLibrarySearch = {},
                onOpenSmartPlaylistDetail = {},
                onNewList = {},
                onLongPressPodcast = {},
                onLongPressList = {},
                onLongPressSmartPlaylist = {},
                onImportOpml = {},
                size = null,
            )
        }
    }
}
