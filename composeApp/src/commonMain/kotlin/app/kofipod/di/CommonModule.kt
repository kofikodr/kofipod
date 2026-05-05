// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.ai.AiConfigRepository
import app.kofipod.ai.AiSummaryRepository
import app.kofipod.ai.AudioDiscussSource
import app.kofipod.ai.AudioSummariser
import app.kofipod.ai.AudioUploadCoordinator
import app.kofipod.ai.AudioUploader
import app.kofipod.ai.ChatSummariser
import app.kofipod.ai.DiscussContext
import app.kofipod.ai.DiscussLoad
import app.kofipod.ai.DiscussRepository
import app.kofipod.ai.DiscussSource
import app.kofipod.ai.GeminiClient
import app.kofipod.ai.HttpTranscriptFetcher
import app.kofipod.ai.SummarySource
import app.kofipod.ai.TextSummariser
import app.kofipod.ai.TranscriptDiscussSource
import app.kofipod.ai.TranscriptFetcher
import app.kofipod.backup.BackupController
import app.kofipod.backup.BackupFolderStore
import app.kofipod.backup.BackupRepository
import app.kofipod.backup.DB_SCHEMA_VERSION
import app.kofipod.backup.DbFileBytes
import app.kofipod.backup.StageDbFile
import app.kofipod.config.AppInfo
import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.data.db.DatabaseFactory
import app.kofipod.data.net.NetworkErrorHandler
import app.kofipod.data.net.buildHttpClient
import app.kofipod.data.recommend.PodcastIndexRecommendationApi
import app.kofipod.data.recommend.RecommendationApi
import app.kofipod.data.recommend.RecommendationsRepository
import app.kofipod.data.recommend.RecommendationsSource
import app.kofipod.data.repo.CategoriesRepository
import app.kofipod.data.repo.CategoriesSource
import app.kofipod.data.repo.ChaptersRepository
import app.kofipod.data.repo.DiscoveryRepository
import app.kofipod.data.repo.DiscoverySource
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.RecentlyViewedRepository
import app.kofipod.data.repo.SearchRepository
import app.kofipod.data.repo.SearchSource
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.data.repo.StatsRepository
import app.kofipod.domain.toSummary
import app.kofipod.opml.OpmlController
import app.kofipod.opml.OpmlRepository
import app.kofipod.opml.PodcastFeedLookup
import app.kofipod.pro.BillingClientPort
import app.kofipod.pro.EntitlementCache
import app.kofipod.pro.PaywallRouter
import app.kofipod.pro.ProEntitlementRepository
import app.kofipod.ui.UiEventBus
import app.kofipod.ui.palette.PaletteCache
import app.kofipod.ui.screens.detail.EpisodeDetailViewModel
import app.kofipod.ui.screens.detail.PodcastDetailViewModel
import app.kofipod.ui.screens.downloads.DownloadsViewModel
import app.kofipod.ui.screens.library.LibraryDetailViewModel
import app.kofipod.ui.screens.library.LibraryViewModel
import app.kofipod.ui.screens.library.StarterPackViewModel
import app.kofipod.ui.screens.paywall.PaywallViewModel
import app.kofipod.ui.screens.player.PlayerViewModel
import app.kofipod.ui.screens.scheduler.SchedulerInfoViewModel
import app.kofipod.ui.screens.search.SearchViewModel
import app.kofipod.ui.screens.settings.SettingsViewModel
import app.kofipod.ui.screens.settings.ai.AiSetupViewModel
import app.kofipod.ui.screens.stats.StatsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val commonDataModule =
    module {
        single { buildHttpClient() }
        single { UiEventBus() }
        single { NetworkErrorHandler(get()) }
        single { PodcastIndexApi.create() }
        // Driver is exposed separately from KofipodDatabase so the SAF backup path
        // can issue a `PRAGMA wal_checkpoint(TRUNCATE)` before reading the on-disk
        // file — otherwise recent committed writes still in the `-wal` sidecar
        // would be missing from the snapshot.
        single<app.cash.sqldelight.db.SqlDriver> { get<DatabaseFactory>().createDriver() }
        single { app.kofipod.db.KofipodDatabase(get<app.cash.sqldelight.db.SqlDriver>()) }
        single { LibraryRepository(get()) }
        single { RecentlyViewedRepository(get()) }
        single { SearchRepository(get()) }
        single<SearchSource> { get<SearchRepository>() }
        single { DiscoveryRepository(get()) }
        single<DiscoverySource> { get<DiscoveryRepository>() }
        single { CategoriesRepository() }
        single { ChaptersRepository(db = get(), http = get()) }
        single<CategoriesSource> { get<CategoriesRepository>() }
        single<RecommendationApi> { PodcastIndexRecommendationApi(get()) }
        single { RecommendationsRepository(db = get(), api = get()) }
        single<RecommendationsSource> { get<RecommendationsRepository>() }
        single { EpisodesRepository(get(), get()) }
        single<EpisodeSource> { get<EpisodesRepository>() }
        single { SettingsRepository(get()) }
        single { StatsRepository(get(), get()) }
        // Use the dedicated AI HttpClient — never the shared one. See AiHttpClient.kt
        // for the rationale (Gemini key travels in `?key=`; logging would leak it).
        single { GeminiClient(client = app.kofipod.ai.buildAiHttpClient()) }
        single<app.kofipod.ai.KeyValidator> { get<GeminiClient>() }
        single<TextSummariser> { get<GeminiClient>() }
        single<ChatSummariser> { get<GeminiClient>() }
        // Narrow seam over `client.generateFromAudio`. The full upload pipeline
        // is the coordinator's job; this binding is just the structured-summary
        // call against an already-active file URI.
        single<AudioSummariser> {
            val gemini = get<GeminiClient>()
            AudioSummariser { apiKey, model, fileUri, mimeType, prompt ->
                gemini.generateFromAudio(
                    apiKey = apiKey,
                    model = model,
                    fileUri = fileUri,
                    mimeType = mimeType,
                    prompt = prompt,
                )
            }
        }
        // Production AudioUploader: bridges the coordinator's single-call
        // contract to GeminiClient's two primitives (resumable upload PUT
        // + state poll). Kept as a fun interface so tests can substitute a
        // synchronous fake and exercise the coordinator without the network.
        single<AudioUploader> {
            val gemini = get<GeminiClient>()
            AudioUploader { apiKey, channel, mimeType, sizeBytes, displayName ->
                gemini
                    .uploadAudio(
                        apiKey = apiKey,
                        fileChannel = channel,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        displayName = displayName,
                    ).fold(
                        onSuccess = { uploaded -> gemini.pollUntilActive(apiKey, uploaded.name) },
                        onFailure = { Result.failure(it) },
                    )
            }
        }
        // Coordinator owns the upload-or-reuse decision. Shared between the
        // Summary and Discuss pipelines so a 60 MB upload doesn't have to run
        // twice for the same episode within Gemini's 48h Files API TTL.
        single { AudioUploadCoordinator(uploader = get<AudioUploader>(), db = get()) }
        single {
            AiConfigRepository(
                keyVault = get(),
                settings = get(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
            )
        }
        single<TranscriptFetcher> { HttpTranscriptFetcher(get()) }
        single<app.kofipod.ai.DownloadSource> {
            val downloads = get<DownloadRepository>()
            app.kofipod.ai.DownloadSource(downloads::forEpisodeFlow)
        }
        single {
            AiSummaryRepository(
                db = get(),
                aiConfig = get(),
                summariser = get<TextSummariser>(),
                coordinator = get<AudioUploadCoordinator>(),
                audio = get<AudioSummariser>(),
                transcripts = get<TranscriptFetcher>(),
                episodes = get<EpisodeSource>(),
                downloads = get<app.kofipod.ai.DownloadSource>(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
                scheduler = get<app.kofipod.background.AiSummaryScheduler>(),
            )
        }
        // Composite source: transcript wins when present, audio sibling
        // covers downloaded episodes that lack a publisher transcript.
        // Mirrors AiSummaryRepository.pickSource's preference order so
        // Summary and Discuss agree on which source they're using for any
        // given episode.
        single<DiscussSource> {
            val transcript = TranscriptDiscussSource(transcripts = get<TranscriptFetcher>())
            val audio = AudioDiscussSource()
            DiscussSource { episode, download ->
                val fromTranscript = transcript.loadContext(episode, download)
                if (fromTranscript is DiscussLoad.Success && fromTranscript.context is DiscussContext.NotAvailable) {
                    audio.loadContext(episode, download)
                } else {
                    fromTranscript
                }
            }
        }
        // Bind the cached-summary read as a thin lambda over AiSummaryRepository.
        // Going through a fun interface (rather than handing DiscussRepository a
        // direct AiSummaryRepository reference) keeps the dependency surface
        // tight and lets unit tests stub the read without standing up the whole
        // summary stack.
        single<SummarySource> {
            val summary = get<AiSummaryRepository>()
            SummarySource(summary::cachedFor)
        }
        single {
            DiscussRepository(
                db = get(),
                aiConfig = get(),
                chat = get<ChatSummariser>(),
                source = get<DiscussSource>(),
                coordinator = get<AudioUploadCoordinator>(),
                episodes = get<EpisodeSource>(),
                downloads = get<app.kofipod.ai.DownloadSource>(),
                summaries = get<SummarySource>(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
            )
        }
        single<PodcastFeedLookup> {
            val api = get<PodcastIndexApi>()
            PodcastFeedLookup { url -> api.podcastByFeedUrl(url).toSummary() }
        }
        single { OpmlRepository(library = get(), lookup = get<PodcastFeedLookup>()) }
        single {
            OpmlController(
                repo = get(),
                port = get(),
                bus = get(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
            )
        }
        single {
            BackupRepository(
                dbFileBytes = get<DbFileBytes>(),
                stageDb = get<StageDbFile>(),
                appVersionCode = AppInfo.versionCode,
                appVersionName = AppInfo.versionName,
                dbSchemaVersion = DB_SCHEMA_VERSION,
            )
        }
        single {
            BackupController(
                repo = get(),
                port = get(),
                store = get<BackupFolderStore>(),
                bus = get(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
            )
        }
        single { PaletteCache(port = get()) }
        single { app.kofipod.data.repo.PlaybackRepository(get()) }
        single<CoroutineScope>(qualifier = org.koin.core.qualifier.named("appScope")) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        single {
            ProEntitlementRepository(
                cache = get<EntitlementCache>(),
                port = get<BillingClientPort>(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
            )
        }
        single { PaywallRouter() }
        single { app.kofipod.bookmarks.BookmarkRepository(db = get()) }
        single { app.kofipod.bookmarks.BookmarkComposer() }
        single {
            app.kofipod.diagnostics.DiagnosticsBootstrapper(
                config = get(),
                crashes = get(),
                telemetry = get(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
            )
        }
        single {
            DownloadRepository(
                db = get(),
                engine = get(),
                settings = get(),
                network = get(),
                scope = get(org.koin.core.qualifier.named("appScope")),
                telemetry = get(),
            )
        }

        viewModel {
            SearchViewModel(
                repo = get(),
                categories = get(),
                recommendations = get<RecommendationsSource>(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
                errors = get(),
                telemetry = get(),
            )
        }
        viewModel { LibraryViewModel(get(), get(), get(), get(), get(), get()) }
        viewModel {
            app.kofipod.ui.screens.bookmarks.BookmarksViewModel(
                bookmarks = get(),
                player = get(),
                episodes = get<EpisodeSource>(),
                downloads = get(),
            )
        }
        viewModel { StarterPackViewModel(get(), get()) }
        viewModel {
            PaywallViewModel(
                repo = get(),
                router = get(),
            )
        }
        viewModel { (listId: String?) -> LibraryDetailViewModel(listId, get(), get(), get(), get(), get()) }
        viewModel {
            SettingsViewModel(
                repo = get(),
                scheduler = get(),
                themeSystem = get(),
                playbackCache = get(),
                aiConfig = get(),
                opml = get(),
                pro = get(),
                paywallRouter = get(),
                backup = get(),
                folderStore = get<BackupFolderStore>(),
                diagnostics = get(),
                telemetry = get(),
                library = get(),
                episodes = get<EpisodesRepository>(),
                notifier = get(),
                uiEvents = get(),
            )
        }
        viewModel { AiSetupViewModel(config = get(), client = get(), summaries = get(), discuss = get()) }
        viewModel { (episodeId: String) ->
            app.kofipod.ui.screens.detail.ai.AiSummaryViewModel(
                episodeId = episodeId,
                repo = get(),
            )
        }
        viewModel { (episodeId: String) ->
            app.kofipod.ui.screens.detail.ai.DiscussViewModel(
                episodeId = episodeId,
                repo = get(),
            )
        }
        viewModel { (episodeId: String) ->
            app.kofipod.ui.screens.askgemini.AskGeminiViewModel(
                episodeId = episodeId,
                repo = get(),
                episodes = get<EpisodeSource>(),
                library = get(),
                playback = get(),
                downloads = get(),
                player = get(),
            )
        }
        viewModel { DownloadsViewModel(get()) }
        viewModel { SchedulerInfoViewModel(get()) }
        viewModel { (podcastId: String) ->
            PodcastDetailViewModel(podcastId, get(), get(), get(), get(), get(), get(), get(), get(), get())
        }
        viewModel { (episodeId: String) ->
            EpisodeDetailViewModel(
                episodeId = episodeId,
                episodes = get<EpisodeSource>(),
                library = get(),
                playback = get(),
                downloads = get(),
                player = get(),
                sharer = get(),
                chapters = get(),
                aiConfig = get(),
                bookmarkRepo = get(),
            )
        }
        viewModel {
            PlayerViewModel(
                player = get(),
                playback = get(),
                episodes = get<EpisodeSource>(),
                settings = get(),
                sharer = get(),
                downloads = get(),
                pro = get(),
                paywallRouter = get(),
                bookmarks = get(),
            )
        }
        viewModel { StatsViewModel(get()) }
    }
