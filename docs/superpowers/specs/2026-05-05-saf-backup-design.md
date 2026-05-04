# User-managed backup via SAF — Design Spec

**Date:** 2026-05-05
**Status:** Design locked, awaiting implementation plan
**Audience:** Implementation plan author

## Goal & framing

Give users a free, user-owned, off-device backup of their library that survives reinstall and device migration without the ~25 MB Android Auto Backup cap and without an OAuth client to maintain. The user picks a folder once via the system file picker — Drive, Dropbox, OneDrive, NextCloud, local storage, anything with a `DocumentsProvider`. The app writes a single backup file there on a schedule and on demand. Restore reads the same file back.

This sits **alongside** Android Auto Backup, not in place of it. Auto Backup keeps working (silent, free, ~daily, up to ~25 MB) for users who never touch the new section. The SAF backup wins when:

- The DB grows past the Auto Backup cap (heavy listeners, lots of cached AI summaries / discuss history).
- The user wants a backup they can verify with their own eyes — file in a folder they control.
- The user wants cross-cloud portability (Google Drive on the old phone, Dropbox on the new one).

No OAuth, no Google Cloud Console, no SDK, no Play verification. Persisted access via `takePersistableUriPermission` on the picked tree URI.

## Trade-offs we accept

- **No silent first-time setup.** The user has to tap "Choose folder" once. The OPML feature already established this paradigm; we're not introducing a new one.
- **No auto-restore on first launch.** Restore is always user-initiated from Settings. A new install starts empty until the user chooses to restore.
- **No encryption at rest.** The user's chosen storage provider handles transport security; the file on disk is whatever SQLite + zip bytes look like. AES-at-rest is a future slice.
- **No multi-slot history in-app.** We always write to the same filename. The user's storage provider (Drive, Dropbox) typically keeps its own version history server-side; that's their job, not ours.
- **No incremental backups.** The full DB is small (well under a megabyte for typical users, under a few MB for heavy AI users). Compressed db inside a zip is the entire payload.
- **No background restart.** After restoring, the app exits and the user re-opens it from the launcher. We don't try to auto-relaunch via `AlarmManager` because OEM background-launch policies (Xiaomi, Vivo, Huawei) make it unreliable.

## What's in the backup

A single bundled file: **`kofipod-backup.kpbak`** — a zip archive with:

- `manifest.json` — backup metadata (see schema below).
- `kofipod.db` — the live SQLDelight database file, byte-for-byte.

This covers subscriptions, lists, episodes, playback state, downloads metadata, AI summaries, discuss history, recently-viewed, sync meta, and any future tables.

**Not included:**

- `kofipod_secure.xml` — BYOK Gemini key. Per-device by design (same reasoning as Auto Backup excludes). User re-pastes their key on the new device.
- `kofipod_local.xml` — device-local pointers (downloaded-APK path, the new backup folder URI itself). Restoring these would carry stale paths across the migration.
- Downloaded audio (`files/downloads/`), streaming cache (`cache/media/`), updater APKs (`files/updates/`). Large and re-fetchable.

### Manifest schema

```json
{
  "schemaVersion": 1,
  "appVersionCode": 7,
  "appVersionName": "0.7.0",
  "dbSchemaVersion": 15,
  "exportedAtMs": 1746400000000,
  "exportedAtIso": "2026-05-05T12:00:00Z",
  "dbSizeBytes": 524288,
  "dbSha256": "abc123…"
}
```

- `schemaVersion` — manifest format version (start at 1; bump if we ever change manifest shape).
- `dbSchemaVersion` — SQLDelight schema version (currently 15). On restore, **reject if `dbSchemaVersion > current`** (would be a downgrade we can't safely apply). Accept `<= current` and let SQLDelight's runtime migrations handle the rest on first open.
- `dbSha256` — sha256 of the db bytes inside the zip. Validated on restore; mismatch surfaces as "backup file appears corrupted."
- Other fields are forensic — useful for support, never load-bearing.

## Storage layout

The user picks one folder, persisted as a tree URI in `kofipod_local.xml` SharedPreferences. We write a fixed filename (`kofipod-backup.kpbak`) and overwrite on each backup. Drive / Dropbox / OneDrive keep their own server-side version history if the user needs a rollback; we don't try to replicate that.

The MIME used at create time is **`application/x-kofipod-backup`** (custom, unregistered). Mirrors the OPML feature's `text/x-opml` trick: SAF won't auto-append `.zip` or any other suffix to a custom unregistered MIME.

## UI surface

A new **Backup** section in Settings, placed **directly after the existing "Library" section** (which holds OPML import/export) and before "Appearance". This puts data-portability features together. The existing Auto-Backup informational row stays where it is — different concept, different lifecycle, different storage caps; conflating them in one section would muddy the user's mental model.

The new section has four rows + one inline status line:

| Row | Subtitle when set | Subtitle when not set | Action |
|---|---|---|---|
| **Backup folder** | "<display name from `DocumentFile`>" | "Not set" | Tap → `OpenDocumentTree` |
| **Last backup** | "Today, 09:14 AM" / "Yesterday, 09:14 AM" / "Mar 14" | "Never" | (none — informational) |
| **Back up now** | (always; disabled when no folder) | (disabled) | Tap → run exporter on `appScope` |
| **Restore from backup…** | (always; disabled when no folder) | (disabled) | Tap → `OpenDocument` filtered to the backup MIME → confirm dialog |

The inline status row sits under "Back up now" and shows `Idle / Backing up… / Restoring… / Error: <message>`. Errors are dismissed by tapping anything else (mirrors `OpmlAction`'s pattern).

The four-row layout deliberately mirrors the OPML pair: each row is its own `SettingRow` with a leading icon and a chevron, no compound rows, no expandable sections.

### Tap behaviour

- **Backup folder → Choose…** opens `ActivityResultContracts.OpenDocumentTree`. On `RESULT_OK`, we call `takePersistableUriPermission` with `FLAG_GRANT_READ | FLAG_GRANT_WRITE`, persist the URI string in `kofipod_local`, and emit a snackbar ("Backup folder set: <name>"). Cancelling is a no-op.
- **Back up now** runs the same code path as the daily worker: build the manifest, copy the live DB file into a zip, write the zip via `ContentResolver.openOutputStream` to the resolved file in the tree URI. On success, emit "Backup saved" and update `lastBackupAtMs`. Errors are surfaced inline.
- **Restore from backup…** opens `ActivityResultContracts.OpenDocument` filtered to `application/x-kofipod-backup` (with `*/*` fallback for pickers that strip MIME filters). User picks a file. We open it as a zip, validate the manifest (`dbSchemaVersion <= current`, `dbSha256` matches db payload), then show a confirmation dialog: **"Replace all data? This will overwrite your library and the app will close. Open it again from your launcher to finish."** On confirm, we stage the db payload to `filesDir/restore.tmp`, write a flag in `kofipod_local` (`pending_restore_path = …`), and `exitProcess(0)`. On next launch, `KofipodApplication.onCreate` consumes the flag *before* Koin starts (so no driver opens against the file we're about to overwrite), copies staged → `kofipod.db`, deletes the staged file, clears the flag, and proceeds normally.

### Restore validation order

Validation runs **before** the confirmation dialog so the user doesn't get a destructive prompt for a backup that was going to fail anyway:

1. Open zip, locate `manifest.json` and `kofipod.db` entries. Reject if either is missing.
2. Parse manifest. Reject on JSON-parse failure ("This doesn't look like a Kofipod backup").
3. Reject if `dbSchemaVersion > current` ("This backup was made with a newer version of Kofipod. Update the app and try again.").
4. Compute sha256 of the db entry bytes; reject on mismatch ("Backup file appears corrupted").
5. Show confirmation dialog. On confirm, stage + restart.

If the user moves the file to another folder later and the original folder URI no longer holds it, restore still works — the `OpenDocument` picker is independent of the persisted tree URI. The tree URI is only used for *writing*.

## Background scheduling

Daily backup runs via WorkManager, mirroring `EpisodeCheckWorker`'s shape:

- New `BackupWorker` (CoroutineWorker, `KoinComponent`).
- New `BackupScheduler` (Android-only) with `enable()` / `disable()`. `enable()` posts a unique periodic work request with constraints **`requireCharging() + setRequiredNetworkType(NetworkType.UNMETERED) + 24h interval`**. `disable()` cancels the unique work.
- Worker is a **no-op** when no folder URI is set or no key in `kofipod_local`. (Don't fail; just `Result.success()` and exit. The scheduler stays enabled so the moment a folder is picked, the next worker tick has work to do.)
- Worker reuses the same `BackupController.runBackup()` method that the manual button calls. Single-flight is enforced by the controller, not the worker — so a manual tap and a scheduled run can't race.

The scheduler is `enable()`'d once on app cold start in `KofipodApplication.onCreate`, same way `Scheduler` (the episode check) is enabled by Settings. We don't gate this on a user toggle in v1 — if the worker is a no-op without a folder, there's no cost.

## Architecture

Mirror the OPML feature's package shape exactly:

```
app.kofipod.backup/
  commonMain/
    BackupController.kt        # single-flight orchestrator on appScope; mirrors OpmlController
    BackupAction.kt            # sealed: Idle | BackingUp | Restoring | Error
    BackupFilePort.kt          # expect interface for SAF picker bridges
    BackupRepository.kt        # pure logic: build manifest, zip db, restore-validate
    BackupPickerHost.kt        # @Composable expect — hoisted in AppShell
    BackupFolderStore.kt       # expect: read/write picked tree URI string
    Manifest.kt                # data class + JSON serialization
  androidMain/
    AndroidBackupFilePort.kt   # SharedFlow + CompletableDeferred bridge, mirrors AndroidOpmlFilePort
    BackupPickerHost.android.kt# rememberLauncherForActivityResult for OpenDocumentTree + OpenDocument
    AndroidBackupFolderStore.kt# SharedPreferences-backed (kofipod_local, key "backup_folder_uri")
    BackupScheduler.android.kt # WorkManager wiring
    BackupWorker.kt            # CoroutineWorker → BackupController.runBackup()
    PendingRestore.kt          # consumed by KofipodApplication.onCreate before Koin starts
  iosMain/
    IosBackupFilePort.kt       # no-op (returns false / null)
    BackupPickerHost.ios.kt    # no-op composable
    IosBackupFolderStore.kt    # in-memory empty
    BackupScheduler.ios.kt     # no-op enable/disable
```

**Reuse:** the named `appScope` Koin singleton, `UiEventBus` for snackbars, `SettingRow` / `SectionLabel` primitives, and `Scheduler.android.kt`'s constraint pattern (charging + unmetered + 24h, unique periodic work).

**New deps:** none. We use:
- `java.util.zip.ZipOutputStream` / `ZipInputStream` (Android-only, lives in `androidMain` — no detekt forbidden-import update needed since we never reference it from `commonMain`).
- `java.security.MessageDigest` for sha256 (same — `androidMain` only).
- `kotlinx.serialization.json` (already in the project for AI features).

The repo's pure logic (manifest build/parse, sha256 verification driven by an `expect` digest seam) lives in `commonMain` so it's unit-testable on JVM. The actual zip read/write is Android-only because it touches `ContentResolver` URIs.

### Single-flight and lifecycle

`BackupController` exposes `action: StateFlow<BackupAction>` and three suspending entry points:

- `chooseFolder()` — fires SAF `OpenDocumentTree` via the port; persists the result; idempotent.
- `runBackup()` — atomic guard via `_action.compareAndSet(Idle, BackingUp)`. Concurrent taps return immediately. Worker calls this same method.
- `runRestore(uri)` — atomic guard via `compareAndSet(Idle, Restoring)`. Validates manifest *first*, throws `RestoreRefused` with a typed reason on validation failure (no destructive action), stages on success, sets the flag, exits process.

A `Backing up…` or `Restoring…` state shown in Settings reflects the controller. Navigation away during backup is fine — the operation runs on `appScope`, same convention as OPML.

### Closing the SQLDelight driver — we don't

The naïve "close driver, copy file, exit" sequence is fragile: the driver isn't directly exposed today (`buildDatabase` constructs it inside the factory call), and any in-flight Flow collector could re-acquire the connection between `close()` and `exitProcess`.

Instead: **stage and restart**. The restore path:

1. Validate the picked file (manifest + sha256). If anything fails, throw and stay idle.
2. Copy the in-zip db payload to `context.filesDir/restore.tmp`. Synchronous, ~hundreds of KB.
3. Set `kofipod_local`'s `pending_restore_path = restore.tmp`.
4. `exitProcess(0)`.

On next cold start, `KofipodApplication.onCreate` checks the flag *before* `startKoin`. If set, it copies `restore.tmp` over `getDatabasePath("kofipod.db")` (and the `-shm` / `-wal` siblings — delete them so SQLite recreates fresh), deletes `restore.tmp`, clears the flag, then proceeds. If anything in this consume step fails (file gone, copy errno), we log and clear the flag rather than blocking startup forever.

This sidesteps the live-driver problem entirely and keeps the destructive write to a single guarded section (process not-yet-Koin-up, no flows can be reading).

### Where last-backup state lives

In `kofipod_local` SharedPreferences, key `last_backup_at_ms` (`Long`). Cheap to read on every Settings recomposition; emitted via the same `SharedPreferences` callbackFlow pattern `AndroidLocalApkPathStore` already uses. We considered `SyncMeta` but `kofipod_local` keeps the SAF-backup state co-located with the folder URI — single source of truth, easier to reason about, won't be dragged around by Auto Backup (which the file is excluded from).

## Error handling

| Failure | Where | Surface |
|---|---|---|
| User cancels picker | Folder pick / restore pick | No-op, no snackbar |
| `takePersistableUriPermission` throws | Folder pick | Inline error: "Couldn't keep access to that folder. Try again." |
| Tree URI revoked (e.g. user removed access in Drive app) | Backup write | Inline error in status row: "Backup folder is no longer accessible. Choose it again." Disable "Back up now" until re-chosen. |
| Out of space at provider | Backup write | "Backup failed: not enough space." |
| Backup file unreadable | Restore | "Couldn't read backup file." |
| Manifest missing / unparseable | Restore validation | "This doesn't look like a Kofipod backup." |
| Schema version too new | Restore validation | "This backup was made with a newer version of Kofipod. Update the app and try again." |
| sha256 mismatch | Restore validation | "Backup file appears corrupted." |
| Worker exception | Background | `Result.retry()` (WorkManager will retry with backoff). User never sees this directly. |

All errors clear when the user taps anything else in the section, mirroring `OpmlController.dismissError()`.

## Testing scope

Per `CLAUDE.md`'s testing conventions: Compose UI tests + Paparazzi snapshots + JVM unit tests for pure logic. New tests:

1. **`BackupRepositoryTest` (JVM unit)** — covers manifest build/parse, validation gates (missing entry, bad json, schema-too-new, sha256 mismatch), and zip round-trip via byte arrays. No real SAF.
2. **`ManifestTest` (JVM unit)** — JSON canary: a fixture file `androidUnitTest/resources/backup/sample_manifest.json` parses cleanly into the Kotlin data class and re-serializes byte-identical (modulo formatting).
3. **`BackupControllerTest` (JVM unit)** — single-flight (concurrent runBackup taps return immediately), error state transitions, idle clears on dismissError. Uses fakes for port + repo + folder store.
4. **Paparazzi** — none required for v1; the new Settings rows reuse `SettingRow` whose snapshots already cover the visual surface.
5. **Manual emulator verification** (per slice plan) — pick folder → "Back up now" → confirm file appears in target via Files app or `adb shell content query` → uninstall → reinstall → restore → confirm subscriptions reappear.

Anti-gaming guard rails (per `CLAUDE.md`): tests assert behaviour (e.g. "validation rejects schema version > current"), not internals (e.g. "BackupRepository.foo() is called with x"). No mocks of the system under test's own methods.

## Open futures (explicitly deferred)

- AES-at-rest with a passphrase the user enters once.
- Per-table backup (e.g. "subscriptions only" / "without AI history").
- Multi-slot retention with a configurable rotation count.
- Background restore (resume-after-process-death for the staged-file step). Today, if the staged file disappears between `exitProcess` and re-launch — vanishingly rare — the user just re-runs restore.
- iOS implementation. Stub only in v1.

## Glossary

- **SAF** — Storage Access Framework. The Android system that provides `OpenDocument`, `OpenDocumentTree`, `CreateDocument` activity result contracts and the `DocumentFile` / `DocumentsProvider` abstractions over arbitrary cloud + local document stores.
- **Tree URI** — what `OpenDocumentTree` returns. Grants read/write across the picked folder and its subtree until either the user revokes access or the app calls `releasePersistableUriPermission`.
- **Persisted permission** — by default the grant survives only for the lifetime of the requesting Activity. `takePersistableUriPermission` asks Android to remember it across reboots.
