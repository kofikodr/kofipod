// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.ai.AiConfigRepository
import app.kofipod.ai.GeminiModel
import app.kofipod.background.Notifier
import app.kofipod.background.Scheduler
import app.kofipod.backup.BackupAction
import app.kofipod.backup.BackupController
import app.kofipod.backup.BackupFolderStore
import app.kofipod.backup.RestoreValidation
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.diagnostics.DiagnosticsConfigRepository
import app.kofipod.opml.OpmlAction
import app.kofipod.opml.OpmlController
import app.kofipod.playback.PlaybackCache
import app.kofipod.pro.PaywallRouter
import app.kofipod.pro.ProEntitlement
import app.kofipod.pro.ProEntitlementRepository
import app.kofipod.ui.UiEvent
import app.kofipod.ui.UiEventBus
import app.kofipod.ui.theme.KofipodThemeMode
import app.kofipod.ui.theme.ThemeSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
    val proEntitlement: ProEntitlement = ProEntitlement.Unknown,
    val restoreInFlight: Boolean = false,
    val backupAction: BackupAction = BackupAction.Idle,
    val backupFolderUri: String? = null,
    val backupFolderName: String? = null,
    val lastBackupAtMs: Long? = null,
    val pendingRestoreConfirm: RestoreValidation.Valid? = null,
    val crashesEnabled: Boolean = true,
    val usageEnabled: Boolean = true,
    val disclosureAcknowledged: Boolean = false,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: Scheduler,
    private val themeSystem: ThemeSystem,
    private val playbackCache: PlaybackCache,
    private val aiConfig: AiConfigRepository,
    private val opml: OpmlController,
    private val pro: ProEntitlementRepository,
    private val paywallRouter: PaywallRouter,
    private val backup: BackupController,
    private val folderStore: BackupFolderStore,
    private val diagnostics: DiagnosticsConfigRepository,
    private val telemetry: app.kofipod.diagnostics.Telemetry,
    private val library: LibraryRepository,
    private val episodes: EpisodesRepository,
    private val notifier: Notifier,
    private val uiEvents: UiEventBus,
) : ViewModel() {
    // Refreshes the displayed cache usage once per second while Settings is visible.
    private val cacheUsedFlow =
        flow {
            while (true) {
                emit(playbackCache.sizeBytes())
                delay(1_000)
            }
        }

    private val purchaseRestoreInFlight = MutableStateFlow(false)

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
            combine(
                backup.action,
                folderStore.treeUriFlow(),
                folderStore.lastBackupAtFlow(),
                backup.pendingRestoreConfirm,
            ) { backupAction, treeUri, lastBackupAtMs, pendingRestoreConfirm ->
                BackupSlice(
                    action = backupAction,
                    treeUri = treeUri,
                    folderName = treeUri?.let { folderStore.displayNameForTreeUri(it) },
                    lastBackupAtMs = lastBackupAtMs,
                    pendingRestoreConfirm = pendingRestoreConfirm,
                )
            },
            combine(
                diagnostics.crashesEnabled,
                diagnostics.usageEnabled,
                diagnostics.disclosureAcknowledged,
            ) { crashes, usage, ack ->
                DiagnosticsState(crashes, usage, ack)
            },
            combine(
                pro.state,
                purchaseRestoreInFlight,
            ) { entitlement, restoring ->
                ProSettingsBlock(entitlement, restoring)
            },
        ) { base, aiOpml, backupSlice, diag, proBlock ->
            base.copy(
                aiConnected = aiOpml.aiConnected,
                aiModel = aiOpml.aiModel,
                opmlAction = aiOpml.opmlAction,
                backupAction = backupSlice.action,
                backupFolderUri = backupSlice.treeUri,
                backupFolderName = backupSlice.folderName,
                lastBackupAtMs = backupSlice.lastBackupAtMs,
                pendingRestoreConfirm = backupSlice.pendingRestoreConfirm,
                crashesEnabled = diag.crashesEnabled,
                usageEnabled = diag.usageEnabled,
                disclosureAcknowledged = diag.disclosureAcknowledged,
                proEntitlement = proBlock.entitlement,
                restoreInFlight = proBlock.restoreInFlight,
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

    fun openPaywall() = paywallRouter.requestPaywall("paywall_settings")

    fun restorePurchase() {
        viewModelScope.launch {
            purchaseRestoreInFlight.value = true
            pro.restorePurchases()
            purchaseRestoreInFlight.value = false
        }
    }

    fun chooseBackupFolder() = backup.chooseFolder()

    fun backupNow() = backup.runBackup()

    fun restoreFromBackup() = backup.runRestore()

    fun confirmRestore() = backup.confirmRestore()

    fun cancelRestoreConfirm() = backup.cancelRestoreConfirm()

    fun dismissBackupError() = backup.dismissError()

    fun setCrashesEnabled(enabled: Boolean) = viewModelScope.launch { diagnostics.setCrashesEnabled(enabled) }

    fun setUsageEnabled(enabled: Boolean) = viewModelScope.launch { diagnostics.setUsageEnabled(enabled) }

    fun acknowledgeDisclosure() = viewModelScope.launch { diagnostics.acknowledgeDisclosure() }

    /**
     * Debug entry point: throws an unhandled exception synchronously on the
     * main thread so the Sentry uncaught-exception handler captures it. Used
     * to verify GlitchTip end-to-end ingestion. The process will die; Sentry
     * persists the event to disk before exit and uploads it on next launch.
     */
    fun forceCrash(): Nothing = error("Kofipod force-crash test from Settings → Debug")

    /**
     * Debug entry point: forces a telemetry smoke-test event to Aptabase,
     * bypassing the disclosure gate and the `enabled` flag. Surfaces the
     * SDK's status (init result, exception class+message if any) via
     * snackbar so we can tell apart "gating broken" from "SDK unreachable
     * from this process" during on-device verification.
     */
    fun debugSendTestTelemetry() =
        viewModelScope.launch {
            val result = telemetry.debugSmokeTest("debug_smoke_test")
            uiEvents.emit(UiEvent.Snackbar(result))
        }

    /**
     * Debug entry point: post the rich single-episode notification using a randomly
     * picked episode from a randomly picked subscribed podcast. Surfaces a snackbar
     * if there's nothing to test with (no podcasts, or none with episodes yet).
     */
    fun sendTestSingleNotification() =
        viewModelScope.launch(Dispatchers.Default) {
            val podcasts = library.podcastsNow()
            if (podcasts.isEmpty()) {
                uiEvents.emit(UiEvent.Snackbar("Subscribe to a podcast to test notifications"))
                return@launch
            }
            // Try up to N random podcasts so a single episode-less subscription doesn't
            // poison the run. After that, fall back to the first non-empty one.
            val shuffled = podcasts.shuffled()
            val match =
                shuffled.firstNotNullOfOrNull { p ->
                    val eps = episodes.episodesNow(p.id)
                    if (eps.isEmpty()) null else p to eps.random()
                }
            if (match == null) {
                uiEvents.emit(UiEvent.Snackbar("No episodes available to test with"))
                return@launch
            }
            val (podcast, ep) = match
            val art = ep.imageUrl.takeIf { it.isNotBlank() } ?: podcast.artworkUrl
            notifier.postSingleNewEpisode(
                podcastTitle = podcast.title,
                episodeTitle = ep.title,
                episodeId = ep.id,
                artworkUrl = art.takeIf { it.isNotBlank() },
            )
        }

    /**
     * Debug entry point: post the generic many-episodes notification. Counts are
     * derived from real subscribed podcasts so the copy ("N new episodes from M
     * shows") looks plausible; the underlying tap intent is fixed (open Library).
     */
    fun sendTestManyNotification() =
        viewModelScope.launch(Dispatchers.Default) {
            val podcasts = library.podcastsNow()
            if (podcasts.isEmpty()) {
                uiEvents.emit(UiEvent.Snackbar("Subscribe to a podcast to test notifications"))
                return@launch
            }
            val shows = podcasts.size.coerceAtMost(MAX_TEST_SHOWS)
            notifier.postManyNewEpisodes(totalEpisodes = shows + 1, totalShows = shows)
        }
}

private const val MAX_TEST_SHOWS = 3

private data class AiAndOpmlState(
    val aiConnected: Boolean,
    val aiModel: GeminiModel,
    val opmlAction: OpmlAction,
)

private data class BackupSlice(
    val action: BackupAction,
    val treeUri: String?,
    val folderName: String?,
    val lastBackupAtMs: Long?,
    val pendingRestoreConfirm: RestoreValidation.Valid?,
)

private data class DiagnosticsState(
    val crashesEnabled: Boolean,
    val usageEnabled: Boolean,
    val disclosureAcknowledged: Boolean,
)

private data class ProSettingsBlock(
    val entitlement: ProEntitlement,
    val restoreInFlight: Boolean,
)
