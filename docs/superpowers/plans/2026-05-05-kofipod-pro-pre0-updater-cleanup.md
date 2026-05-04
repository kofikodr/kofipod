# Kofipod Pro — Pre-Slice 0: Updater Cleanup

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the in-app updater package and all integration points so the codebase enters Slice 0 (Pro entitlement plumbing + Gradle flavors) with no dead distribution paths.

**Architecture:** Detach consumers (worker / notifier / settings UI+VM+repo / Android+iOS DI / Application) one task at a time so the codebase compiles after every commit. Then delete the source files (common + androidMain + iosMain) in a single atomic commit. Finally clean up the AndroidManifest, FileProvider, file_paths, and backup rules.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Koin DI, SQLDelight (preferences live in `SyncMeta` table via `SettingsRepository`).

**Spec:** `docs/superpowers/specs/2026-05-04-kofipod-pro-unlock-design.md` § "Removed in this release: in-app updater" and § "Slice plan" Pre-0.

**Green-check sequence after every task** (run before each commit):

```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```

After Task 8 (test deletion), additionally run:

```bash
./gradlew :composeApp:testDebugUnitTest
```

The pre-commit hook (`scripts/git-hooks/pre-commit`) re-runs `ktlintFormat` + `detekt` automatically; if you haven't installed it, `./gradlew installGitHooks` once per clone.

---

## Task 1: Detach updater piggyback from `EpisodeCheckWorker`

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/background/EpisodeCheckWorker.kt`

The worker piggybacks an app-update check onto the daily episode check (lines 72–88). Remove that block plus the imports and KoinComponent fields it needed.

- [ ] **Step 1: Apply the edit**

Replace the entire file contents with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.data.repo.autoDownloadEnabledBool
import app.kofipod.data.repo.notifyNewEpisodesEnabledBool
import app.kofipod.downloads.DownloadJob
import app.kofipod.downloads.downloadFileName
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class EpisodeCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val library: LibraryRepository by inject()
    private val episodes: EpisodesRepository by inject()
    private val settings: SettingsRepository by inject()
    private val downloads: DownloadRepository by inject()
    private val notifier: Notifier by inject()

    override suspend fun doWork(): Result =
        runCatching {
            val cap = settings.storageCapBytes().first()
            var totalNew = 0
            var showsWithNew = 0
            var notifyNew = 0
            var notifyShows = 0
            val now = System.currentTimeMillis()

            for (podcast in library.podcastsFlow().first()) {
                val feedId = podcast.id.toLongOrNull() ?: continue
                val result = episodes.refresh(podcast.id, feedId, now)
                if (result.inserted > 0) {
                    totalNew += result.inserted
                    showsWithNew++
                    if (podcast.notifyNewEpisodesEnabledBool()) {
                        notifyNew += result.inserted
                        notifyShows++
                    }
                    if (podcast.autoDownloadEnabledBool()) {
                        result.insertedEpisodes.forEach { ep ->
                            downloads.enqueue(
                                episodeId = ep.id,
                                url = ep.enclosureUrl,
                                fileName = downloadFileName(ep.id, ep.enclosureMimeType),
                                source = DownloadJob.Source.Auto,
                            )
                        }
                    }
                }
            }

            downloads.evictUntilUnderCap(cap)
            SchedulerRunLog.append(
                settings,
                SchedulerRun(at = now, inserted = totalNew, shows = showsWithNew),
            )
            if (notifyNew > 0) notifier.postNewEpisodes(notifyNew, notifyShows)
            Result.success()
        }.getOrElse { Result.retry() }
}
```

- [ ] **Step 2: Compile**

Run:
```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/androidMain/kotlin/app/kofipod/background/EpisodeCheckWorker.kt
git commit -m "$(cat <<'EOF'
chore(updater): drop daily app-update piggyback from EpisodeCheckWorker

Pre-Slice-0 cleanup before Pro work. Distribution moves to Play
Store + F-Droid; both handle updates natively, so the in-app
updater is being removed in stages.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Remove `postUpdateAvailable` from `Notifier` + `MainActivity` intent handler

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/background/Notifier.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/background/Notifier.android.kt`
- Modify: `composeApp/src/iosMain/kotlin/app/kofipod/background/Notifier.ios.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/MainActivity.kt`

- [ ] **Step 1: Drop `postUpdateAvailable` from the common interface**

In `composeApp/src/commonMain/kotlin/app/kofipod/background/Notifier.kt`, remove the line `fun postUpdateAvailable(version: String)`. The interface keeps `postNewEpisodes` only.

- [ ] **Step 2: Drop the Android impl + channel + intent extra**

In `composeApp/src/androidMain/kotlin/app/kofipod/background/Notifier.android.kt`, replace the file contents with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import app.kofipod.R

actual class Notifier(private val context: Context) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID_NEW_EPISODES) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID_NEW_EPISODES,
                        "New episodes",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
        }
    }

    actual fun postNewEpisodes(
        totalEpisodes: Int,
        totalShows: Int,
    ) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val text = "from $totalShows show" + if (totalShows == 1) "" else "s"
        val notif =
            NotificationCompat.Builder(context, CHANNEL_ID_NEW_EPISODES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$totalEpisodes new episodes")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        mgr.notify(NOTIFY_ID_NEW_EPISODES, notif)
    }

    companion object {
        const val CHANNEL_ID_NEW_EPISODES = "kofipod.new_episodes"
        const val NOTIFY_ID_NEW_EPISODES = 42
    }
}
```

- [ ] **Step 3: Drop the iOS no-op**

In `composeApp/src/iosMain/kotlin/app/kofipod/background/Notifier.ios.kt`, delete the line `actual fun postUpdateAvailable(version: String) { /* iOS doesn't sideload — no-op */ }`. The remaining `actual class Notifier` keeps only `postNewEpisodes`. (Read the file first; if removing this line leaves the actual class empty of methods other than the one preserved, that's fine.)

- [ ] **Step 4: Remove the update intent handler from `MainActivity`**

In `composeApp/src/androidMain/kotlin/app/kofipod/MainActivity.kt`, find the `handleDeepLink` function and remove the second `if (intent?.getBooleanExtra(Notifier.EXTRA_OPEN_SETTINGS_FOR_UPDATE...` block. After the edit `handleDeepLink` should be:

```kotlin
private fun handleDeepLink(intent: Intent?) {
    if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
        DeepLinks.requestOpenPlayer()
    }
}
```

If the import `import app.kofipod.background.Notifier` is now unused, remove it as well.

- [ ] **Step 5: Compile**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/background/Notifier.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/background/Notifier.android.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/background/Notifier.ios.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/MainActivity.kt
git commit -m "$(cat <<'EOF'
chore(updater): drop update notifications + MainActivity intent

Removes the App-updates notification channel, postUpdateAvailable
method (interface + Android impl + iOS no-op), and the deep-link
handler that opened Settings on tap.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Remove the "App update" section + "Check for app updates" toggle from `SettingsScreen`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt`

Three things go: the `SectionLabel("App update")` + `UpdateCard(...)` block at the top, the `SettingRow(title = "Check for app updates", ...)` inside the Downloads section, and every helper composable / function that supported the update card (`UpdateCard`, `UpToDateRow`, `AvailableRow`, `ReadyToInstallRow`, `UpdatePillButton`, `DownloadProgress`, `downloadButtonLabel`, `lastCheckedSubtitle`, `formatMb`).

- [ ] **Step 1: Remove the import**

Delete the line `import app.kofipod.data.repo.UpdateUiState` (currently line 49).

- [ ] **Step 2: Remove the top "App update" section**

In `SettingsScreen()`, delete this block (currently lines 89–97):

```kotlin
SectionLabel("App update", topSpacing = 22.dp)
UpdateCard(
    update = state.update,
    action = state.updateAction,
    onCheck = viewModel::checkForUpdates,
    onDownload = viewModel::downloadUpdate,
    onInstall = viewModel::installUpdate,
    onDismiss = viewModel::dismissUpdate,
)
```

The first `SectionLabel("Library", topSpacing = 22.dp)` now sits directly under the screen title.

- [ ] **Step 3: Remove the "Check for app updates" SettingRow**

Inside the Downloads section, delete this block (currently lines 173–184):

```kotlin
Spacer(Modifier.height(8.dp))
SettingRow(
    icon = KPIconName.Download,
    title = "Check for app updates",
    subtitle = "Looks for newer Kofipod releases on GitHub during the daily check",
    trailing = {
        PinkSwitch(
            checked = state.autoUpdateCheck,
            onCheckedChange = viewModel::setAutoUpdateCheck,
            testTag = "autoUpdateCheckSwitch",
        )
    },
)
```

The Downloads section retains `Daily check for new episodes` and `Download on Wi-Fi only` rows.

- [ ] **Step 4: Remove the helper composables and helpers**

Delete the entire region starting at the comment `// Update card` (currently around line 580) through the end of `formatMb`. That covers the following declarations, all of which will be unreferenced after Step 3:

- `@Composable private fun UpdateCard(...)`
- `@Composable private fun UpToDateRow(...)`
- `@Composable private fun AvailableRow(...)`
- `@Composable private fun ReadyToInstallRow(...)`
- `@Composable private fun UpdatePillButton(...)`
- `@Composable private fun DownloadProgress(...)`
- `private fun downloadButtonLabel(...)`
- `private fun lastCheckedSubtitle(...)`
- `private fun formatMb(...)`

The `// Formatting` comment block with `formatGb` and `formatSize` stays — those are used by the storage/cache cards.

- [ ] **Step 5: Compile**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```
Expected: BUILD SUCCESSFUL. (The ViewModel still exposes `state.update`, `state.updateAction`, `state.autoUpdateCheck`, `checkForUpdates`, etc. — they're now unreferenced from the UI but compile fine. Task 4 cleans them up.)

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt
git commit -m "$(cat <<'EOF'
chore(updater): remove App-update section + check-for-updates toggle

Strips the UpdateCard and its supporting composables from
SettingsScreen, plus the Downloads-section toggle that gated the
daily background update check.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Remove updater fields and functions from `SettingsViewModel` + Koin factory

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`

The VM exposed `state.update`, `state.updateAction`, `state.autoUpdateCheck`, the `UpdateAction` sealed type, four functions (`checkForUpdates`, `downloadUpdate`, `installUpdate`, `dismissUpdate`), and `setAutoUpdateCheck`. All are now unused by the UI; remove them and the three constructor arguments that fed them.

- [ ] **Step 1: Replace `SettingsViewModel.kt`**

Replace the entire file contents with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.ai.AiConfigRepository
import app.kofipod.ai.GeminiModel
import app.kofipod.background.Scheduler
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.opml.OpmlAction
import app.kofipod.opml.OpmlController
import app.kofipod.playback.PlaybackCache
import app.kofipod.ui.theme.KofipodThemeMode
import app.kofipod.ui.theme.ThemeSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: KofipodThemeMode = KofipodThemeMode.System,
    val dailyCheck: Boolean = true,
    val wifiOnly: Boolean = true,
    val storageCapBytes: Long = SettingsRepository.DEFAULT_CAP_BYTES,
    val streamCacheCapBytes: Long = SettingsRepository.DEFAULT_STREAM_CACHE_CAP_BYTES,
    val streamCacheUsedBytes: Long = 0L,
    val skipForward: Int = 30,
    val skipBack: Int = 10,
    val aiConnected: Boolean = false,
    val aiModel: GeminiModel = GeminiModel.Flash,
    val opmlAction: OpmlAction = OpmlAction.Idle,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: Scheduler,
    private val themeSystem: ThemeSystem,
    private val playbackCache: PlaybackCache,
    private val aiConfig: AiConfigRepository,
    private val opml: OpmlController,
) : ViewModel() {
    // Refreshes the displayed cache usage once per second while Settings is visible.
    private val cacheUsedFlow =
        flow {
            while (true) {
                emit(playbackCache.sizeBytes())
                delay(1_000)
            }
        }

    val state: StateFlow<SettingsUiState> =
        combine(
            combine(
                repo.themeMode(),
                repo.dailyCheckEnabled(),
                repo.wifiOnly(),
                repo.storageCapBytes(),
                repo.skipForwardSeconds(),
                repo.skipBackSeconds(),
                repo.streamCacheCapBytes(),
                cacheUsedFlow,
            ) { values ->
                SettingsUiState(
                    themeMode = values[0] as KofipodThemeMode,
                    dailyCheck = values[1] as Boolean,
                    wifiOnly = values[2] as Boolean,
                    storageCapBytes = values[3] as Long,
                    skipForward = values[4] as Int,
                    skipBack = values[5] as Int,
                    streamCacheCapBytes = values[6] as Long,
                    streamCacheUsedBytes = values[7] as Long,
                )
            },
            combine(
                aiConfig.isKeyConfigured(),
                aiConfig.model(),
                opml.action,
            ) { aiConnected, aiModel, opmlAction ->
                AiAndOpmlState(aiConnected, aiModel, opmlAction)
            },
        ) { base, combined ->
            base.copy(
                aiConnected = combined.aiConnected,
                aiModel = combined.aiModel,
                opmlAction = combined.opmlAction,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: KofipodThemeMode) =
        viewModelScope.launch {
            repo.setThemeMode(mode)
            themeSystem.apply(mode)
        }

    fun setDailyCheck(on: Boolean) =
        viewModelScope.launch {
            repo.setDailyCheckEnabled(on)
            if (on) scheduler.enable() else scheduler.disable()
        }

    fun setWifiOnly(on: Boolean) = viewModelScope.launch { repo.setWifiOnly(on) }

    fun setCap(bytes: Long) = viewModelScope.launch { repo.setStorageCapBytes(bytes) }

    fun setStreamCacheCap(bytes: Long) = viewModelScope.launch { repo.setStreamCacheCapBytes(bytes) }

    fun setSkipForward(sec: Int) = viewModelScope.launch { repo.setSkipForward(sec) }

    fun setSkipBack(sec: Int) = viewModelScope.launch { repo.setSkipBack(sec) }

    fun importOpml() = opml.importOpml()

    fun exportOpml() = opml.exportOpml()
}

private data class AiAndOpmlState(
    val aiConnected: Boolean,
    val aiModel: GeminiModel,
    val opmlAction: OpmlAction,
)
```

- [ ] **Step 2: Update the Koin factory in `CommonModule.kt`**

Open `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`. Make three edits:

1. Remove these two import lines:

```kotlin
import app.kofipod.data.repo.UpdateRepository
import app.kofipod.ui.screens.settings.UpdateActionPort
```

If the file no longer references `NetworkErrorHandler` after this task (it was only used by the VM's deleted `checkForUpdates` / `downloadUpdate` flows), also delete that import. Search the file with grep to confirm — keep the import only if some other binding in the module still uses it.

2. Remove the `UpdateRepository` singleton binding:

```kotlin
single { UpdateRepository(settings = get(), localApk = get()) }
```

3. Replace the `SettingsViewModel` factory. The current block looks like:

```kotlin
viewModel {
    SettingsViewModel(
        repo = get(),
        scheduler = get(),
        themeSystem = get(),
        playbackCache = get(),
        updateChecker = get(),
        updateRepo = get(),
        updateActions = get<UpdateActionPort>(),
        aiConfig = get(),
        errors = get(),
        opml = get(),
    )
}
```

Replace it with:

```kotlin
viewModel {
    SettingsViewModel(
        repo = get(),
        scheduler = get(),
        themeSystem = get(),
        playbackCache = get(),
        aiConfig = get(),
        opml = get(),
    )
}
```

Four arguments removed: `updateChecker`, `updateRepo`, `updateActions`, and `errors`. The new VM's constructor (Step 1 above) takes exactly these six parameters in this order.

- [ ] **Step 3: Compile**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "$(cat <<'EOF'
chore(updater): drop updater fields/functions from SettingsViewModel

Removes UpdateAction, the update + updateAction + autoUpdateCheck
state fields, four control functions, and the three constructor
args (updateChecker, updateRepo, updateActions). Updates the Koin
factory in CommonModule and removes the UpdateRepository singleton.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Remove updater preference keys and accessors from `SettingsRepository`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/data/repo/SettingsRepository.kt`

The `autoUpdateCheckEnabled` toggle is gone; so are the seven `KEY_UPDATE_*` constants used by the deleted `UpdateRepository`. Stale rows in the `SyncMeta` table on existing dev installs are harmless — the keys just won't be read anymore.

- [ ] **Step 1: Remove the three `autoUpdateCheck*` accessors**

Delete these three function declarations (currently lines 75–79):

```kotlin
fun autoUpdateCheckEnabled(): Flow<Boolean> = metaFlow(KEY_AUTO_UPDATE_CHECK).map { it?.toBoolean() ?: true }

fun autoUpdateCheckEnabledNow(): Boolean = getMetaNow(KEY_AUTO_UPDATE_CHECK)?.toBoolean() ?: true

fun setAutoUpdateCheckEnabled(enabled: Boolean) = put(KEY_AUTO_UPDATE_CHECK, enabled.toString())
```

- [ ] **Step 2: Remove the eight obsolete keys from the companion**

Delete these constants from the `companion object` (currently lines 96 and 99–109):

```kotlin
const val KEY_AUTO_UPDATE_CHECK = "auto_update_check_enabled"
```

and the entire updater-keys block:

```kotlin
// Update-checker keys. These ride existing Auto Backup so the user's "skipped
// v1.2.0" preference and last-checked timestamp persist across reinstalls. The
// device-local APK path lives in `LocalApkPathStore` instead — see that class
// for why it must NOT be backed up.
const val KEY_UPDATE_LATEST_VERSION = "update_latest_version"
const val KEY_UPDATE_RELEASE_URL = "update_release_url"
const val KEY_UPDATE_APK_URL = "update_apk_url"
const val KEY_UPDATE_APK_SIZE = "update_apk_size_bytes"
const val KEY_UPDATE_RELEASE_NOTES = "update_release_notes"
const val KEY_UPDATE_DISMISSED_VERSION = "update_dismissed_version"
const val KEY_UPDATE_LAST_CHECK_AT = "update_last_check_at_ms"
```

The remaining keys (`KEY_STORAGE_CAP`, `KEY_STREAM_CACHE_CAP`, `KEY_THEME`, `KEY_DAILY_CHECK`, `KEY_WIFI_ONLY`, `KEY_SKIP_FWD`, `KEY_SKIP_BACK`, `KEY_SCHEDULER_RUNS`, `KEY_AI_MODEL`, `KEY_STATS_TIER_EMITTED`, `KEY_STATS_TIER_SEEN`) are untouched.

- [ ] **Step 3: Compile**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/data/repo/SettingsRepository.kt
git commit -m "$(cat <<'EOF'
chore(updater): drop updater preference keys from SettingsRepository

Removes autoUpdateCheck flow/now/setter and the seven KEY_UPDATE_*
constants used by the deleted UpdateRepository. Existing rows in
the SyncMeta table on dev devices are harmless — they're simply
no longer read.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Detach Android DI bindings + `KofipodApplication` reconcile call

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt`

- [ ] **Step 1: Edit `AndroidModule.kt`**

Replace the file contents with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.ai.AndroidKeyVault
import app.kofipod.ai.KeyVault
import app.kofipod.background.AiSummaryScheduler
import app.kofipod.background.AndroidAiSummaryScheduler
import app.kofipod.background.Notifier
import app.kofipod.background.Scheduler
import app.kofipod.data.db.DatabaseFactory
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.downloads.DownloadEngine
import app.kofipod.downloads.DownloadEngineApi
import app.kofipod.network.AndroidNetworkMonitor
import app.kofipod.network.NetworkMonitor
import app.kofipod.opml.AndroidOpmlFilePort
import app.kofipod.opml.OpmlFilePort
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlaybackCache
import app.kofipod.share.Sharer
import app.kofipod.ui.palette.AndroidPalettePort
import app.kofipod.ui.palette.PalettePort
import app.kofipod.ui.theme.ThemeSystem
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidPlatformModule =
    module {
        single { DatabaseFactory(androidContext()) }
        single { KofipodPlayer(androidContext()) }
        single { DownloadEngine(androidContext()) }
        single<DownloadEngineApi> { get<DownloadEngine>() }
        single<NetworkMonitor> { AndroidNetworkMonitor(androidContext()) }
        single {
            // Read the cap synchronously at Koin resolution; SimpleCache is constructed once per
            // process and can't be re-sized without reopening, so later slider changes apply on
            // next process start.
            val capBytes = get<SettingsRepository>().streamCacheCapBytesNow()
            PlaybackCache(androidContext(), capBytes)
        }
        single { Scheduler(androidContext()) }
        single<AiSummaryScheduler> { AndroidAiSummaryScheduler(androidContext()) }
        single { Notifier(androidContext()) }
        single { Sharer(androidContext()) }
        single { ThemeSystem(androidContext()) }
        single<PalettePort> { AndroidPalettePort(androidContext()) }
        single<KeyVault> { AndroidKeyVault(androidContext()) }
        // The Android OPML port is a singleton that the picker-host composable subscribes
        // to. Both the interface and the concrete type resolve to the same instance so the
        // composable (which casts to the concrete) sees what the VM (which uses the
        // interface) is signalling.
        single { AndroidOpmlFilePort() }
        single<OpmlFilePort> { get<AndroidOpmlFilePort>() }
    }
```

(Removed: imports of `AndroidUpdateActionPort`, `UpdateActionPort`, `AndroidLocalApkPathStore`, `LocalApkPathStore`, `UpdateChecker`, `UpdateInstaller`; the four `single { ... }` lines for `LocalApkPathStore`, `UpdateChecker`, `UpdateInstaller`, `UpdateActionPort`.)

- [ ] **Step 2: Edit `KofipodApplication.kt`**

Open `composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt`. Remove the `import app.kofipod.update.UpdateInstaller` line (currently line 9) and remove the line `get<UpdateInstaller>(UpdateInstaller::class.java).reconcileDownloadedApk()` (currently line 27). Verify no other updater references remain in the file.

- [ ] **Step 3: Compile**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileDebugKotlinAndroid
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt
git commit -m "$(cat <<'EOF'
chore(updater): drop Android DI bindings + reconcile call

Removes UpdateChecker, UpdateInstaller, LocalApkPathStore, and
UpdateActionPort bindings from the Android Koin module, plus the
reconcileDownloadedApk call wired into Application onCreate.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Detach iOS DI bindings

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/app/kofipod/di/IosPlatformModule.kt`

- [ ] **Step 1: Edit `IosPlatformModule.kt`**

Open the file. Remove these imports:

```kotlin
import app.kofipod.ui.screens.settings.IosUpdateActionPort
import app.kofipod.ui.screens.settings.UpdateActionPort
import app.kofipod.update.IosLocalApkPathStore
import app.kofipod.update.LocalApkPathStore
import app.kofipod.update.UpdateChecker
```

Remove these `single { ... }` bindings (find them by scanning the module body):

```kotlin
single<UpdateActionPort> { IosUpdateActionPort() }
single<LocalApkPathStore> { IosLocalApkPathStore() }
single { UpdateChecker() }
```

Leave the rest of the module intact. If after the edit any line is left dangling (e.g., a trailing comma issue), reformat with `ktlintFormat`.

- [ ] **Step 2: Compile**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileKotlinIosSimulatorArm64
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/iosMain/kotlin/app/kofipod/di/IosPlatformModule.kt
git commit -m "$(cat <<'EOF'
chore(updater): drop iOS DI bindings

Removes UpdateChecker, IosLocalApkPathStore, and IosUpdateActionPort
bindings from the iOS Koin module ahead of the source-file deletion.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Delete updater unit tests

**Files:**
- Delete: `composeApp/src/androidUnitTest/kotlin/app/kofipod/repo/UpdateRepositoryTest.kt`
- Delete: `composeApp/src/androidUnitTest/kotlin/app/kofipod/update/VersionCompareTest.kt`

The tests reference `UpdateRepository`, `UpdateUiState`, `LocalApkPathStore`, `UpdateInfo`, `VersionCompare` — all about to be deleted. Drop the tests first so Task 9 can delete sources without breaking the test compilation pass.

- [ ] **Step 1: Delete the test files**

```bash
rm composeApp/src/androidUnitTest/kotlin/app/kofipod/repo/UpdateRepositoryTest.kt
rm composeApp/src/androidUnitTest/kotlin/app/kofipod/update/VersionCompareTest.kt
rmdir composeApp/src/androidUnitTest/kotlin/app/kofipod/update
```
(The `rmdir` removes the now-empty `update` test package directory. If any non-test files were created there since this plan was written, list the directory first and skip `rmdir`.)

- [ ] **Step 2: Run unit tests**

```bash
./gradlew :composeApp:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL with the remaining test suite still green.

- [ ] **Step 3: Commit**

```bash
git add -A composeApp/src/androidUnitTest/kotlin/app/kofipod/
git commit -m "$(cat <<'EOF'
chore(updater): delete updater unit tests

Removes UpdateRepositoryTest and VersionCompareTest before the
source files they exercise get deleted in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Delete updater source files (atomic across common / Android / iOS)

**Files:**
- Delete (commonMain):
  - `composeApp/src/commonMain/kotlin/app/kofipod/update/UpdateChecker.kt`
  - `composeApp/src/commonMain/kotlin/app/kofipod/update/UpdateConfig.kt`
  - `composeApp/src/commonMain/kotlin/app/kofipod/update/UpdateModels.kt`
  - `composeApp/src/commonMain/kotlin/app/kofipod/update/VersionCompare.kt`
  - `composeApp/src/commonMain/kotlin/app/kofipod/update/LocalApkPathStore.kt`
  - `composeApp/src/commonMain/kotlin/app/kofipod/data/repo/UpdateRepository.kt`
  - `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/UpdateActionPort.kt`
- Delete (androidMain):
  - `composeApp/src/androidMain/kotlin/app/kofipod/update/UpdateChecker.android.kt`
  - `composeApp/src/androidMain/kotlin/app/kofipod/update/UpdateInstaller.kt`
  - `composeApp/src/androidMain/kotlin/app/kofipod/update/AndroidLocalApkPathStore.kt`
  - `composeApp/src/androidMain/kotlin/app/kofipod/ui/screens/settings/AndroidUpdateActionPort.kt`
- Delete (iosMain):
  - `composeApp/src/iosMain/kotlin/app/kofipod/update/UpdateChecker.ios.kt`
  - `composeApp/src/iosMain/kotlin/app/kofipod/update/IosLocalApkPathStore.kt`
  - `composeApp/src/iosMain/kotlin/app/kofipod/ui/screens/settings/IosUpdateActionPort.kt`

Because `expect` declarations and their `actual` implementations must be deleted together for compilation to succeed, this lands as one commit.

- [ ] **Step 1: Delete all source files**

```bash
rm composeApp/src/commonMain/kotlin/app/kofipod/update/UpdateChecker.kt \
   composeApp/src/commonMain/kotlin/app/kofipod/update/UpdateConfig.kt \
   composeApp/src/commonMain/kotlin/app/kofipod/update/UpdateModels.kt \
   composeApp/src/commonMain/kotlin/app/kofipod/update/VersionCompare.kt \
   composeApp/src/commonMain/kotlin/app/kofipod/update/LocalApkPathStore.kt \
   composeApp/src/commonMain/kotlin/app/kofipod/data/repo/UpdateRepository.kt \
   composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/UpdateActionPort.kt \
   composeApp/src/androidMain/kotlin/app/kofipod/update/UpdateChecker.android.kt \
   composeApp/src/androidMain/kotlin/app/kofipod/update/UpdateInstaller.kt \
   composeApp/src/androidMain/kotlin/app/kofipod/update/AndroidLocalApkPathStore.kt \
   composeApp/src/androidMain/kotlin/app/kofipod/ui/screens/settings/AndroidUpdateActionPort.kt \
   composeApp/src/iosMain/kotlin/app/kofipod/update/UpdateChecker.ios.kt \
   composeApp/src/iosMain/kotlin/app/kofipod/update/IosLocalApkPathStore.kt \
   composeApp/src/iosMain/kotlin/app/kofipod/ui/screens/settings/IosUpdateActionPort.kt
rmdir composeApp/src/commonMain/kotlin/app/kofipod/update \
      composeApp/src/androidMain/kotlin/app/kofipod/update \
      composeApp/src/iosMain/kotlin/app/kofipod/update
```

(If any of the `rmdir` calls fail because the directory contains additional files added since this plan was written, leave the directory and proceed.)

- [ ] **Step 2: Compile + run tests**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64 :composeApp:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL across all four tasks.

- [ ] **Step 3: Commit**

```bash
git add -A composeApp/src/commonMain composeApp/src/androidMain composeApp/src/iosMain
git commit -m "$(cat <<'EOF'
chore(updater): delete updater source files (common, Android, iOS)

Removes the entire app.kofipod.update package and its
UpdateActionPort siblings under ui/screens/settings, plus the
UpdateRepository in data/repo. All consumers were detached in the
preceding commits, so this lands clean.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Manifest, FileProvider, and backup-rules cleanup

**Files:**
- Modify: `composeApp/src/androidMain/AndroidManifest.xml`
- Delete: `composeApp/src/androidMain/res/xml/file_paths.xml`
- Modify: `composeApp/src/androidMain/res/xml/backup_rules.xml`
- Modify: `composeApp/src/androidMain/res/xml/backup_rules_legacy.xml`

The `REQUEST_INSTALL_PACKAGES` permission and the `FileProvider` were both updater-only. The `kofipod_local.xml` SharedPreferences exclusion in the backup rules was the device-local APK pointer — gone with the updater. Delete `file_paths.xml` since the FileProvider is going away too. (If/when Snippets in Slice 3+ needs a FileProvider for outgoing share URIs, add it back fresh.)

- [ ] **Step 1: Edit `AndroidManifest.xml`**

Replace the file contents with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application
        android:name=".KofipodApplication"
        android:label="${appLabel}"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/backup_rules"
        android:fullBackupContent="@xml/backup_rules_legacy"
        android:usesCleartextTraffic="false"
        android:theme="@style/Theme.Kofipod">
        <activity
            android:name=".MainActivity"
            android:launchMode="singleTask"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name=".playback.KofipodPlaybackService"
            android:foregroundServiceType="mediaPlayback"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaLibraryService" />
                <action android:name="android.media.browse.MediaBrowserService" />
            </intent-filter>
        </service>
        <service
            android:name=".downloads.DownloadService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />
        <provider
            android:name=".playback.auto.ArtworkProvider"
            android:authorities="${applicationId}.artwork"
            android:exported="true"
            android:grantUriPermissions="true" />
        <meta-data
            android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc" />
    </application>
</manifest>
```

(Removed: the `REQUEST_INSTALL_PACKAGES` permission + its three-line comment, and the entire `<provider android:name="androidx.core.content.FileProvider" ...>` block including its `meta-data` child.)

- [ ] **Step 2: Delete `file_paths.xml`**

```bash
rm composeApp/src/androidMain/res/xml/file_paths.xml
```

- [ ] **Step 3: Edit `backup_rules.xml`**

Replace the file contents with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Auto Backup rules for Android 12+ (API 31+).

  cloud-backup: what gets uploaded to the user's Google Drive backup
  (transparent, free, doesn't count against Drive quota, ~25 MB cap per app).

  device-transfer: what gets copied during direct device-to-device transfer
  (cable / Quick Start). Same set as cloud — both restore the library.

  Included: SQLDelight database (subscriptions, lists, episode metadata,
  playback state, settings) and any SharedPreferences. Together this is
  well under 1 MB.

  Not included: downloaded audio under files/downloads/ and the streaming
  playback cache under cache/media/. Those live in domains we never
  <include>, so Auto Backup skips them by default.

  Explicitly excluded:
    * `kofipod_secure` — encrypted store for the user's BYOK Gemini API
      key. The key is per-device by design and must not sync; restoring it
      onto another phone would expose the user's quota to a device they no
      longer control. The user re-pastes their key on the new device if
      they want AI features there.
-->
<data-extraction-rules>
    <cloud-backup>
        <include domain="database" path="." />
        <include domain="sharedpref" path="." />
        <exclude domain="sharedpref" path="kofipod_secure.xml" />
    </cloud-backup>
    <device-transfer>
        <include domain="database" path="." />
        <include domain="sharedpref" path="." />
        <exclude domain="sharedpref" path="kofipod_secure.xml" />
    </device-transfer>
</data-extraction-rules>
```

- [ ] **Step 4: Edit `backup_rules_legacy.xml`**

Replace the file contents with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Legacy fullBackupContent rules for Android 6.0–11 (API 23–30).
  Keep in sync with backup_rules.xml — same include + exclude set.
  Downloads and the streaming cache live outside the included domains,
  so Auto Backup omits them by default.

  Explicitly excluded:
    * `kofipod_secure` — encrypted store for the user's BYOK Gemini API
      key. The key is per-device; restoring it onto another phone would
      expose the user's quota to a device they no longer control.
-->
<full-backup-content>
    <include domain="database" path="." />
    <include domain="sharedpref" path="." />
    <exclude domain="sharedpref" path="kofipod_secure.xml" />
</full-backup-content>
```

- [ ] **Step 5: Compile + assemble debug APK**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64 :composeApp:assembleDebug
```
Expected: BUILD SUCCESSFUL across all four tasks. `assembleDebug` is included to confirm the manifest still parses end-to-end.

- [ ] **Step 6: Manual emulator verification**

Boot the `Pixel_9a` AVD (per `CLAUDE.md`) and install the debug APK:

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_9a &
./gradlew :composeApp:installDebug
```

Open the app, navigate to Settings, and confirm:
- The "App update" section at the top of Settings is **gone**.
- The Downloads section no longer has a "Check for app updates" toggle (only "Daily check for new episodes" and "Download on Wi-Fi only" remain).
- The Library, Backup, Appearance, AI features, Storage, and Scheduler links are intact.

Optional: dump the Settings layout to confirm element bounds:

```bash
~/Library/Android/sdk/platform-tools/adb shell uiautomator dump /sdcard/view.xml
~/Library/Android/sdk/platform-tools/adb pull /sdcard/view.xml /tmp/
```

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/androidMain/AndroidManifest.xml \
        composeApp/src/androidMain/res/xml/backup_rules.xml \
        composeApp/src/androidMain/res/xml/backup_rules_legacy.xml
git rm composeApp/src/androidMain/res/xml/file_paths.xml
git commit -m "$(cat <<'EOF'
chore(updater): drop manifest perm, FileProvider, file_paths, backup rules

REQUEST_INSTALL_PACKAGES + the FileProvider + file_paths.xml were
all updater-only. Backup rules drop the kofipod_local.xml exclusion
(the SharedPreferences file lived in AndroidLocalApkPathStore which
is gone). kofipod_secure.xml exclusion is preserved for the BYOK
Gemini key.

Pre-Slice-0 cleanup complete; codebase is ready for Pro entitlement
plumbing in Slice 0.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Done criteria

- [ ] All ten tasks committed.
- [ ] `./gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64 :composeApp:testDebugUnitTest` is green from a clean checkout of the post-Task-10 commit.
- [ ] `grep -r "app\.kofipod\.update\|UpdateRepository\|UpdateChecker\|UpdateInstaller\|UpdateActionPort\|LocalApkPathStore\|UpdateConfig\|UpdateModels\|VersionCompare\|REQUEST_INSTALL_PACKAGES\|kofipod_local\|EXTRA_OPEN_SETTINGS_FOR_UPDATE\|postUpdateAvailable\|autoUpdateCheck" composeApp/src/` returns **no matches**.
- [ ] Settings screen on `Pixel_9a` no longer shows the "App update" section or the "Check for app updates" toggle.
- [ ] No new dependencies introduced; no schema migration required (existing `KEY_AUTO_UPDATE_CHECK` rows in `SyncMeta` are simply unread going forward).
