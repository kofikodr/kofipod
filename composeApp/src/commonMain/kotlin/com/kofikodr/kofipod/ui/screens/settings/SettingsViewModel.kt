// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.ai.AiConfigRepository
import com.kofikodr.kofipod.ai.GeminiModel
import com.kofikodr.kofipod.background.Notifier
import com.kofikodr.kofipod.background.Scheduler
import com.kofikodr.kofipod.backup.BackupAction
import com.kofikodr.kofipod.backup.BackupController
import com.kofikodr.kofipod.backup.BackupFolderStore
import com.kofikodr.kofipod.backup.RestoreValidation
import com.kofikodr.kofipod.data.api.PodcastIndexConfigRepository
import com.kofikodr.kofipod.data.api.PodcastIndexCredentials
import com.kofikodr.kofipod.data.net.NetworkErrorHandler
import com.kofikodr.kofipod.data.repo.EpisodesRepository
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.data.repo.UpdateRepository
import com.kofikodr.kofipod.data.repo.UpdateUiState
import com.kofikodr.kofipod.data.search.ItunesStorefront
import com.kofikodr.kofipod.data.search.ItunesStorefrontStore
import com.kofikodr.kofipod.diagnostics.DiagnosticsConfigRepository
import com.kofikodr.kofipod.opml.OpmlAction
import com.kofikodr.kofipod.opml.OpmlController
import com.kofikodr.kofipod.playback.PlaybackCache
import com.kofikodr.kofipod.pro.PaywallRouter
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import com.kofikodr.kofipod.ui.UiEvent
import com.kofikodr.kofipod.ui.UiEventBus
import com.kofikodr.kofipod.ui.theme.KofipodThemeMode
import com.kofikodr.kofipod.ui.theme.ThemeSystem
import com.kofikodr.kofipod.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase of the user-driven update flow ("user tapped the button"). Distinct from
 * [UpdateUiState] which describes "what the system knows" (latest version, dismissed,
 * downloaded). Together they fully describe the banner's appearance.
 */
sealed interface UpdateAction {
    data object Idle : UpdateAction

    data object Checking : UpdateAction

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdateAction

    data class Error(val message: String) : UpdateAction
}

data class SettingsUiState(
    val themeMode: KofipodThemeMode = KofipodThemeMode.System,
    val dailyCheck: Boolean = true,
    val wifiOnly: Boolean = true,
    val autoUpdateCheck: Boolean = true,
    val storageCapBytes: Long = SettingsRepository.DEFAULT_CAP_BYTES,
    val streamCacheCapBytes: Long = SettingsRepository.DEFAULT_STREAM_CACHE_CAP_BYTES,
    val streamCacheUsedBytes: Long = 0L,
    val skipForward: Int = 30,
    val skipBack: Int = 10,
    val update: UpdateUiState = UpdateUiState.UpToDate(null),
    val updateAction: UpdateAction = UpdateAction.Idle,
    val aiConnected: Boolean = false,
    val aiModel: GeminiModel = GeminiModel.Flash,
    val opmlAction: OpmlAction = OpmlAction.Idle,
    /** True on FOSS builds (key blank at compile time). Drives the conditional PI settings row. */
    val showPodcastIndexByok: Boolean = PodcastIndexCredentials.key.isBlank(),
    val podcastIndexConnected: Boolean = false,
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
    val itunesStorefront: ItunesStorefront = ItunesStorefront.Default,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: Scheduler,
    private val themeSystem: ThemeSystem,
    private val playbackCache: PlaybackCache,
    private val updateChecker: UpdateChecker,
    private val updateRepo: UpdateRepository,
    // Wrapped in an interface so commonMain VM stays Android-free.
    private val updateActions: UpdateActionPort,
    private val aiConfig: AiConfigRepository,
    private val piConfig: PodcastIndexConfigRepository,
    private val errors: NetworkErrorHandler,
    private val opml: OpmlController,
    private val pro: ProEntitlementRepository,
    private val paywallRouter: PaywallRouter,
    private val backup: BackupController,
    private val folderStore: BackupFolderStore,
    private val diagnostics: DiagnosticsConfigRepository,
    private val telemetry: com.kofikodr.kofipod.diagnostics.Telemetry,
    private val library: LibraryRepository,
    private val episodes: EpisodesRepository,
    private val notifier: Notifier,
    private val uiEvents: UiEventBus,
    private val itunesStorefronts: ItunesStorefrontStore,
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
    private val updateActionFlow = MutableStateFlow<UpdateAction>(UpdateAction.Idle)

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
                repo.autoUpdateCheckEnabled(),
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
                    autoUpdateCheck = values[8] as Boolean,
                )
            },
            combine(
                updateRepo.state(),
                updateActionFlow,
                aiConfig.isKeyConfigured(),
                aiConfig.model(),
                opml.action,
            ) { updateState, action, aiConnected, aiModel, opmlAction ->
                AiAndUpdateState(updateState, action, aiConnected, aiModel, opmlAction)
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
                update = aiOpml.updateState,
                updateAction = aiOpml.action,
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
        }
            // Chained because outer combine already takes 5 typed slices and adding
            // a 6th would force vararg form and lose the inference.
            .combine(itunesStorefronts.storefrontFlow()) { settings, storefront ->
                settings.copy(itunesStorefront = storefront)
            }
            // Chained for the same reason: keeps inference intact and avoids the vararg form.
            .combine(piConfig.isConfigured()) { settings, piConnected ->
                settings.copy(podcastIndexConnected = piConnected)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

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

    fun setAutoUpdateCheck(on: Boolean) = viewModelScope.launch { repo.setAutoUpdateCheckEnabled(on) }

    fun setItunesStorefront(storefront: ItunesStorefront) {
        itunesStorefronts.setStorefront(storefront)
    }

    fun setCap(bytes: Long) = viewModelScope.launch { repo.setStorageCapBytes(bytes) }

    fun setStreamCacheCap(bytes: Long) = viewModelScope.launch { repo.setStreamCacheCapBytes(bytes) }

    fun setSkipForward(sec: Int) = viewModelScope.launch { repo.setSkipForward(sec) }

    fun setSkipBack(sec: Int) = viewModelScope.launch { repo.setSkipBack(sec) }

    fun checkForUpdates() =
        viewModelScope.launch {
            updateActionFlow.value = UpdateAction.Checking
            runCatching { updateChecker.check(force = true) }
                .onSuccess { updateActionFlow.value = UpdateAction.Idle }
                .onFailure {
                    val msg = errors.handle(it, hasCachedData = false, fallback = "network error") ?: "network error"
                    updateActionFlow.value = UpdateAction.Error("Check failed: $msg")
                }
        }

    fun downloadUpdate() {
        val available = (state.value.update as? UpdateUiState.Available)?.info ?: return
        viewModelScope.launch {
            updateActionFlow.value = UpdateAction.Downloading(0L, available.apkSizeBytes)
            runCatching {
                updateActions.downloadApk(available) { downloaded, total ->
                    updateActionFlow.value = UpdateAction.Downloading(downloaded, total)
                }
            }
                .onSuccess { updateActionFlow.value = UpdateAction.Idle }
                .onFailure {
                    val msg = errors.handle(it, hasCachedData = false, fallback = "unknown") ?: "unknown"
                    updateActionFlow.value = UpdateAction.Error("Download failed: $msg")
                }
        }
    }

    fun installUpdate() {
        val ready = state.value.update as? UpdateUiState.ReadyToInstall ?: return
        if (!updateActions.canInstall()) {
            updateActions.openInstallPermissionSettings()
            return
        }
        updateActions.installApk(ready.apkPath)
    }

    fun dismissUpdate() = viewModelScope.launch { updateRepo.dismissCurrentVersion() }

    fun importOpml() = opml.importOpml()

    fun exportOpml() = opml.exportOpml()

    fun openPaywall() = paywallRouter.requestPaywall("paywall_settings")

    fun tapConnections(onPro: () -> Unit) {
        if (pro.state.value is ProEntitlement.Pro) onPro() else paywallRouter.requestPaywall("paywall_connections")
    }

    fun restorePurchase() {
        viewModelScope.launch {
            purchaseRestoreInFlight.value = true
            pro.restorePurchases()
            purchaseRestoreInFlight.value = false
        }
    }

    /**
     * Validates [code] against the embedded reviewer-unlock hash. On success,
     * emits a confirmation snackbar; on failure, an error snackbar. Used by the
     * hidden tap-version-7× affordance to grant Pro for Play Store / sideload
     * review without a real purchase.
     */
    fun submitReviewerUnlock(code: String) =
        viewModelScope.launch {
            val ok = pro.applyReviewerUnlock(code.trim())
            if (ok) {
                telemetry.track(com.kofikodr.kofipod.diagnostics.TelemetryEvent.ReviewerUnlockApplied)
            }
            uiEvents.emit(
                UiEvent.Snackbar(
                    if (ok) "Pro unlocked for review" else "Invalid code",
                ),
            )
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

private data class AiAndUpdateState(
    val updateState: UpdateUiState,
    val action: UpdateAction,
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
