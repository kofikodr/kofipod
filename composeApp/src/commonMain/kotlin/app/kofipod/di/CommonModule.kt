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
import app.kofipod.data.api.GithubReleasesApi
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
import app.kofipod.data.repo.UpdateRepository
import app.kofipod.domain.toSummary
import app.kofipod.opml.OpmlController
import app.kofipod.opml.OpmlRepository
import app.kofipod.opml.PodcastFeedLookup
import app.kofipod.playlists.EpisodeFactsRepository
import app.kofipod.playlists.EpisodeFactsRepositoryImpl
import app.kofipod.playlists.PredicateEvaluator
import app.kofipod.playlists.SmartPlaylistRepository
import app.kofipod.playlists.SmartPlaylistRepositoryImpl
import app.kofipod.playlists.SmartPlaylistResolver
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
import app.kofipod.ui.screens.settings.UpdateActionPort
import app.kofipod.ui.screens.settings.ai.AiSetupViewModel
import app.kofipod.ui.screens.stats.StatsViewModel
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
        single { UpdateRepository(settings = get(), localApk = get()) }
        single { GithubReleasesApi(get()) }
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
        // Snippets (Slice 3) — common-side bindings. Android-context-bound
        // siblings (FileChecker, SnippetExporter, SnippetRenderLauncher) live
        // in the Android Koin module.
        single { app.kofipod.snippets.SnippetSourceResolver(get()) }
        single { app.kofipod.snippets.SnippetRepository(get()) }
        single { app.kofipod.snippets.FileSizer() }
        // Snippet Slice 4 — caption / waveform / format-routing collaborators.
        // CaptionDeps is a seam so SnippetCaptionRepository stays unit-testable
        // without MockK. The production adapter delegates to the four concrete
        // classes listed below; Task 16 may extract this into its own binding
        // if the DI module grows too wide.
        single<app.kofipod.snippets.CaptionDeps> {
            val downloads: DownloadRepository = get()
            val episodes: EpisodesRepository = get()
            val coordinator: AudioUploadCoordinator = get()
            val gemini: GeminiClient = get()
            val config: AiConfigRepository = get()
            object : app.kofipod.snippets.CaptionDeps {
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
                                model = app.kofipod.ai.GeminiModel.Flash,
                                fileUri = acquired.fileUri,
                                mimeType = acquired.mimeType,
                                prompt = prompt,
                            ).getOrThrow()
                    }
            }
        }
        single { app.kofipod.snippets.SnippetCaptionPicker() }
        single { app.kofipod.snippets.WaveformGenerator() }
        single {
            app.kofipod.snippets.SnippetCaptionRepository(
                get<app.kofipod.data.repo.EpisodeSource>(),
                get<app.kofipod.ai.TranscriptFetcher>(),
                get<app.kofipod.snippets.CaptionDeps>(),
                get<app.kofipod.snippets.SnippetCaptionPicker>(),
            )
        }
        // ────────────────────────────────────────────────────────────────────
        // PKM (Slice 5 + 6) — Markdown export + connection-bound sinks
        // ────────────────────────────────────────────────────────────────────
        single<app.kofipod.pkm.MarkdownFormatter> { app.kofipod.pkm.MarkdownFormatterImpl() }

        // Zero-auth sinks — bound as their concrete types so the coordinator factory
        // can resolve them unambiguously without named qualifiers (Defect 4 resolution).
        single { app.kofipod.pkm.sinks.ClipboardSink(get()) }
        single { app.kofipod.pkm.sinks.ShareFileSink(get(), get()) }

        // Connection-bound sinks — wired here for the first time (Tasks 8 + 10 created
        // the classes but deferred DI to Task 11 per the plan).
        single { app.kofipod.pkm.sinks.ReadwiseClient(get()) }

        // ObsidianSink(writer, connectionLoader): writer = ObsidianFolderWriterImpl (platform actual),
        // connectionLoader = lambda over PkmConnectionRepository.
        single {
            val connRepo = get<app.kofipod.pkm.connections.PkmConnectionRepository>()
            app.kofipod.pkm.sinks.ObsidianSink(
                writer = get<app.kofipod.pkm.sinks.ObsidianFolderWriterImpl>(),
                connectionLoader = {
                    connRepo.observe(app.kofipod.pkm.connections.ConnectionKind.Obsidian).first()
                },
            )
        }
        single {
            val connRepo = get<app.kofipod.pkm.connections.PkmConnectionRepository>()
            app.kofipod.pkm.sinks.ReadwiseSink(
                client = get(),
                vault = get(),
                connectionLoader = {
                    connRepo.observe(app.kofipod.pkm.connections.ConnectionKind.Readwise).first()
                },
            )
        }

        // SinkRegistry — maps ConnectionKind → ExportSink for connection-bound destinations.
        single {
            app.kofipod.pkm.sinks.SinkRegistry(
                mapOf(
                    app.kofipod.pkm.connections.ConnectionKind.Obsidian to get<app.kofipod.pkm.sinks.ObsidianSink>(),
                    app.kofipod.pkm.connections.ConnectionKind.Readwise to get<app.kofipod.pkm.sinks.ReadwiseSink>(),
                ),
            )
        }

        // ExportLogRepository — interface bound to the SQLDelight-backed impl.
        single<app.kofipod.pkm.connections.ExportLogRepository> {
            app.kofipod.pkm.connections.ExportLogRepositoryImpl(get())
        }

        // PkmExportScheduler — bound by the platform module: AndroidPkmExportScheduler
        // (WorkManager) on Android, IosPkmExportScheduler (no-op) on iOS.

        // PkmConnectionRepository — Slice 6 wiring; required by ObsidianSink and ReadwiseSink loaders.
        single { app.kofipod.pkm.connections.PkmConnectionRepository(db = get(), vault = get()) }

        // PkmExportDeps adapter — five-method seam over the production repos. Lives
        // in DI rather than its own file so the only extra surface is one Koin block.
        single<app.kofipod.pkm.PkmExportDeps> {
            val snippetRepo: app.kofipod.snippets.SnippetRepository = get()
            val bookmarkRepo: app.kofipod.bookmarks.BookmarkRepository = get()
            val summaryRepo: app.kofipod.ai.AiSummaryRepository = get()
            val episodeRepo: app.kofipod.data.repo.EpisodesRepository = get()
            val libraryRepo: app.kofipod.data.repo.LibraryRepository = get()
            object : app.kofipod.pkm.PkmExportDeps {
                override suspend fun snippetById(id: String) = snippetRepo.selectById(id)

                override suspend fun bookmarkById(id: String) = bookmarkRepo.selectById(id)

                override suspend fun summaryFor(episodeId: String) = summaryRepo.cachedNow(episodeId)

                override fun episode(id: String) = episodeRepo.episodeNow(id)

                override fun podcast(id: String) = libraryRepo.podcastNow(id)
            }
        }

        single {
            app.kofipod.pkm.PkmExportCoordinator(
                deps = get(),
                formatter = get(),
                sinks = get(),
                exportLog = get(),
                scheduler = get(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
                clipboardSink = get<app.kofipod.pkm.sinks.ClipboardSink>(),
                shareFileSink = get<app.kofipod.pkm.sinks.ShareFileSink>(),
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

        single { app.kofipod.search.LibrarySearchRepository(driver = get()) }
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
        // 8 positional deps (slice 7 task 10): repo, episodes, stats, opml, pro,
        // paywallRouter, smartPlaylistRepo, smartPlaylistResolver. Bump in lockstep
        // with the LibraryViewModel ctor declaration order — Koin throws at runtime
        // if these drift.
        viewModel { LibraryViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel {
            app.kofipod.ui.screens.bookmarks.BookmarksViewModel(
                bookmarks = get(),
                player = get(),
                episodes = get<EpisodeSource>(),
                downloads = get(),
                pkmExport = get(),
                paywallRouter = get(),
                pro = get(),
            )
        }
        viewModel { app.kofipod.ui.screens.search.LibrarySearchViewModel(get()) }
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
                snippetRepo = get(),
                fileSizer = get(),
                pkmExport = get(),
                paywallRouter = get(),
                pro = get(),
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
            app.kofipod.ui.screens.snippet.SnippetEditorViewModel(
                snippetId = snippetId,
                snippets = get(),
                launcher = get(),
                player = get(),
                waveformGen = get(),
            )
        }
        viewModel { StatsViewModel(get()) }
        viewModel {
            app.kofipod.ui.screens.connections.ConnectionsViewModel(
                connections = app.kofipod.ui.screens.connections.PkmConnectionsSource(get()),
                readwiseClient = get(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
            )
        }
        // Slice 7 Task 9 — Smart Playlist editor. `params.getOrNull<String>()` lets
        // create-mode (no parametersOf arg) resolve to null and edit-mode (passing the
        // id via parametersOf) resolve to a String. Mirrors the LibraryDetailViewModel
        // factory pattern above.
        viewModel { params ->
            app.kofipod.ui.screens.playlists.SmartPlaylistEditorViewModel(
                playlists = get(),
                resolver = get(),
                library = get(),
                playlistId = params.getOrNull<String>(),
            )
        }
        // Slice 7 Task 10 — Smart Playlist detail. Required `playlistId` is passed via
        // `parametersOf(playlistId)` from the screen's `koinViewModel { ... }` block.
        viewModel { params ->
            app.kofipod.ui.screens.playlists.SmartPlaylistDetailViewModel(
                playlists = get(),
                resolver = get(),
                playlistId = params.get<String>(),
            )
        }
    }
