// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.di

import com.kofikodr.kofipod.ai.AiConfigRepository
import com.kofikodr.kofipod.ai.AiSummaryRepository
import com.kofikodr.kofipod.ai.AudioDiscussSource
import com.kofikodr.kofipod.ai.AudioSummariser
import com.kofikodr.kofipod.ai.AudioUploadCoordinator
import com.kofikodr.kofipod.ai.AudioUploader
import com.kofikodr.kofipod.ai.ChatSummariser
import com.kofikodr.kofipod.ai.DiscussContext
import com.kofikodr.kofipod.ai.DiscussLoad
import com.kofikodr.kofipod.ai.DiscussRepository
import com.kofikodr.kofipod.ai.DiscussSource
import com.kofikodr.kofipod.ai.GeminiClient
import com.kofikodr.kofipod.ai.HttpTranscriptFetcher
import com.kofikodr.kofipod.ai.SummarySource
import com.kofikodr.kofipod.ai.TextSummariser
import com.kofikodr.kofipod.ai.TranscriptDiscussSource
import com.kofikodr.kofipod.ai.TranscriptFetcher
import com.kofikodr.kofipod.backup.BackupController
import com.kofikodr.kofipod.backup.BackupFolderStore
import com.kofikodr.kofipod.backup.BackupRepository
import com.kofikodr.kofipod.backup.DB_SCHEMA_VERSION
import com.kofikodr.kofipod.backup.DbFileBytes
import com.kofikodr.kofipod.backup.StageDbFile
import com.kofikodr.kofipod.config.AppInfo
import com.kofikodr.kofipod.data.api.GithubReleasesApi
import com.kofikodr.kofipod.data.api.PodcastIndexApi
import com.kofikodr.kofipod.data.db.DatabaseFactory
import com.kofikodr.kofipod.data.net.NetworkErrorHandler
import com.kofikodr.kofipod.data.net.buildHttpClient
import com.kofikodr.kofipod.data.recommend.PodcastIndexRecommendationApi
import com.kofikodr.kofipod.data.recommend.RecommendationApi
import com.kofikodr.kofipod.data.recommend.RecommendationsRepository
import com.kofikodr.kofipod.data.recommend.RecommendationsSource
import com.kofikodr.kofipod.data.repo.CategoriesRepository
import com.kofikodr.kofipod.data.repo.CategoriesSource
import com.kofikodr.kofipod.data.repo.ChaptersRepository
import com.kofikodr.kofipod.data.repo.DiscoveryRepository
import com.kofikodr.kofipod.data.repo.DiscoverySource
import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.EpisodesRepository
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.data.repo.RecentlyViewedRepository
import com.kofikodr.kofipod.data.repo.SearchRepository
import com.kofikodr.kofipod.data.repo.SearchSource
import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.data.repo.StatsRepository
import com.kofikodr.kofipod.data.repo.UpdateRepository
import com.kofikodr.kofipod.domain.toSummary
import com.kofikodr.kofipod.opml.OpmlController
import com.kofikodr.kofipod.opml.OpmlRepository
import com.kofikodr.kofipod.opml.PodcastFeedLookup
import com.kofikodr.kofipod.playlists.EpisodeFactsRepository
import com.kofikodr.kofipod.playlists.EpisodeFactsRepositoryImpl
import com.kofikodr.kofipod.playlists.PredicateEvaluator
import com.kofikodr.kofipod.playlists.SmartPlaylistRepository
import com.kofikodr.kofipod.playlists.SmartPlaylistRepositoryImpl
import com.kofikodr.kofipod.playlists.SmartPlaylistResolver
import com.kofikodr.kofipod.pro.BillingClientPort
import com.kofikodr.kofipod.pro.EntitlementCache
import com.kofikodr.kofipod.pro.PaywallRouter
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import com.kofikodr.kofipod.ui.UiEventBus
import com.kofikodr.kofipod.ui.palette.PaletteCache
import com.kofikodr.kofipod.ui.screens.detail.EpisodeDetailViewModel
import com.kofikodr.kofipod.ui.screens.detail.PodcastDetailViewModel
import com.kofikodr.kofipod.ui.screens.downloads.DownloadsViewModel
import com.kofikodr.kofipod.ui.screens.library.LibraryDetailViewModel
import com.kofikodr.kofipod.ui.screens.library.LibraryViewModel
import com.kofikodr.kofipod.ui.screens.library.StarterPackViewModel
import com.kofikodr.kofipod.ui.screens.paywall.PaywallViewModel
import com.kofikodr.kofipod.ui.screens.player.PlayerViewModel
import com.kofikodr.kofipod.ui.screens.scheduler.SchedulerInfoViewModel
import com.kofikodr.kofipod.ui.screens.search.SearchViewModel
import com.kofikodr.kofipod.ui.screens.settings.SettingsViewModel
import com.kofikodr.kofipod.ui.screens.settings.UpdateActionPort
import com.kofikodr.kofipod.ui.screens.settings.ai.AiSetupViewModel
import com.kofikodr.kofipod.ui.screens.stats.StatsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
        single { com.kofikodr.kofipod.db.KofipodDatabase(get<app.cash.sqldelight.db.SqlDriver>()) }
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
        single { com.kofikodr.kofipod.data.repo.RemoteEpisodeCache() }
        single { SettingsRepository(get()) }
        single { StatsRepository(get(), get()) }
        single { UpdateRepository(settings = get(), localApk = get()) }
        single { GithubReleasesApi(get()) }
        // Use the dedicated AI HttpClient — never the shared one. See AiHttpClient.kt
        // for the rationale (Gemini key travels in `?key=`; logging would leak it).
        single { GeminiClient(client = com.kofikodr.kofipod.ai.buildAiHttpClient()) }
        single<com.kofikodr.kofipod.ai.KeyValidator> { get<GeminiClient>() }
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
            AudioUploader { apiKey, localPath, mimeType, sizeBytes, displayName, onProgress ->
                gemini
                    .uploadAudio(
                        apiKey = apiKey,
                        localPath = localPath,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        displayName = displayName,
                        onProgress = onProgress,
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
        single<com.kofikodr.kofipod.ai.DownloadSource> {
            val downloads = get<DownloadRepository>()
            com.kofikodr.kofipod.ai.DownloadSource(downloads::forEpisodeFlow)
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
                downloads = get<com.kofikodr.kofipod.ai.DownloadSource>(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
                scheduler = get<com.kofikodr.kofipod.background.AiSummaryScheduler>(),
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
                downloads = get<com.kofikodr.kofipod.ai.DownloadSource>(),
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
        single { com.kofikodr.kofipod.data.repo.PlaybackRepository(get()) }
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
        single { com.kofikodr.kofipod.bookmarks.BookmarkRepository(db = get()) }
        single { com.kofikodr.kofipod.bookmarks.BookmarkComposer() }
        // Snippets (Slice 3) — common-side bindings. Android-context-bound
        // siblings (FileChecker, SnippetExporter, SnippetRenderLauncher) live
        // in the Android Koin module.
        single { com.kofikodr.kofipod.snippets.SnippetSourceResolver(get()) }
        single { com.kofikodr.kofipod.snippets.SnippetRepository(get()) }
        single { com.kofikodr.kofipod.snippets.FileSizer() }
        // Snippet Slice 4 — caption / waveform / format-routing collaborators.
        // CaptionDeps is a seam so SnippetCaptionRepository stays unit-testable
        // without MockK. The production adapter delegates to the four concrete
        // classes listed below; Task 16 may extract this into its own binding
        // if the DI module grows too wide.
        single<com.kofikodr.kofipod.snippets.CaptionDeps> {
            val downloads: DownloadRepository = get()
            val episodes: EpisodesRepository = get()
            val coordinator: AudioUploadCoordinator = get()
            val gemini: GeminiClient = get()
            val config: AiConfigRepository = get()
            object : com.kofikodr.kofipod.snippets.CaptionDeps {
                override suspend fun isAudioReadyFor(episodeId: String): Boolean = !downloads.localPathFor(episodeId).isNullOrBlank()

                override suspend fun currentGeminiKey(): String? = config.currentKey()

                override suspend fun transcribeForCaption(
                    episodeId: String,
                    prompt: String,
                ): Result<String> =
                    runCatching {
                        val key = config.currentKey() ?: error("no Gemini key")
                        val episode = episodes.episodeNow(episodeId) ?: error("no episode")
                        val download = downloads.rowFor(episodeId) ?: error("no download row")
                        val acquired = coordinator.acquire(key, episode, download).getOrThrow()
                        gemini
                            .transcribeFromAudio(
                                apiKey = key,
                                model = com.kofikodr.kofipod.ai.GeminiModel.Flash,
                                fileUri = acquired.fileUri,
                                mimeType = acquired.mimeType,
                                prompt = prompt,
                            ).getOrThrow()
                    }
            }
        }
        single { com.kofikodr.kofipod.snippets.SnippetCaptionPicker() }
        single { com.kofikodr.kofipod.snippets.WaveformGenerator() }
        single {
            com.kofikodr.kofipod.snippets.SnippetCaptionRepository(
                get<com.kofikodr.kofipod.data.repo.EpisodeSource>(),
                get<com.kofikodr.kofipod.ai.TranscriptFetcher>(),
                get<com.kofikodr.kofipod.snippets.CaptionDeps>(),
                get<com.kofikodr.kofipod.snippets.SnippetCaptionPicker>(),
            )
        }
        // ────────────────────────────────────────────────────────────────────
        // PKM (Slice 5 + 6) — Markdown export + connection-bound sinks
        // ────────────────────────────────────────────────────────────────────
        single<com.kofikodr.kofipod.pkm.MarkdownFormatter> { com.kofikodr.kofipod.pkm.MarkdownFormatterImpl() }

        // Zero-auth sinks — bound as their concrete types so the coordinator factory
        // can resolve them unambiguously without named qualifiers (Defect 4 resolution).
        single { com.kofikodr.kofipod.pkm.sinks.ClipboardSink(get()) }
        single { com.kofikodr.kofipod.pkm.sinks.ShareFileSink(get(), get()) }

        // Connection-bound sinks — wired here for the first time (Tasks 8 + 10 created
        // the classes but deferred DI to Task 11 per the plan).
        single { com.kofikodr.kofipod.pkm.sinks.ReadwiseClient(get()) }

        // ObsidianSink(writer, connectionLoader): writer = ObsidianFolderWriterImpl (platform actual),
        // connectionLoader = lambda over PkmConnectionRepository.
        single {
            val connRepo = get<com.kofikodr.kofipod.pkm.connections.PkmConnectionRepository>()
            com.kofikodr.kofipod.pkm.sinks.ObsidianSink(
                writer = get<com.kofikodr.kofipod.pkm.sinks.ObsidianFolderWriterImpl>(),
                connectionLoader = {
                    connRepo.observe(com.kofikodr.kofipod.pkm.connections.ConnectionKind.Obsidian).first()
                },
            )
        }
        single {
            val connRepo = get<com.kofikodr.kofipod.pkm.connections.PkmConnectionRepository>()
            com.kofikodr.kofipod.pkm.sinks.ReadwiseSink(
                client = get(),
                vault = get(),
                connectionLoader = {
                    connRepo.observe(com.kofikodr.kofipod.pkm.connections.ConnectionKind.Readwise).first()
                },
            )
        }

        // SinkRegistry — maps ConnectionKind → ExportSink for connection-bound destinations.
        single {
            com.kofikodr.kofipod.pkm.sinks.SinkRegistry(
                mapOf(
                    com.kofikodr.kofipod.pkm.connections.ConnectionKind.Obsidian to get<com.kofikodr.kofipod.pkm.sinks.ObsidianSink>(),
                    com.kofikodr.kofipod.pkm.connections.ConnectionKind.Readwise to get<com.kofikodr.kofipod.pkm.sinks.ReadwiseSink>(),
                ),
            )
        }

        // ExportLogRepository — interface bound to the SQLDelight-backed impl.
        single<com.kofikodr.kofipod.pkm.connections.ExportLogRepository> {
            com.kofikodr.kofipod.pkm.connections.ExportLogRepositoryImpl(get())
        }

        // PkmExportScheduler — bound by the platform module: AndroidPkmExportScheduler
        // (WorkManager) on Android, IosPkmExportScheduler (no-op) on iOS.

        // PkmConnectionRepository — Slice 6 wiring; required by ObsidianSink and ReadwiseSink loaders.
        single { com.kofikodr.kofipod.pkm.connections.PkmConnectionRepository(db = get(), vault = get()) }

        // PkmExportDeps adapter — five-method seam over the production repos. Lives
        // in DI rather than its own file so the only extra surface is one Koin block.
        single<com.kofikodr.kofipod.pkm.PkmExportDeps> {
            val snippetRepo: com.kofikodr.kofipod.snippets.SnippetRepository = get()
            val bookmarkRepo: com.kofikodr.kofipod.bookmarks.BookmarkRepository = get()
            val summaryRepo: com.kofikodr.kofipod.ai.AiSummaryRepository = get()
            val episodeRepo: com.kofikodr.kofipod.data.repo.EpisodesRepository = get()
            val libraryRepo: com.kofikodr.kofipod.data.repo.LibraryRepository = get()
            object : com.kofikodr.kofipod.pkm.PkmExportDeps {
                override suspend fun snippetById(id: String) = snippetRepo.selectById(id)

                override suspend fun bookmarkById(id: String) = bookmarkRepo.selectById(id)

                override suspend fun summaryFor(episodeId: String) = summaryRepo.cachedNow(episodeId)

                override fun episode(id: String) = episodeRepo.episodeNow(id)

                override fun podcast(id: String) = libraryRepo.podcastNow(id)
            }
        }

        single {
            com.kofikodr.kofipod.pkm.PkmExportCoordinator(
                deps = get(),
                formatter = get(),
                sinks = get(),
                exportLog = get(),
                scheduler = get(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
                clipboardSink = get<com.kofikodr.kofipod.pkm.sinks.ClipboardSink>(),
                shareFileSink = get<com.kofikodr.kofipod.pkm.sinks.ShareFileSink>(),
            )
        }
        // ────────────────────────────────────────────────────────────────────
        // Smart Playlists (Slice 7) — episode-facts read model + predicate
        // evaluator + repository + resolver. Bound interface-first so test
        // fakes can substitute via Koin override (see Slice 6 patterns).
        // SmartPlaylistResolver.clock defaults to Clock.System; positional
        // get()s fill in the non-defaulted facts/evaluator deps only.
        // ────────────────────────────────────────────────────────────────────
        single<EpisodeFactsRepository> { EpisodeFactsRepositoryImpl(get()) }
        single { PredicateEvaluator() }
        single<SmartPlaylistRepository> { SmartPlaylistRepositoryImpl(get()) }
        single { SmartPlaylistResolver(facts = get(), evaluator = get()) }

        single { com.kofikodr.kofipod.search.LibrarySearchRepository(driver = get()) }
        single {
            com.kofikodr.kofipod.diagnostics.DiagnosticsBootstrapper(
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
                fileChecker = get(),
                uiEvents = get(),
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
        // 7 positional deps: repo, episodes, opml, pro, paywallRouter,
        // smartPlaylistRepo, smartPlaylistResolver. Bump in lockstep with the
        // LibraryViewModel ctor declaration order — Koin throws at runtime if
        // these drift.
        viewModel { LibraryViewModel(get(), get(), get(), get(), get(), get(), get()) }
        viewModel {
            com.kofikodr.kofipod.ui.screens.bookmarks.BookmarksViewModel(
                bookmarks = get(),
                player = get(),
                episodes = get<EpisodeSource>(),
                downloads = get(),
                pkmExport = get(),
                paywallRouter = get(),
                pro = get(),
            )
        }
        viewModel { com.kofikodr.kofipod.ui.screens.search.LibrarySearchViewModel(get()) }
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
                updateChecker = get(),
                updateRepo = get(),
                updateActions = get<UpdateActionPort>(),
                aiConfig = get(),
                errors = get(),
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
            com.kofikodr.kofipod.ui.screens.detail.ai.AiSummaryViewModel(
                episodeId = episodeId,
                repo = get(),
            )
        }
        viewModel { (episodeId: String) ->
            com.kofikodr.kofipod.ui.screens.detail.ai.DiscussViewModel(
                episodeId = episodeId,
                repo = get(),
            )
        }
        viewModel { (episodeId: String) ->
            com.kofikodr.kofipod.ui.screens.askgemini.AskGeminiViewModel(
                episodeId = episodeId,
                repo = get(),
                episodes = get<EpisodeSource>(),
                library = get(),
                playback = get(),
                downloads = get(),
                player = get(),
            )
        }
        viewModel { DownloadsViewModel(get(), get()) }
        viewModel { SchedulerInfoViewModel(get()) }
        viewModel { (podcastId: String) ->
            PodcastDetailViewModel(podcastId, get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
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
                snippetRepo = get(),
                fileSizer = get(),
                pkmExport = get(),
                paywallRouter = get(),
                pro = get(),
                remoteCache = get(),
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
                snippets = get(),
            )
        }
        viewModel { (snippetId: String) ->
            com.kofikodr.kofipod.ui.screens.snippet.SnippetEditorViewModel(
                snippetId = snippetId,
                snippets = get(),
                launcher = get(),
                player = get(),
                waveformGen = get(),
                episodes = get<EpisodeSource>(),
                library = get(),
                downloads = get(),
            )
        }
        viewModel { StatsViewModel(get()) }
        viewModel {
            com.kofikodr.kofipod.ui.screens.connections.ConnectionsViewModel(
                connections = com.kofikodr.kofipod.ui.screens.connections.PkmConnectionsSource(get()),
                readwiseClient = get(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
            )
        }
        // Slice 7 Task 9 — Smart Playlist editor. `params.getOrNull<String>()` lets
        // create-mode (no parametersOf arg) resolve to null and edit-mode (passing the
        // id via parametersOf) resolve to a String. Mirrors the LibraryDetailViewModel
        // factory pattern above.
        viewModel { (playlistId: String?, initialName: String?) ->
            com.kofikodr.kofipod.ui.screens.playlists.SmartPlaylistEditorViewModel(
                playlists = get(),
                resolver = get(),
                library = get(),
                playlistId = playlistId,
                initialName = initialName,
            )
        }
        // Slice 7 Task 10 — Smart Playlist detail. Required `playlistId` is passed via
        // `parametersOf(playlistId)` from the screen's `koinViewModel { ... }` block.
        viewModel { params ->
            com.kofikodr.kofipod.ui.screens.playlists.SmartPlaylistDetailViewModel(
                playlists = get(),
                resolver = get(),
                playlistId = params.get<String>(),
            )
        }
    }
