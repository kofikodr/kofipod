# Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in crash reporting (GlitchTip via Sentry KMP SDK) and anonymized usage telemetry (Aptabase) to Kofipod, gated by a one-time first-launch disclosure and per-channel toggles in Settings.

**Architecture:** Two parallel pipelines under `app.kofipod.diagnostics/` — `CrashReporter` and `Telemetry`, both `expect class` with Android actuals that lazy-init their respective SDKs and an iOS no-op stub. A `DiagnosticsConfigRepository` exposes three boolean flows (`crashesEnabled`, `usageEnabled`, `disclosureAcknowledged`) backed by the existing `kofipod_secure` `EncryptedSharedPreferences` file. A `DiagnosticsBootstrapper` combines flows and toggles SDKs on/off; until disclosure is acknowledged, both subsystems stay disabled regardless of toggle state. A bottom-sheet disclosure is shown once on first launch from `AppShell`. Event vocabulary lives in a sealed `TelemetryEvent` class so the entire telemetry surface is grep-able.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Sentry Kotlin Multiplatform SDK (`io.sentry:sentry-kotlin-multiplatform`), Aptabase Kotlin SDK (`com.aptabase:aptabase-kotlin`), AndroidX Security `EncryptedSharedPreferences`, Koin, BuildKonfig, kotlinx.coroutines Flow, JUnit4, Compose UI Test, Paparazzi.

**Spec:** `docs/superpowers/specs/2026-05-05-diagnostics-design.md`

---

## File Structure

**New files:**

| Path | Responsibility |
|---|---|
| `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/DiagnosticsConfigRepository.kt` | Interface for the three flag flows |
| `composeApp/src/androidMain/kotlin/app/kofipod/diagnostics/AndroidDiagnosticsConfigRepository.kt` | EncryptedSharedPreferences-backed impl |
| `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/CrashReporter.kt` | `expect class CrashReporter` |
| `composeApp/src/androidMain/kotlin/app/kofipod/diagnostics/CrashReporter.android.kt` | Android actual using Sentry KMP |
| `composeApp/src/iosMain/kotlin/app/kofipod/diagnostics/CrashReporter.ios.kt` | iOS no-op stub |
| `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/Telemetry.kt` | `expect class Telemetry` |
| `composeApp/src/androidMain/kotlin/app/kofipod/diagnostics/Telemetry.android.kt` | Android actual using Aptabase SDK |
| `composeApp/src/iosMain/kotlin/app/kofipod/diagnostics/Telemetry.ios.kt` | iOS no-op stub |
| `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/TelemetryEvent.kt` | Sealed event vocabulary |
| `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/CrashReporterScrubber.kt` | Pure scrubbing logic |
| `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/DiagnosticsBootstrapper.kt` | Wires flags → SDK enable/disable |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/PrivacyDiagnosticsSection.kt` | Settings section composable |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/DiagnosticsDisclosureSheet.kt` | First-launch bottom sheet |
| `composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/CrashReporterScrubberTest.kt` | Scrubber unit tests |
| `composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/TelemetryEventTest.kt` | Event vocabulary tests |
| `composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/DiagnosticsConfigRepositoryTest.kt` | Repo tests with fake prefs |
| `composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/DiagnosticsBootstrapperTest.kt` | Bootstrapper gating tests |
| `composeApp/src/commonTest/kotlin/app/kofipod/ui/screens/settings/SettingsDiagnosticsTest.kt` | Compose UI test for Settings |
| `composeApp/src/commonTest/kotlin/app/kofipod/ui/shell/FirstLaunchDisclosureTest.kt` | Compose UI test for sheet |
| `composeApp/src/test/kotlin/app/kofipod/screenshots/PrivacyDiagnosticsSnapshots.kt` | Paparazzi snapshots |
| `docs/privacy.md` | User-facing privacy disclosure doc |
| `docs/diagnostics-hosting.md` | Maintainer hosting runbook |

**Modified files:**

| Path | Change |
|---|---|
| `gradle/libs.versions.toml` | Add Sentry KMP + Aptabase deps and Sentry Gradle plugin |
| `composeApp/build.gradle.kts` | Add `SENTRY_DSN`, `APTABASE_APP_KEY` to BuildKonfig; conditionally apply Sentry plugin |
| `local.properties.template` | Document new keys |
| `config/detekt/detekt.yml` | Forbid `io.sentry.*`, `com.aptabase.*` in `commonMain` |
| `composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt` | Start `DiagnosticsBootstrapper` |
| `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` | Register diagnostics Koin singletons |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt` | Render `PrivacyDiagnosticsSection` |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt` | Expose diagnostics state + actions |
| `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt` | Render `DiagnosticsDisclosureSheet` |
| Various call-sites | Inject `Telemetry` and call `track(...)` for the v1 event vocabulary |

---

## Task 1: Dependencies, BuildKonfig keys, detekt rules

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/build.gradle.kts`
- Modify: `local.properties.template`
- Modify: `config/detekt/detekt.yml`

- [ ] **Step 1: Add versions and libraries to `libs.versions.toml`**

Locate the `[versions]`, `[libraries]`, and `[plugins]` tables. Add (using current pinned versions — verify against Maven Central before commit):

```toml
[versions]
# ... existing entries ...
sentryKmp = "0.27.0"
aptabase = "0.0.10"

[libraries]
# ... existing entries ...
sentry-kmp = { module = "io.sentry:sentry-kotlin-multiplatform", version.ref = "sentryKmp" }
aptabase = { module = "com.aptabase:aptabase-kotlin", version.ref = "aptabase" }

[plugins]
# ... existing entries ...
sentry-android = { id = "io.sentry.android.gradle", version.ref = "sentryKmp" }
```

- [ ] **Step 2: Wire dependencies into `composeApp/build.gradle.kts`**

In the `kotlin { sourceSets { ... } }` block, add `sentry-kmp` to `commonMain.dependencies` and `aptabase` to `androidMain.dependencies`. (Sentry KMP is multiplatform; Aptabase Kotlin is Android-only.)

```kotlin
commonMain.dependencies {
    // ... existing ...
    implementation(libs.sentry.kmp)
}
androidMain.dependencies {
    // ... existing ...
    implementation(libs.aptabase)
}
```

- [ ] **Step 3: Add BuildKonfig fields**

In `composeApp/build.gradle.kts`, extend the `buildkonfig { defaultConfigs { ... } }` block (lines ~212-220) by adding two lines after the existing entries:

```kotlin
buildConfigField(STRING, "SENTRY_DSN", readSecret("SENTRY_DSN"))
buildConfigField(STRING, "APTABASE_APP_KEY", readSecret("APTABASE_APP_KEY"))
```

- [ ] **Step 4: Document new keys in `local.properties.template`**

Append:

```
# GlitchTip DSN for crash reports (leave empty to disable). Format:
# https://<public_key>@crash.example.com/<project_id>
SENTRY_DSN=

# Aptabase app key for usage telemetry (leave empty to disable). Format:
# A-EU-XXXXXXXXXX
APTABASE_APP_KEY=
```

- [ ] **Step 5: Update detekt forbidden imports**

In `config/detekt/detekt.yml`, find the `style: ForbiddenImport: imports:` list and add:

```yaml
  - 'io.sentry.**'
  - 'com.aptabase.**'
```

These are forbidden in `**/commonMain/**` only (the existing `excludes` pattern covers this). The expect/actual seam keeps `commonMain` clean of platform SDK imports.

- [ ] **Step 6: Verify the build still compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :composeApp:detekt :composeApp:ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml composeApp/build.gradle.kts \
        local.properties.template config/detekt/detekt.yml
git commit -m "build(diagnostics): add sentry-kmp + aptabase deps and BuildKonfig keys"
```

---

## Task 2: TelemetryEvent vocabulary

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/TelemetryEvent.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/TelemetryEventTest.kt`

- [ ] **Step 1: Write the failing test**

Create `TelemetryEventTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryEventTest {

    @Test
    fun `every event name uses snake_case lowercase`() {
        val pattern = Regex("^[a-z][a-z0-9_]*$")
        every().forEach { event ->
            assertTrue(
                "event name '${event.name}' does not match snake_case",
                pattern.matches(event.name),
            )
        }
    }

    @Test
    fun `every event prop value comes from a known enum vocabulary`() {
        val allowed = setOf("typed", "category", "transcript", "audio")
        every().forEach { event ->
            event.props.values.forEach { value ->
                assertTrue(
                    "prop value '$value' on event '${event.name}' is not from the allowed vocabulary",
                    value in allowed,
                )
            }
        }
    }

    @Test
    fun `app_opened event has stable name and empty props`() {
        val e: TelemetryEvent = TelemetryEvent.AppOpened
        assertEquals("app_opened", e.name)
        assertEquals(emptyMap<String, String>(), e.props)
    }

    @Test
    fun `search_performed event carries source prop`() {
        val e: TelemetryEvent = TelemetryEvent.SearchPerformed(SearchSource.TYPED)
        assertEquals("search_performed", e.name)
        assertEquals(mapOf("source" to "typed"), e.props)
    }

    private fun every(): List<TelemetryEvent> = listOf(
        TelemetryEvent.AppOpened,
        TelemetryEvent.SearchPerformed(SearchSource.TYPED),
        TelemetryEvent.SearchPerformed(SearchSource.CATEGORY),
        TelemetryEvent.EpisodeDownloaded,
        TelemetryEvent.EpisodePlayed,
        TelemetryEvent.AiSummaryGenerated(AiPath.TRANSCRIPT),
        TelemetryEvent.AiSummaryGenerated(AiPath.AUDIO),
        TelemetryEvent.AiDiscussMessageSent(AiPath.TRANSCRIPT),
        TelemetryEvent.AiDiscussMessageSent(AiPath.AUDIO),
    )
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.diagnostics.TelemetryEventTest"`
Expected: FAIL with unresolved references (`TelemetryEvent`, `SearchSource`, `AiPath`).

- [ ] **Step 3: Write the production code**

Create `TelemetryEvent.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

sealed class TelemetryEvent(val name: String, val props: Map<String, String>) {
    object AppOpened : TelemetryEvent("app_opened", emptyMap())

    data class SearchPerformed(val source: SearchSource) :
        TelemetryEvent("search_performed", mapOf("source" to source.value))

    object EpisodeDownloaded : TelemetryEvent("episode_downloaded", emptyMap())

    object EpisodePlayed : TelemetryEvent("episode_played", emptyMap())

    data class AiSummaryGenerated(val path: AiPath) :
        TelemetryEvent("ai_summary_generated", mapOf("path" to path.value))

    data class AiDiscussMessageSent(val path: AiPath) :
        TelemetryEvent("ai_discuss_message_sent", mapOf("path" to path.value))
}

enum class SearchSource(val value: String) {
    TYPED("typed"),
    CATEGORY("category"),
}

enum class AiPath(val value: String) {
    TRANSCRIPT("transcript"),
    AUDIO("audio"),
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.diagnostics.TelemetryEventTest"`
Expected: 4 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/TelemetryEvent.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/TelemetryEventTest.kt
git commit -m "feat(diagnostics): add TelemetryEvent sealed vocabulary"
```

---

## Task 3: CrashReporterScrubber (pure)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/CrashReporterScrubber.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/CrashReporterScrubberTest.kt`

The scrubber is pure — it operates on `String` and a small `Breadcrumb` data class declared in this file, decoupled from Sentry SDK types so it stays in `commonMain` and is trivially testable. The Android actual `CrashReporter` adapts Sentry's types to/from these.

- [ ] **Step 1: Write the failing test**

Create `CrashReporterScrubberTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashReporterScrubberTest {

    @Test
    fun `scrubMessage strips query strings from URLs`() {
        val raw = "Failed to fetch https://api.podcastindex.org/podcasts?key=secret&q=foo for episode"
        val scrubbed = CrashReporterScrubber.scrubMessage(raw)
        assertEquals(
            "Failed to fetch https://api.podcastindex.org/podcasts for episode",
            scrubbed,
        )
    }

    @Test
    fun `scrubMessage handles multiple URLs in one string`() {
        val raw = "Tried https://x.com/a?b=1 then https://y.com/c?d=2"
        val scrubbed = CrashReporterScrubber.scrubMessage(raw)
        assertEquals("Tried https://x.com/a then https://y.com/c", scrubbed)
    }

    @Test
    fun `scrubMessage leaves plain text unchanged`() {
        val raw = "NullPointerException at line 42"
        assertEquals(raw, CrashReporterScrubber.scrubMessage(raw))
    }

    @Test
    fun `scrubBreadcrumb drops http breadcrumbs containing gemini`() {
        val crumb = Breadcrumb(
            category = "http",
            message = "GET https://generativelanguage.googleapis.com/v1/models",
            data = mapOf("url" to "https://generativelanguage.googleapis.com/v1/models"),
        )
        assertNull(CrashReporterScrubber.scrubBreadcrumb(crumb))
    }

    @Test
    fun `scrubBreadcrumb drops http breadcrumbs containing googleapis`() {
        val crumb = Breadcrumb(
            category = "http",
            message = "POST https://oauth2.googleapis.com/token",
            data = emptyMap(),
        )
        assertNull(CrashReporterScrubber.scrubBreadcrumb(crumb))
    }

    @Test
    fun `scrubBreadcrumb drops http breadcrumbs containing podcastindex`() {
        val crumb = Breadcrumb(
            category = "http",
            message = "GET https://api.podcastindex.org/search?q=foo",
            data = emptyMap(),
        )
        assertNull(CrashReporterScrubber.scrubBreadcrumb(crumb))
    }

    @Test
    fun `scrubBreadcrumb drops query category breadcrumbs`() {
        val crumb = Breadcrumb(
            category = "query",
            message = "SELECT * FROM Episode WHERE id = ?",
            data = emptyMap(),
        )
        assertNull(CrashReporterScrubber.scrubBreadcrumb(crumb))
    }

    @Test
    fun `scrubBreadcrumb keeps innocuous http breadcrumbs but strips query strings in data`() {
        val crumb = Breadcrumb(
            category = "http",
            message = "GET https://example.com/feed?x=1",
            data = mapOf("url" to "https://example.com/feed?x=1"),
        )
        val scrubbed = CrashReporterScrubber.scrubBreadcrumb(crumb)!!
        assertEquals("GET https://example.com/feed", scrubbed.message)
        assertEquals(mapOf("url" to "https://example.com/feed"), scrubbed.data)
    }

    @Test
    fun `scrubBreadcrumb passes through non-http non-query categories unchanged`() {
        val crumb = Breadcrumb(category = "ui", message = "navigate to Library", data = emptyMap())
        assertEquals(crumb, CrashReporterScrubber.scrubBreadcrumb(crumb))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.diagnostics.CrashReporterScrubberTest"`
Expected: FAIL with unresolved references.

- [ ] **Step 3: Write the production code**

Create `CrashReporterScrubber.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

/**
 * A breadcrumb shape decoupled from Sentry SDK types so the scrubber stays
 * in commonMain. The Android CrashReporter adapts Sentry's Breadcrumb to/from this.
 */
data class Breadcrumb(
    val category: String,
    val message: String,
    val data: Map<String, String>,
)

object CrashReporterScrubber {

    private val urlWithQueryRegex = Regex("""(https?://[^\s?]+)\?[^\s]*""")

    private val sensitiveHttpHosts = listOf("gemini", "googleapis", "podcastindex")

    private val droppedCategories = setOf("query")

    fun scrubMessage(raw: String): String =
        urlWithQueryRegex.replace(raw) { match -> match.groupValues[1] }

    fun scrubBreadcrumb(crumb: Breadcrumb): Breadcrumb? {
        if (crumb.category in droppedCategories) return null
        if (crumb.category == "http") {
            val haystack = (crumb.message + " " + crumb.data.values.joinToString(" ")).lowercase()
            if (sensitiveHttpHosts.any { it in haystack }) return null
        }
        return crumb.copy(
            message = scrubMessage(crumb.message),
            data = crumb.data.mapValues { (_, v) -> scrubMessage(v) },
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.diagnostics.CrashReporterScrubberTest"`
Expected: 9 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/CrashReporterScrubber.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/CrashReporterScrubberTest.kt
git commit -m "feat(diagnostics): add pure CrashReporterScrubber"
```

---

## Task 4: DiagnosticsConfigRepository

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/DiagnosticsConfigRepository.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/diagnostics/AndroidDiagnosticsConfigRepository.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/DiagnosticsConfigRepositoryTest.kt`

Following the `KeyVault` pattern (interface in `commonMain`, Android impl in `androidMain` using EncryptedSharedPreferences) so we don't need Robolectric for unit tests — tests use a fake.

- [ ] **Step 1: Define the interface**

Create `DiagnosticsConfigRepository.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import kotlinx.coroutines.flow.Flow

/**
 * Three persistent boolean flags controlling diagnostics:
 *
 * - [crashesEnabled] — user-controlled toggle for crash reports
 * - [usageEnabled] — user-controlled toggle for usage events
 * - [disclosureAcknowledged] — set true when the user acknowledges the
 *   first-launch disclosure. Until true, [DiagnosticsBootstrapper] keeps
 *   both subsystems disabled regardless of the toggles.
 *
 * Default values: crashes ON, usage ON, acknowledged FALSE.
 */
interface DiagnosticsConfigRepository {
    val crashesEnabled: Flow<Boolean>
    val usageEnabled: Flow<Boolean>
    val disclosureAcknowledged: Flow<Boolean>

    suspend fun setCrashesEnabled(enabled: Boolean)
    suspend fun setUsageEnabled(enabled: Boolean)
    suspend fun acknowledgeDisclosure()
}
```

- [ ] **Step 2: Write the failing test using a fake**

Create `DiagnosticsConfigRepositoryTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsConfigRepositoryTest {

    @Test
    fun `defaults — crashes on, usage on, disclosure not acknowledged`() = runTest {
        val repo = FakeDiagnosticsConfigRepository()
        assertTrue(repo.crashesEnabled.first())
        assertTrue(repo.usageEnabled.first())
        assertFalse(repo.disclosureAcknowledged.first())
    }

    @Test
    fun `setCrashesEnabled false flips the crashes flow`() = runTest {
        val repo = FakeDiagnosticsConfigRepository()
        repo.setCrashesEnabled(false)
        assertFalse(repo.crashesEnabled.first())
        assertTrue(repo.usageEnabled.first())
    }

    @Test
    fun `setUsageEnabled false flips the usage flow`() = runTest {
        val repo = FakeDiagnosticsConfigRepository()
        repo.setUsageEnabled(false)
        assertFalse(repo.usageEnabled.first())
        assertTrue(repo.crashesEnabled.first())
    }

    @Test
    fun `acknowledgeDisclosure flips the acknowledgement flow`() = runTest {
        val repo = FakeDiagnosticsConfigRepository()
        repo.acknowledgeDisclosure()
        assertTrue(repo.disclosureAcknowledged.first())
    }

    @Test
    fun `flags are independent`() = runTest {
        val repo = FakeDiagnosticsConfigRepository()
        repo.setCrashesEnabled(false)
        repo.setUsageEnabled(false)
        repo.acknowledgeDisclosure()
        assertFalse(repo.crashesEnabled.first())
        assertFalse(repo.usageEnabled.first())
        assertTrue(repo.disclosureAcknowledged.first())
    }

    @Test
    fun `setting flags emits new values to existing collectors`() = runTest {
        val repo = FakeDiagnosticsConfigRepository()
        val emissions = mutableListOf<Boolean>()
        emissions.add(repo.crashesEnabled.first())
        repo.setCrashesEnabled(false)
        emissions.add(repo.crashesEnabled.first())
        assertEquals(listOf(true, false), emissions)
    }
}
```

- [ ] **Step 3: Write the fake repository**

In the same test file, append:

```kotlin
class FakeDiagnosticsConfigRepository : DiagnosticsConfigRepository {
    private val crashes = kotlinx.coroutines.flow.MutableStateFlow(true)
    private val usage = kotlinx.coroutines.flow.MutableStateFlow(true)
    private val ack = kotlinx.coroutines.flow.MutableStateFlow(false)

    override val crashesEnabled: kotlinx.coroutines.flow.Flow<Boolean> = crashes
    override val usageEnabled: kotlinx.coroutines.flow.Flow<Boolean> = usage
    override val disclosureAcknowledged: kotlinx.coroutines.flow.Flow<Boolean> = ack

    override suspend fun setCrashesEnabled(enabled: Boolean) { crashes.value = enabled }
    override suspend fun setUsageEnabled(enabled: Boolean) { usage.value = enabled }
    override suspend fun acknowledgeDisclosure() { ack.value = true }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.diagnostics.DiagnosticsConfigRepositoryTest"`
Expected: FAIL — `DiagnosticsConfigRepository` interface compiles, but tests should pass once the fake is the impl. (If they already pass, that's fine — this is a contract test on the interface + fake.)

- [ ] **Step 5: Write the Android implementation**

Create `AndroidDiagnosticsConfigRepository.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val PREFS_FILE = "kofipod_secure"
private const val KEY_CRASHES_ENABLED = "diagnostics.crashes.enabled"
private const val KEY_USAGE_ENABLED = "diagnostics.usage.enabled"
private const val KEY_DISCLOSURE_ACK = "diagnostics.disclosure.acknowledged"

/**
 * EncryptedSharedPreferences-backed implementation. Shares the [PREFS_FILE]
 * with [app.kofipod.ai.AndroidKeyVault] — both are excluded from Auto Backup
 * via backup_rules.xml, so flags do not survive device migration. That is
 * the intended fail-safe: a new device always sees disclosure unacknowledged.
 */
class AndroidDiagnosticsConfigRepository(context: Context) : DiagnosticsConfigRepository {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val crashes = MutableStateFlow(prefs.getBoolean(KEY_CRASHES_ENABLED, true))
    private val usage = MutableStateFlow(prefs.getBoolean(KEY_USAGE_ENABLED, true))
    private val ack = MutableStateFlow(prefs.getBoolean(KEY_DISCLOSURE_ACK, false))

    override val crashesEnabled: Flow<Boolean> = crashes.asStateFlow()
    override val usageEnabled: Flow<Boolean> = usage.asStateFlow()
    override val disclosureAcknowledged: Flow<Boolean> = ack.asStateFlow()

    override suspend fun setCrashesEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_CRASHES_ENABLED, enabled).commit()
        crashes.value = enabled
    }

    override suspend fun setUsageEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_USAGE_ENABLED, enabled).commit()
        usage.value = enabled
    }

    override suspend fun acknowledgeDisclosure() = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_DISCLOSURE_ACK, true).commit()
        ack.value = true
    }
}
```

- [ ] **Step 6: Re-run the test (should still pass — the fake is the contract)**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.diagnostics.DiagnosticsConfigRepositoryTest"`
Expected: 6 tests, all PASS.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/DiagnosticsConfigRepository.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/diagnostics/AndroidDiagnosticsConfigRepository.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/DiagnosticsConfigRepositoryTest.kt
git commit -m "feat(diagnostics): add DiagnosticsConfigRepository with EncryptedSharedPreferences impl"
```

---

## Task 5: CrashReporter expect/actual

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/CrashReporter.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/diagnostics/CrashReporter.android.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/diagnostics/CrashReporter.ios.kt`

The Sentry KMP SDK (v0.27 at time of writing) exposes `Sentry.init { ... }` taking an `OptionsConfiguration` lambda. Verify exact option names against the pinned version's source — the names in the spec are the **intent**; the actual code calls whatever the SDK exposes (e.g. `sendDefaultPii`, `release`, `environment`, `beforeSend`, `beforeBreadcrumb` are stable; some Android-only options like `attachScreenshot` may not be exposed at the KMP layer and that is acceptable — they default to off).

- [ ] **Step 1: Define the expect class**

Create `CrashReporter.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

/**
 * Lazy-initialized crash-reporting facade. Constructor does NOT initialize
 * the underlying SDK. The SDK is loaded and configured on first [enable].
 */
expect class CrashReporter {
    fun enable()
    fun disable()
    fun isEnabled(): Boolean
}
```

- [ ] **Step 2: Implement the iOS no-op stub**

Create `CrashReporter.ios.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

actual class CrashReporter {
    actual fun enable() = Unit
    actual fun disable() = Unit
    actual fun isEnabled(): Boolean = false
}
```

- [ ] **Step 3: Implement the Android actual**

Create `CrashReporter.android.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import android.content.Context
import app.kofipod.config.BuildKonfig
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb as SentryBreadcrumb

private const val LOG_TAG = "Kofipod-Diag"

/**
 * Android-side crash reporter using Sentry KMP SDK. Compatible with
 * GlitchTip, which speaks the Sentry wire protocol — the only difference
 * is the [BuildKonfig.SENTRY_DSN] points at a GlitchTip instance.
 *
 * If the DSN is empty (e.g. F-Droid build, fork without secrets),
 * [enable] is a permanent no-op.
 */
actual class CrashReporter(private val context: Context) {

    private var enabled = false

    actual fun enable() {
        if (enabled) return
        if (BuildKonfig.SENTRY_DSN.isBlank()) return
        Sentry.init(context) { options ->
            options.dsn = BuildKonfig.SENTRY_DSN
            options.release = BuildKonfig.VERSION_NAME
            options.environment = if (BuildKonfig.SENTRY_DSN.contains("debug")) "debug" else "release"
            options.sendDefaultPii = false
            options.enableAutoSessionTracking = false
            options.beforeSend = { event -> scrub(event) }
            options.beforeBreadcrumb = { crumb -> adapt(crumb) }
        }
        enabled = true
    }

    actual fun disable() {
        if (!enabled) return
        Sentry.close()
        enabled = false
    }

    actual fun isEnabled(): Boolean = enabled

    private fun scrub(event: SentryEvent): SentryEvent? {
        event.message?.let { msg ->
            msg.formatted?.let { msg.formatted = CrashReporterScrubber.scrubMessage(it) }
            msg.message?.let { msg.message = CrashReporterScrubber.scrubMessage(it) }
        }
        event.exceptions?.forEach { ex ->
            ex.value?.let { ex.value = CrashReporterScrubber.scrubMessage(it) }
        }
        return event
    }

    private fun adapt(crumb: SentryBreadcrumb): SentryBreadcrumb? {
        val pure = Breadcrumb(
            category = crumb.category.orEmpty(),
            message = crumb.message.orEmpty(),
            data = crumb.data.mapValues { (_, v) -> v?.toString().orEmpty() },
        )
        val scrubbed = CrashReporterScrubber.scrubBreadcrumb(pure) ?: return null
        crumb.message = scrubbed.message
        scrubbed.data.forEach { (k, v) -> crumb.data[k] = v }
        return crumb
    }
}
```

> Note: the Sentry KMP API surface evolves; if the field names above (`message.formatted`, `event.exceptions`, `crumb.data`) differ in the pinned SDK version, adjust to the actual API. The contract you must preserve is: every outbound event passes `event.message`, exception values, and breadcrumb data through `CrashReporterScrubber`.

- [ ] **Step 4: Verify everything compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/CrashReporter.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/diagnostics/CrashReporter.android.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/diagnostics/CrashReporter.ios.kt
git commit -m "feat(diagnostics): add CrashReporter expect/actual with Sentry KMP"
```

---

## Task 6: Telemetry expect/actual

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/Telemetry.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/diagnostics/Telemetry.android.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/diagnostics/Telemetry.ios.kt`

- [ ] **Step 1: Define the expect class**

Create `Telemetry.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

/**
 * Lazy-initialized telemetry facade. Constructor does NOT initialize the
 * underlying SDK. The SDK is loaded and configured on first [enable].
 * [track] is a no-op when not enabled.
 */
expect class Telemetry {
    fun enable()
    fun disable()
    fun track(event: TelemetryEvent)
}
```

- [ ] **Step 2: Implement the iOS no-op stub**

Create `Telemetry.ios.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

actual class Telemetry {
    actual fun enable() = Unit
    actual fun disable() = Unit
    actual fun track(event: TelemetryEvent) = Unit
}
```

- [ ] **Step 3: Implement the Android actual**

Create `Telemetry.android.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import android.content.Context
import app.kofipod.config.BuildKonfig
import com.aptabase.Aptabase
import com.aptabase.InitOptions

/**
 * Android-side usage telemetry using Aptabase. Identifier-less by
 * construction — never passes a userId, never persists a per-install ID.
 * Aptabase's server hashes IP + UA + a daily-rotated salt so the same
 * device is a different ID every 24h.
 *
 * If the app key is empty (F-Droid build, fork without secrets), [enable]
 * is a permanent no-op.
 */
actual class Telemetry(private val context: Context) {

    private var enabled = false

    actual fun enable() {
        if (enabled) return
        if (BuildKonfig.APTABASE_APP_KEY.isBlank()) return
        Aptabase.init(
            context,
            BuildKonfig.APTABASE_APP_KEY,
            InitOptions(host = null /* default cloud */),
        )
        enabled = true
    }

    actual fun disable() {
        if (!enabled) return
        // Aptabase has no explicit close; setting our flag stops further track() calls.
        enabled = false
    }

    actual fun track(event: TelemetryEvent) {
        if (!enabled) return
        Aptabase.trackEvent(event.name, event.props)
    }
}
```

> Note: Aptabase's Kotlin SDK surface (`Aptabase.init`, `InitOptions`, `trackEvent`) is stable but verify against the pinned version. If `InitOptions` is not present in 0.0.10, drop it — `Aptabase.init(context, appKey)` is the minimal call.

- [ ] **Step 4: Verify everything compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/Telemetry.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/diagnostics/Telemetry.android.kt \
        composeApp/src/iosMain/kotlin/app/kofipod/diagnostics/Telemetry.ios.kt
git commit -m "feat(diagnostics): add Telemetry expect/actual with Aptabase"
```

---

## Task 7: DiagnosticsBootstrapper

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/DiagnosticsBootstrapper.kt`
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/DiagnosticsBootstrapperTest.kt`

The bootstrapper is testable in commonTest because its only dependencies are the repository interface and two plain expect classes. We'll test it by injecting a fake repo and asserting on a fake reporter/telemetry — but `CrashReporter` and `Telemetry` are expect classes (not interfaces), so we test on the JVM/Android side using a wrapper interface introduced specifically for testability is overkill. Instead, test the Bootstrapper by observing the *flag computation* — a small pure helper extracted from the class.

- [ ] **Step 1: Write the failing test**

Create `DiagnosticsBootstrapperTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsBootstrapperTest {

    @Test
    fun `crashes effective is false when toggle on but disclosure not acknowledged`() = runTest {
        val toggle = MutableStateFlow(true)
        val ack = MutableStateFlow(false)
        val effective = DiagnosticsBootstrapper.effective(toggle, ack)
        assertEquals(false, effective.first())
    }

    @Test
    fun `effective is false when disclosure acknowledged but toggle off`() = runTest {
        val toggle = MutableStateFlow(false)
        val ack = MutableStateFlow(true)
        val effective = DiagnosticsBootstrapper.effective(toggle, ack)
        assertEquals(false, effective.first())
    }

    @Test
    fun `effective is true only when both toggle and acknowledgement are true`() = runTest {
        val toggle = MutableStateFlow(true)
        val ack = MutableStateFlow(true)
        val effective = DiagnosticsBootstrapper.effective(toggle, ack)
        assertEquals(true, effective.first())
    }

    @Test
    fun `effective re-emits when acknowledgement flips`() = runTest {
        val toggle = MutableStateFlow(true)
        val ack = MutableStateFlow(false)
        val effective = DiagnosticsBootstrapper.effective(toggle, ack)
        val emissions = mutableListOf<Boolean>()
        emissions.add(effective.first())
        ack.value = true
        emissions.add(effective.first())
        assertEquals(listOf(false, true), emissions)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.diagnostics.DiagnosticsBootstrapperTest"`
Expected: FAIL with unresolved `DiagnosticsBootstrapper.effective`.

- [ ] **Step 3: Write the production code**

Create `DiagnosticsBootstrapper.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Wires [DiagnosticsConfigRepository] flags to the two SDK facades.
 *
 * Effective state for each subsystem is `toggle && disclosureAcknowledged`.
 * Until the user has acknowledged the first-launch disclosure, neither
 * SDK is initialized regardless of toggle state.
 */
class DiagnosticsBootstrapper(
    private val config: DiagnosticsConfigRepository,
    private val crashes: CrashReporter,
    private val telemetry: Telemetry,
    private val appScope: CoroutineScope,
) {
    fun start() {
        effective(config.crashesEnabled, config.disclosureAcknowledged)
            .onEach { if (it) crashes.enable() else crashes.disable() }
            .launchIn(appScope)
        effective(config.usageEnabled, config.disclosureAcknowledged)
            .onEach { if (it) telemetry.enable() else telemetry.disable() }
            .launchIn(appScope)
    }

    companion object {
        fun effective(toggle: Flow<Boolean>, acknowledged: Flow<Boolean>): Flow<Boolean> =
            combine(toggle, acknowledged) { t, a -> t && a }.distinctUntilChanged()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.diagnostics.DiagnosticsBootstrapperTest"`
Expected: 4 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/diagnostics/DiagnosticsBootstrapper.kt \
        composeApp/src/androidUnitTest/kotlin/app/kofipod/diagnostics/DiagnosticsBootstrapperTest.kt
git commit -m "feat(diagnostics): add DiagnosticsBootstrapper gating SDKs on disclosure"
```

---

## Task 8: Koin wiring + Application startup

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidPlatformModule.kt` (Android Koin module — locate via `find composeApp/src/androidMain -name "*Module*.kt"`)

- [ ] **Step 1: Locate the Android Koin module**

Run: `grep -rn "androidPlatformModule" composeApp/src/androidMain --include="*.kt"`
Expected: One file (likely `AndroidPlatformModule.kt`) where `val androidPlatformModule = module { ... }` is defined.

- [ ] **Step 2: Register the Android implementations**

In the file from Step 1, inside the `module { }` block, add:

```kotlin
import app.kofipod.diagnostics.AndroidDiagnosticsConfigRepository
import app.kofipod.diagnostics.CrashReporter
import app.kofipod.diagnostics.DiagnosticsConfigRepository
import app.kofipod.diagnostics.Telemetry
// ...

single<DiagnosticsConfigRepository> { AndroidDiagnosticsConfigRepository(androidContext()) }
single { CrashReporter(androidContext()) }
single { Telemetry(androidContext()) }
```

- [ ] **Step 3: Register the bootstrapper in `CommonModule.kt`**

In `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`, inside the `commonDataModule = module { }` block, add:

```kotlin
import app.kofipod.diagnostics.DiagnosticsBootstrapper
// ...

single {
    DiagnosticsBootstrapper(
        config = get(),
        crashes = get(),
        telemetry = get(),
        appScope = get(qualifier = org.koin.core.qualifier.named("appScope")),
    )
}
```

(Use whatever idiom the existing module already uses for the named `"appScope"`. If it's done via a constant, reuse it.)

- [ ] **Step 4: Start the bootstrapper from `KofipodApplication`**

In `composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt`, add to imports:

```kotlin
import app.kofipod.diagnostics.DiagnosticsBootstrapper
```

After the `startKoin { ... }` block and after the existing `get<UpdateInstaller>(...)` / `get<AiSummaryRepository>(...)` lines, add:

```kotlin
get<DiagnosticsBootstrapper>(DiagnosticsBootstrapper::class.java).start()
```

- [ ] **Step 5: Verify it builds**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Smoke test — install and launch**

Run:
```bash
~/Library/Android/sdk/platform-tools/adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
~/Library/Android/sdk/platform-tools/adb shell am start -n app.kofipod/.MainActivity
~/Library/Android/sdk/platform-tools/adb logcat -d | grep -i "kofipod\|sentry\|aptabase" | tail -40
```
Expected: app launches without crashing. With empty DSN/key, neither SDK initializes — no errors related to network endpoints.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidPlatformModule.kt \
        composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt
git commit -m "feat(diagnostics): wire DiagnosticsBootstrapper via Koin and start on app create"
```

---

## Task 9: SettingsViewModel — diagnostics state and actions

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` (factory update)

- [ ] **Step 1: Read the current SettingsViewModel**

Run: `cat composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt`

Note the existing constructor signature, the `state: StateFlow<SettingsUiState>` shape, and the action method style (e.g. `fun setSomething(...)`). Match that style exactly.

- [ ] **Step 2: Add diagnostics fields to `SettingsUiState`**

In `SettingsViewModel.kt`, find the `data class SettingsUiState(...)` definition and add three fields:

```kotlin
val crashesEnabled: Boolean = true,
val usageEnabled: Boolean = true,
val disclosureAcknowledged: Boolean = false,
```

- [ ] **Step 3: Inject `DiagnosticsConfigRepository`**

Add it as a constructor parameter and wire it into the existing state-collection logic. Inside the `init { }` block (or wherever the existing state is built), add a `combine` over the three diagnostics flows that updates the `SettingsUiState` accordingly. Pattern the new code after how the file already exposes other flow-backed state.

- [ ] **Step 4: Add three action methods**

```kotlin
fun setCrashesEnabled(enabled: Boolean) {
    viewModelScope.launch { diagnostics.setCrashesEnabled(enabled) }
}

fun setUsageEnabled(enabled: Boolean) {
    viewModelScope.launch { diagnostics.setUsageEnabled(enabled) }
}

fun acknowledgeDisclosure() {
    viewModelScope.launch { diagnostics.acknowledgeDisclosure() }
}
```

(Use whatever scope name the file already uses; `viewModelScope` is the Android Lifecycle convention.)

- [ ] **Step 5: Update the Koin factory**

In `CommonModule.kt`, locate `viewModel { ... }` for `SettingsViewModel`. Add `diagnostics = get()` (or positional `get()`) for the new `DiagnosticsConfigRepository` parameter.

- [ ] **Step 6: Verify it builds**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt
git commit -m "feat(settings): expose diagnostics state and actions in SettingsViewModel"
```

---

## Task 10: Privacy & Diagnostics Settings section

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/PrivacyDiagnosticsSection.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt`
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/ui/screens/settings/SettingsDiagnosticsTest.kt`

- [ ] **Step 1: Write the Compose UI test**

Create `SettingsDiagnosticsTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SettingsDiagnosticsTest {

    @Test
    fun `crashes switch defaults on and toggles off when tapped`() = runComposeUiTest {
        val crashes = mutableStateOf(true)
        setContent {
            PrivacyDiagnosticsSection(
                crashesEnabled = crashes.value,
                usageEnabled = true,
                onCrashesEnabledChange = { crashes.value = it },
                onUsageEnabledChange = {},
                onOpenPrivacyPolicy = {},
            )
        }
        onNodeWithTag("diagnostics.crashes").assertIsDisplayed().assertIsOn()
        onNodeWithTag("diagnostics.crashes").performClick()
        onNodeWithTag("diagnostics.crashes").assertIsOff()
    }

    @Test
    fun `usage switch defaults on and toggles off when tapped`() = runComposeUiTest {
        val usage = mutableStateOf(true)
        setContent {
            PrivacyDiagnosticsSection(
                crashesEnabled = true,
                usageEnabled = usage.value,
                onCrashesEnabledChange = {},
                onUsageEnabledChange = { usage.value = it },
                onOpenPrivacyPolicy = {},
            )
        }
        onNodeWithTag("diagnostics.usage").assertIsDisplayed().assertIsOn()
        onNodeWithTag("diagnostics.usage").performClick()
        onNodeWithTag("diagnostics.usage").assertIsOff()
    }

    @Test
    fun `whats sent disclosure expands when tapped`() = runComposeUiTest {
        setContent {
            PrivacyDiagnosticsSection(
                crashesEnabled = true,
                usageEnabled = true,
                onCrashesEnabledChange = {},
                onUsageEnabledChange = {},
                onOpenPrivacyPolicy = {},
            )
        }
        onNodeWithText("What's sent?").assertIsDisplayed()
        // Tapping should reveal the field list — exact label is set in the production code.
        onNodeWithText("What's sent?").performClick()
        onNodeWithText("Stack trace").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.ui.screens.settings.SettingsDiagnosticsTest"`
Expected: FAIL with unresolved `PrivacyDiagnosticsSection`.

- [ ] **Step 3: Write the production composable**

Create `PrivacyDiagnosticsSection.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.kofipod.ui.primitives.SectionLabel
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun PrivacyDiagnosticsSection(
    crashesEnabled: Boolean,
    usageEnabled: Boolean,
    onCrashesEnabledChange: (Boolean) -> Unit,
    onUsageEnabledChange: (Boolean) -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalKofipodColors.current
    Column(modifier.fillMaxWidth()) {
        SectionLabel("Privacy & Diagnostics")
        Spacer(Modifier.height(8.dp))

        DiagnosticsToggleRow(
            tag = "diagnostics.crashes",
            title = "Send crash reports",
            subtitle = "Help fix bugs by sharing anonymous crash details when the app crashes. No personal information.",
            disclosureKey = "crashes",
            checked = crashesEnabled,
            onCheckedChange = onCrashesEnabledChange,
            disclosureLines = listOf(
                "Stack trace",
                "Exception class and message (URLs scrubbed)",
                "OS version, device model (e.g. \"Pixel 7\")",
                "App version, locale",
            ),
        )

        Spacer(Modifier.height(12.dp))

        DiagnosticsToggleRow(
            tag = "diagnostics.usage",
            title = "Share anonymous usage data",
            subtitle = "Help prioritize features by sharing counts of how often they're used. No identifiers, no IP address stored.",
            disclosureKey = "usage",
            checked = usageEnabled,
            onCheckedChange = onUsageEnabledChange,
            disclosureLines = listOf(
                "Event name (e.g. \"search_performed\")",
                "Event properties (fixed enum values)",
                "App version, OS version, locale",
                "No client identifier ever sent",
            ),
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Read the privacy policy ›",
            color = c.accent,
            modifier = Modifier
                .testTag("diagnostics.privacyPolicy")
                .clickable { onOpenPrivacyPolicy() }
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun DiagnosticsToggleRow(
    tag: String,
    title: String,
    subtitle: String,
    disclosureKey: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    disclosureLines: List<String>,
) {
    val c = LocalKofipodColors.current
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = c.fg)
                Text(subtitle, color = c.fgMuted)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(tag),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "What's sent?",
            color = c.fgMuted,
            modifier = Modifier
                .testTag("$tag.disclosureToggle")
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
        )
        if (expanded) {
            Column(Modifier.padding(start = 8.dp)) {
                disclosureLines.forEach { line ->
                    Text("• $line", color = c.fgMuted)
                }
            }
        }
    }
}
```

> Note: if `LocalKofipodColors` does not expose `fg`, `fgMuted`, or `accent` under those exact names, substitute the equivalents from the existing palette. The intent is "section label, primary text, muted text, accent for the link."

- [ ] **Step 4: Render the section in `SettingsScreen`**

In `SettingsScreen.kt`, locate where existing sections are stacked in the `Column { ... }`. Add (after Storage, before About):

```kotlin
import app.kofipod.ui.screens.settings.PrivacyDiagnosticsSection
// ...

PrivacyDiagnosticsSection(
    crashesEnabled = state.crashesEnabled,
    usageEnabled = state.usageEnabled,
    onCrashesEnabledChange = viewModel::setCrashesEnabled,
    onUsageEnabledChange = viewModel::setUsageEnabled,
    onOpenPrivacyPolicy = {
        // Open https://github.com/<repo>/blob/master/docs/privacy.md
        // Use the existing UrlOpener / Sharer / platform-bridge that the app
        // already uses for "open release notes" in the About section.
    },
)
```

For `onOpenPrivacyPolicy`, use the same URL-opening mechanism the existing About section uses (search the codebase for the GitHub releases URL handler — likely a `UrlOpener` or similar in `androidMain`).

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.ui.screens.settings.SettingsDiagnosticsTest"`
Expected: 3 tests, all PASS.

- [ ] **Step 6: Compile-check the full module**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/PrivacyDiagnosticsSection.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt \
        composeApp/src/commonTest/kotlin/app/kofipod/ui/screens/settings/SettingsDiagnosticsTest.kt
git commit -m "feat(settings): add Privacy & Diagnostics section with toggles and disclosures"
```

---

## Task 11: First-launch disclosure bottom sheet

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/DiagnosticsDisclosureSheet.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt`
- Create: `composeApp/src/commonTest/kotlin/app/kofipod/ui/shell/FirstLaunchDisclosureTest.kt`

- [ ] **Step 1: Write the failing test**

Create `FirstLaunchDisclosureTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FirstLaunchDisclosureTest {

    @Test
    fun `sheet renders when not acknowledged`() = runComposeUiTest {
        setContent {
            DiagnosticsDisclosureSheet(
                visible = true,
                onAcknowledge = {},
                onOpenSettings = {},
            )
        }
        onNodeWithText("Got it").assertIsDisplayed()
        onNodeWithText("Open Settings").assertIsDisplayed()
    }

    @Test
    fun `sheet does not render when already acknowledged`() = runComposeUiTest {
        setContent {
            DiagnosticsDisclosureSheet(
                visible = false,
                onAcknowledge = {},
                onOpenSettings = {},
            )
        }
        onNodeWithText("Got it").assertDoesNotExist()
    }

    @Test
    fun `tapping Got it invokes acknowledge callback`() = runComposeUiTest {
        var acknowledged = false
        setContent {
            DiagnosticsDisclosureSheet(
                visible = true,
                onAcknowledge = { acknowledged = true },
                onOpenSettings = {},
            )
        }
        onNodeWithText("Got it").performClick()
        kotlin.test.assertTrue(acknowledged)
    }

    @Test
    fun `tapping Open Settings invokes both acknowledge and navigate callbacks`() = runComposeUiTest {
        var acknowledged = false
        var settingsOpened = false
        setContent {
            DiagnosticsDisclosureSheet(
                visible = true,
                onAcknowledge = { acknowledged = true },
                onOpenSettings = { settingsOpened = true },
            )
        }
        onNodeWithText("Open Settings").performClick()
        kotlin.test.assertTrue(acknowledged)
        kotlin.test.assertTrue(settingsOpened)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.ui.shell.FirstLaunchDisclosureTest"`
Expected: FAIL with unresolved `DiagnosticsDisclosureSheet`.

- [ ] **Step 3: Write the production composable**

Create `DiagnosticsDisclosureSheet.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kofipod.ui.theme.LocalKofipodColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsDisclosureSheet(
    visible: Boolean,
    onAcknowledge: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!visible) return
    val c = LocalKofipodColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onAcknowledge,
        sheetState = sheetState,
    ) {
        Column(Modifier.padding(24.dp).fillMaxWidth()) {
            Text(
                "Help improve Kofipod",
                color = c.fg,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Kofipod sends anonymous crash reports and usage counts so the developer " +
                    "can fix bugs and prioritize features. No personal information, no " +
                    "tracking across apps. You can turn either off in Settings → Privacy " +
                    "& Diagnostics at any time.",
                color = c.fgMuted,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = {
                    onAcknowledge()
                    onOpenSettings()
                }) { Text("Open Settings") }
                Button(onClick = onAcknowledge) { Text("Got it") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
```

- [ ] **Step 4: Wire the sheet into `AppShell.kt`**

Read the current `AppShell.kt` to find where the navigation host root composable is rendered. Add:

```kotlin
import app.kofipod.diagnostics.DiagnosticsConfigRepository
import app.kofipod.ui.shell.DiagnosticsDisclosureSheet
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
// ...

@Composable
fun AppShell(/* existing parameters */) {
    val diagnostics: DiagnosticsConfigRepository = koinInject()
    val acknowledged by diagnostics.disclosureAcknowledged.collectAsState(initial = true)
    val scope = rememberCoroutineScope()

    // ... existing AppShell content ...

    DiagnosticsDisclosureSheet(
        visible = !acknowledged,
        onAcknowledge = { scope.launch { diagnostics.acknowledgeDisclosure() } },
        onOpenSettings = {
            // Navigate to Route.Settings — use whatever NavController/navigate idiom AppShell already uses
        },
    )
}
```

The `initial = true` is intentional: while we wait for the first emission from disk, we treat acknowledgement as true to avoid a flash of the sheet on every launch. The first real emission either confirms `true` (sheet stays hidden) or flips to `false` (sheet appears).

For the `onOpenSettings` body, use the same navigation hook AppShell uses for bottom-nav taps (search the file for how `Route.Settings` is navigated to).

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "app.kofipod.ui.shell.FirstLaunchDisclosureTest"`
Expected: 4 tests, all PASS.

- [ ] **Step 6: Compile-check**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/DiagnosticsDisclosureSheet.kt \
        composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt \
        composeApp/src/commonTest/kotlin/app/kofipod/ui/shell/FirstLaunchDisclosureTest.kt
git commit -m "feat(diagnostics): add first-launch disclosure bottom sheet"
```

---

## Task 12: Sentry Gradle plugin (release builds only)

**Files:**
- Modify: `composeApp/build.gradle.kts`

The plugin uploads R8 mapping files to GlitchTip (Sentry-compatible) on release builds, enabling deobfuscated stack traces. Gated on DSN being non-blank so forks without secrets still build cleanly.

- [ ] **Step 1: Apply the plugin conditionally**

In `composeApp/build.gradle.kts`, near the top in the `plugins { }` block, plugins must be declared statically — but we can configure it conditionally. Add:

```kotlin
plugins {
    // ... existing ...
    alias(libs.plugins.sentry.android)
}
```

Then, after the `android { }` block (or wherever cross-cutting Gradle config lives), add:

```kotlin
sentry {
    val dsn = readSecret("SENTRY_DSN")
    val authToken = System.getenv("SENTRY_AUTH_TOKEN").orEmpty()
    val canUpload = dsn.isNotBlank() && authToken.isNotBlank()

    autoUploadProguardMapping.set(canUpload)
    includeProguardMapping.set(canUpload)
    autoInstallation.set(false)         // we manage SDK deps manually
    tracingInstrumentation.set(false)   // not needed
    autoUploadNativeSymbols.set(false)
    uploadNativeSymbols.set(false)

    // Direct uploads to GlitchTip require pointing at its endpoint.
    if (canUpload) {
        url.set(deriveUploadUrl(dsn))
        org.set(System.getenv("SENTRY_ORG") ?: "kofipod")
        projectName.set(System.getenv("SENTRY_PROJECT") ?: "kofipod-android")
    }
}

fun deriveUploadUrl(dsn: String): String {
    // DSN format: https://<key>@host[/path]/<projectId>
    // Upload URL is the host (with optional path), no trailing project segment.
    val withoutScheme = dsn.substringAfter("://")
    val host = withoutScheme.substringAfter("@").substringBefore("/")
    return "https://$host"
}
```

> If the Sentry Gradle plugin's DSL differs in the pinned version (some properties may have changed names between major versions), adapt to whatever the pinned version exposes. The contract: when DSN + auth token are set, the plugin uploads the R8 mapping for release variants; otherwise it does nothing.

- [ ] **Step 2: Document the env var**

Append to `local.properties.template` after the `APTABASE_APP_KEY` block:

```
# Sentry/GlitchTip auth token for mapping uploads (CI only — do not commit).
# Set as SENTRY_AUTH_TOKEN env var in CI. Leave unset locally.
```

- [ ] **Step 3: Verify debug builds still work without secrets**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL. No mapping upload attempted.

- [ ] **Step 4: Verify release build with empty DSN still completes**

Run: `./gradlew :composeApp:assembleRelease` (if a signing config is available; otherwise just `:composeApp:bundleRelease` until the upload step is reached)
Expected: BUILD SUCCESSFUL. No upload attempted because DSN is empty.

- [ ] **Step 5: Commit**

```bash
git add composeApp/build.gradle.kts local.properties.template
git commit -m "build(diagnostics): apply Sentry Gradle plugin for release mapping upload"
```

---

## Task 13: Wire telemetry call-sites for v1 events

**Files:**
- Modify: Various ViewModels and repositories where the v1 events fire.

For each event below, inject `Telemetry` (Koin) into the class that owns the action and call `track(...)` exactly where the action *succeeds*. Never inside an error path.

- [ ] **Step 1: Wire `app_opened`**

In `KofipodApplication.kt`, after `get<DiagnosticsBootstrapper>(...).start()`, add:

```kotlin
get<app.kofipod.diagnostics.Telemetry>(app.kofipod.diagnostics.Telemetry::class.java)
    .track(app.kofipod.diagnostics.TelemetryEvent.AppOpened)
```

Note: this fires *before* the disclosure is acknowledged on first run, but `Telemetry.enable()` will not have been called yet (Bootstrapper hasn't observed acknowledged=true), so `track` is a no-op. After acknowledgement, subsequent cold starts emit `app_opened`.

- [ ] **Step 2: Wire `search_performed`**

Find the search ViewModel: `grep -rn "class SearchViewModel" composeApp/src/commonMain --include="*.kt"`. Inject `Telemetry` via constructor, update Koin factory, and in the action that performs a search call:

```kotlin
telemetry.track(TelemetryEvent.SearchPerformed(SearchSource.TYPED))
```

If there are separate paths for typed vs category search, fire `SearchSource.CATEGORY` from the category browse flow.

- [ ] **Step 3: Wire `episode_downloaded`**

Find the download success path: `grep -rn "class DownloadRepository" composeApp/src/commonMain --include="*.kt"`. After a successful download completes (state becomes "downloaded"), inject `Telemetry` and call:

```kotlin
telemetry.track(TelemetryEvent.EpisodeDownloaded)
```

Only on the success transition — not on enqueue, not on retry.

- [ ] **Step 4: Wire `episode_played`**

Find where playback begins on a fresh episode (first time this app session). The cleanest hook is in `KofipodPlayer` (Android actual) or whichever ViewModel issues "play this episode" — pick the one that fires *once per episode start*, not every resume. Call:

```kotlin
telemetry.track(TelemetryEvent.EpisodePlayed)
```

- [ ] **Step 5: Wire `ai_summary_generated`**

In `AiSummaryRepository`, after the pipeline writes a successful summary row to the DB (the `Ready` state transition), call:

```kotlin
telemetry.track(TelemetryEvent.AiSummaryGenerated(
    if (usedTranscript) AiPath.TRANSCRIPT else AiPath.AUDIO
))
```

Inject `Telemetry` via constructor and update the Koin factory.

- [ ] **Step 6: Wire `ai_discuss_message_sent`**

In `DiscussRepository`, after a successful response is persisted (not on send), call:

```kotlin
telemetry.track(TelemetryEvent.AiDiscussMessageSent(
    if (context is ChatContext.Transcript) AiPath.TRANSCRIPT else AiPath.AUDIO
))
```

Inject `Telemetry` via constructor and update the Koin factory.

- [ ] **Step 7: Verify it builds**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:detekt :composeApp:ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Run all tests to confirm no regressions**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add -A composeApp/src
git commit -m "feat(diagnostics): wire Telemetry call-sites for v1 event vocabulary"
```

---

## Task 14: Paparazzi snapshots

**Files:**
- Create: `composeApp/src/test/kotlin/app/kofipod/screenshots/PrivacyDiagnosticsSnapshots.kt`

- [ ] **Step 1: Read an existing snapshot to match style**

Run: `ls composeApp/src/test/kotlin/app/kofipod/screenshots/` and read one existing file to match the Paparazzi rule setup, theme wrapping, and test annotation style.

- [ ] **Step 2: Write the snapshot tests**

Create `PrivacyDiagnosticsSnapshots.kt` mirroring the existing pattern:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.screenshots

import app.cash.paparazzi.Paparazzi
import app.kofipod.ui.screens.settings.PrivacyDiagnosticsSection
import app.kofipod.ui.shell.DiagnosticsDisclosureSheet
import app.kofipod.ui.theme.KofipodTheme
import org.junit.Rule
import org.junit.Test

class PrivacyDiagnosticsSnapshots {

    @get:Rule val paparazzi: Paparazzi = paparazziRule()  // reuse the helper used by other snapshot files

    @Test fun privacyDiagnosticsSection_lightTheme_togglesOn() {
        paparazzi.snapshot {
            KofipodTheme(dark = false) {
                PrivacyDiagnosticsSection(
                    crashesEnabled = true,
                    usageEnabled = true,
                    onCrashesEnabledChange = {},
                    onUsageEnabledChange = {},
                    onOpenPrivacyPolicy = {},
                )
            }
        }
    }

    @Test fun privacyDiagnosticsSection_darkTheme_togglesOff() {
        paparazzi.snapshot {
            KofipodTheme(dark = true) {
                PrivacyDiagnosticsSection(
                    crashesEnabled = false,
                    usageEnabled = false,
                    onCrashesEnabledChange = {},
                    onUsageEnabledChange = {},
                    onOpenPrivacyPolicy = {},
                )
            }
        }
    }

    @Test fun disclosureSheet_lightTheme() {
        paparazzi.snapshot {
            KofipodTheme(dark = false) {
                DiagnosticsDisclosureSheet(
                    visible = true,
                    onAcknowledge = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    @Test fun disclosureSheet_darkTheme() {
        paparazzi.snapshot {
            KofipodTheme(dark = true) {
                DiagnosticsDisclosureSheet(
                    visible = true,
                    onAcknowledge = {},
                    onOpenSettings = {},
                )
            }
        }
    }
}
```

> Note: replace `paparazziRule()` and `KofipodTheme(dark = …)` with whatever the existing snapshot files use as the rule factory and theme entry points. The key thing is *four snapshots*: section in light/dark, sheet in light/dark.

- [ ] **Step 3: Record baselines**

Run: `./gradlew :composeApp:recordPaparazziDebug --tests "app.kofipod.screenshots.PrivacyDiagnosticsSnapshots"`
Expected: BUILD SUCCESSFUL. Four PNGs written under `composeApp/src/test/snapshots/images/`.

- [ ] **Step 4: Verify the new baselines**

Run: `./gradlew :composeApp:verifyPaparazziDebug --tests "app.kofipod.screenshots.PrivacyDiagnosticsSnapshots"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/test/kotlin/app/kofipod/screenshots/PrivacyDiagnosticsSnapshots.kt \
        composeApp/src/test/snapshots/images/
git commit -m "test(diagnostics): add Paparazzi snapshots for Privacy & Diagnostics UI"
```

---

## Task 15: Privacy doc and hosting runbook

**Files:**
- Create: `docs/privacy.md`
- Create: `docs/diagnostics-hosting.md`

- [ ] **Step 1: Write `docs/privacy.md`**

Create `docs/privacy.md`:

```markdown
# Kofipod Privacy

Kofipod ships with two opt-in diagnostic channels. Both default ON in
non-F-Droid builds but no data is sent until you've acknowledged the
first-launch disclosure once. F-Droid builds are inert: the build keys
are empty so neither subsystem is ever initialized.

## Crash reports

When enabled, Kofipod sends details about crashes so the developer can
fix them. Sent over HTTPS to a self-hosted GlitchTip instance.

| Field | Example | Notes |
|---|---|---|
| Stack trace | `NullPointerException at app.kofipod...:123` | Deobfuscated server-side |
| Exception class & message | `IOException: Failed to fetch ...` | URLs scrubbed |
| OS version | `Android 14` | |
| Device model | `Pixel 7` | Not unique |
| App version | `1.4.2` | |
| Locale | `en-US` | |
| Breadcrumbs | navigation events | URLs to Gemini, Google APIs, Podcast Index dropped |
| Release tag | `1.4.2` | |

**Not sent:** IP address, user ID, screen contents, view hierarchy, search queries, episode titles, podcast feed URLs, API keys.

## Anonymous usage data

When enabled, Kofipod sends counts of how often features are used so the
developer can prioritize work. Sent over HTTPS to Aptabase.

Events emitted (v1):

- `app_opened`
- `search_performed` with `source` ∈ {`typed`, `category`}
- `episode_downloaded`
- `episode_played`
- `ai_summary_generated` with `path` ∈ {`transcript`, `audio`}
- `ai_discuss_message_sent` with `path` ∈ {`transcript`, `audio`}

Per-event metadata: app version, OS version, locale.

**No client identifier is ever sent.** Aptabase's server hashes
`SHA(your IP + user-agent + a daily-rotated salt)` to count distinct
users. Because the salt rotates every 24 hours, the same device is a
different ID tomorrow. The raw IP is not stored.

## Toggling

Settings → Privacy & Diagnostics — two switches, one for each channel.
Turning a switch off stops further sends. It does not delete data
already sent.

## Data retention

- Crash reports: 30 days.
- Usage events: 12 months (Aptabase default).

## Hosting

- Crashes: GlitchTip self-hosted on Railway. Source:
  https://gitlab.com/glitchtip/glitchtip-frontend
- Usage: Aptabase cloud (https://aptabase.com). Source:
  https://github.com/aptabase/aptabase

## License of this app

GPL-3.0-or-later. See LICENSE.
```

- [ ] **Step 2: Write `docs/diagnostics-hosting.md`**

Create `docs/diagnostics-hosting.md`:

```markdown
# Diagnostics Hosting Runbook

## GlitchTip on Railway

1. Create a new Railway project, name it `kofipod-glitchtip`.
2. Deploy from GlitchTip's official `docker-compose.yml`
   (https://glitchtip.com/documentation/install). Services:
   `glitchtip` (web), `worker`, `postgres`, `redis`.
3. Set environment variables on the `glitchtip` and `worker` services:
   - `SECRET_KEY` — generate with `openssl rand -hex 32`
   - `DATABASE_URL` — Railway-provided Postgres URL
   - `REDIS_URL` — Railway-provided Redis URL
   - `EMAIL_URL` — leave unset (no SMTP needed; admin signup is local)
   - `DEFAULT_FROM_EMAIL` — your contact email
   - `ENABLE_USER_REGISTRATION` — set to `true` initially, flip to
     `false` after the first admin account is created
   - retention env (per current GlitchTip docs) set to 30 days
4. Attach a persistent volume to Postgres.
5. Custom domain: route a subdomain (e.g. `crash.kofipod.app`) at the
   `glitchtip` service. Railway provisions TLS automatically.
6. Sign up the admin account via the web UI; flip
   `ENABLE_USER_REGISTRATION=false`.
7. Create a project named `kofipod-android`. Note the DSN.
8. Generate a personal auth token (Profile → Auth Tokens) with
   `project:write` scope. Use only in CI.
9. Local dev: paste DSN into `local.properties` as `SENTRY_DSN=...`.
   CI: set `SENTRY_DSN` and `SENTRY_AUTH_TOKEN` as repo secrets.

### Backups

Schedule a weekly Postgres dump via Railway's scheduled jobs feature.
Crash data is non-precious; losing a week is acceptable.

### Cost

Expect $5–8/month at idle for low-thousand-MAU traffic.

## Aptabase cloud

1. Sign up at https://aptabase.com (or self-host using the AGPL-3 repo
   if/when you outgrow the free tier).
2. Create an app named `Kofipod`. Note the App Key (format `A-EU-…` or
   `A-US-…`).
3. Local dev: paste App Key into `local.properties` as
   `APTABASE_APP_KEY=...`. CI: set as repo secret.
4. Free tier: 20 000 events/month with no overage charges (paused
   until the next month if exceeded). At ~5 events/MAU/day this covers
   roughly 130 MAU. Migrate to self-host if you outgrow it.
```

- [ ] **Step 3: Commit**

```bash
git add docs/privacy.md docs/diagnostics-hosting.md
git commit -m "docs(diagnostics): add user privacy doc and maintainer hosting runbook"
```

---

## Task 16: Manual verification + final green-check

**Files:** none — this is verification only.

- [ ] **Step 1: Set up secrets locally**

Add real values for `SENTRY_DSN` and `APTABASE_APP_KEY` to `local.properties`. Confirm they are *not* staged for commit (`local.properties` is gitignored).

- [ ] **Step 2: Build and install debug APK**

Run:
```bash
./gradlew :composeApp:installDebug
~/Library/Android/sdk/platform-tools/adb shell am start -n app.kofipod/.MainActivity
```
Expected: app launches.

- [ ] **Step 3: Confirm pre-acknowledgement silence**

Use a network monitor (`mitmproxy`, Charles, or `adb shell tcpdump`) to confirm zero outbound requests to your GlitchTip host or `*.aptabase.com` *before* tapping "Got it" on the disclosure sheet.

- [ ] **Step 4: Acknowledge and confirm `app_opened` arrives in Aptabase**

Tap "Got it". Cold-start the app once more. Refresh the Aptabase dashboard. Expected: `app_opened` event count incremented.

- [ ] **Step 5: Confirm crashes reach GlitchTip**

Easiest: temporarily add a debug-only forced exception (a hidden long-press on the About row that throws `IllegalStateException("kofipod-diag-test")`). Trigger it. Refresh the GlitchTip dashboard. Expected: a new issue appears with a deobfuscated stack trace pointing at the long-press handler.

After confirming, **remove the forced-exception code** and recommit (don't ship the test crash hook).

- [ ] **Step 6: Confirm toggle-off stops sends**

In Settings, turn off "Send crash reports". Trigger another forced exception. Confirm no new issue arrives over a 60-second window.

In Settings, turn off "Share anonymous usage data". Perform a search. Confirm no `search_performed` event arrives.

- [ ] **Step 7: Confirm release build initializes the SDKs**

Run: `./gradlew :composeApp:assembleRelease` (with signing config available)
Install the release APK, opt in, repeat steps 4–5. R8 obfuscation is on; mapping was uploaded by the Sentry plugin in Task 12; stack traces should still be readable in GlitchTip.

- [ ] **Step 8: Run the full green-check sequence**

Run, in order:

```bash
./gradlew :composeApp:ktlintFormat
./gradlew :composeApp:detekt
./gradlew :composeApp:compileDebugKotlinAndroid
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:testDebugUnitTest
./gradlew :composeApp:verifyPaparazziDebug
```
Expected: all green.

- [ ] **Step 9: Final commit if any cleanup landed**

```bash
git status
# If anything is dirty (e.g. removal of the test-crash hook):
git add -A && git commit -m "chore(diagnostics): clean up after manual verification"
```

- [ ] **Step 10: Open PR**

```bash
git push -u origin worktree-remote-telemetry
gh pr create --title "feat: opt-in diagnostics — GlitchTip crashes + Aptabase usage" \
  --body "Implements docs/superpowers/specs/2026-05-05-diagnostics-design.md per docs/superpowers/plans/2026-05-05-diagnostics-implementation.md."
```
