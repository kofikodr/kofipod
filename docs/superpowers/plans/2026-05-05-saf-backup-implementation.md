# User-managed backup via SAF — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL — use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each phase ends with a verifiable green-check (compile + ktlintFormat + detekt + tests + iOS sim-arm64 compile + emulator interaction where the slice has UI).

**Spec:** `docs/superpowers/specs/2026-05-05-saf-backup-design.md` — read first.

**Goal:** Ship the spec's v1 surface — a "Backup" section in Settings that lets the user pick a folder via SAF, run on-demand backups + restores, and have a daily WorkManager-driven background backup. Three slices, each independently shippable and verifiable on the Pixel_9a AVD.

The design deliberately mirrors the existing OPML feature (`commit c6700a7`). Treat the OPML files as the working reference for naming, layering, picker bridging, and Koin wiring. Where this plan says "mirror OPML's X", look there first and copy the shape.

---

## Conventions

- **Commits:** every task ends with one commit. Format `type(scope): subject` where `scope` is `backup` for everything in this plan (e.g. `feat(backup): add BackupController and BackupAction`).
- **SPDX header** on every new Kotlin file:

  ```kotlin
  // SPDX-License-Identifier: GPL-3.0-or-later
  ```

- **Package root for this work:** `app.kofipod.backup`.
- **Green-check sequence per slice:**

  ```
  ./gradlew :composeApp:compileDebugKotlinAndroid
  ./gradlew :composeApp:ktlintFormat :composeApp:detekt
  ./gradlew :composeApp:testDebugUnitTest
  ./gradlew :composeApp:compileKotlinIosSimulatorArm64
  # then per-slice emulator interaction (see slice's "Verify on emulator" step)
  ```

- **Detekt forbidden imports:** every new Android-only artefact added to `androidMain` that wraps a JVM-only or Android-only API gets added to `config/detekt/detekt.yml` `style>ForbiddenImport>imports` so it can't leak into `commonMain`. For this plan that means `java.util.zip.*`, `java.security.MessageDigest`, `androidx.documentfile.*`, and `androidx.work.*` (already there).
- **Koin ViewModel factories:** every new ViewModel constructor parameter must land in `di/CommonModule.kt` in the same commit — per the lockstep rule in `CLAUDE.md`. `SettingsViewModel` already takes 10 params; this plan adds one (`backup: BackupController`) and we update the factory in lockstep.
- **No file paths, URI strings, or any portion of the picked tree URI in any log.** The URI carries the user's cloud account / folder structure and shouldn't appear in logcat. Log operation names + status codes only, mirroring `Kofipod-AI`. Tag for this feature: `Kofipod-Backup`.

---

# Slice 1 — Folder picker, manifest model, manual "Back up now"

**User-facing outcome:** A new "Backup" section in Settings with three rows: Backup folder (Choose…), Last backup (Never), Back up now (disabled). Picking a folder enables the button; tapping it writes `kofipod-backup.kpbak` to the picked folder. No worker, no restore yet.

**Verifiable on emulator:** Settings → Backup → Choose folder → pick a folder via Drive/Files → "Back up now" → confirm `kofipod-backup.kpbak` appears in that folder via the Files app or `adb shell content query`.

### Task 1.1: Common-side model — `BackupAction`, `Manifest`, `BackupFilePort`, `BackupFolderStore`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/BackupAction.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/Manifest.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/BackupFilePort.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/BackupFolderStore.kt`

- [ ] **Step 1: `BackupAction.kt`** — sealed interface, mirrors `OpmlAction`:

  ```kotlin
  sealed interface BackupAction {
      data object Idle : BackupAction
      data object BackingUp : BackupAction
      data object Restoring : BackupAction
      data class Error(val message: String) : BackupAction
  }
  ```

- [ ] **Step 2: `Manifest.kt`** — `@Serializable` data class matching the spec's manifest schema, plus a small companion with `fromJson(String)` / `toJson()` helpers via `kotlinx.serialization.json.Json`. Default `Json` config: `prettyPrint = true`, `ignoreUnknownKeys = true`. Include a const `MANIFEST_FILENAME = "manifest.json"`, `DB_FILENAME_IN_ZIP = "kofipod.db"`, `BACKUP_FILENAME = "kofipod-backup.kpbak"`, `BACKUP_MIME = "application/x-kofipod-backup"`, `MANIFEST_SCHEMA_VERSION = 1`.

- [ ] **Step 3: `BackupFilePort.kt`** — `interface` with three suspending methods:

  ```kotlin
  interface BackupFilePort {
      /** null = user cancelled. Returns the persisted tree URI string. */
      suspend fun pickFolder(): String?

      /** Writes [content] to BACKUP_FILENAME inside [treeUri]; overwrites if present. */
      suspend fun writeBackup(treeUri: String, content: ByteArray)

      /** null = user cancelled. Reads the picked single file's bytes. */
      suspend fun pickAndReadBackup(): ByteArray?
  }
  ```

  Doc-comment: mirror `OpmlFilePort`'s — Android binds the flow-driven port, iOS binds a no-op.

- [ ] **Step 4: `BackupFolderStore.kt`** — `expect class` (NOT interface, so Koin's `single` can resolve concrete) with:

  ```kotlin
  expect class BackupFolderStore {
      fun treeUriNow(): String?
      fun setTreeUri(uri: String?)
      fun treeUriFlow(): Flow<String?>
      fun lastBackupAtNow(): Long?
      fun setLastBackupAt(ms: Long?)
      fun lastBackupAtFlow(): Flow<Long?>
  }
  ```

  Doc-comment: explains storage lives in `kofipod_local.xml` (excluded from Auto Backup) and that we keep the SAF-backup state co-located with the folder URI for single-source-of-truth reasons.

- [ ] **Step 5: green-check** (no UI yet). Commit `feat(backup): add common-side action, manifest, ports`.

### Task 1.2: Android backings — `AndroidBackupFilePort`, `BackupPickerHost.android`, `BackupFolderStore.android`

**Files:**
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/backup/AndroidBackupFilePort.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/backup/BackupPickerHost.android.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/backup/BackupFolderStore.android.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/BackupPickerHost.kt` (`@Composable expect fun BackupPickerHost()`)

- [ ] **Step 1: `BackupFolderStore.android.kt`** — `actual class BackupFolderStore(context: Context)`, backed by `context.getSharedPreferences("kofipod_local", MODE_PRIVATE)`. Keys: `backup_folder_uri` (String), `last_backup_at_ms` (Long; `getLong` with sentinel `0L` → null). Flows via the same `callbackFlow` + `OnSharedPreferenceChangeListener` pattern that `AndroidLocalApkPathStore` uses (lines 24–36 of `AndroidLocalApkPathStore.kt`).

- [ ] **Step 2: `AndroidBackupFilePort.kt`** — three `MutableSharedFlow` request streams (`folderPicks`, `restorePicks`, and a paired `writeRequests` that the host doesn't actually need a launcher for since writes go via the persisted `treeUri`), each emitting a `CompletableDeferred<…>`. Use `extraBufferCapacity = 1` and a 5-minute `PICKER_TIMEOUT_MS`, mirroring `AndroidOpmlFilePort`. Constructor takes a `Context` for `contentResolver` access; the actual file write happens in this class (the host only handles the launcher), so:

  ```kotlin
  class AndroidBackupFilePort(
      private val context: Context,
      private val store: BackupFolderStore,
  ) : BackupFilePort {

      data class FolderPickRequest(
          val deferred: CompletableDeferred<String?>,
      )
      data class RestorePickRequest(
          val deferred: CompletableDeferred<ByteArray?>,
      )

      private val _folderPicks = MutableSharedFlow<FolderPickRequest>(0, 1)
      val folderPicks: SharedFlow<FolderPickRequest> = _folderPicks.asSharedFlow()
      private val _restorePicks = MutableSharedFlow<RestorePickRequest>(0, 1)
      val restorePicks: SharedFlow<RestorePickRequest> = _restorePicks.asSharedFlow()

      override suspend fun pickFolder(): String? { /* emit + await */ }
      override suspend fun pickAndReadBackup(): ByteArray? { /* emit + await */ }

      override suspend fun writeBackup(treeUri: String, content: ByteArray) {
          // Resolve treeUri → DocumentFile.fromTreeUri → child("kofipod-backup.kpbak"),
          // creating it (with BACKUP_MIME) if absent, deleting + recreating if present.
          // Then context.contentResolver.openOutputStream(file.uri).use { it.write(content) }.
          // Throws SecurityException if the URI was revoked → caller surfaces "folder no longer accessible".
      }
  }
  ```

  Use `androidx.documentfile.provider.DocumentFile` (already a transitive dep via `appcompat`; if not present add `androidx.documentfile:documentfile:1.0.1` to `androidMain`). All file IO inside `withContext(Dispatchers.IO)`.

- [ ] **Step 3: `BackupPickerHost.kt` + `BackupPickerHost.android.kt`** — `@Composable expect fun BackupPickerHost()`. Android actual mirrors `OpmlPickerHost.android.kt`:
  - `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree())` for folder pick. On result, call `context.contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ | FLAG_GRANT_WRITE)`, then complete the pending deferred with `uri.toString()`.
  - `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` for restore pick. On result, read all bytes via `contentResolver.openInputStream(uri).use { it.readBytes() }` and complete the deferred.
  - Two `LaunchedEffect(port, "backup-folder-picks") { port.folderPicks.collect { … } }` collectors that bridge `SharedFlow` emissions to launcher invocations.
  - The `MIME_TYPES_FOR_RESTORE` array: `arrayOf(BACKUP_MIME, "application/zip", "*/*")` — same fallback pattern OPML uses.
  - Use the same `PendingHolder<T>` private class (or extract it into `composeApp/src/androidMain/kotlin/app/kofipod/util/PendingHolder.kt` and migrate OPML to use the shared one — **do this now**, since we're about to have two consumers).

- [ ] **Step 4: hoist `BackupPickerHost()` in `AppShell`.** Add the call right after `OpmlPickerHost()` in `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt:133`. iOS no-op composable (next task) keeps it KMP-clean.

- [ ] **Step 5: detekt config.** Add `androidx.documentfile.*`, `java.util.zip.*`, and `java.security.MessageDigest` to `config/detekt/detekt.yml` `ForbiddenImport>imports` (alongside the existing entries).

- [ ] **Step 6: green-check** (no UI rows yet, but the host is hoisted and the port resolves). Commit `feat(backup): add Android SAF picker host and folder store`.

### Task 1.3: iOS stubs

**Files:**
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/backup/IosBackupFilePort.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/backup/BackupPickerHost.ios.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/backup/BackupFolderStore.ios.kt`

- [ ] **Step 1: `IosBackupFilePort.kt`** — `class IosBackupFilePort : BackupFilePort` returning `null` from both pickers and throwing `IllegalStateException("backup not supported on iOS")` from `writeBackup`. The `runBackup` path will short-circuit on `treeUriNow() == null` so the throw is defensive only.

- [ ] **Step 2: `BackupPickerHost.ios.kt`** — no-op composable, mirroring `OpmlPickerHost.ios.kt`.

- [ ] **Step 3: `BackupFolderStore.ios.kt`** — `actual class BackupFolderStore` with in-memory empty implementations (returns `null`, flows emit `null` once). Comment: "iOS doesn't surface backup UI in v1; this exists only to keep the Koin graph consistent across targets."

- [ ] **Step 4: green-check**, including the iOS sim-arm64 compile. Commit `feat(backup): add iOS no-op stubs for backup port and store`.

### Task 1.4: `BackupRepository` (pure logic) + `BackupController` (single-flight orchestration)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/BackupRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/BackupController.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/Sha256.kt` (`expect fun sha256(bytes: ByteArray): String`)
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/backup/Sha256.android.kt` (`MessageDigest.getInstance("SHA-256")`)
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/backup/Sha256.ios.kt` (use `platform.CoreCrypto.CC_SHA256` or, simpler for v1, throw `NotImplementedError` — the iOS path never runs)
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/Zip.kt` (`expect class ZipBuilder` with `addEntry(name, bytes)` + `finish(): ByteArray`, plus `expect fun readZipEntries(bytes): Map<String, ByteArray>`)
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/backup/Zip.android.kt` (`ZipOutputStream` / `ZipInputStream` backed)
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/backup/Zip.ios.kt` (throws `NotImplementedError` — never invoked)

**Why two new `expect`s instead of putting everything in `androidMain`:** the repo's pure logic (manifest build, validation gates, zip round-trip orchestration) needs to be JVM-unit-testable per `CLAUDE.md`. Hiding `MessageDigest` and `ZipOutputStream` behind tiny seams is the smallest cut that keeps the unit tests target-portable.

- [ ] **Step 1: `Sha256.kt` + actuals.** `expect fun sha256(bytes: ByteArray): String` returning a 64-char lowercase hex. Android: `MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }` inside `androidMain`. iOS: `error("not supported on iOS")`.

- [ ] **Step 2: `Zip.kt` + actuals.** `expect class ZipBuilder() { fun addEntry(name: String, bytes: ByteArray); fun finish(): ByteArray }` and `expect fun readZipEntries(bytes: ByteArray): Map<String, ByteArray>`. Android actuals use `ZipOutputStream` / `ZipInputStream` with `ByteArrayOutputStream` / `ByteArrayInputStream`. iOS: throws.

- [ ] **Step 3: `BackupRepository.kt`.** Constructor takes:

  ```kotlin
  class BackupRepository(
      private val dbFileBytes: () -> ByteArray,   // reads the live DB file from disk
      private val stageDb: (ByteArray) -> Unit,    // writes to filesDir/restore.tmp
      private val appVersionCode: Int,
      private val appVersionName: String,
      private val dbSchemaVersion: Int,            // wire from Schema.version (or hard-coded 15 for v1)
      private val clock: Clock = Clock.System,
  )
  ```

  Methods:
  - `fun buildBackup(): ByteArray` — read live db bytes; compute sha256; build manifest; produce zip via `ZipBuilder`. Pure: no SAF, no IO outside the injected lambdas.
  - `fun validateBackup(zipBytes: ByteArray): RestoreValidation` — return a sealed result: `Valid(dbBytes)` / `Invalid(reason: RestoreError)`. Reasons: `MissingEntry`, `BadManifest`, `SchemaTooNew(found: Int, current: Int)`, `Sha256Mismatch`, `ZipUnreadable`. Validation order matches the spec § "Restore validation order".
  - `fun stageRestore(dbBytes: ByteArray)` — calls the injected `stageDb` lambda.

  ```kotlin
  sealed interface RestoreError {
      data object MissingEntry : RestoreError
      data object BadManifest : RestoreError
      data class SchemaTooNew(val found: Int, val current: Int) : RestoreError
      data object Sha256Mismatch : RestoreError
      data object ZipUnreadable : RestoreError
      fun toUserMessage(): String  // concrete strings here, not in the UI layer
  }
  sealed interface RestoreValidation {
      data class Valid(val dbBytes: ByteArray) : RestoreValidation
      data class Invalid(val error: RestoreError) : RestoreValidation
  }
  ```

- [ ] **Step 4: `BackupController.kt`.** Single-flight orchestrator on `appScope`, mirrors `OpmlController` exactly:

  ```kotlin
  class BackupController(
      private val repo: BackupRepository,
      private val port: BackupFilePort,
      private val store: BackupFolderStore,
      private val bus: UiEventBus,
      private val appScope: CoroutineScope,
      private val clock: Clock = Clock.System,
      private val exitProcess: () -> Unit = { kotlin.system.exitProcess(0) },
  ) {
      private val _action = MutableStateFlow<BackupAction>(BackupAction.Idle)
      val action: StateFlow<BackupAction> = _action.asStateFlow()

      fun chooseFolder() { /* emit pickFolder, persist via store, snackbar */ }
      fun runBackup() {
          if (!_action.compareAndSet(Idle, BackingUp)) return
          val treeUri = store.treeUriNow() ?: run { _action.value = Idle; return }
          appScope.launch { … }
      }
      fun runRestore() { /* picks file, validates, on Valid sets pending_restore + exits */ }
      fun dismissError() { … }
  }
  ```

  `runRestore`'s pending-restore handoff: write the `Valid.dbBytes` to `filesDir/restore.tmp` via `repo.stageRestore`, write `pending_restore_path = "restore.tmp"` to the folder store (we extend the store interface with a third key in this same task — see Step 5), emit a snackbar "Restoring — the app will close. Open it again from your launcher.", delay 500ms so the snackbar is visible, then `exitProcess()`.

- [ ] **Step 5: extend `BackupFolderStore` with the pending-restore flag.**

  Add to the expect class: `fun pendingRestoreFilenameNow(): String?`, `fun setPendingRestoreFilename(name: String?)`. Android backing: same prefs file, key `pending_restore_filename`. iOS: empty.

- [ ] **Step 6: green-check** (unit tests don't exist yet — Task 1.5 adds them; for now just the compile + ktlint + detekt + tests-don't-fail trio). Commit `feat(backup): add BackupRepository, BackupController, sha256 + zip seams`.

### Task 1.5: Unit tests for `BackupRepository` and `BackupController`

**Files:**
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/backup/BackupRepositoryTest.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/backup/BackupControllerTest.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/backup/ManifestTest.kt`
- Create: `composeApp/src/androidUnitTest/resources/backup/sample_manifest.json` (fixture, schema version 1)

- [ ] **Step 1: `ManifestTest`** — fixture loads, parses into `Manifest`, re-serializes round-trip-equal-by-fields. Adding a new optional field doesn't break (use `ignoreUnknownKeys = true`).

- [ ] **Step 2: `BackupRepositoryTest`** — covers:
  - `buildBackup` produces a zip whose entries are exactly `manifest.json` + `kofipod.db`.
  - `validateBackup` returns `Valid` for output of `buildBackup` (round-trip).
  - `validateBackup` returns `Invalid(SchemaTooNew)` when manifest's `dbSchemaVersion` exceeds current.
  - `validateBackup` returns `Invalid(Sha256Mismatch)` when the db payload is mutated post-zip.
  - `validateBackup` returns `Invalid(MissingEntry)` when db entry is removed.
  - `validateBackup` returns `Invalid(BadManifest)` when manifest is non-JSON.
  - `validateBackup` returns `Invalid(ZipUnreadable)` when given non-zip bytes.

  No mocks of the repo's own methods. Inject fakes for the lambdas (db bytes generator, stage callback).

- [ ] **Step 3: `BackupControllerTest`** — covers:
  - Concurrent `runBackup()` calls: only one fires the port's `writeBackup`. Use a fake port whose `writeBackup` blocks on a `CompletableDeferred`; assert second call returns immediately.
  - On port success, `lastBackupAtMs` is updated via the store fake.
  - On port throw (`SecurityException` simulating revoked URI), `_action` transitions to `Error("Backup folder is no longer accessible. Choose it again.")`.
  - `runBackup()` with `treeUriNow() == null` is a no-op (state stays Idle).
  - `runRestore()` with a valid backup sets the pending-restore flag and calls the injected `exitProcess` exactly once.
  - `runRestore()` with `Invalid(SchemaTooNew)` sets `_action` to `Error(<spec copy>)` and does NOT call exitProcess.
  - `dismissError()` returns to `Idle` only when current state is `Error`.

- [ ] **Step 4: green-check** — all three test files pass; mirror runner conventions from existing `OpmlRepositoryTest`.

  Commit `test(backup): add unit tests for BackupRepository, BackupController, manifest`.

### Task 1.6: Koin wiring + Settings UI rows

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt`
- Modify: `composeApp/src/iosMain/kotlin/app/kofipod/di/IosPlatformModule.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt`

- [ ] **Step 1: `CommonModule.kt`** — add (after the OPML wiring block, ~line 217):

  ```kotlin
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
          store = get(),
          bus = get(),
          appScope = get(org.koin.core.qualifier.named("appScope")),
      )
  }
  ```

  `DbFileBytes` and `StageDbFile` are tiny `fun interface` seams over the platform calls — defined in `commonMain/.../backup/PlatformBackupIo.kt` and bound from `androidPlatformModule`. Android binding reads `context.getDatabasePath("kofipod.db")` and writes to `context.filesDir/restore.tmp`. iOS binding throws (never invoked). Add `const val DB_SCHEMA_VERSION = 15` next to `MANIFEST_SCHEMA_VERSION` in `Manifest.kt` and add a code comment that pairs it with `composeApp/src/commonMain/sqldelight/app/kofipod/db/migrations/`'s highest migration number.

  Also update the `SettingsViewModel` factory to take `backup = get()`.

- [ ] **Step 2: `AndroidModule.kt`** — add:

  ```kotlin
  single { BackupFolderStore(androidContext()) }
  single { AndroidBackupFilePort(androidContext(), get()) }
  single<BackupFilePort> { get<AndroidBackupFilePort>() }
  single<DbFileBytes> { DbFileBytes { androidContext().getDatabasePath("kofipod.db").readBytes() } }
  single<StageDbFile> {
      StageDbFile { bytes ->
          val ctx = androidContext()
          File(ctx.filesDir, "restore.tmp").writeBytes(bytes)
      }
  }
  ```

- [ ] **Step 3: `IosPlatformModule.kt`** — add the iOS no-op bindings (`BackupFolderStore()`, `IosBackupFilePort` cast to `BackupFilePort`, `DbFileBytes`/`StageDbFile` throwing).

- [ ] **Step 4: `SettingsViewModel.kt`** — add `backup: BackupController` constructor parameter and a `backupAction: StateFlow<BackupAction>`, `backupFolderName: StateFlow<String?>`, `lastBackupAt: StateFlow<Long?>` derived from `store.treeUriFlow()` + `store.lastBackupAtFlow()`. Expose them as part of `SettingsUiState`. Add three forwarding methods: `chooseBackupFolder()`, `backupNow()`, `restoreFromBackup()`.

  Folder display name: in the VM, on Android, resolve `treeUri` → `DocumentFile.fromTreeUri(ctx, Uri.parse(uri)).name` via a small `expect fun displayNameForTreeUri(uri: String): String?`. iOS returns `null`. Refresh whenever `treeUriFlow()` emits.

- [ ] **Step 5: `SettingsScreen.kt`** — replace the existing static "Backup" SectionLabel block (currently lines 131–138, the read-only Auto-Backup explainer) with the four-row layout from the spec. Keep the original Auto-Backup explainer below the four rows under a quieter sub-section label "About automatic system backup" — same copy as today, just relocated. Use `SettingRow` for each row, with the disabled state when no folder URI is set. Status line shows `Idle` / `Backing up…` / `Restoring…` / `Error: <message>`. Mirror the OPML rows' `subtitle` switch on `backupAction`.

  Restore confirmation: a `material3.AlertDialog` with title "Replace all data?" and body matching the spec exactly. Two buttons: cancel (returns to idle) + `Replace and close` (destructive accent).

- [ ] **Step 6: green-check + emulator interaction.**
  - Install: `./gradlew :composeApp:installDebug`.
  - Open Settings → Backup section renders. Backup folder reads "Not set".
  - Tap "Backup folder" → SAF folder picker opens. Pick a Drive folder.
  - Subtitle updates to the picked folder's display name. Snackbar: "Backup folder set: …".
  - "Back up now" enabled. Tap. Status flips to "Backing up…" then back to idle. Snackbar: "Backup saved".
  - Verify on the device: open the Files app → navigate to the picked folder → see `kofipod-backup.kpbak`. Or via adb:

    ```bash
    ~/Library/Android/sdk/platform-tools/adb shell content query --uri content://com.android.providers.media.documents/document/<picked> 2>/dev/null | grep kofipod-backup
    ```

  - "Last backup" updates to "Today, HH:MM".

  Commit `feat(backup): add Backup section in Settings with folder picker and manual backup`.

---

# Slice 2 — Restore + process restart

**User-facing outcome:** "Restore from backup…" lights up. Tap → picker → pick the `.kpbak` → confirm dialog → app exits. Re-open the app from the launcher → library is restored to the backup state.

**Verifiable on emulator:** Install Slice 1 build → run a backup → uninstall the app → reinstall → tap Restore → pick the backed-up file → confirm dialog → app closes → launch app again → confirm subscriptions, episodes, AI history reappear.

### Task 2.1: Pending-restore consumption in `KofipodApplication.onCreate`

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/backup/PendingRestore.kt`

- [ ] **Step 1: `PendingRestore.kt`.** Single static method:

  ```kotlin
  internal object PendingRestore {
      const val STAGED_FILENAME = "restore.tmp"
      const val PREF_FILE = "kofipod_local"
      const val PREF_KEY = "pending_restore_filename"

      /** Run BEFORE startKoin. If a pending restore exists, copy staged → live db,
       *  delete -shm/-wal siblings, clear staged + flag. Idempotent on repeated launches. */
      fun consumeIfPresent(context: Context) { … }
  }
  ```

  Implementation reads the pref directly (no Koin yet, no folder store wired). Copies `filesDir/restore.tmp` → `getDatabasePath("kofipod.db")`. Then deletes `kofipod.db-shm` / `kofipod.db-wal` so SQLite recreates fresh against the new file. Wraps everything in `runCatching` and logs (`Kofipod-Backup` tag) — failures clear the flag rather than blocking startup. Posts a snackbar via a one-shot `kofipod_local` flag (`restore_completed = true`) that `AppShell`'s `LaunchedEffect` reads on first composition and surfaces as "Library restored" — see Step 3.

- [ ] **Step 2: `KofipodApplication.onCreate`** — call `PendingRestore.consumeIfPresent(this)` as the first line, before `ThemeSystem.applyPersistedToProcess`. Yes, that's literally first — Koin must not be up, no existing collectors must be running.

- [ ] **Step 3: post-restart snackbar.** After the consume step, when present, set the `restore_completed` pref. In `AppShell` (commonMain) read this via a new `BackupController.consumeRestoredSignal()` method on first composition (`LaunchedEffect(Unit)` in the shell) and emit `UiEvent.Snackbar("Library restored")`. The flag clears on read.

- [ ] **Step 4: green-check + emulator interaction.** This task isn't independently emulator-verifiable (no UI yet beyond Slice 1's surface) — it's the plumbing for Slice 2.2. Just compile + tests. Commit `feat(backup): consume pending restore before Koin starts`.

### Task 2.2: Restore button wiring + confirmation dialog

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt`

- [ ] **Step 1: VM** — `restoreFromBackup()` calls `controller.runRestore()`. Controller already implements the validate-then-confirm-then-exit flow (Task 1.4 Step 4).

  But: confirmation dialog needs to be a UI concern, not a controller concern. Restructure:
  - Controller exposes `runRestoreValidate()` returning a suspend `Result<RestoreValidation.Valid>`. Errors set `_action` to `Error(...)` (idle state).
  - Controller exposes `confirmRestore(valid: RestoreValidation.Valid)` which performs the stage + exit dance.
  - VM bridges: `restoreFromBackup()` → calls `runRestoreValidate()` → on `Valid`, posts a `pendingRestoreConfirm: StateFlow<RestoreValidation.Valid?>` → UI shows the dialog → on confirm calls `vm.confirmRestore()` which forwards to controller.

- [ ] **Step 2: dialog.** `material3.AlertDialog` rendered from `SettingsScreen` based on `state.pendingRestoreConfirm`. Title: "Replace all data?" Body matches spec. Buttons:
  - Cancel → `vm.cancelRestoreConfirm()` (clears the StateFlow).
  - "Replace and close" (destructive accent) → `vm.confirmRestore()`.

- [ ] **Step 3: green-check + emulator interaction.**
  - Install build with Slice 1 + Slice 2.1.
  - Confirm a backup exists from Slice 1.
  - In Settings → Backup → tap Restore → SAF picker → pick `kofipod-backup.kpbak` → dialog → tap Cancel → dialog dismisses, no destructive action.
  - Tap Restore again → confirm → snackbar "Restoring…" → app exits within ~1s.
  - Re-open the app from the launcher → snackbar "Library restored" → confirm subscriptions appear.

  Commit `feat(backup): add restore flow with validation and confirmation dialog`.

### Task 2.3: Negative-path emulator verification

This is a verification task only (no code) — but it's important enough to enumerate:

- [ ] Pick a non-zip file (any random PDF / image) → Restore → expect inline error "This doesn't look like a Kofipod backup", no dialog.
- [ ] Hand-edit a backup zip's `manifest.json` to bump `dbSchemaVersion` to 99, repack, restore → expect "This backup was made with a newer version of Kofipod. Update the app and try again.", no dialog.
- [ ] Hand-corrupt the db payload by appending a byte, repack, restore → expect "Backup file appears corrupted", no dialog.

  No commit — these are emulator-only checks recorded in the slice's verification log.

---

# Slice 3 — Daily background backup

**User-facing outcome:** Once a folder is set, the app silently backs up about once a day on charging + Wi-Fi. The "Last backup" line in Settings reflects this.

**Verifiable on emulator:** With a folder set, force-run the worker via `adb shell am broadcast` (or WorkManager's `ListenableWorker.startWork()` test invocation) and confirm the file is overwritten + the timestamp updates.

### Task 3.1: `BackupWorker` + `BackupScheduler` + Application boot

**Files:**
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/background/BackupWorker.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/background/BackupScheduler.android.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/background/BackupScheduler.kt` (`expect class`)
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/background/BackupScheduler.ios.kt` (no-op)
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt` — `enable()` after Koin start
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` — wire scheduler
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt` — Android backing

- [ ] **Step 1: `BackupScheduler.kt` (expect)** — `expect class BackupScheduler { fun enable(); fun disable() }`. Mirrors the existing `Scheduler` shape (lines 1–38 of `Scheduler.android.kt`).

- [ ] **Step 2: `BackupScheduler.android.kt`** — `actual class BackupScheduler(context: Context)`:

  ```kotlin
  actual fun enable() {
      val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
          .setConstraints(
              Constraints.Builder()
                  .setRequiresCharging(true)
                  .setRequiredNetworkType(NetworkType.UNMETERED)
                  .build()
          )
          .addTag(TAG)
          .build()
      WorkManager.getInstance(context)
          .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
  }
  actual fun disable() = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME).let {}
  companion object {
      const val UNIQUE_NAME = "saf_backup"
      const val TAG = "saf_backup"
  }
  ```

- [ ] **Step 3: `BackupWorker.kt`** — `CoroutineWorker, KoinComponent`. `doWork()`:

  ```kotlin
  override suspend fun doWork(): Result =
      runCatching {
          val store: BackupFolderStore by inject()
          if (store.treeUriNow().isNullOrEmpty()) return@runCatching Result.success()
          val controller: BackupController by inject()
          // Reuse the same code path the manual button calls. runBackup is fire-and-forget;
          // we need to await it for the worker — add an internal awaiting variant.
          controller.runBackupAwaiting()  // suspend version (see Step 4)
          Result.success()
      }.getOrElse { Result.retry() }
  ```

- [ ] **Step 4: `BackupController.runBackupAwaiting()`.** Internal suspend variant of `runBackup()` that awaits the same `appScope` job rather than fire-and-forget. Single-flight is preserved: if a manual run is in-flight, the worker awaits the same job's completion via a shared `Mutex`.

- [ ] **Step 5: `iosMain` no-op.** `actual class BackupScheduler { fun enable() {} fun disable() {} }`.

- [ ] **Step 6: Koin wiring.** `single { BackupScheduler(androidContext()) }` in `androidPlatformModule`; `single { BackupScheduler() }` in iOS.

- [ ] **Step 7: `KofipodApplication.onCreate`** — after `startKoin`, call `get<BackupScheduler>().enable()`. Always-on; the worker no-ops without a folder URI.

- [ ] **Step 8: green-check + emulator interaction.**
  - Install. Confirm a folder is set (or set it).
  - Force-run the worker:

    ```bash
    ~/Library/Android/sdk/platform-tools/adb shell am broadcast \
      -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS \
      -p app.kofipod
    # Or use WorkManager Inspector in Android Studio to manually trigger the unique work.
    ```

    (Easier path for the verification: change the periodic interval to 15 min in a debug-only branch, plug the device in, turn off cellular and connect to Wi-Fi, wait ~16 min — confirms the scheduler actually fires under real constraints. Revert the interval before commit.)

  - Verify the backup file's mtime advanced + the "Last backup" line updates.

  Commit `feat(backup): add periodic BackupWorker on charging + unmetered network`.

---

# Verification matrix (post-Slice-3, before declaring done)

| Check | Command / action | Expected |
|---|---|---|
| Compile (Android debug) | `./gradlew :composeApp:compileDebugKotlinAndroid` | Green |
| Compile (iOS sim arm64) | `./gradlew :composeApp:compileKotlinIosSimulatorArm64` | Green |
| Lint | `./gradlew :composeApp:ktlintFormat :composeApp:detekt` | Green, no diff |
| Unit tests | `./gradlew :composeApp:testDebugUnitTest` | Green |
| Paparazzi | `./gradlew :composeApp:verifyPaparazziDebug` | Green (no new baselines required) |
| Manual: pick folder | Settings → Backup → Choose | URI persists across app cold start |
| Manual: manual backup | Settings → Back up now | File lands; "Last backup" updates |
| Manual: restore | uninstall → install → restore | Library reappears after relaunch |
| Manual: revoked URI | Revoke folder access in Drive app → Back up now | Inline error, button disabled until re-chosen |
| Manual: schema-too-new | Hand-edit manifest, restore | Inline error, no destructive action |
| Manual: scheduled backup | Force-run worker via WorkManager Inspector | File overwritten; timestamp advances |
| Code review | Spawn `feature-dev:code-reviewer` subagent over the full diff | All critical / high findings addressed |
| Test audit | Spawn `test-quality-auditor` subagent over the new tests | All critical / high findings addressed |

---

# Out of scope (deferred)

- AES-at-rest with passphrase. Future slice.
- Multi-slot rotation (e.g. weekly snapshots). Future slice.
- Per-table backup (subscriptions only / without AI history). Future slice.
- iOS implementation beyond the no-op stubs.
- Auto-relaunch after restore (requires AlarmManager + OEM-specific exemptions; the spec accepts manual relaunch).
- Auto-restore on first launch (always user-initiated).
- A "Restore in progress" splash screen on the post-exit launch — the snackbar is enough for v1.
