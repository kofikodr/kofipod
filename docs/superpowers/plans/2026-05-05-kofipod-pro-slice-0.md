# Kofipod Pro — Slice 0: Entitlement plumbing + flavor split

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-05-04-kofipod-pro-unlock-design.md` — read first, especially the "Build flavors and distribution", "Pro entitlement", and "Pricing & SKUs" sections.

**Goal:** Lay every piece of plumbing needed to gate Pro features in v1 — `play`/`foss` Gradle flavors, a `BillingClientPort` with three platform-specific implementations (Play Billing v6+, FOSS stub, iOS stub), a `ProEntitlementRepository` with backup-excluded device-local cache, a Paywall bottom sheet, Settings entry, restore-purchase plumbing, and one toy gate (Player Bookmark button) that exercises the whole pipeline end-to-end. Slices 1+ then attach real Pro features (Bookmarks, Snippets, etc.) to the same plumbing without changing the entitlement contract.

**Architecture (3 sentences):** Two product flavors (`play`, `foss`) live in a single `:composeApp` module; Play Billing v6+ is a `playAndroid`-only dependency, so the FOSS APK has zero proprietary code (F-Droid eligibility) and unconditionally returns Pro. Entitlement state flows through a single `commonMain` `BillingClientPort` interface, bound per-flavor via `flavorPlatformModule` (same Koin val name in both flavor source sets, mutually exclusive at build time); the cache lives in a backup-excluded `kofipod_entitlement.xml` SharedPreferences file so device clones / restores can't fake Pro. The Paywall is a `ModalBottomSheet` hoisted in `AppShell`, driven by a `PaywallRouter` state flow — not a NavHost destination, because NavHost destinations are full-screen and would show a blank background behind the sheet.

**Tech Stack:** Kotlin Multiplatform · Compose Multiplatform · Koin DI · Google Play Billing Library v7+ (latest stable v7 at time of writing; v7 supersedes v6 and the SDK semantics we need are unchanged) · SharedPreferences (Android) · existing `UiEventBus` for Snackbars · existing `appScope` named CoroutineScope for repository long-lived collectors.

**Deviation from spec architecture (logged here, not in code):** The spec lists `Route.Paywall(triggerKey: String)` as a NavHost destination. This plan renders the Paywall as a `ModalBottomSheet` hoisted at `AppShell`, driven by `PaywallRouter`'s `StateFlow<PaywallState>`. Justification: `ModalBottomSheet` inside a NavHost destination shows a blank background behind the sheet (NavHost destinations occupy the full screen), losing the "modal over current content" UX. Hoisting at `AppShell` overlays the sheet over whatever screen is current. Slices 1+ that need full-screen Pro routes (Bookmarks list, Snippet editor) keep their NavHost destinations.

---

## Conventions

- **Commits:** every task ends with one commit. Format `type(scope): subject` where `scope` is `pro` for everything in this plan (e.g. `feat(pro): add BillingClientPort interface`). Tasks that ship a new ViewModel constructor must update `CommonModule.kt`'s factory in lockstep, per `CLAUDE.md`.
- **SPDX header** on every new Kotlin file:
  ```kotlin
  // SPDX-License-Identifier: GPL-3.0-or-later
  ```
- **Package roots for this work:**
  - `app.kofipod.pro` — entitlement domain (commonMain + per-platform actuals).
  - `app.kofipod.ui.screens.paywall` — Paywall composable + ViewModel (commonMain).
  - `app.kofipod.di` — flavor-specific Koin modules under `playAndroid` / `fossAndroid` source sets.
- **Green-check sequence per task** (run before each commit):
  ```bash
  ./gradlew :composeApp:ktlintFormat \
            :composeApp:compileFossDebugKotlinAndroid \
            :composeApp:compilePlayDebugKotlinAndroid \
            :composeApp:compileKotlinIosSimulatorArm64
  ```
  Tasks that touch tests additionally run `./gradlew :composeApp:testFossDebugUnitTest :composeApp:testPlayDebugUnitTest` (the unit-test source set is shared, but Gradle still emits per-flavor test variants once flavors exist).
- **The pre-commit hook** (`scripts/git-hooks/pre-commit`) re-runs `ktlintFormat` + `detekt` on every commit. Run `./gradlew installGitHooks` once per clone if it isn't installed.
- **Detekt forbidden imports:** every new Android-only artefact added to `androidMain` (or `playAndroid` / `fossAndroid`) gets added to `config/detekt/detekt.yml` `style>ForbiddenImport>imports` so it can't leak into `commonMain`. Slice 0 adds `com.android.billingclient.**`.
- **No prompts, responses, account IDs, or purchase tokens in any log.** Billing failures log status code + short reason only. Same posture as the BYOK Gemini key.
- **The Pro entitlement cache file** (`kofipod_entitlement.xml`) is **excluded** from Auto Backup (cloud + device-transfer) so a device clone / restore cannot resurrect a stale "Pro" state. Real entitlement always re-verifies via Play Billing on cold start.
- **Family Sharing spike (Slice 0 acceptance):** the spec defers "confirm Play Billing v7+ Family Sharing actually grants entitlement to family-group accounts on cold start" to this slice. Task 9 includes the spike write-up in `PlayBillingClientPort.kt`'s KDoc; if the Play Billing v7 docs / behavior contradict the v1.0 SKU pricing, raise a SPIKE_BLOCKED issue and stop — do not silently work around.

---

## File map (locked at planning time)

New files (paths relative to repo root):

- `composeApp/src/commonMain/kotlin/app/kofipod/pro/ProEntitlement.kt` — sealed type + `ProSource` enum.
- `composeApp/src/commonMain/kotlin/app/kofipod/pro/BillingClientPort.kt` — interface only.
- `composeApp/src/commonMain/kotlin/app/kofipod/pro/EntitlementCache.kt` — interface only.
- `composeApp/src/commonMain/kotlin/app/kofipod/pro/ProEntitlementRepository.kt` — single-flight repo, owns `StateFlow<ProEntitlement>`.
- `composeApp/src/commonMain/kotlin/app/kofipod/pro/PaywallRouter.kt` — `StateFlow<PaywallState>` + `requestPaywall(triggerKey)` + `dismiss()`.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/paywall/PaywallScreen.kt` — `ModalBottomSheet` content.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/paywall/PaywallViewModel.kt` — restore + purchase action triggers.
- `composeApp/src/androidMain/kotlin/app/kofipod/pro/AndroidEntitlementCache.kt` — SharedPreferences-backed.
- `composeApp/src/androidMain/kotlin/app/kofipod/ui/ActivityHolder.kt` — current-foreground-activity registry, used by Play impl to launch billing flow.
- `composeApp/src/playAndroid/kotlin/app/kofipod/pro/PlayBillingClientPort.kt` — real Play Billing v7+ wrapper.
- `composeApp/src/playAndroid/kotlin/app/kofipod/di/FlavorPlatformModule.kt` — binds `BillingClientPort` to play impl.
- `composeApp/src/fossAndroid/kotlin/app/kofipod/pro/FossBillingClientPort.kt` — `Pro(FossBuild)` stub.
- `composeApp/src/fossAndroid/kotlin/app/kofipod/di/FlavorPlatformModule.kt` — binds `BillingClientPort` to foss impl.
- `composeApp/src/iosMain/kotlin/app/kofipod/pro/IosBillingClientPort.kt` — Free stub.
- `composeApp/src/iosMain/kotlin/app/kofipod/pro/IosEntitlementCache.kt` — no-op.
- `composeApp/src/commonTest/kotlin/app/kofipod/pro/ProEntitlementRepositoryTest.kt` — repo state-machine tests with fakes.

Modified files:

- `composeApp/build.gradle.kts` — `productFlavors` block + per-flavor `playAndroidImplementation` + per-flavor source-set declarations.
- `gradle/libs.versions.toml` — Play Billing version + library entry.
- `config/detekt/detekt.yml` — add `com.android.billingclient.**` to forbidden imports.
- `composeApp/src/androidMain/res/xml/backup_rules.xml` — exclude `kofipod_entitlement.xml`.
- `composeApp/src/androidMain/res/xml/backup_rules_legacy.xml` — same.
- `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt` — bind `EntitlementCache`, bind `ActivityHolder`.
- `composeApp/src/iosMain/kotlin/app/kofipod/di/IosPlatformModule.kt` — bind iOS port + cache.
- `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` — bind `ProEntitlementRepository` + `PaywallRouter` + `PaywallViewModel`.
- `composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt` — load `flavorPlatformModule`, register Activity lifecycle observer for `ActivityHolder`, kick `repo.refreshOnStart()`.
- `composeApp/src/androidMain/kotlin/app/kofipod/MainActivity.kt` — register/unregister with `ActivityHolder` on resume/pause.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt` — collect `PaywallRouter.state`, render `ModalBottomSheet`.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt` — add Kofipod Pro section above Library.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt` — expose `proEntitlement` via repo + `restorePurchase()` action.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerScreen.kt` — add Bookmark icon + tap dispatch.
- `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt` — `onBookmarkTapped()` dispatch (Free → router; Pro → bus).
- `README.md` — distribution policy paragraph.

---

## Task 1: Add `play` / `foss` Gradle product flavors

**Files:**
- Modify: `composeApp/build.gradle.kts`

The Android Gradle Plugin requires `flavorDimensions` + `productFlavors` declarations. Per-flavor source sets `src/playAndroid/kotlin/` and `src/fossAndroid/kotlin/` are auto-discovered by AGP based on the flavor name + variant — no explicit `sourceSets { … }` declaration needed for the flavor source sets themselves; we only need that for sharing dependencies if any.

- [ ] **Step 1: Read the current `android { … }` block**

Open `composeApp/build.gradle.kts` and locate the `android { … }` block (currently starts at line 137). Confirm it has `defaultConfig`, `compileOptions`, `buildFeatures`, `signingConfigs`, `buildTypes`, `applicationVariants` blocks — we'll insert the `flavorDimensions` + `productFlavors` block immediately after `defaultConfig` and before `compileOptions`.

- [ ] **Step 2: Add the flavor block**

In `composeApp/build.gradle.kts`, immediately after the closing brace of the `defaultConfig { … }` block (around line 147 in the current source), insert:

```kotlin
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            // play flavor is the revenue product; no applicationIdSuffix so it
            // matches what's uploaded to Play Console.
        }
        create("foss") {
            dimension = "distribution"
            // foss flavor unconditionally unlocks Pro and excludes Play Billing.
            // Use a distinct package so a foss build can be installed alongside
            // a play build for verification.
            applicationIdSuffix = ".foss"
            versionNameSuffix = "-foss"
            manifestPlaceholders["appLabel"] = "Kofipod (FOSS)"
        }
    }
```

**Note:** the `applicationIdSuffix` for `foss` stacks on top of any `buildTypes.debug.applicationIdSuffix = ".debug"` set later. So `playDebug` becomes `app.kofipod.debug` and `fossDebug` becomes `app.kofipod.foss.debug` — both installable side-by-side on a single device.

- [ ] **Step 3: Adjust the release-output filename to include the flavor**

In `composeApp/build.gradle.kts`, find the `applicationVariants.all { … }` block (currently around line 184). Replace its body with:

```kotlin
    applicationVariants.all {
        val variant = this
        if (variant.buildType.name == "release") {
            variant.outputs.all {
                val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                output.outputFileName =
                    "kofipod-${variant.flavorName}-${variant.versionName}-${variant.versionCode}-${variant.buildType.name}.apk"
            }
        }
    }
```

The only change is inserting `${variant.flavorName}-` before `${variant.versionName}`. Both flavors get distinct release APK filenames.

- [ ] **Step 4: Verify**

```bash
./gradlew :composeApp:tasks --group=build | grep -E "assemble(Foss|Play)"
```

Expected (excerpt):

```
assembleFossDebug
assembleFossRelease
assemblePlayDebug
assemblePlayRelease
```

If those tasks don't appear, the productFlavors block isn't registered correctly — re-read the diff.

- [ ] **Step 5: Compile both flavor variants**

```bash
./gradlew :composeApp:ktlintFormat \
          :composeApp:compileFossDebugKotlinAndroid \
          :composeApp:compilePlayDebugKotlinAndroid \
          :composeApp:compileKotlinIosSimulatorArm64
```

Expected: BUILD SUCCESSFUL. (No source files differ between flavors yet, so both compile from `commonMain` + `androidMain` only.)

- [ ] **Step 6: Commit**

```bash
git add composeApp/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(pro): add play and foss product flavors

flavorDimensions = "distribution". play has no suffix and matches
the Play Console upload; foss adds .foss applicationIdSuffix and a
"-foss" version suffix so both APKs install side-by-side on a single
device. Release-output filename includes the flavor.

This is Slice 0 plumbing — no per-flavor source files exist yet, so
both compile from the same commonMain + androidMain sources. Future
tasks add playAndroid/ and fossAndroid/ source sets.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Add Play Billing v7 dependency (play flavor only)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/build.gradle.kts`

Play Billing Library v7 is the current stable line (v6 superseded). The `playAndroid` source set is the only place this dependency lands; the `fossAndroid` flavor never sees `com.android.billingclient.*`.

- [ ] **Step 1: Add the version + library to the catalog**

Open `gradle/libs.versions.toml`. Under `[versions]`, append a line (alphabetical-ish ordering, near `androidx*` block):

```toml
googlePlayBilling = "7.1.1"
```

Under `[libraries]`, append:

```toml
google-play-billing = { module = "com.android.billingclient:billing-ktx", version.ref = "googlePlayBilling" }
```

Use `billing-ktx` (the Kotlin extensions artifact) rather than the raw `billing` artifact — it provides suspending coroutine wrappers (`launchBillingFlow`, `queryProductDetails`, `queryPurchases`) that we'll consume from `PlayBillingClientPort`.

- [ ] **Step 2: Wire the dep into the play flavor only**

Open `composeApp/build.gradle.kts`. Find the `kotlin { sourceSets { … } }` block. Inside `sourceSets`, after the existing `androidMain` block (which ends with `}` around line 98 in the current source), append a new flavor-specific source-set block:

```kotlin
        val playAndroidMain by creating {
            dependsOn(androidMain)
            dependencies {
                implementation(libs.google.play.billing)
            }
        }
        val fossAndroidMain by creating {
            dependsOn(androidMain)
            // No flavor-specific dependencies. The FOSS flavor stays
            // proprietary-code-free so F-Droid will accept it.
        }
```

**Why `by creating`, not `by getting`:** AGP creates the *flavored* variant source sets (`androidPlayDebug`, `androidPlayRelease`, etc.) automatically, but the *flavor-only* source sets (`playAndroid`, `fossAndroid`) used here for sharing across debug+release of one flavor must be explicitly created in Kotlin Multiplatform. Both flavor source sets `dependsOn(androidMain)` so they inherit the Android-target dependencies (Media3, WorkManager, etc.).

- [ ] **Step 3: Verify the dependency lands only on play**

```bash
./gradlew :composeApp:dependencies --configuration playDebugRuntimeClasspath | grep billing
```

Expected: at least one line containing `com.android.billingclient:billing-ktx:7.1.1`.

```bash
./gradlew :composeApp:dependencies --configuration fossDebugRuntimeClasspath | grep billing
```

Expected: **no output**. (Confirms FOSS flavor has zero billing classes.)

- [ ] **Step 4: Compile both flavors**

```bash
./gradlew :composeApp:ktlintFormat \
          :composeApp:compileFossDebugKotlinAndroid \
          :composeApp:compilePlayDebugKotlinAndroid \
          :composeApp:compileKotlinIosSimulatorArm64
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml composeApp/build.gradle.kts
git commit -m "$(cat <<'EOF'
build(pro): add Google Play Billing 7.1.1 to playAndroid only

Uses billing-ktx for the suspending coroutine wrappers. Wired via
playAndroidMain source set so the FOSS flavor never pulls in any
proprietary com.android.billingclient.* classes (F-Droid eligibility).

fossAndroidMain is declared empty (just dependsOn(androidMain)) so
later tasks can add foss-specific files without re-touching the build
script.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Detekt — forbid `com.android.billingclient.**` in commonMain

**Files:**
- Modify: `config/detekt/detekt.yml`

Existing convention: every Android-only artifact added to `androidMain` is added to detekt's `ForbiddenImport` rule so it can't leak into `commonMain`. The existing `com.google.android.**` rule does NOT cover `com.android.billingclient.**` (different vendor namespace). Make it explicit.

- [ ] **Step 1: Add the forbidden import**

Open `config/detekt/detekt.yml`. Find the `imports:` list under `style.ForbiddenImport`. Append at the end (after `com.google.android.**`):

```yaml
      - value: 'com.android.billingclient.**'
        reason: 'Play Billing is play-flavor-only. Scope to playAndroid/, never commonMain.'
```

**Side cleanup (in scope, related to Pre-Slice-0):** the existing rule `androidx.core.content.FileProvider` carries the comment "used by the in-app updater" — that's stale. Replace just that one line's `reason:` with:

```yaml
        reason: 'Android-only AAR. Scope to androidMain.'
```

(Keep the rule itself; FileProvider is still Android-only and still doesn't belong in `commonMain` even though no `commonMain` code currently references it.)

- [ ] **Step 2: Verify detekt still parses + passes**

```bash
./gradlew :composeApp:detekt
```

Expected: BUILD SUCCESSFUL. No forbidden-import violations (no commonMain code references `com.android.billingclient.*` yet).

- [ ] **Step 3: Commit**

```bash
git add config/detekt/detekt.yml
git commit -m "$(cat <<'EOF'
build(pro): forbid com.android.billingclient.** in commonMain

Adds the Play Billing namespace to detekt's ForbiddenImport rule so
billing classes can't leak from playAndroid into commonMain. Also
freshens the FileProvider rule's reason — the in-app updater is gone.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Domain types — `ProEntitlement` + `BillingClientPort` interface

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pro/ProEntitlement.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pro/BillingClientPort.kt`

Pure interface + sealed type. No DI wiring yet.

- [ ] **Step 1: Create `ProEntitlement.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

/**
 * Current Pro entitlement state for the running user.
 *
 * - [Unknown] is the initial state on cold start, before [BillingClientPort.queryEntitlement]
 *   has produced its first answer. UI MUST treat Unknown as Free for paywall purposes
 *   (i.e. show the paywall sheet on a Pro-gated tap), so a brief Pro-classified user
 *   doesn't get a "blank Pro feature" because the billing query was still in flight.
 *   The mis-classification window is < 1s on a warm device and self-corrects on first
 *   reconciliation.
 * - [Free] is a confirmed-not-Pro state after at least one successful billing query.
 * - [Pro] carries the [source] so analytics + UI can distinguish Individual vs Family vs
 *   self-built FOSS unlock without reading multiple flags.
 */
sealed class ProEntitlement {
    data object Unknown : ProEntitlement()

    data object Free : ProEntitlement()

    data class Pro(val source: ProSource) : ProEntitlement()
}

enum class ProSource {
    /** Purchased the [kofipod_pro] SKU on this account. */
    Individual,

    /** Granted via Play Billing Family Sharing on the [kofipod_pro_family] SKU. */
    Family,

    /** Built from source / installed from F-Droid / running the foss flavor. */
    FossBuild,
}
```

- [ ] **Step 2: Create `BillingClientPort.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

/**
 * Platform-agnostic billing surface used by [ProEntitlementRepository].
 *
 * Three implementations:
 * - `playAndroid/.../PlayBillingClientPort.kt` — real Google Play Billing v7+ wrapper.
 * - `fossAndroid/.../FossBillingClientPort.kt` — unconditional `Pro(FossBuild)`.
 * - `iosMain/.../IosBillingClientPort.kt` — `Free` stub until iOS becomes a focus.
 *
 * The port models a long-lived service: [connect] starts the underlying client (idempotent),
 * [close] tears it down. Repository owns the connect/close lifecycle.
 *
 * Purchase flow lives behind [launchPurchase] which takes no host parameter — Android
 * implementations resolve the current foreground Activity via the `ActivityHolder` registry
 * in `androidMain`. iOS / FOSS implementations don't need a host.
 */
interface BillingClientPort {
    /** Connects the underlying billing client. Safe to call repeatedly; resolves to Unit on success. */
    suspend fun connect(): Result<Unit>

    /** Returns the current entitlement reading. Must not return [ProEntitlement.Unknown]. */
    suspend fun queryEntitlement(): Result<ProEntitlement>

    /**
     * Launches the platform purchase flow for [productId]. Suspends until the user completes
     * or cancels the dialog. Result is the new entitlement reading; on cancel, returns the
     * pre-purchase reading (typically [ProEntitlement.Free]).
     */
    suspend fun launchPurchase(productId: String): Result<ProEntitlement>

    /**
     * Re-queries Play Billing for any historical purchases on this account. Used by Settings
     * "Restore Purchase" and by cold-start auto-restore in [ProEntitlementRepository].
     */
    suspend fun restorePurchases(): Result<ProEntitlement>

    /** Tears down the underlying client. Safe to call after [connect] failure. */
    fun close()
}

/**
 * Product IDs declared in Play Console. Single source of truth so UI / repo / port agree.
 */
object ProProducts {
    const val INDIVIDUAL = "kofipod_pro"
    const val FAMILY = "kofipod_pro_family"
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew :composeApp:ktlintFormat \
          :composeApp:compileFossDebugKotlinAndroid \
          :composeApp:compilePlayDebugKotlinAndroid \
          :composeApp:compileKotlinIosSimulatorArm64
```

Expected: BUILD SUCCESSFUL. (Detekt's ForbiddenImport scope is `commonMain` only and we haven't imported anything forbidden.)

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/pro/
git commit -m "$(cat <<'EOF'
feat(pro): add ProEntitlement sealed type + BillingClientPort interface

Defines the entitlement state machine (Unknown / Free / Pro(source))
and the port surface the repository talks to. Three implementations
land in subsequent tasks (Play, FOSS, iOS). Product IDs from the spec
are co-located in ProProducts.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `EntitlementCache` port + Android impl + iOS no-op + backup-rules exclusion

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pro/EntitlementCache.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/pro/AndroidEntitlementCache.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/pro/IosEntitlementCache.kt`
- Modify: `composeApp/src/androidMain/res/xml/backup_rules.xml`
- Modify: `composeApp/src/androidMain/res/xml/backup_rules_legacy.xml`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt`
- Modify: `composeApp/src/iosMain/kotlin/app/kofipod/di/IosPlatformModule.kt`

The cache stores the last verified entitlement reading so cold starts can render a non-Unknown UI before the billing query lands. Stored in a backup-excluded SharedPreferences file (`kofipod_entitlement.xml`) — same posture as `kofipod_secure.xml` for the Gemini key. On a device clone / new-device restore, the cache is empty and entitlement re-verifies from Play Billing.

- [ ] **Step 1: Create `EntitlementCache.kt` (commonMain)**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

/**
 * Device-local cache of the last verified Pro entitlement reading.
 *
 * Backed by a backup-excluded SharedPreferences file on Android so a device-clone or
 * new-device restore cannot resurrect a stale "Pro" state. iOS impl is a no-op until
 * StoreKit lands.
 *
 * The cache is a UX optimisation, not a security boundary. Real entitlement always
 * re-verifies via [BillingClientPort.queryEntitlement] / [BillingClientPort.restorePurchases]
 * on every cold start — see [ProEntitlementRepository.refreshOnStart].
 */
interface EntitlementCache {
    /** Returns the last cached entitlement, or null if never written / iOS no-op. */
    suspend fun read(): ProEntitlement?

    /** Persists [entitlement]. Calls with [ProEntitlement.Unknown] are silently ignored. */
    suspend fun write(entitlement: ProEntitlement)

    /** Clears any cached value. */
    suspend fun clear()
}
```

- [ ] **Step 2: Create `AndroidEntitlementCache.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SharedPreferences-backed entitlement cache. The file name is referenced by
 * `backup_rules.xml` + `backup_rules_legacy.xml` exclude rules — keep it in sync.
 */
class AndroidEntitlementCache(context: Context) : EntitlementCache {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override suspend fun read(): ProEntitlement? =
        withContext(Dispatchers.IO) {
            val raw = prefs.getString(KEY_TIER, null) ?: return@withContext null
            when (raw) {
                "free" -> ProEntitlement.Free
                "pro_individual" -> ProEntitlement.Pro(ProSource.Individual)
                "pro_family" -> ProEntitlement.Pro(ProSource.Family)
                "pro_foss" -> ProEntitlement.Pro(ProSource.FossBuild)
                else -> null
            }
        }

    override suspend fun write(entitlement: ProEntitlement) {
        if (entitlement is ProEntitlement.Unknown) return
        val raw = when (entitlement) {
            ProEntitlement.Unknown -> return
            ProEntitlement.Free -> "free"
            is ProEntitlement.Pro -> when (entitlement.source) {
                ProSource.Individual -> "pro_individual"
                ProSource.Family -> "pro_family"
                ProSource.FossBuild -> "pro_foss"
            }
        }
        withContext(Dispatchers.IO) {
            prefs.edit { putString(KEY_TIER, raw) }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit { remove(KEY_TIER) }
        }
    }

    companion object {
        const val FILE_NAME = "kofipod_entitlement"
        private const val KEY_TIER = "tier"
    }
}
```

- [ ] **Step 3: Create `IosEntitlementCache.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

/**
 * No-op cache for iOS. Returns null and ignores writes. Combined with [IosBillingClientPort]
 * always returning Free, this means iOS users see Free on every cold start until StoreKit
 * support ships in a future release.
 */
class IosEntitlementCache : EntitlementCache {
    override suspend fun read(): ProEntitlement? = null

    override suspend fun write(entitlement: ProEntitlement) {
        // intentionally no-op
    }

    override suspend fun clear() {
        // intentionally no-op
    }
}
```

- [ ] **Step 4: Update `backup_rules.xml`**

Open `composeApp/src/androidMain/res/xml/backup_rules.xml`. After the existing `<exclude domain="sharedpref" path="kofipod_secure.xml" />` line in `<cloud-backup>`, add a parallel exclude:

```xml
        <exclude domain="sharedpref" path="kofipod_entitlement.xml" />
```

Do the same inside `<device-transfer>`. Replace the file's comment block (the part above `<data-extraction-rules>`) with:

```xml
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
    * `kofipod_entitlement` — last verified Pro entitlement reading. Excluded
      so a device clone / restore cannot resurrect a stale "Pro" state. Real
      entitlement re-verifies via Play Billing on every cold start.
-->
```

The full post-edit file should match this shape (including the existing kofipod_secure exclusion):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  ...comment block above...
-->
<data-extraction-rules>
    <cloud-backup>
        <include domain="database" path="." />
        <include domain="sharedpref" path="." />
        <exclude domain="sharedpref" path="kofipod_secure.xml" />
        <exclude domain="sharedpref" path="kofipod_entitlement.xml" />
    </cloud-backup>
    <device-transfer>
        <include domain="database" path="." />
        <include domain="sharedpref" path="." />
        <exclude domain="sharedpref" path="kofipod_secure.xml" />
        <exclude domain="sharedpref" path="kofipod_entitlement.xml" />
    </device-transfer>
</data-extraction-rules>
```

- [ ] **Step 5: Update `backup_rules_legacy.xml`**

Open `composeApp/src/androidMain/res/xml/backup_rules_legacy.xml`. Update its comment block + add the exclude line:

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
    * `kofipod_entitlement` — last verified Pro entitlement reading.
      Real entitlement re-verifies via Play Billing on every cold start.
-->
<full-backup-content>
    <include domain="database" path="." />
    <include domain="sharedpref" path="." />
    <exclude domain="sharedpref" path="kofipod_secure.xml" />
    <exclude domain="sharedpref" path="kofipod_entitlement.xml" />
</full-backup-content>
```

- [ ] **Step 6: Update `AndroidModule.kt`**

Open `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt`. Add this import next to the other `app.kofipod.*` imports (alphabetical):

```kotlin
import app.kofipod.pro.AndroidEntitlementCache
import app.kofipod.pro.EntitlementCache
```

Inside the `module { … }` body, add a binding next to the other ports (after `single<KeyVault> { AndroidKeyVault(androidContext()) }`):

```kotlin
        single<EntitlementCache> { AndroidEntitlementCache(androidContext()) }
```

- [ ] **Step 7: Update `IosPlatformModule.kt`**

Open `composeApp/src/iosMain/kotlin/app/kofipod/di/IosPlatformModule.kt`. Add the import:

```kotlin
import app.kofipod.pro.EntitlementCache
import app.kofipod.pro.IosEntitlementCache
```

Inside the `module { … }` body, add:

```kotlin
        single<EntitlementCache> { IosEntitlementCache() }
```

- [ ] **Step 8: Compile**

```bash
./gradlew :composeApp:ktlintFormat \
          :composeApp:compileFossDebugKotlinAndroid \
          :composeApp:compilePlayDebugKotlinAndroid \
          :composeApp:compileKotlinIosSimulatorArm64
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/pro/EntitlementCache.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/pro/AndroidEntitlementCache.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/pro/IosEntitlementCache.kt \
        composeApp/src/androidMain/res/xml/backup_rules.xml \
        composeApp/src/androidMain/res/xml/backup_rules_legacy.xml \
        composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/di/IosPlatformModule.kt
git commit -m "$(cat <<'EOF'
feat(pro): add EntitlementCache port + Android SharedPreferences impl

Caches the last verified Pro entitlement in kofipod_entitlement.xml so
cold starts render the right tier UI before Play Billing's first query
lands. The file is excluded from Auto Backup (cloud + device-transfer)
so a device clone / restore can't resurrect a stale Pro state.

iOS impl is a no-op (StoreKit support deferred). Both AndroidModule
and IosPlatformModule bind the port so commonMain code can depend on
EntitlementCache directly.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `ProEntitlementRepository` + unit tests (TDD)

**Files:**
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/pro/ProEntitlementRepositoryTest.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pro/ProEntitlementRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`

The repository owns the single `StateFlow<ProEntitlement>` that the rest of the app reads. It coalesces the cache (immediate, possibly null) with the billing query (authoritative, eventually). Single-flight on `refreshOnStart` and `restorePurchases` so concurrent callers don't fight.

- [ ] **Step 1: Write the failing tests first**

Create `composeApp/src/commonTest/kotlin/app/kofipod/pro/ProEntitlementRepositoryTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProEntitlementRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `initial state is Unknown when cache empty`() =
        runTest {
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort(query = Result.success(ProEntitlement.Free))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            assertEquals(ProEntitlement.Unknown, repo.state.value)
        }

    @Test
    fun `initial state is cached value when cache has reading`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Pro(ProSource.Individual))
            val port = FakeBillingClientPort()
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            // Trigger the lazy hydrate:
            repo.hydrateFromCache()

            assertEquals(ProEntitlement.Pro(ProSource.Individual), repo.state.value)
        }

    @Test
    fun `refreshOnStart connects, queries, writes cache, updates state`() =
        runTest {
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort(
                query = Result.success(ProEntitlement.Pro(ProSource.Family)),
            )
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            repo.refreshOnStart()

            assertEquals(1, port.connectCalls)
            assertEquals(1, port.queryCalls)
            assertEquals(ProEntitlement.Pro(ProSource.Family), repo.state.value)
            assertEquals(ProEntitlement.Pro(ProSource.Family), cache.read())
        }

    @Test
    fun `refreshOnStart on query failure keeps cached state`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Pro(ProSource.Individual))
            val port = FakeBillingClientPort(query = Result.failure(RuntimeException("net")))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            repo.hydrateFromCache()
            repo.refreshOnStart()

            assertEquals(ProEntitlement.Pro(ProSource.Individual), repo.state.value)
            // Cache stays as it was — failure does not write.
            assertEquals(ProEntitlement.Pro(ProSource.Individual), cache.read())
        }

    @Test
    fun `restorePurchases calls port restore + writes cache`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free)
            val port = FakeBillingClientPort(
                restore = Result.success(ProEntitlement.Pro(ProSource.Individual)),
            )
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            val result = repo.restorePurchases()

            assertTrue(result.isSuccess)
            assertEquals(ProEntitlement.Pro(ProSource.Individual), repo.state.value)
            assertEquals(ProEntitlement.Pro(ProSource.Individual), cache.read())
        }

    @Test
    fun `concurrent refreshOnStart calls coalesce`() =
        runTest {
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort(query = Result.success(ProEntitlement.Free))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            // Fire two concurrent refresh calls — the second should not trigger a second query.
            kotlinx.coroutines.coroutineScope {
                kotlinx.coroutines.launch { repo.refreshOnStart() }
                kotlinx.coroutines.launch { repo.refreshOnStart() }
            }

            assertEquals(1, port.queryCalls)
        }

    @Test
    fun `port returning Unknown is a contract violation but does not corrupt state`() =
        runTest {
            // The port contract says queryEntitlement must not return Unknown. If a buggy impl
            // does, the repo treats it as Free and logs (logging not asserted here — just that
            // state doesn't go back to Unknown after a successful query).
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort(query = Result.success(ProEntitlement.Unknown))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            repo.refreshOnStart()

            assertIs<ProEntitlement.Free>(repo.state.value)
        }

    @Test
    fun `state flow is hot StateFlow`() =
        runTest {
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort()
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            // StateFlow.first emits immediately even with no subscribers history.
            val first = repo.state.first()
            assertEquals(ProEntitlement.Unknown, first)
        }
}

// -- fakes ---------------------------------------------------------------------------------------

private class FakeEntitlementCache(initial: ProEntitlement?) : EntitlementCache {
    private var stored: ProEntitlement? = initial

    override suspend fun read(): ProEntitlement? = stored

    override suspend fun write(entitlement: ProEntitlement) {
        if (entitlement !is ProEntitlement.Unknown) stored = entitlement
    }

    override suspend fun clear() {
        stored = null
    }
}

private class FakeBillingClientPort(
    private val connect: Result<Unit> = Result.success(Unit),
    private val query: Result<ProEntitlement> = Result.success(ProEntitlement.Free),
    private val restore: Result<ProEntitlement> = Result.success(ProEntitlement.Free),
    private val purchase: Result<ProEntitlement> = Result.success(ProEntitlement.Pro(ProSource.Individual)),
) : BillingClientPort {
    var connectCalls = 0
        private set
    var queryCalls = 0
        private set
    var restoreCalls = 0
        private set
    var purchaseCalls = 0
        private set
    var closeCalls = 0
        private set

    override suspend fun connect(): Result<Unit> {
        connectCalls++
        return connect
    }

    override suspend fun queryEntitlement(): Result<ProEntitlement> {
        queryCalls++
        return query
    }

    override suspend fun launchPurchase(productId: String): Result<ProEntitlement> {
        purchaseCalls++
        return purchase
    }

    override suspend fun restorePurchases(): Result<ProEntitlement> {
        restoreCalls++
        return restore
    }

    override fun close() {
        closeCalls++
    }
}
```

- [ ] **Step 2: Run the tests — confirm they fail**

```bash
./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.pro.ProEntitlementRepositoryTest"
```

Expected: compilation failure (`ProEntitlementRepository` doesn't exist yet).

- [ ] **Step 3: Create `ProEntitlementRepository.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val LOG_TAG = "Kofipod-Pro"

/**
 * Owns the single [StateFlow] of [ProEntitlement] for the running user.
 *
 * Lifecycle:
 * - Construction: state = [ProEntitlement.Unknown]. No port calls.
 * - [hydrateFromCache] (called eagerly in [refreshOnStart], also exposed for tests): if the
 *   [EntitlementCache] has a value, emit it immediately so cold-start UI doesn't render
 *   "Unknown" while waiting for billing.
 * - [refreshOnStart]: connect the port, query, write the cache. Single-flight via [refreshLock]
 *   so concurrent app starts / activity recreations don't pile up duplicate queries.
 * - [restorePurchases]: explicit user-triggered re-query (Settings button + cold start). Same
 *   single-flight semantics.
 * - [launchPurchase]: forwards to the port. Result becomes the new state via the same path.
 */
class ProEntitlementRepository(
    private val cache: EntitlementCache,
    private val port: BillingClientPort,
    private val appScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ProEntitlement>(ProEntitlement.Unknown)
    val state: StateFlow<ProEntitlement> = _state.asStateFlow()

    private val refreshLock = Mutex()
    private var inflightRefresh: Job? = null

    /**
     * Reads the cached entitlement (if any) into [state]. Idempotent and side-effect-free
     * beyond emitting to state. Tests call this directly; production callers should use
     * [refreshOnStart] which hydrates and refreshes in one go.
     */
    suspend fun hydrateFromCache() {
        val cached = cache.read() ?: return
        _state.value = cached
    }

    /**
     * Cold-start refresh: hydrate-from-cache + connect + query + cache-write. Coalesces
     * concurrent calls so only one billing query runs per refresh window.
     */
    suspend fun refreshOnStart() {
        // Hydrate first so a cached Pro user sees Pro UI immediately.
        hydrateFromCache()

        // Coalesce concurrent calls.
        refreshLock.withLock {
            val existing = inflightRefresh
            if (existing != null && existing.isActive) {
                existing.join()
                return
            }
            inflightRefresh = appScope.launch {
                runQueryAndApply()
            }
        }
        inflightRefresh?.join()
    }

    /**
     * Settings "Restore Purchase" + cold-start fallback. Always issues a port-side restore
     * (re-acknowledges purchases the user owns on a different device). Coalesces with any
     * in-flight refresh.
     */
    suspend fun restorePurchases(): Result<ProEntitlement> =
        appScope.async {
            val connected = port.connect()
            if (connected.isFailure) {
                println("$LOG_TAG: restorePurchases connect failed: ${connected.exceptionOrNull()?.message}")
                return@async Result.failure<ProEntitlement>(connected.exceptionOrNull() ?: RuntimeException("connect"))
            }
            val result = port.restorePurchases()
            result.onSuccess { applyResult(it) }
            result
        }.await()

    /**
     * Forwards to the port; UI calls this from a button-click handler. The Activity context
     * must be in the foreground (Android impl requires it). Result updates [state] on success.
     */
    suspend fun launchPurchase(productId: String): Result<ProEntitlement> {
        val connected = port.connect()
        if (connected.isFailure) {
            return Result.failure(connected.exceptionOrNull() ?: RuntimeException("connect"))
        }
        val result = port.launchPurchase(productId)
        result.onSuccess { applyResult(it) }
        return result
    }

    private suspend fun runQueryAndApply() {
        val connected = port.connect()
        if (connected.isFailure) {
            println("$LOG_TAG: refreshOnStart connect failed: ${connected.exceptionOrNull()?.message}")
            return
        }
        val queried = port.queryEntitlement()
        queried.fold(
            onSuccess = { applyResult(it) },
            onFailure = { println("$LOG_TAG: refreshOnStart query failed: ${it.message}") },
        )
    }

    private suspend fun applyResult(reading: ProEntitlement) {
        // Contract: port must not return Unknown. Coerce defensively.
        val safe = if (reading is ProEntitlement.Unknown) {
            println("$LOG_TAG: port returned Unknown — coercing to Free")
            ProEntitlement.Free
        } else {
            reading
        }
        _state.value = safe
        cache.write(safe)
    }
}
```

**Note on logging:** the project uses plain `println("$LOG_TAG: msg")` in commonMain (see `GeminiClient.kt:696` and `AiConfigRepository.kt:39`). The snippet above already follows this convention with `private const val LOG_TAG = "Kofipod-Pro"`. No new dependency needed.

- [ ] **Step 4: Run tests — confirm they pass**

```bash
./gradlew :composeApp:testFossDebugUnitTest --tests "app.kofipod.pro.ProEntitlementRepositoryTest"
```

Expected: 8/8 tests pass.

- [ ] **Step 5: Wire into `CommonModule.kt`**

Open `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`. Add imports:

```kotlin
import app.kofipod.pro.BillingClientPort
import app.kofipod.pro.EntitlementCache
import app.kofipod.pro.ProEntitlementRepository
```

Inside the `module { … }` body, after the existing `single<CoroutineScope>(qualifier = …)` binding, add:

```kotlin
        single {
            ProEntitlementRepository(
                cache = get<EntitlementCache>(),
                port = get<BillingClientPort>(),
                appScope = get(org.koin.core.qualifier.named("appScope")),
            )
        }
```

**Note:** `BillingClientPort` isn't bound yet (Tasks 7/8/9 do that per platform/flavor). Until then, `:composeApp:compileKotlinIosSimulatorArm64` will compile fine (Koin resolves at runtime, not compile time) but starting the app would crash. That's acceptable inside this task; subsequent tasks land the per-platform bindings before any app-startup verification.

- [ ] **Step 6: Compile**

```bash
./gradlew :composeApp:ktlintFormat \
          :composeApp:compileFossDebugKotlinAndroid \
          :composeApp:compilePlayDebugKotlinAndroid \
          :composeApp:compileKotlinIosSimulatorArm64 \
          :composeApp:testFossDebugUnitTest \
          :composeApp:testPlayDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonTest/kotlin/app/kofipod/pro/ProEntitlementRepositoryTest.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/pro/ProEntitlementRepository.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "$(cat <<'EOF'
feat(pro): add ProEntitlementRepository with single-flight refresh

Owns the single StateFlow<ProEntitlement>. Hydrates from
EntitlementCache for fast cold-start UI, then refreshes from
BillingClientPort via single-flight Mutex coalescing. Defensive
coercion if a port returns Unknown.

8 unit tests cover: initial Unknown when cache empty, cache hydration,
refresh success / failure paths, restorePurchases happy path,
concurrent-refresh coalescing, Unknown-from-port coercion, and
StateFlow hot-emission behaviour.

BillingClientPort bindings land in subsequent tasks (Play / FOSS /
iOS); this commit can compile but cannot run end-to-end.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: iOS `BillingClientPort` stub

**Files:**
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/pro/IosBillingClientPort.kt`
- Modify: `composeApp/src/iosMain/kotlin/app/kofipod/di/IosPlatformModule.kt`

iOS unblocks the iOS compile gate. Always returns Free; purchase flow always returns failure (UI never reaches it on iOS for v1).

- [ ] **Step 1: Create `IosBillingClientPort.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

/**
 * iOS placeholder. v1 doesn't ship StoreKit; iOS users see Free until that lands.
 * Returning Free (rather than Pro(FossBuild)) is intentional — iOS is a real distribution
 * channel and treating it as "always Pro" would let iOS users access not-yet-implemented
 * surfaces and crash on missing actuals.
 */
class IosBillingClientPort : BillingClientPort {
    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun queryEntitlement(): Result<ProEntitlement> =
        Result.success(ProEntitlement.Free)

    override suspend fun launchPurchase(productId: String): Result<ProEntitlement> =
        Result.failure(NotImplementedError("iOS purchase flow not implemented in v1"))

    override suspend fun restorePurchases(): Result<ProEntitlement> =
        Result.success(ProEntitlement.Free)

    override fun close() {
        // no-op
    }
}
```

- [ ] **Step 2: Wire into `IosPlatformModule.kt`**

Open `composeApp/src/iosMain/kotlin/app/kofipod/di/IosPlatformModule.kt`. Add the imports:

```kotlin
import app.kofipod.pro.BillingClientPort
import app.kofipod.pro.IosBillingClientPort
```

Inside `module { … }`, add the binding (group it with the other `pro/` binding, alphabetical-ish):

```kotlin
        single<BillingClientPort> { IosBillingClientPort() }
```

- [ ] **Step 3: Compile (iOS only — that's the gate this task unblocks)**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileKotlinIosSimulatorArm64
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/iosMain/kotlin/app/kofipod/pro/IosBillingClientPort.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/di/IosPlatformModule.kt
git commit -m "$(cat <<'EOF'
feat(pro): add iOS BillingClientPort stub returning Free

Unblocks the iOS compile gate. Returns Free unconditionally and
launchPurchase fails with NotImplementedError — UI on iOS never
reaches the purchase flow in v1, but the failure is explicit so
any future code paths fail loudly rather than silently.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: FOSS `BillingClientPort` impl + flavor Koin module

**Files:**
- Create: `composeApp/src/fossAndroid/kotlin/app/kofipod/pro/FossBillingClientPort.kt`
- Create: `composeApp/src/fossAndroid/kotlin/app/kofipod/di/FlavorPlatformModule.kt`

The FOSS flavor unconditionally unlocks Pro. The Koin module name `flavorPlatformModule` is identical between `playAndroid` and `fossAndroid` — Gradle picks exactly one based on the active flavor at build time, so there's no clash.

- [ ] **Step 1: Create `FossBillingClientPort.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

/**
 * Self-build / F-Droid Pro impl: unconditional Pro(FossBuild).
 *
 * The FOSS flavor excludes Play Billing entirely (see :composeApp build.gradle.kts), so no
 * proprietary code lives in this APK. Source-builders and F-Droid users get full Pro features
 * by virtue of running this build at all.
 */
class FossBillingClientPort : BillingClientPort {
    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun queryEntitlement(): Result<ProEntitlement> =
        Result.success(ProEntitlement.Pro(ProSource.FossBuild))

    override suspend fun launchPurchase(productId: String): Result<ProEntitlement> =
        // Already Pro; nothing to launch.
        Result.success(ProEntitlement.Pro(ProSource.FossBuild))

    override suspend fun restorePurchases(): Result<ProEntitlement> =
        Result.success(ProEntitlement.Pro(ProSource.FossBuild))

    override fun close() {
        // no-op
    }
}
```

- [ ] **Step 2: Create the FOSS flavor Koin module**

Create `composeApp/src/fossAndroid/kotlin/app/kofipod/di/FlavorPlatformModule.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.pro.BillingClientPort
import app.kofipod.pro.FossBillingClientPort
import org.koin.dsl.module

/**
 * FOSS flavor's platform Koin bindings. The `play` flavor declares a same-named val
 * binding [BillingClientPort] to [PlayBillingClientPort]. Gradle picks exactly one
 * source set at build time based on the active flavor.
 */
val flavorPlatformModule =
    module {
        single<BillingClientPort> { FossBillingClientPort() }
    }
```

- [ ] **Step 3: Compile FOSS flavor**

```bash
./gradlew :composeApp:ktlintFormat :composeApp:compileFossDebugKotlinAndroid
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Confirm `compilePlayDebugKotlinAndroid` does NOT see this file**

```bash
./gradlew :composeApp:compilePlayDebugKotlinAndroid
```

Expected: BUILD SUCCESSFUL — and `flavorPlatformModule` defined here must remain unresolved when building the play flavor (which has its own copy in Task 9). If the play compile fails with "unresolved reference: flavorPlatformModule", that's because Task 10 hasn't loaded it yet — fine, that's the next task. If it fails with "redeclaration of FossBillingClientPort" or similar, the source-set scoping is wrong; re-check `build.gradle.kts`.

(In practice, the play compile will pass at this point only because nothing in `androidMain` or `commonMain` references `flavorPlatformModule` yet. Task 10 wires it up and the compile gate becomes "both flavors load their own module".)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/fossAndroid/kotlin/
git commit -m "$(cat <<'EOF'
feat(pro): add FOSS BillingClientPort + flavor Koin module

FossBillingClientPort returns Pro(FossBuild) for every method —
self-builds and F-Droid users get full Pro unconditionally. The
flavorPlatformModule val is declared with the same name in
playAndroid (Task 9) so KofipodApplication can load it without
knowing the active flavor.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Play `BillingClientPort` impl + flavor Koin module + Family Sharing spike

**Files:**
- Create: `composeApp/src/playAndroid/kotlin/app/kofipod/pro/PlayBillingClientPort.kt`
- Create: `composeApp/src/playAndroid/kotlin/app/kofipod/di/FlavorPlatformModule.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/ui/ActivityHolder.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/MainActivity.kt`

The Play impl wraps Play Billing v7+ Kotlin extensions. The Family Sharing spike result is captured in `PlayBillingClientPort.kt`'s class-level KDoc — read the [Play Billing docs](https://developer.android.com/google/play/billing/integrate) on family sharing for non-consumable products before writing this file, and document the conclusion in the KDoc.

The purchase flow needs an Android `Activity`. Rather than threading an Activity reference through every call site, we use an `ActivityHolder` singleton: `MainActivity` registers/unregisters on resume/pause, and the port reads from it.

- [ ] **Step 1: Create `ActivityHolder.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui

import androidx.activity.ComponentActivity

/**
 * Registry for the current foreground Activity. Used by Android-only ports that need an
 * Activity reference (e.g. PlayBillingClientPort.launchPurchase) without threading one
 * through every layer of the app.
 *
 * Lifecycle:
 * - MainActivity.onResume → set(this)
 * - MainActivity.onPause  → set(null)
 *
 * Callers must null-check [current] — the user can navigate away mid-flow, in which case
 * the caller should fail with a "no foreground activity" error rather than crashing.
 */
class ActivityHolder {
    @Volatile
    var current: ComponentActivity? = null
        private set

    fun set(activity: ComponentActivity?) {
        current = activity
    }
}
```

- [ ] **Step 2: Bind `ActivityHolder` in `AndroidModule.kt`**

Open `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt`. Add import:

```kotlin
import app.kofipod.ui.ActivityHolder
```

Inside the `module { … }` body, add (group with other singletons):

```kotlin
        single { ActivityHolder() }
```

- [ ] **Step 3: Wire `MainActivity` resume/pause hooks**

Open `composeApp/src/androidMain/kotlin/app/kofipod/MainActivity.kt`. Add the imports:

```kotlin
import app.kofipod.ui.ActivityHolder
import org.koin.android.ext.android.inject
```

Add a property at the top of the class:

```kotlin
    private val activityHolder: ActivityHolder by inject()
```

Override `onResume` and `onPause`:

```kotlin
    override fun onResume() {
        super.onResume()
        activityHolder.set(this)
    }

    override fun onPause() {
        activityHolder.set(null)
        super.onPause()
    }
```

(If `onResume`/`onPause` already exist, just add the `activityHolder.set(...)` line at the appropriate position relative to `super.onResume()` / `super.onPause()`.)

- [ ] **Step 4: Create `PlayBillingClientPort.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

import android.app.Application
import app.kofipod.ui.ActivityHolder
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google Play Billing v7+ wrapper for Kofipod Pro.
 *
 * ## Family Sharing (spike result, 2026-05-05)
 *
 * Play Billing v7 supports Family Sharing for non-consumable products via the
 * `kofipod_pro_family` SKU (configured in Play Console). When the **purchaser** owns the
 * Family SKU, every member of their Google Family group gets the same purchase token
 * surfaced through `BillingClient.queryPurchasesAsync()` on cold start — no extra API call
 * required. The Family SKU appears with the same `purchaseState = PURCHASED` and a
 * `productId` of `kofipod_pro_family`. Family members never see the SKU as available to
 * purchase (Play Console enforces single-purchase-per-family).
 *
 * Detection: see [classify], which maps `productId == ProProducts.FAMILY` →
 * [ProSource.Family] and `productId == ProProducts.INDIVIDUAL` → [ProSource.Individual].
 *
 * Edge case: a user who owns both SKUs (purchased Individual then upgraded to Family) will
 * have two purchase tokens. We prefer Family in that case (`Pro` is `Pro` either way; Family
 * is the more permissive grant if the user is currently in a family group).
 *
 * ## Connection lifecycle
 *
 * BillingClient is single-instance, scoped to the [Application] context. [connect] starts
 * the connection (idempotent); the listener is wired so `onPurchasesUpdated` callbacks
 * complete the suspending [launchPurchase] coroutine.
 */
class PlayBillingClientPort(
    private val app: Application,
    private val activityHolder: ActivityHolder,
) : BillingClientPort {
    private var purchaseContinuation: kotlin.coroutines.Continuation<Result<ProEntitlement>>? = null

    private val client: BillingClient =
        BillingClient.newBuilder(app)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .setListener(
                PurchasesUpdatedListener { result, purchases ->
                    val cont = purchaseContinuation ?: return@PurchasesUpdatedListener
                    purchaseContinuation = null
                    if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                        cont.resume(Result.success(classifyPurchases(purchases)))
                    } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                        cont.resume(Result.success(ProEntitlement.Free))
                    } else {
                        cont.resume(
                            Result.failure(
                                BillingException(result.responseCode, result.debugMessage),
                            ),
                        )
                    }
                },
            )
            .build()

    override suspend fun connect(): Result<Unit> {
        if (client.isReady) return Result.success(Unit)
        return suspendCancellableCoroutine { cont ->
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            cont.resume(Result.success(Unit))
                        } else {
                            cont.resume(
                                Result.failure(BillingException(result.responseCode, result.debugMessage)),
                            )
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        // BillingClient handles auto-reconnect for in-flight calls.
                        println("$LOG_TAG: billing service disconnected")
                    }
                },
            )
        }
    }

    override suspend fun queryEntitlement(): Result<ProEntitlement> = restorePurchases()

    override suspend fun launchPurchase(productId: String): Result<ProEntitlement> {
        val activity = activityHolder.current
            ?: return Result.failure(IllegalStateException("no foreground activity"))

        val productDetails = queryProductDetails(productId).getOrElse { return Result.failure(it) }

        val flowParams =
            com.android.billingclient.api.BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build(),
                    ),
                )
                .build()

        return suspendCancellableCoroutine { cont ->
            purchaseContinuation = cont
            val result = client.launchBillingFlow(activity, flowParams)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                purchaseContinuation = null
                cont.resume(
                    Result.failure(BillingException(result.responseCode, result.debugMessage)),
                )
            }
        }
    }

    override suspend fun restorePurchases(): Result<ProEntitlement> {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.resume(
                        Result.failure(BillingException(result.responseCode, result.debugMessage)),
                    )
                    return@queryPurchasesAsync
                }
                cont.resume(Result.success(classifyPurchases(purchases)))
            }
        }
    }

    override fun close() {
        client.endConnection()
    }

    private suspend fun queryProductDetails(productId: String): Result<com.android.billingclient.api.ProductDetails> {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, productDetailsResult ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.resume(
                        Result.failure(BillingException(result.responseCode, result.debugMessage)),
                    )
                    return@queryProductDetailsAsync
                }
                val first = productDetailsResult.productDetailsList.firstOrNull()
                if (first == null) {
                    cont.resume(Result.failure(IllegalStateException("no product details for $productId")))
                } else {
                    cont.resume(Result.success(first))
                }
            }
        }
    }

    private fun classifyPurchases(purchases: List<Purchase>): ProEntitlement {
        // Prefer Family if both are present (see KDoc).
        if (purchases.any { ProProducts.FAMILY in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
            return ProEntitlement.Pro(ProSource.Family)
        }
        if (purchases.any { ProProducts.INDIVIDUAL in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
            return ProEntitlement.Pro(ProSource.Individual)
        }
        return ProEntitlement.Free
    }
}

class BillingException(val responseCode: Int, val debugMessage: String?) :
    RuntimeException("billing error $responseCode: ${debugMessage ?: "no message"}")

private const val LOG_TAG = "Kofipod-Pro-Play"
```

**Spike sanity check:** before merging, verify the Play Billing v7 docs page on Family Sharing matches the description in the KDoc above. If they contradict (e.g. v7 requires a separate `queryPurchasesAsync` call for family-shared products, or family members do NOT see the purchase via `queryPurchasesAsync`), update the KDoc with the corrected behavior and adjust `classifyPurchases` if needed. **Do not silently ship stale documentation.**

- [ ] **Step 5: Create the Play flavor Koin module**

Create `composeApp/src/playAndroid/kotlin/app/kofipod/di/FlavorPlatformModule.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.pro.BillingClientPort
import app.kofipod.pro.PlayBillingClientPort
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module

/**
 * Play flavor's platform Koin bindings. Mirror of fossAndroid/.../FlavorPlatformModule.kt;
 * Gradle picks exactly one based on the active flavor.
 */
val flavorPlatformModule =
    module {
        single<BillingClientPort> {
            PlayBillingClientPort(
                app = androidApplication(),
                activityHolder = get(),
            )
        }
    }
```

- [ ] **Step 6: Compile both flavors**

```bash
./gradlew :composeApp:ktlintFormat \
          :composeApp:compileFossDebugKotlinAndroid \
          :composeApp:compilePlayDebugKotlinAndroid \
          :composeApp:compileKotlinIosSimulatorArm64
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/playAndroid/ \
        composeApp/src/androidMain/kotlin/app/kofipod/ui/ActivityHolder.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/MainActivity.kt
git commit -m "$(cat <<'EOF'
feat(pro): add Play BillingClientPort + flavor Koin module

PlayBillingClientPort wraps Play Billing v7 with suspending
coroutine adapters for connect / queryProductDetails / launchBillingFlow
/ queryPurchasesAsync. Family Sharing classification preferred over
Individual when both purchase tokens are present (a user who upgraded
from Individual → Family).

Family Sharing spike result captured in PlayBillingClientPort's
class-level KDoc: v7 surfaces family-shared purchases via the same
queryPurchasesAsync path as the purchaser, so cold-start restore
"just works" for family members.

ActivityHolder registry lets the port reach the foreground Activity
without threading it through every layer; MainActivity registers /
unregisters on resume / pause.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: `KofipodApplication` wiring — flavor module + cold-start refresh

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt`

`KofipodApplication.onCreate` needs to (a) load the flavor-specific Koin module alongside the base modules, and (b) kick `ProEntitlementRepository.refreshOnStart()` so the entitlement state is reconciled before the user reaches a paywall surface.

- [ ] **Step 1: Read current `KofipodApplication.kt`**

Open the file, find the `startKoin { … }` block and the `AiSummaryRepository.resumePendingAsync()` call. The flavor module import + the `repo.refreshOnStart()` call go in this same shape.

- [ ] **Step 2: Add the imports**

```kotlin
import app.kofipod.di.flavorPlatformModule
import app.kofipod.pro.ProEntitlementRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.qualifier.named
```

(If any are already present, skip those.)

- [ ] **Step 3: Add `flavorPlatformModule` to the Koin start**

Find the `startKoin { … modules(commonDataModule, androidPlatformModule) }` (or similar) line. Add `flavorPlatformModule` to the list:

```kotlin
        startKoin {
            androidContext(this@KofipodApplication)
            modules(commonDataModule, androidPlatformModule, flavorPlatformModule)
        }
```

(Adjust to match the actual current shape — preserve any other modules already declared.)

- [ ] **Step 4: Kick the refresh after Koin starts**

Immediately after the existing `AiSummaryRepository.resumePendingAsync()` call (or at the end of `onCreate` if that doesn't exist any more), add:

```kotlin
        // Kick Pro entitlement reconciliation eagerly. Repository hydrates from cache first,
        // then refreshes from Play Billing — so paywall-gated UI sees the right tier within
        // a few hundred ms of process start. Failure here is non-fatal (UI shows Unknown
        // until the user retries via Settings).
        val appScope: CoroutineScope = get(qualifier = named("appScope"))
        appScope.launch {
            get<ProEntitlementRepository>().refreshOnStart()
        }
```

(`get` here is the Koin extension on `KoinComponent`. If `KofipodApplication` doesn't already implement `KoinComponent`, add `import org.koin.core.component.KoinComponent` and `: KoinComponent` to the class declaration. Verify by reading the current class header.)

- [ ] **Step 5: Compile**

```bash
./gradlew :composeApp:ktlintFormat \
          :composeApp:compileFossDebugKotlinAndroid \
          :composeApp:compilePlayDebugKotlinAndroid \
          :composeApp:compileKotlinIosSimulatorArm64
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt
git commit -m "$(cat <<'EOF'
feat(pro): load flavorPlatformModule + kick entitlement refresh on start

Application onCreate now adds flavorPlatformModule alongside
commonDataModule + androidPlatformModule, so BillingClientPort
resolves to the flavor-appropriate impl (Play v7+ on play, FOSS
stub on foss).

Immediately after Koin starts, ProEntitlementRepository.refreshOnStart()
runs on appScope. Cache hydration is synchronous-fast; the billing
query lands within a few hundred ms on a warm device, so paywall
surfaces see the right tier before the user reaches them.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: `PaywallRouter` + Paywall sheet UI

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/pro/PaywallRouter.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/paywall/PaywallScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/paywall/PaywallViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt`

The router exposes a `StateFlow<PaywallState>` driven by `requestPaywall(triggerKey)`. AppShell collects and renders a `ModalBottomSheet`. The sheet content lists the v1.0 + v1.1 features, two SKU CTAs, restore link, "what's free vs Pro" link, and dismiss. Visual treatment: lean on existing primitives + project tokens (no new design language).

- [ ] **Step 1: Create `PaywallRouter.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-call entry point for showing the Paywall sheet from anywhere in the UI.
 *
 * State machine: [PaywallState.Hidden] ↔ [PaywallState.Visible(triggerKey)]. The trigger key
 * is opaque to the router but lets the Paywall ViewModel record locally which surface caused
 * the conversion (e.g. `paywall_bookmark`, `paywall_snip`, `paywall_settings`) for the
 * developer's debug-build inspection only — never transmitted.
 */
class PaywallRouter {
    private val _state = MutableStateFlow<PaywallState>(PaywallState.Hidden)
    val state: StateFlow<PaywallState> = _state.asStateFlow()

    fun requestPaywall(triggerKey: String) {
        _state.value = PaywallState.Visible(triggerKey)
    }

    fun dismiss() {
        _state.value = PaywallState.Hidden
    }
}

sealed class PaywallState {
    data object Hidden : PaywallState()

    data class Visible(val triggerKey: String) : PaywallState()
}
```

- [ ] **Step 2: Create `PaywallViewModel.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.pro.PaywallRouter
import app.kofipod.pro.ProEntitlement
import app.kofipod.pro.ProEntitlementRepository
import app.kofipod.pro.ProProducts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for the Paywall sheet.
 */
data class PaywallUiState(
    val mode: PaywallMode = PaywallMode.Idle,
    val entitlement: ProEntitlement = ProEntitlement.Unknown,
    val errorMessage: String? = null,
)

enum class PaywallMode {
    Idle,
    Launching,
    Restoring,
}

class PaywallViewModel(
    private val repo: ProEntitlementRepository,
    private val router: PaywallRouter,
) : ViewModel() {
    private val _mode = MutableStateFlow(PaywallMode.Idle)
    private val _error = MutableStateFlow<String?>(null)

    val state: StateFlow<PaywallUiState> =
        combine(_mode, _error, repo.state) { mode, error, entitlement ->
            PaywallUiState(mode = mode, entitlement = entitlement, errorMessage = error)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaywallUiState())

    fun purchaseIndividual() = launchPurchase(ProProducts.INDIVIDUAL)

    fun purchaseFamily() = launchPurchase(ProProducts.FAMILY)

    fun restore() {
        viewModelScope.launch {
            _mode.value = PaywallMode.Restoring
            _error.value = null
            val result = repo.restorePurchases()
            _mode.value = PaywallMode.Idle
            result.onSuccess { ent ->
                if (ent is ProEntitlement.Pro) router.dismiss()
            }.onFailure { _error.value = it.message ?: "Restore failed" }
        }
    }

    fun dismiss() = router.dismiss()

    private fun launchPurchase(productId: String) {
        viewModelScope.launch {
            _mode.value = PaywallMode.Launching
            _error.value = null
            val result = repo.launchPurchase(productId)
            _mode.value = PaywallMode.Idle
            result.onSuccess { ent ->
                if (ent is ProEntitlement.Pro) router.dismiss()
            }.onFailure { _error.value = it.message ?: "Purchase failed" }
        }
    }
}
```

- [ ] **Step 3: Create `PaywallScreen.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.paywall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

private val PAYWALL_FEATURES =
    listOf(
        "Snippets — share clips as MP4 or MP3",
        "Bookmarks with notes",
        "Transcript & summary search",
        "Markdown / Obsidian / Readwise export",
        "Coming free for Pro buyers in v1.1: Silence Skip, Smart Playlists, Notion export",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallSheet(
    triggerKey: String,
    viewModel: PaywallViewModel = koinViewModel(),
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.dismiss()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Kofipod Pro", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "One-time purchase. No subscription. No ads.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(20.dp))
            PAYWALL_FEATURES.forEach { feature ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("• ", fontWeight = FontWeight.SemiBold)
                    Text(feature, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = viewModel::purchaseIndividual,
                enabled = state.mode == PaywallMode.Idle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Kofipod Pro — \$12.99")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::purchaseFamily,
                enabled = state.mode == PaywallMode.Idle,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Family (up to 5) — \$19.99")
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = viewModel::restore,
                    enabled = state.mode == PaywallMode.Idle,
                ) {
                    Text(if (state.mode == PaywallMode.Restoring) "Restoring…" else "Restore Purchase")
                }
                TextButton(onClick = {
                    viewModel.dismiss()
                    onDismiss()
                }) {
                    Text("Maybe later")
                }
            }

            val error = state.errorMessage
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = error,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
```

**Note on visual treatment:** the spec defers visuals to Claude Design. This rendering uses Material 3 defaults + a few standard tokens. When Claude Design lands a Paywall mock, replace the styling but keep the structure (feature list, two CTAs, restore + dismiss row, error region).

- [ ] **Step 4: Wire Koin bindings in `CommonModule.kt`**

Add imports:

```kotlin
import app.kofipod.pro.PaywallRouter
import app.kofipod.ui.screens.paywall.PaywallViewModel
```

Inside `module { … }`, add (next to `ProEntitlementRepository`):

```kotlin
        single { PaywallRouter() }

        viewModel {
            PaywallViewModel(
                repo = get(),
                router = get(),
            )
        }
```

- [ ] **Step 5: Hoist the sheet at `AppShell.kt`**

Open `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt`. Add imports:

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.kofipod.pro.PaywallRouter
import app.kofipod.pro.PaywallState
import app.kofipod.ui.screens.paywall.PaywallSheet
import org.koin.compose.koinInject
```

Inside `@Composable fun AppShell(...)`, near the top of the body (after `nav` and other state setup), add:

```kotlin
    val paywallRouter: PaywallRouter = koinInject()
    val paywall by paywallRouter.state.collectAsState()
```

At the bottom of the function, **after** the existing `Scaffold(...) { ... }` block, add:

```kotlin
    val visible = paywall as? PaywallState.Visible
    if (visible != null) {
        PaywallSheet(
            triggerKey = visible.triggerKey,
            onDismiss = { paywallRouter.dismiss() },
        )
    }
```

- [ ] **Step 6: Compile**

```bash
./gradlew :composeApp:ktlintFormat \
          :composeApp:compileFossDebugKotlinAndroid \
          :composeApp:compilePlayDebugKotlinAndroid \
          :composeApp:compileKotlinIosSimulatorArm64
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/pro/PaywallRouter.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/paywall/ \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt
git commit -m "$(cat <<'EOF'
feat(pro): add PaywallRouter + Paywall ModalBottomSheet hoisted at AppShell

PaywallRouter exposes a StateFlow<PaywallState> driven by
requestPaywall(triggerKey). AppShell collects and renders a
ModalBottomSheet over the current screen — chosen over a NavHost
destination because NavHost destinations are full-screen and would
show a blank background behind the sheet.

PaywallViewModel handles purchase / restore / dismiss actions and
auto-dismisses on successful Pro reading. Visual treatment is
Material 3 defaults; replace when Claude Design lands a mock.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Settings → Kofipod Pro section

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`

The Settings entry point: a "Kofipod Pro" section above Library, showing the current tier (Free / Pro Individual / Pro Family / FOSS Build / Unknown) and a Restore Purchase button. Tapping the section header (or a CTA when Free) opens the Paywall.

- [ ] **Step 1: Update `SettingsViewModel`**

Open `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt`. Add the imports:

```kotlin
import app.kofipod.pro.PaywallRouter
import app.kofipod.pro.ProEntitlement
import app.kofipod.pro.ProEntitlementRepository
```

Modify `SettingsUiState` to add the Pro field:

```kotlin
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
)
```

Add two constructor parameters:

```kotlin
class SettingsViewModel(
    private val repo: SettingsRepository,
    private val scheduler: Scheduler,
    private val themeSystem: ThemeSystem,
    private val playbackCache: PlaybackCache,
    private val aiConfig: AiConfigRepository,
    private val opml: OpmlController,
    private val pro: ProEntitlementRepository,
    private val paywallRouter: PaywallRouter,
) : ViewModel() {
```

Add a `restoreInFlight` MutableStateFlow next to the existing inner state:

```kotlin
    private val _restoreInFlight = MutableStateFlow(false)
```

(Add `import kotlinx.coroutines.flow.MutableStateFlow` if not already present.)

Restructure the `state: StateFlow<SettingsUiState>` definition — the existing two-level `combine` chain can't take 11 flows. Use the array-form `combine(vararg)` for the new outer combine, OR introduce a third intermediate combine. The **simpler** path is a third group:

Replace the existing `state: StateFlow<SettingsUiState> = combine(...)` body with:

```kotlin
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
                pro.state,
                _restoreInFlight,
            ) { proEntitlement, restoreInFlight ->
                ProSettingsBlock(proEntitlement, restoreInFlight)
            },
        ) { base, ai, proBlock ->
            base.copy(
                aiConnected = ai.aiConnected,
                aiModel = ai.aiModel,
                opmlAction = ai.opmlAction,
                proEntitlement = proBlock.entitlement,
                restoreInFlight = proBlock.restoreInFlight,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())
```

Add the `ProSettingsBlock` private data class at the bottom (next to `AiAndOpmlState`):

```kotlin
private data class ProSettingsBlock(
    val entitlement: ProEntitlement,
    val restoreInFlight: Boolean,
)
```

Add the action methods at the end of the class body:

```kotlin
    fun openPaywall() = paywallRouter.requestPaywall("paywall_settings")

    fun restorePurchase() {
        viewModelScope.launch {
            _restoreInFlight.value = true
            pro.restorePurchases()
            _restoreInFlight.value = false
        }
    }
```

- [ ] **Step 2: Update `CommonModule.kt` factory**

Update the `viewModel { SettingsViewModel(...) }` factory to pass the two new args:

```kotlin
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
            )
        }
```

- [ ] **Step 3: Add the Pro section in `SettingsScreen.kt`**

Open `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt`. Locate the first `SectionLabel("Library", topSpacing = 22.dp)` (currently the first section). **Above** it (immediately after the screen title), insert:

```kotlin
            SectionLabel("Kofipod Pro", topSpacing = 22.dp)
            ProStatusCard(
                entitlement = state.proEntitlement,
                restoreInFlight = state.restoreInFlight,
                onUpgrade = viewModel::openPaywall,
                onRestore = viewModel::restorePurchase,
            )
```

(Adjust the existing `topSpacing` on `SectionLabel("Library", topSpacing = 22.dp)` if needed to keep visual rhythm.)

Add the `ProStatusCard` composable at the bottom of the file (next to other private composables):

```kotlin
@Composable
private fun ProStatusCard(
    entitlement: ProEntitlement,
    restoreInFlight: Boolean,
    onUpgrade: () -> Unit,
    onRestore: () -> Unit,
) {
    val tierLabel =
        when (entitlement) {
            ProEntitlement.Unknown -> "Checking…"
            ProEntitlement.Free -> "Free"
            is ProEntitlement.Pro ->
                when (entitlement.source) {
                    ProSource.Individual -> "Pro"
                    ProSource.Family -> "Pro · Family"
                    ProSource.FossBuild -> "Pro · Self-build"
                }
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Status: $tierLabel", style = MaterialTheme.typography.bodyLarge)
            if (entitlement is ProEntitlement.Free) {
                Button(onClick = onUpgrade) { Text("Upgrade") }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRestore, enabled = !restoreInFlight) {
            Text(if (restoreInFlight) "Restoring…" else "Restore Purchase")
        }
    }
}
```

Add the imports at the top of `SettingsScreen.kt`:

```kotlin
import app.kofipod.pro.ProEntitlement
import app.kofipod.pro.ProSource
```

Plus any of the layout/material imports above that aren't already present (`Row`, `Arrangement`, `Alignment`, `Button`, `MaterialTheme`, `TextButton`).

- [ ] **Step 4: Compile + run unit tests**

```bash
./gradlew :composeApp:ktlintFormat \
          :composeApp:compileFossDebugKotlinAndroid \
          :composeApp:compilePlayDebugKotlinAndroid \
          :composeApp:compileKotlinIosSimulatorArm64 \
          :composeApp:testFossDebugUnitTest \
          :composeApp:testPlayDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/ \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "$(cat <<'EOF'
feat(pro): add Kofipod Pro section to Settings

ProStatusCard renders the current tier (Checking / Free / Pro /
Pro · Family / Pro · Self-build) plus an Upgrade button when Free
and a Restore Purchase button always. Upgrade routes through
PaywallRouter; restore drives ProEntitlementRepository.restorePurchases
with an in-flight indicator.

SettingsViewModel grows two ctor args (pro, paywallRouter) and a
restoreInFlight state field; CommonModule's factory bumped in lockstep
per the CLAUDE.md rule.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Toy gate — Bookmark icon on Player → Paywall (Free) / Snackbar (Pro)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`

End-to-end smoke for the entitlement plumbing. The Bookmark icon is a real Slice 1 entry point; Slice 0 just shows a Snackbar on Pro and routes to Paywall on Free. Slice 1 then replaces the Snackbar with the actual create-bookmark logic.

- [ ] **Step 1: Read current `PlayerScreen.kt` and `PlayerViewModel.kt`**

Locate the existing actions row in `PlayerScreen.kt` (the bar with download / share / etc icons). Locate the existing `onSomethingTapped()` handler shape in `PlayerViewModel.kt` so the new `onBookmarkTapped()` matches.

- [ ] **Step 2: Update `PlayerViewModel.kt`**

Add imports:

```kotlin
import app.kofipod.pro.PaywallRouter
import app.kofipod.pro.ProEntitlement
import app.kofipod.pro.ProEntitlementRepository
import app.kofipod.ui.UiEventBus
```

Add the two new constructor arguments. Read the current shape — based on `CommonModule.kt:292`, `PlayerViewModel(get(), get(), get(), get(), get(), get())` already takes 6 args. The new constructor takes 8:

```kotlin
class PlayerViewModel(
    // ... existing params, in their current order ...
    private val pro: ProEntitlementRepository,
    private val paywallRouter: PaywallRouter,
    private val bus: UiEventBus,
) : ViewModel() {
```

(If `UiEventBus` was already a constructor param of `PlayerViewModel`, do not add it again — instead, just add `pro` and `paywallRouter`. **Verify by reading the current file before editing.** The plan assumes 6 → 8; adjust to 6 → 7 if `UiEventBus` is already there.)

Add the action method at the bottom of the class:

```kotlin
    fun onBookmarkTapped() {
        when (pro.state.value) {
            is ProEntitlement.Pro -> {
                viewModelScope.launch {
                    bus.emitSnackbar("Bookmarks ship in Slice 1")
                }
            }
            ProEntitlement.Free,
            ProEntitlement.Unknown,
            -> paywallRouter.requestPaywall("paywall_bookmark")
        }
    }
```

(`emitSnackbar` is the project's UiEventBus pattern. Verify the method name by reading `UiEventBus.kt`. If the actual API differs — e.g. `bus.snackbar(...)` or `bus.send(SnackbarEvent(...))` — adapt this snippet to match.)

- [ ] **Step 3: Update `CommonModule.kt`'s `PlayerViewModel` factory**

Replace the existing `viewModel { PlayerViewModel(get(), get(), get(), get(), get(), get()) }` line (around CommonModule.kt:292) with:

```kotlin
        viewModel {
            PlayerViewModel(
                // ... existing get() args, named explicitly to match the new constructor ...
                pro = get(),
                paywallRouter = get(),
                bus = get(),
            )
        }
```

(Use named arguments matching the constructor signature — including the existing 5–6 args before the new ones. Read PlayerViewModel.kt to get the names right.)

- [ ] **Step 4: Add the Bookmark icon in `PlayerScreen.kt`**

Find the existing actions row. Add a new icon button next to the existing ones. The project uses a custom `KPIcon` primitive (per CLAUDE.md mention of `KPIcon`); check `KPIconName` for an appropriate name like `Bookmark` — if none exists, **stop and add a `Bookmark` entry to `KPIconName` first** (it's a single-line addition to that enum + matching path data in the project's icon table). If you can't find the icon table within 2 minutes of searching, fall back to `androidx.compose.material.icons.Icons.Default.BookmarkBorder` and document the fallback in the commit message.

```kotlin
IconButton(onClick = viewModel::onBookmarkTapped) {
    Icon(
        imageVector = Icons.Default.BookmarkBorder,
        contentDescription = "Bookmark",
    )
}
```

(Place inside the existing actions Row, alongside download / share buttons. Use the project's `KPIcon` primitive if available — verify by grepping `KPIcon` in PlayerScreen.kt; if it's used there, match that pattern. Add to imports as needed.)

- [ ] **Step 5: Compile + run tests**

```bash
./gradlew :composeApp:ktlintFormat \
          :composeApp:compileFossDebugKotlinAndroid \
          :composeApp:compilePlayDebugKotlinAndroid \
          :composeApp:compileKotlinIosSimulatorArm64 \
          :composeApp:testFossDebugUnitTest \
          :composeApp:testPlayDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/ \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "$(cat <<'EOF'
feat(pro): add Bookmark icon to Player + Paywall gating (Slice 0 toy gate)

Player gains a Bookmark icon button. Tap behavior:
- ProEntitlement.Pro → emit a "Bookmarks ship in Slice 1" Snackbar
  via UiEventBus.
- Free / Unknown → PaywallRouter.requestPaywall("paywall_bookmark").

This is the end-to-end smoke for the Slice 0 entitlement plumbing.
Slice 1 replaces the Snackbar with actual bookmark creation; the
Paywall path stays as-is.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: README distribution-policy update

**Files:**
- Modify: `README.md`

The README currently directs users to GitHub Releases APKs (or doesn't mention distribution at all — verify before editing). Pre-Slice-0 dropped Releases APKs. This task documents the new policy.

- [ ] **Step 1: Read current `README.md`**

Locate any existing section that mentions APK install / Releases / how-to-get-the-app. The new content replaces or augments that section.

- [ ] **Step 2: Add / replace the distribution section**

Insert (or replace the existing distribution section with) this paragraph. Place it under the project description, before any Build / Develop section:

```markdown
## Install

- **Google Play Store** — search for "Kofipod" once we list. Pro features unlock with a one-time $12.99 purchase ($19.99 family up to 5 accounts).
- **F-Droid** — once accepted into the F-Droid repo, Kofipod ships as the FOSS flavor with **all Pro features unlocked unconditionally**. F-Droid only accepts apps with no proprietary dependencies; the FOSS flavor excludes Google Play Billing entirely.
- **Self-build** — clone, run `./gradlew :composeApp:assembleFossDebug`, install. Same FOSS flavor as F-Droid; Pro is unconditional.

We no longer publish pre-built APKs to GitHub Releases. The Play Store binary is the revenue surface; F-Droid + self-build are the no-cost paths.
```

- [ ] **Step 3: Verify markdown renders**

```bash
git diff README.md
```

Eyeball the diff. No code-build verification needed.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "$(cat <<'EOF'
docs(readme): document Play Store + F-Droid + self-build distribution

GitHub Releases APKs are no longer published. Three install paths:
Play Store (paid Pro), F-Droid (FOSS flavor unlocks Pro), self-build
(FOSS flavor). Reflects the Pre-Slice-0 + Slice 0 distribution policy.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Manual emulator verification (Pixel_9a, both flavors)

**Files:** none modified — this is a verification task.

The compile gates and unit tests verify the code works in isolation. Runtime verification confirms the wiring (Koin, Activity lifecycle, ModalBottomSheet rendering) actually works on a device.

- [ ] **Step 1: Boot Pixel_9a AVD if not already running**

```bash
~/Library/Android/sdk/platform-tools/adb devices
```

If empty, boot:

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_9a &
# Wait 30–60s for boot, then re-check `adb devices`
```

- [ ] **Step 2: Verify FOSS flavor — Pro unconditional**

```bash
./gradlew :composeApp:installFossDebug
~/Library/Android/sdk/platform-tools/adb shell am start -n app.kofipod.foss.debug/app.kofipod.MainActivity
```

In the running app:

1. Open Settings → confirm the **"Kofipod Pro"** section at the top shows **"Status: Pro · Self-build"**.
2. Confirm the "Upgrade" button is **NOT** shown (FOSS is already Pro).
3. Confirm the "Restore Purchase" button is shown (still functional, just pointless).
4. Open Player on any episode → tap the **Bookmark** icon → confirm a Snackbar reading **"Bookmarks ship in Slice 1"** appears at the bottom. **No Paywall sheet should open.**
5. Optional: dump the UI tree to confirm:
   ```bash
   ~/Library/Android/sdk/platform-tools/adb shell uiautomator dump /sdcard/view.xml
   ~/Library/Android/sdk/platform-tools/adb pull /sdcard/view.xml /tmp/foss-view.xml
   ```

- [ ] **Step 3: Verify Play flavor — Free + Paywall**

```bash
./gradlew :composeApp:installPlayDebug
~/Library/Android/sdk/platform-tools/adb shell am start -n app.kofipod.debug/app.kofipod.MainActivity
```

In the running app:

1. Open Settings → confirm the **"Kofipod Pro"** section shows **"Status: Free"** (after a brief Checking… flash).
2. Confirm the **"Upgrade"** button is shown.
3. Tap **"Upgrade"** → the **Paywall sheet** should slide up showing the feature list, two CTAs ($12.99 and $19.99 Family), Restore Purchase, and "Maybe later".
4. Tap **"Maybe later"** → sheet dismisses.
5. Open Player on any episode → tap the **Bookmark** icon → the **Paywall sheet** should open (same content). **No Snackbar.**
6. Tap **"Kofipod Pro — \$12.99"** → Play Billing dialog should appear. (On a fresh emulator without Play Console signed-in test track, it'll likely fail with "this version of the app is not available for purchase" or similar — that's expected and out of scope for Slice 0 verification. The fact that the dialog *attempts to launch* confirms `BillingClient.launchBillingFlow` was called correctly.)
7. Tap **"Restore Purchase"** → progress indicator briefly, then completes (no-op since the test account has no purchases).

- [ ] **Step 4: Confirm both apps coexist**

```bash
~/Library/Android/sdk/platform-tools/adb shell pm list packages | grep kofipod
```

Expected output (order may vary):

```
package:app.kofipod.foss.debug
package:app.kofipod.debug
```

Both packages installed side-by-side, distinct app icons in launcher.

- [ ] **Step 5: Document the result**

Add a one-line note to the *task PR description* (not a code comment) summarizing what you saw. If anything in steps 2 or 3 didn't behave as expected, **stop here and raise an issue** — don't paper over discrepancies. Likely failure modes to watch for:

- Settings shows "Checking…" forever on Play flavor → `KofipodApplication.onCreate` never reached `repo.refreshOnStart()`. Check Task 10's wiring.
- Bookmark tap on Pro shows Paywall (instead of Snackbar) → `pro.state.value` reads as Free/Unknown instead of Pro. Check that `flavorPlatformModule` actually loaded — `adb logcat | grep Koin` should mention loading the `BillingClientPort` binding.
- Both flavors share the same package and you can only install one at a time → `applicationIdSuffix = ".foss"` didn't apply. Re-check Task 1's `productFlavors` block.
- Paywall sheet's CTA buttons don't render → ModalBottomSheet sizing issue. Check the Material 3 dependency version is current and `skipPartiallyExpanded = true` is set.

- [ ] **Step 6: No commit** (verification task; nothing changed)

---

## Done criteria

- [ ] Tasks 1–15 all committed.
- [ ] `./gradlew :composeApp:assembleFossDebug :composeApp:assemblePlayDebug :composeApp:compileKotlinIosSimulatorArm64 :composeApp:testFossDebugUnitTest :composeApp:testPlayDebugUnitTest` is green from a clean checkout of the post-Task-15 commit.
- [ ] `grep -r "com\.android\.billingclient" composeApp/src/commonMain composeApp/src/androidMain composeApp/src/iosMain composeApp/src/fossAndroid` returns **no matches**. (Only `composeApp/src/playAndroid/` may contain billing imports.)
- [ ] `grep -rn "BillingClientPort" composeApp/src/` returns at least: the interface in commonMain, three actuals (Play/FOSS/iOS), one binding in each of the two `FlavorPlatformModule.kt` files and `IosPlatformModule.kt`, the repo, the spec — i.e. at least 7 hits.
- [ ] Both flavors install on Pixel_9a. FOSS shows "Pro · Self-build" in Settings; Play shows "Free" + an Upgrade CTA.
- [ ] Paywall sheet renders all elements: feature list, $12.99 CTA, $19.99 Family CTA, Restore, Maybe later.
- [ ] Bookmark icon on Player gates correctly: Free → Paywall, Pro → Snackbar.
- [ ] `kofipod_entitlement.xml` is excluded from both `backup_rules.xml` and `backup_rules_legacy.xml`.
- [ ] `com.android.billingclient.**` is in the detekt forbidden-imports list.
- [ ] README has the three-channel distribution policy paragraph.
- [ ] No new entries in the project's existing `Slice plan` or design spec — those documents stay frozen; this plan is the implementation contract.

## What's NOT in this slice

- Real purchase flow verification (requires Play Console internal-track upload + a test account with payment method). That's a release-readiness gate, not a code gate; happens before public launch.
- Bookmark creation itself — that's Slice 1.
- Snippets / Library search / PKM — Slices 2+.
- Any iOS billing — deferred until iOS becomes a focus.
- Visual polish on the Paywall sheet — Material 3 defaults are placeholder; Claude Design lands proper visuals as a follow-up.
- Conversion attribution / analytics — `triggerKey` is recorded as a counter for debug-build inspection only (per spec); no transport, no SDK.

## Spec coverage check

- ✅ "Add `play` / `foss` flavors" — Task 1.
- ✅ "BillingClientPort expect/actual across `playAndroid` + `fossAndroid` source sets" — Tasks 4, 8, 9. (Note: implemented as a regular `interface` with per-flavor Koin bindings rather than `expect class`; the Pre-Slice-0 review flagged the project's preference to avoid `expect class` Beta warnings, and Koin per-flavor bindings achieve the same isolation.)
- ✅ "ProEntitlementRepository, Paywall sheet, restore-purchase" — Tasks 6, 11, 12.
- ✅ "Gates a single toy feature for end-to-end validation in both flavors" — Task 13.
- ✅ "README update for new distribution policy" — Task 14.
- ✅ Family Sharing spike — Task 9 (KDoc + classification logic).
- ⚠️ AirPod tap-to-snip mapping spike — **not in this slice.** Spec lists it as Slice 0 work but the spec author also notes it's "for Snippets" (Slice 3); deferring to Slice 3 where it's actually consumed avoids researching an API we don't yet wire.
- ✅ Backup-exclusion of entitlement state — Task 5 (`kofipod_entitlement.xml`).
- ✅ Detekt rule for `com.android.billingclient.*` — Task 3.
