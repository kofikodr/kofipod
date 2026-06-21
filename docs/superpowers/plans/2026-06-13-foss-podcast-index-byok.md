# FOSS Podcast Index BYOK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let FOSS-flavor users paste their own Podcast Index API key/secret in Settings so search works without rebuilding or restarting the app.

**Architecture:** Mirror the existing Gemini BYOK stack — an encrypted credential store, a reactive config repository, a Settings sub-screen with validate-before-save — and make `PodcastIndexApi` resolve credentials through a suspend client *provider* that rebuilds the SDK client only when the effective credentials change. Effective credentials = stored BYOK creds if present, else the build-time `PodcastIndexCredentials` (blank on FOSS).

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Koin DI, SQLDelight (unrelated here), `com.mr3y.podcastindex` SDK (`PodcastIndexClient` interface + `ktor3` impl), AndroidX Security `EncryptedSharedPreferences`, kotlinx.coroutines, kotlin.test + Paparazzi.

**Spec:** `docs/superpowers/specs/2026-06-13-foss-podcast-index-byok-design.md`

---

## File Structure

**New — commonMain:**
- `data/api/PodcastIndexCreds.kt` — `data class PodcastIndexCreds(key, secret)`.
- `data/api/PodcastIndexCredentialStore.kt` — `interface PodcastIndexCredentialStore`.
- `data/api/PodcastIndexConfigRepository.kt` — reactive façade over the store.
- `data/api/EffectivePodcastIndexCredentials.kt` — BYOK-or-build-time resolver.
- `data/api/PodcastIndexClientProvider.kt` — suspend provider that rebuilds the SDK client on creds change.
- `data/api/PodcastIndexValidator.kt` — `interface` + default impl + `PodcastIndexValidation` result.
- `ui/screens/settings/podcastindex/PodcastIndexSetupViewModel.kt`
- `ui/screens/settings/podcastindex/PodcastIndexSetupScreen.kt`

**New — androidMain:** `data/api/PodcastIndexCredentialStore.android.kt` — `AndroidPodcastIndexCredentialStore`.
**New — iosMain:** `data/api/PodcastIndexCredentialStore.ios.kt` — `IosPodcastIndexCredentialStoreStub`.

**Modified:**
- `data/api/PodcastIndexApi.kt` — take `PodcastIndexClientProvider`; drop `create()`.
- `di/CommonModule.kt` — register store consumers, provider, validator, config repo, VM; rewire `PodcastIndexApi`.
- `di/AndroidModule.kt` / `di/IosPlatformModule.kt` — bind `PodcastIndexCredentialStore`.
- `ui/nav/Routes.kt` — add `PodcastIndexSetup` route.
- `ui/nav/KofipodNavHost.kt` — add `composable<Route.PodcastIndexSetup>` + `onOpenPodcastIndexSetup` callback.
- `ui/screens/settings/SettingsScreen.kt` + its ViewModel — conditional row.
- `androidMain/res/xml/backup_rules_legacy.xml` — only if it lacks the `kofipod_secure` exclusion.

**New tests (commonTest unless noted):**
- `PodcastIndexConfigRepositoryTest`, `EffectivePodcastIndexCredentialsTest`,
  `PodcastIndexClientProviderTest`, `PodcastIndexApiProviderTest`,
  `PodcastIndexValidatorTest`, `PodcastIndexSetupViewModelTest`.
- Paparazzi (`test` source set): `PodcastIndexSetupSnapshots`.

**Conventions:** every new file starts with `// SPDX-License-Identifier: GPL-3.0-or-later`. Use `Dispatchers.Default` (never `IO`) in commonMain. Commit after each task. Run `./gradlew :composeApp:testFossDebugUnitTest --tests "<fqcn>"` for a single JVM test class.

---

## Task 1: Credential types + store seam + iOS stub

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexCreds.kt`
- Create: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexCredentialStore.kt`
- Create: `composeApp/src/iosMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexCredentialStore.ios.kt`

This task creates pure declarations (no logic), so there is no unit test — the store's behaviour is covered through the config repository (Task 2) with a fake, and the Android impl is exercised in the on-device step (Task 10).

- [ ] **Step 1: Create the credential value type**

```kotlin
// PodcastIndexCreds.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

/** A Podcast Index API key + secret pair. Both must be non-blank to be usable. */
data class PodcastIndexCreds(
    val key: String,
    val secret: String,
) {
    val isUsable: Boolean get() = key.isNotBlank() && secret.isNotBlank()
}
```

- [ ] **Step 2: Create the store interface**

```kotlin
// PodcastIndexCredentialStore.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

/**
 * On-device encrypted store for user-supplied Podcast Index credentials (FOSS BYOK).
 * Mirrors [com.kofikodr.kofipod.ai.KeyVault] but holds a key+secret pair. Returns null
 * unless BOTH values are present and non-blank.
 */
interface PodcastIndexCredentialStore {
    suspend fun get(): PodcastIndexCreds?

    suspend fun set(creds: PodcastIndexCreds)

    suspend fun clear()
}
```

- [ ] **Step 3: Create the iOS stub**

```kotlin
// PodcastIndexCredentialStore.ios.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

/** iOS has no real Podcast Index integration yet; BYOK is a no-op (matches IosKeyVaultStub). */
class IosPodcastIndexCredentialStoreStub : PodcastIndexCredentialStore {
    override suspend fun get(): PodcastIndexCreds? = null

    override suspend fun set(creds: PodcastIndexCreds) = Unit

    override suspend fun clear() = Unit
}
```

- [ ] **Step 4: Verify it compiles (common + iOS)**

Run: `./gradlew :composeApp:compileFossDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexCreds.kt \
        composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexCredentialStore.kt \
        composeApp/src/iosMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexCredentialStore.ios.kt
git commit -m "feat(pi-byok): credential value type + store seam + iOS stub"
```

---

## Task 2: PodcastIndexConfigRepository (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexConfigRepository.kt`
- Test: `composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexConfigRepositoryTest.kt`

Mirrors `AiConfigRepository` (hydrate-on-startup, flag-vault sync, no in-memory memoisation).

- [ ] **Step 1: Write the failing test**

```kotlin
// PodcastIndexConfigRepositoryTest.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastIndexConfigRepositoryTest {
    private class FakeStore(var stored: PodcastIndexCreds? = null, val failSet: Boolean = false) :
        PodcastIndexCredentialStore {
        override suspend fun get(): PodcastIndexCreds? = stored
        override suspend fun set(creds: PodcastIndexCreds) {
            if (failSet) error("disk full")
            stored = creds
        }
        override suspend fun clear() { stored = null }
    }

    @Test
    fun hydratesConfiguredTrue_whenStoreHasUsableCreds() = runTest {
        val repo = PodcastIndexConfigRepository(FakeStore(PodcastIndexCreds("k", "s")), backgroundScope)
        advanceUntilIdle()
        assertTrue(repo.isConfigured().value)
    }

    @Test
    fun hydratesConfiguredFalse_whenStoreEmpty() = runTest {
        val repo = PodcastIndexConfigRepository(FakeStore(null), backgroundScope)
        advanceUntilIdle()
        assertFalse(repo.isConfigured().value)
    }

    @Test
    fun setCredentials_persistsAndFlipsFlag() = runTest {
        val store = FakeStore(null)
        val repo = PodcastIndexConfigRepository(store, backgroundScope)
        advanceUntilIdle()
        repo.setCredentials(PodcastIndexCreds("k", "s"))
        assertTrue(repo.isConfigured().value)
        assertEquals(PodcastIndexCreds("k", "s"), repo.currentCreds())
    }

    @Test
    fun setCredentials_doesNotFlipFlag_whenStoreWriteFails() = runTest {
        val repo = PodcastIndexConfigRepository(FakeStore(null, failSet = true), backgroundScope)
        advanceUntilIdle()
        runCatching { repo.setCredentials(PodcastIndexCreds("k", "s")) }
        assertFalse(repo.isConfigured().value, "flag must stay false when the encrypted write fails")
    }

    @Test
    fun disconnect_clearsStoreAndFlag() = runTest {
        val store = FakeStore(PodcastIndexCreds("k", "s"))
        val repo = PodcastIndexConfigRepository(store, backgroundScope)
        advanceUntilIdle()
        repo.disconnect()
        assertFalse(repo.isConfigured().value)
        assertNull(repo.currentCreds())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.data.api.PodcastIndexConfigRepositoryTest"`
Expected: FAIL — `PodcastIndexConfigRepository` is unresolved.

- [ ] **Step 3: Implement**

```kotlin
// PodcastIndexConfigRepository.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reactive façade over [PodcastIndexCredentialStore] for the FOSS BYOK flow. Mirrors
 * AiConfigRepository: hydrate once on startup, keep the in-memory flag and the encrypted
 * store in sync, and never memoise the raw secret in process memory.
 */
class PodcastIndexConfigRepository(
    private val store: PodcastIndexCredentialStore,
    appScope: CoroutineScope,
) {
    private val configured = MutableStateFlow(false)

    init {
        appScope.launch {
            configured.value =
                runCatching { store.get()?.isUsable == true }
                    .getOrElse {
                        println("Kofipod-PI: credential hydration failed: ${it::class.simpleName}")
                        false
                    }
        }
    }

    fun isConfigured(): StateFlow<Boolean> = configured.asStateFlow()

    /** One-shot read for the client provider. Never log or persist. */
    suspend fun currentCreds(): PodcastIndexCreds? = store.get()?.takeIf { it.isUsable }

    suspend fun setCredentials(creds: PodcastIndexCreds) {
        store.set(creds)
        configured.value = true
    }

    suspend fun disconnect() {
        store.clear()
        configured.value = false
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.data.api.PodcastIndexConfigRepositoryTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexConfigRepository.kt \
        composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexConfigRepositoryTest.kt
git commit -m "feat(pi-byok): reactive PodcastIndexConfigRepository"
```

---

## Task 3: EffectivePodcastIndexCredentials (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/EffectivePodcastIndexCredentials.kt`
- Test: `composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/data/api/EffectivePodcastIndexCredentialsTest.kt`

The build-time fallback is injected (default reads the `PodcastIndexCredentials` expect object) so the resolver is unit-testable without depending on `BuildConfig`.

- [ ] **Step 1: Write the failing test**

```kotlin
// EffectivePodcastIndexCredentialsTest.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class EffectivePodcastIndexCredentialsTest {
    private class FakeStore(var stored: PodcastIndexCreds?) : PodcastIndexCredentialStore {
        override suspend fun get(): PodcastIndexCreds? = stored
        override suspend fun set(creds: PodcastIndexCreds) { stored = creds }
        override suspend fun clear() { stored = null }
    }

    private fun config(stored: PodcastIndexCreds?, scope: kotlinx.coroutines.CoroutineScope) =
        PodcastIndexConfigRepository(FakeStore(stored), scope)

    @Test
    fun returnsBuildTime_whenNoByok() = runTest {
        val effective =
            EffectivePodcastIndexCredentials(
                config = config(null, backgroundScope),
                buildTime = PodcastIndexCreds("build-key", "build-secret"),
            )
        advanceUntilIdle()
        assertEquals(PodcastIndexCreds("build-key", "build-secret"), effective.resolve())
    }

    @Test
    fun returnsByok_whenConfigured() = runTest {
        val effective =
            EffectivePodcastIndexCredentials(
                config = config(PodcastIndexCreds("user-key", "user-secret"), backgroundScope),
                buildTime = PodcastIndexCreds("build-key", "build-secret"),
            )
        advanceUntilIdle()
        assertEquals(PodcastIndexCreds("user-key", "user-secret"), effective.resolve())
    }

    @Test
    fun returnsBuildTime_whenByokHalfFilled() = runTest {
        val effective =
            EffectivePodcastIndexCredentials(
                config = config(PodcastIndexCreds("user-key", ""), backgroundScope),
                buildTime = PodcastIndexCreds("build-key", "build-secret"),
            )
        advanceUntilIdle()
        assertEquals(PodcastIndexCreds("build-key", "build-secret"), effective.resolve())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.data.api.EffectivePodcastIndexCredentialsTest"`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implement**

```kotlin
// EffectivePodcastIndexCredentials.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

/**
 * Resolves which Podcast Index credentials are actually in effect: user-supplied BYOK creds
 * when configured and usable, otherwise the build-time [PodcastIndexCredentials] (blank on FOSS,
 * the maintainer key on Play). [buildTime] is injectable so this is unit-testable without BuildConfig.
 */
class EffectivePodcastIndexCredentials(
    private val config: PodcastIndexConfigRepository,
    private val buildTime: PodcastIndexCreds =
        PodcastIndexCreds(PodcastIndexCredentials.key, PodcastIndexCredentials.secret),
) {
    suspend fun resolve(): PodcastIndexCreds = config.currentCreds()?.takeIf { it.isUsable } ?: buildTime
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.data.api.EffectivePodcastIndexCredentialsTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/EffectivePodcastIndexCredentials.kt \
        composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/data/api/EffectivePodcastIndexCredentialsTest.kt
git commit -m "feat(pi-byok): EffectivePodcastIndexCredentials resolver"
```

---

## Task 4: PodcastIndexClientProvider (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexClientProvider.kt`
- Test: `composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexClientProviderTest.kt`

The provider returns a `PodcastIndexClient`, rebuilding the delegate only when effective creds change. The `build` factory is injected so tests don't need the real SDK. Concurrency guarded by a `Mutex`.

- [ ] **Step 1: Write the failing test**

```kotlin
// PodcastIndexClientProviderTest.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import com.mr3y.podcastindex.PodcastIndexClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastIndexClientProviderTest {
    // A fake effective resolver backed by a mutable creds holder.
    private class FixedEffective(var creds: PodcastIndexCreds) {
        fun asEffective(): suspend () -> PodcastIndexCreds = { creds }
    }

    // We can't easily instantiate the SDK's PodcastIndexClient; use a sentinel per creds value.
    private fun fakeClient(): PodcastIndexClient = object : PodcastIndexClient {
        override val search get() = error("unused"); override val podcasts get() = error("unused")
        override val episodes get() = error("unused"); override val recent get() = error("unused")
        override val misc get() = error("unused"); override val categories get() = error("unused")
        override val apple get() = error("unused"); override val value get() = error("unused")
        override val hub get() = error("unused"); override val stats get() = error("unused")
    }

    @Test
    fun buildsOnce_andReusesWhenCredsUnchanged() = runTest {
        var builds = 0
        val holder = FixedEffective(PodcastIndexCreds("k", "s"))
        val provider = PodcastIndexClientProvider(resolve = holder.asEffective(), build = { builds++; fakeClient() })
        val a = provider.get(); val b = provider.get()
        assertSame(a, b)
        assertEquals(1, builds)
    }

    @Test
    fun rebuildsOnceWhenCredsChange() = runTest {
        var builds = 0
        val holder = FixedEffective(PodcastIndexCreds("k1", "s1"))
        val provider = PodcastIndexClientProvider(resolve = holder.asEffective(), build = { builds++; fakeClient() })
        provider.get()
        holder.creds = PodcastIndexCreds("k2", "s2")
        provider.get(); provider.get()
        assertEquals(2, builds, "one initial build + one rebuild on the creds change")
    }

    @Test
    fun concurrentGets_doNotDoubleBuild() = runTest {
        var builds = 0
        val holder = FixedEffective(PodcastIndexCreds("k", "s"))
        val provider = PodcastIndexClientProvider(resolve = holder.asEffective(), build = { builds++; fakeClient() })
        (1..8).map { async { provider.get() } }.awaitAll()
        assertEquals(1, builds)
    }
}
```

> Note for the implementer: the `PodcastIndexClient` interface property list in `fakeClient()` above is illustrative. Open `com.mr3y.podcastindex.PodcastIndexClient` (jump-to-definition) and override exactly its declared members with `error("unused")`. The test only needs an identity-distinct instance, never calls a member.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.data.api.PodcastIndexClientProviderTest"`
Expected: FAIL — `PodcastIndexClientProvider` unresolved.

- [ ] **Step 3: Implement**

```kotlin
// PodcastIndexClientProvider.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import com.kofikodr.kofipod.config.BuildKonfig
import com.mr3y.podcastindex.PodcastIndexClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.mr3y.podcastindex.ktor3.PodcastIndexClient as Ktor3PodcastIndexClient

/**
 * Supplies a [PodcastIndexClient] built from the currently-effective credentials, rebuilding the
 * SDK client only when those credentials change (e.g. the user connects/disconnects BYOK creds).
 * The SDK client captures key/secret at construction, so this is how credential changes take
 * effect without an app restart. A [Mutex] keeps concurrent searches from racing the rebuild.
 */
class PodcastIndexClientProvider(
    private val resolve: suspend () -> PodcastIndexCreds,
    private val build: (PodcastIndexCreds) -> PodcastIndexClient = { creds ->
        Ktor3PodcastIndexClient(
            authKey = creds.key,
            authSecret = creds.secret,
            userAgent = BuildKonfig.USER_AGENT,
        )
    },
) {
    private val lock = Mutex()
    private var builtFor: PodcastIndexCreds? = null
    private var current: PodcastIndexClient? = null

    suspend fun get(): PodcastIndexClient {
        val creds = resolve()
        lock.withLock {
            val existing = current
            if (existing != null && builtFor == creds) return existing
            return build(creds).also {
                current = it
                builtFor = creds
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.data.api.PodcastIndexClientProviderTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexClientProvider.kt \
        composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexClientProviderTest.kt
git commit -m "feat(pi-byok): reactive PodcastIndexClientProvider"
```

---

## Task 5: Route PodcastIndexApi through the provider (TDD)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexApi.kt`
- Test: `composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexApiProviderTest.kt`

Change `PodcastIndexApi` to hold a `PodcastIndexClientProvider` and call `provider.get()` at the start of each method; delete the static `create()`.

- [ ] **Step 1: Write the failing test**

```kotlin
// PodcastIndexApiProviderTest.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PodcastIndexApiProviderTest {
    @Test
    fun trending_resolvesClientThroughProviderEachCall() = runTest {
        var gets = 0
        // Build a provider whose build() returns a stub client whose misc.getTrending returns empty.
        val provider =
            PodcastIndexClientProvider(
                resolve = { PodcastIndexCreds("k", "s") },
                build = { StubPodcastIndexClient() },
            )
        val countingProvider = object {
            suspend fun get() = provider.get().also { gets++ }
        }
        // PodcastIndexApi takes the real provider; assert it delegates without throwing.
        val api = PodcastIndexApi(provider)
        assertEquals(emptyList(), api.trending(limit = 1))
    }
}
```

> Implementer note: `StubPodcastIndexClient` must implement `com.mr3y.podcastindex.PodcastIndexClient` with a `misc` whose `getTrending(...)` returns an empty `TrendingPodcasts`-shaped result. Inspect the SDK's `MiscApi.getTrending` return type and build the minimal empty value. If constructing the SDK's response types is impractical in a unit test, replace this test with a direct `PodcastIndexClientProvider` delegation assertion (Task 4 already covers rebuild logic) and verify `PodcastIndexApi` wiring through the on-device step instead — note the reduction here in the commit message rather than leaving a hollow test.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.data.api.PodcastIndexApiProviderTest"`
Expected: FAIL — `PodcastIndexApi` still takes a `PodcastIndexClient`, constructor mismatch.

- [ ] **Step 3: Modify `PodcastIndexApi`**

Replace the constructor and the `client.` call sites, and delete the `create()` factory:

```kotlin
class PodcastIndexApi(private val clientProvider: PodcastIndexClientProvider) {
    suspend fun searchByTitle(query: String, limit: Int = DEFAULT_LIMIT): List<PodcastFeed> =
        clientProvider.get().search.forPodcastsByTitle(title = query, limit = limit).feeds.filterContentTypes()

    suspend fun searchByTerm(query: String, limit: Int = DEFAULT_LIMIT): List<PodcastFeed> =
        clientProvider.get().search.forPodcastsByTerm(term = query, limit = limit).feeds.filterContentTypes()

    suspend fun searchByPerson(person: String, limit: Int = DEFAULT_LIMIT): List<EpisodeFeed> =
        clientProvider.get().search.forEpisodesByPerson(name = person, limit = limit).items

    suspend fun trending(limit: Int = DEFAULT_LIMIT, includeCategories: List<Category> = emptyList()):
        List<com.mr3y.podcastindex.model.TrendingFeed> =
        clientProvider.get().misc.getTrending(limit = limit, includeCategories = includeCategories).feeds

    suspend fun podcastByFeedId(feedId: Long): PodcastFeed = clientProvider.get().podcasts.byFeedId(id = feedId).feed

    suspend fun podcastByFeedUrl(url: String): PodcastFeed = clientProvider.get().podcasts.byFeedUrl(url = url).feed

    suspend fun episodesByFeedId(feedId: Long, limit: Int = EPISODE_LIMIT): List<EpisodeFeed> =
        clientProvider.get().episodes.byFeedId(ids = listOf(feedId), limit = limit).items

    private fun List<PodcastFeed>.filterContentTypes(): List<PodcastFeed> =
        filter { feed ->
            val m = feed.medium?.lowercase()
            m != "music" && m != "musicl" && m != "audiobook"
        }

    companion object {
        const val PAGE_SIZE = 10
        const val DEFAULT_LIMIT = 30
        const val EPISODE_LIMIT = 50
    }
}
```

Remove the now-unused imports `BuildKonfig`, `PodcastIndexCredentials`, and the `Ktor3PodcastIndexClient` alias from this file (they moved to `PodcastIndexClientProvider`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.data.api.PodcastIndexApiProviderTest"`
Expected: PASS. (`./gradlew :composeApp:compileFossDebugKotlinAndroid` will now fail at the `CommonModule` `PodcastIndexApi.create()` call site — that is fixed in Task 9; do not commit a broken compile. Sequence: if doing tasks out of order, apply the Task 9 DI change for `PodcastIndexApi` before compiling the whole module.)

- [ ] **Step 5: Commit** (include the Task 9 DI edit for `single { PodcastIndexApi(...) }` if compiling the full module here)

```bash
git add composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexApi.kt \
        composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexApiProviderTest.kt
git commit -m "feat(pi-byok): PodcastIndexApi resolves client via provider, drop create()"
```

---

## Task 6: PodcastIndexValidator (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexValidator.kt`
- Test: `composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexValidatorTest.kt`

A seam that probes the API with the candidate creds. The network `probe` lambda is injected for tests; classification of the failure (auth vs network) is a pure function tested directly.

- [ ] **Step 1: Write the failing test**

```kotlin
// PodcastIndexValidatorTest.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PodcastIndexValidatorTest {
    @Test
    fun validProbe_returnsValid() = runTest {
        val v = DefaultPodcastIndexValidator(probe = { /* success */ })
        assertEquals(PodcastIndexValidation.Valid, v.validate(PodcastIndexCreds("k", "s")))
    }

    @Test
    fun authFailure_returnsInvalid() = runTest {
        val v = DefaultPodcastIndexValidator(probe = { throw RuntimeException("HTTP 401 Unauthorized") })
        assertEquals(PodcastIndexValidation.Invalid, v.validate(PodcastIndexCreds("k", "s")))
    }

    @Test
    fun forbidden_returnsInvalid() = runTest {
        val v = DefaultPodcastIndexValidator(probe = { throw RuntimeException("response 403") })
        assertEquals(PodcastIndexValidation.Invalid, v.validate(PodcastIndexCreds("k", "s")))
    }

    @Test
    fun otherFailure_returnsNetworkError() = runTest {
        val v = DefaultPodcastIndexValidator(probe = { throw RuntimeException("Unable to resolve host") })
        assertEquals(PodcastIndexValidation.NetworkError, v.validate(PodcastIndexCreds("k", "s")))
    }

    @Test
    fun classify_matchesStatusInCauseChain() {
        assertEquals(PodcastIndexValidation.Invalid, classifyPodcastIndexFailure(RuntimeException(IllegalStateException("status=401"))))
        assertEquals(PodcastIndexValidation.NetworkError, classifyPodcastIndexFailure(RuntimeException("timeout")))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.data.api.PodcastIndexValidatorTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement**

```kotlin
// PodcastIndexValidator.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import com.kofikodr.kofipod.config.BuildKonfig
import com.mr3y.podcastindex.ktor3.PodcastIndexClient as Ktor3PodcastIndexClient

enum class PodcastIndexValidation { Valid, Invalid, NetworkError }

interface PodcastIndexValidator {
    suspend fun validate(creds: PodcastIndexCreds): PodcastIndexValidation
}

/**
 * Validates Podcast Index creds with one cheap authenticated call (trending, max 1) using a
 * throwaway client built from the candidate creds. 401/403 in the error chain → Invalid;
 * anything else that throws → NetworkError. [probe] is injected so tests don't hit the network.
 */
class DefaultPodcastIndexValidator(
    private val probe: suspend (PodcastIndexCreds) -> Unit = { creds ->
        Ktor3PodcastIndexClient(
            authKey = creds.key,
            authSecret = creds.secret,
            userAgent = BuildKonfig.USER_AGENT,
        ).misc.getTrending(limit = 1)
    },
) : PodcastIndexValidator {
    override suspend fun validate(creds: PodcastIndexCreds): PodcastIndexValidation =
        runCatching { probe(creds) }
            .fold(
                onSuccess = { PodcastIndexValidation.Valid },
                onFailure = { classifyPodcastIndexFailure(it) },
            )
}

/** Walks the throwable cause chain looking for an HTTP 401/403 signal → Invalid, else NetworkError. */
internal fun classifyPodcastIndexFailure(error: Throwable): PodcastIndexValidation {
    var t: Throwable? = error
    while (t != null) {
        val m = t.message?.lowercase().orEmpty()
        if ("401" in m || "403" in m || "unauthorized" in m || "forbidden" in m) {
            return PodcastIndexValidation.Invalid
        }
        t = t.cause
    }
    return PodcastIndexValidation.NetworkError
}
```

> Implementer note: confirm during the on-device step what exception the `ktor3` client actually throws on a 401 (likely a Ktor `ClientRequestException` whose message includes the status line). The substring classifier above is deliberately defensive; if the SDK surfaces a typed status, prefer matching that type. Adjust `classifyPodcastIndexFailure` and add a test case if so.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.data.api.PodcastIndexValidatorTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexValidator.kt \
        composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexValidatorTest.kt
git commit -m "feat(pi-byok): PodcastIndexValidator with auth/network classification"
```

---

## Task 7: PodcastIndexSetupViewModel (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/settings/podcastindex/PodcastIndexSetupViewModel.kt`
- Test: `composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/ui/screens/settings/podcastindex/PodcastIndexSetupViewModelTest.kt`

Mirrors `AiSetupViewModel`: validate-before-save, double-submit guard, disconnect with confirm, error→copy mapping lifted to a testable `internal fun`.

- [ ] **Step 1: Write the failing test**

```kotlin
// PodcastIndexSetupViewModelTest.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.settings.podcastindex

import com.kofikodr.kofipod.data.api.PodcastIndexConfigRepository
import com.kofikodr.kofipod.data.api.PodcastIndexCredentialStore
import com.kofikodr.kofipod.data.api.PodcastIndexCreds
import com.kofikodr.kofipod.data.api.PodcastIndexValidation
import com.kofikodr.kofipod.data.api.PodcastIndexValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastIndexSetupViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeStore(var stored: PodcastIndexCreds? = null) : PodcastIndexCredentialStore {
        override suspend fun get() = stored
        override suspend fun set(creds: PodcastIndexCreds) { stored = creds }
        override suspend fun clear() { stored = null }
    }
    private class FakeValidator(val result: PodcastIndexValidation) : PodcastIndexValidator {
        var calls = 0
        override suspend fun validate(creds: PodcastIndexCreds): PodcastIndexValidation { calls++; return result }
    }

    private fun vm(validator: PodcastIndexValidator, store: FakeStore, scope: kotlinx.coroutines.CoroutineScope) =
        PodcastIndexSetupViewModel(PodcastIndexConfigRepository(store, scope), validator)

    @Test
    fun connect_validCreds_persistsAndClearsFields() = runTest {
        val store = FakeStore(); val validator = FakeValidator(PodcastIndexValidation.Valid)
        val sut = vm(validator, store, backgroundScope)
        sut.onKeyChange("k"); sut.onSecretChange("s")
        sut.connect(); advanceUntilIdle()
        assertEquals(PodcastIndexCreds("k", "s"), store.stored)
        assertTrue(sut.state.value.connected)
        assertEquals("", sut.state.value.keyValue)
        assertEquals("", sut.state.value.secretValue)
        assertNull(sut.state.value.errorMessage)
    }

    @Test
    fun connect_invalidCreds_doesNotPersist_showsError() = runTest {
        val store = FakeStore(); val validator = FakeValidator(PodcastIndexValidation.Invalid)
        val sut = vm(validator, store, backgroundScope)
        sut.onKeyChange("k"); sut.onSecretChange("bad")
        sut.connect(); advanceUntilIdle()
        assertNull(store.stored)
        assertFalse(sut.state.value.connected)
        assertEquals(invalidCredsCopy(), sut.state.value.errorMessage)
    }

    @Test
    fun connect_networkError_showsNetworkCopy() = runTest {
        val sut = vm(FakeValidator(PodcastIndexValidation.NetworkError), FakeStore(), backgroundScope)
        sut.onKeyChange("k"); sut.onSecretChange("s"); sut.connect(); advanceUntilIdle()
        assertEquals(networkErrorCopy(), sut.state.value.errorMessage)
    }

    @Test
    fun connect_blankFields_showsPromptWithoutValidating() = runTest {
        val validator = FakeValidator(PodcastIndexValidation.Valid)
        val sut = vm(validator, FakeStore(), backgroundScope)
        sut.onKeyChange("k") // secret left blank
        sut.connect(); advanceUntilIdle()
        assertEquals(0, validator.calls)
        assertEquals(missingFieldsCopy(), sut.state.value.errorMessage)
    }

    @Test
    fun disconnect_clearsStoredCreds() = runTest {
        val store = FakeStore(PodcastIndexCreds("k", "s"))
        val sut = vm(FakeValidator(PodcastIndexValidation.Valid), store, backgroundScope)
        advanceUntilIdle()
        sut.requestDisconnect(); sut.confirmDisconnect(); advanceUntilIdle()
        assertNull(store.stored)
        assertFalse(sut.state.value.connected)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.ui.screens.settings.podcastindex.PodcastIndexSetupViewModelTest"`
Expected: FAIL — `PodcastIndexSetupViewModel` unresolved.

- [ ] **Step 3: Implement**

```kotlin
// PodcastIndexSetupViewModel.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.settings.podcastindex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.data.api.PodcastIndexConfigRepository
import com.kofikodr.kofipod.data.api.PodcastIndexCreds
import com.kofikodr.kofipod.data.api.PodcastIndexValidation
import com.kofikodr.kofipod.data.api.PodcastIndexValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PodcastIndexSetupUiState(
    val connected: Boolean = false,
    val keyValue: String = "",
    val secretValue: String = "",
    val verifying: Boolean = false,
    val errorMessage: String? = null,
    val showDisconnectConfirm: Boolean = false,
)

class PodcastIndexSetupViewModel(
    private val config: PodcastIndexConfigRepository,
    private val validator: PodcastIndexValidator,
) : ViewModel() {
    private val keyValue = MutableStateFlow("")
    private val secretValue = MutableStateFlow("")
    private val verifying = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val showDisconnectConfirm = MutableStateFlow(false)

    val state: StateFlow<PodcastIndexSetupUiState> =
        combine(
            config.isConfigured(), keyValue, secretValue, verifying, errorMessage, showDisconnectConfirm,
        ) { values ->
            PodcastIndexSetupUiState(
                connected = values[0] as Boolean,
                keyValue = values[1] as String,
                secretValue = values[2] as String,
                verifying = values[3] as Boolean,
                errorMessage = values[4] as String?,
                showDisconnectConfirm = values[5] as Boolean,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastIndexSetupUiState())

    fun onKeyChange(value: String) {
        keyValue.value = value
        if (errorMessage.value != null) errorMessage.value = null
    }

    fun onSecretChange(value: String) {
        secretValue.value = value
        if (errorMessage.value != null) errorMessage.value = null
    }

    fun connect() {
        if (verifying.value) return
        val creds = PodcastIndexCreds(keyValue.value.trim(), secretValue.value.trim())
        if (!creds.isUsable) {
            errorMessage.value = missingFieldsCopy()
            return
        }
        viewModelScope.launch {
            verifying.value = true
            errorMessage.value = null
            val result = validator.validate(creds)
            verifying.value = false
            when (result) {
                PodcastIndexValidation.Valid -> {
                    config.setCredentials(creds)
                    keyValue.value = ""
                    secretValue.value = ""
                }
                PodcastIndexValidation.Invalid -> errorMessage.value = invalidCredsCopy()
                PodcastIndexValidation.NetworkError -> errorMessage.value = networkErrorCopy()
            }
        }
    }

    fun requestDisconnect() { showDisconnectConfirm.value = true }
    fun cancelDisconnect() { showDisconnectConfirm.value = false }

    fun confirmDisconnect() =
        viewModelScope.launch {
            config.disconnect()
            showDisconnectConfirm.value = false
            keyValue.value = ""
            secretValue.value = ""
        }
}

internal fun missingFieldsCopy(): String = "Enter both your Podcast Index key and secret."
internal fun invalidCredsCopy(): String = "That key or secret was rejected. Double-check both values."
internal fun networkErrorCopy(): String = "Couldn't reach Podcast Index. Check your connection and try again."
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:testFossDebugUnitTest --tests "com.kofikodr.kofipod.ui.screens.settings.podcastindex.PodcastIndexSetupViewModelTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/settings/podcastindex/PodcastIndexSetupViewModel.kt \
        composeApp/src/commonTest/kotlin/com/kofikodr/kofipod/ui/screens/settings/podcastindex/PodcastIndexSetupViewModelTest.kt
git commit -m "feat(pi-byok): PodcastIndexSetupViewModel (validate-before-save)"
```

---

## Task 8: PodcastIndexSetupScreen + Paparazzi snapshots

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/settings/podcastindex/PodcastIndexSetupScreen.kt`
- Test: `composeApp/src/test/kotlin/com/kofikodr/kofipod/screenshots/PodcastIndexSetupSnapshots.kt`

Mirror `AiSetupScreen` structure. Split a stateless `PodcastIndexSetupContent(state, callbacks…)` from the Koin-wired `PodcastIndexSetupScreen(onBack)` so Paparazzi can snapshot without Koin/network (same pattern the codebase uses for `LibraryContent`, `AiSetupScreen`).

- [ ] **Step 1: Implement the screen**

Open `ui/screens/settings/ai/AiSetupScreen.kt` and follow its exact composable vocabulary (`KofipodScaffold`/back header, `OutlinedTextField`, the connect button, `ConnectedFooter`, `DisconnectConfirmDialog`, theme colors via `LocalKofipodColors`). Produce:

```kotlin
// PodcastIndexSetupScreen.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.settings.podcastindex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PodcastIndexSetupScreen(
    onBack: () -> Unit,
    viewModel: PodcastIndexSetupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    PodcastIndexSetupContent(
        state = state,
        onKeyChange = viewModel::onKeyChange,
        onSecretChange = viewModel::onSecretChange,
        onConnect = viewModel::connect,
        onRequestDisconnect = viewModel::requestDisconnect,
        onCancelDisconnect = viewModel::cancelDisconnect,
        onConfirmDisconnect = viewModel::confirmDisconnect,
        onBack = onBack,
    )
}

@Composable
internal fun PodcastIndexSetupContent(
    state: PodcastIndexSetupUiState,
    onKeyChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onConnect: () -> Unit,
    onRequestDisconnect: () -> Unit,
    onCancelDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    // Two OutlinedTextFields (Key, Secret — secret uses PasswordVisualTransformation),
    // both disabled while state.verifying; an inline error Text(state.errorMessage);
    // a Connect button ("Connect" / "Verifying…") calling onConnect; a ConnectedFooter with
    // Disconnect when state.connected; a confirm dialog gated on state.showDisconnectConfirm;
    // helper text + link to https://podcastindex.org/api. Mirror AiSetupScreen's exact layout/spacing.
}
```

> Implementer note: write the body by adapting `AiSetupScreen`/its `AiSetupContent` 1:1 — same scaffold, same field/footer composables — substituting two fields and the Podcast Index copy. Do not invent new design primitives.

- [ ] **Step 2: Add Paparazzi snapshots**

```kotlin
// PodcastIndexSetupSnapshots.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.screenshots

import com.kofikodr.kofipod.ui.screens.settings.podcastindex.PodcastIndexSetupContent
import com.kofikodr.kofipod.ui.screens.settings.podcastindex.PodcastIndexSetupUiState
// + the project's standard Paparazzi rule + KofipodTheme wrapper used by sibling snapshot files.

// Snapshot states: empty, verifying=true, errorMessage set, connected=true.
// Follow an existing snapshot file (e.g. the AI setup or TokensSnapshots) for the rule + theme harness.
```

- [ ] **Step 3: Record baselines**

Run: `./gradlew :composeApp:recordPaparazziDebug --tests "com.kofikodr.kofipod.screenshots.PodcastIndexSetupSnapshots"`
Then eyeball the PNGs under `composeApp/src/test/snapshots/images/` before committing.

- [ ] **Step 4: Verify snapshots**

Run: `./gradlew :composeApp:verifyPaparazziDebug --tests "com.kofikodr.kofipod.screenshots.PodcastIndexSetupSnapshots"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/settings/podcastindex/PodcastIndexSetupScreen.kt \
        composeApp/src/test/kotlin/com/kofikodr/kofipod/screenshots/PodcastIndexSetupSnapshots.kt \
        composeApp/src/test/snapshots/images/
git commit -m "feat(pi-byok): PodcastIndexSetupScreen + Paparazzi baselines"
```

---

## Task 9: Wire DI, navigation, and the conditional Settings row

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/di/CommonModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/kofikodr/kofipod/di/AndroidModule.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/kofikodr/kofipod/di/IosPlatformModule.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/nav/Routes.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/nav/KofipodNavHost.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/settings/SettingsScreen.kt` + its ViewModel

No new behavior tests here (covered by Tasks 2–8); the gate is "the app compiles for both flavors + iOS and the row appears only when build-time creds are blank," verified in Task 10.

- [ ] **Step 1: DI — CommonModule**

Replace `single { PodcastIndexApi.create() }` (currently line ~98) with:

```kotlin
single { PodcastIndexConfigRepository(store = get(), appScope = get(named("appScope"))) }
single { EffectivePodcastIndexCredentials(config = get()) }
single { PodcastIndexClientProvider(resolve = { get<EffectivePodcastIndexCredentials>().resolve() }) }
single<PodcastIndexValidator> { DefaultPodcastIndexValidator() }
single { PodcastIndexApi(get<PodcastIndexClientProvider>()) }
```

Add the viewModel factory near the other settings VMs (next to `AiSetupViewModel`, line ~547):

```kotlin
viewModel { PodcastIndexSetupViewModel(config = get(), validator = get()) }
```

Add imports for the new types and `org.koin.core.qualifier.named` (already imported elsewhere in the file — reuse).

- [ ] **Step 2: DI — platform store bindings**

`AndroidModule.kt`:

```kotlin
single<PodcastIndexCredentialStore> { AndroidPodcastIndexCredentialStore(androidContext()) }
```

`IosPlatformModule.kt`:

```kotlin
single<PodcastIndexCredentialStore> { IosPodcastIndexCredentialStoreStub() }
```

- [ ] **Step 3: Android store implementation**

```kotlin
// PodcastIndexCredentialStore.android.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_FILE = "kofipod_secure" // shared with the Gemini key; already backup-excluded
private const val KEY = "podcast_index_key"
private const val SECRET = "podcast_index_secret"

class AndroidPodcastIndexCredentialStore(private val context: Context) : PodcastIndexCredentialStore {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun get(): PodcastIndexCreds? =
        withContext(Dispatchers.IO) {
            val k = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
            val s = prefs.getString(SECRET, null)?.takeIf { it.isNotBlank() }
            if (k != null && s != null) PodcastIndexCreds(k, s) else null
        }

    override suspend fun set(creds: PodcastIndexCreds) =
        withContext(Dispatchers.IO) {
            val ok = prefs.edit().putString(KEY, creds.key).putString(SECRET, creds.secret).commit()
            check(ok) { "Failed to persist Podcast Index credentials to encrypted preferences" }
        }

    override suspend fun clear() =
        withContext(Dispatchers.IO) {
            val ok = prefs.edit().remove(KEY).remove(SECRET).commit()
            check(ok) { "Failed to clear Podcast Index credentials from encrypted preferences" }
        }
}
```

- [ ] **Step 4: Navigation route + host**

`Routes.kt` — add next to `AiSetup`:

```kotlin
@Serializable data object PodcastIndexSetup : Route
```

`KofipodNavHost.kt` — add a `composable<Route.PodcastIndexSetup>` and thread an `onOpenPodcastIndexSetup` callback into the Settings destination exactly like `onOpenAiSetup`:

```kotlin
composable<Route.PodcastIndexSetup> {
    PodcastIndexSetupScreen(onBack = { navController.popBackStack() })
}
```

(Import `PodcastIndexSetupScreen`. Wherever `onOpenAiSetup = { navController.navigate(Route.AiSetup) }` is passed to the Settings screen — there are multiple call sites — add the sibling `onOpenPodcastIndexSetup = { navController.navigate(Route.PodcastIndexSetup) }`.)

- [ ] **Step 5: Settings screen row (conditional) + ViewModel state**

In the Settings ViewModel, mirror `aiConnected` with two fields:

```kotlin
// show iff the build embeds no Podcast Index key (true on FOSS, false on Play) — compile-time constant
val showPodcastIndexByok: Boolean = com.kofikodr.kofipod.data.api.PodcastIndexCredentials.key.isBlank()
// and combine podcastIndexConfig.isConfigured() into the state as `podcastIndexConnected`
```

In `SettingsScreen.kt`, add an `onOpenPodcastIndexSetup: () -> Unit` parameter and, right after the AI section, render the row only when `state.showPodcastIndexByok`:

```kotlin
if (state.showPodcastIndexByok) {
    SectionLabel("Podcast search (FOSS)", topSpacing = 22.dp)
    SettingRow(
        icon = KPIconName.Search,
        title = if (state.podcastIndexConnected) "Podcast Index connected" else "Add Podcast Index API key",
        subtitle =
            if (state.podcastIndexConnected) {
                "Tap to manage your key"
            } else {
                "Search needs a free Podcast Index API key. Tap to add yours."
            },
        onClick = onOpenPodcastIndexSetup,
        trailing = { KPIcon(name = KPIconName.ChevronRight, color = c.textMute, size = 18.dp) },
    )
}
```

> Implementer note: use the actual `KPIconName` member that exists for search (check the enum; the AI row uses `Pencil`). If there is no `Search` icon, reuse an existing relevant one rather than adding an asset.

- [ ] **Step 6: Compile everything (both flavors + iOS)**

Run: `./gradlew :composeApp:compileFossDebugKotlinAndroid :composeApp:compilePlayDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL (no remaining `PodcastIndexApi.create()` reference).

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/di/CommonModule.kt \
        composeApp/src/androidMain/kotlin/com/kofikodr/kofipod/di/AndroidModule.kt \
        composeApp/src/iosMain/kotlin/com/kofikodr/kofipod/di/IosPlatformModule.kt \
        composeApp/src/androidMain/kotlin/com/kofikodr/kofipod/data/api/PodcastIndexCredentialStore.android.kt \
        composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/nav/Routes.kt \
        composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/nav/KofipodNavHost.kt \
        composeApp/src/commonMain/kotlin/com/kofikodr/kofipod/ui/screens/settings/
git commit -m "feat(pi-byok): wire DI, nav route, and conditional Settings row"
```

---

## Task 10: Backup rule, full green checks, and on-device exercise

**Files:**
- Inspect (modify only if needed): `composeApp/src/androidMain/res/xml/backup_rules_legacy.xml`

- [ ] **Step 1: Confirm backup exclusion**

Confirm both `backup_rules.xml` and `backup_rules_legacy.xml` exclude `kofipod_secure` (the file the new store reuses). `backup_rules.xml` already does. If `backup_rules_legacy.xml` lacks `<exclude domain="sharedpref" path="kofipod_secure.xml" />` (legacy API 23–30 form), add it. No new prefs file is introduced, so nothing else changes.

- [ ] **Step 2: Full test suite (both flavors)**

Run: `./gradlew :composeApp:testFossDebugUnitTest :composeApp:testPlayDebugUnitTest`
Expected: PASS. (If `RssFeedClientTest.timeout_returnsNetworkError_doesNotWriteCache` flakes, re-run it in isolation — it is a known flaky timeout test unrelated to this change.)

- [ ] **Step 3: Snapshots + lint + iOS**

Run: `./gradlew :composeApp:verifyPaparazziDebug :composeApp:ktlintCheck :composeApp:detekt :composeApp:compileKotlinIosSimulatorArm64`
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 4: Test audit (Step 5 gate)** — dispatch `test-quality-auditor` over all new/modified tests. Fix every Critical/High and re-audit.

- [ ] **Step 5: Code review (Step 8 gate)** — run `kode-review` (agentic) over the branch diff; fall back to `feature-dev:code-reviewer` on CLI failure. Fix Critical/High, re-review.

- [ ] **Step 6: On-device exercise (Step 7 gate)** — build + install **foss debug** to the phone with creds copied to `local.properties` (foss still ships blank build-time creds, so the BYOK path is exercised):

```bash
ANDROID_SERIAL=<device> ./gradlew :composeApp:installFossDebug
```

Then: open Settings → confirm the "Podcast search (FOSS)" row is present (it must NOT appear on a play build). Open it, paste a real key+secret from podcastindex.org, tap Connect → expect "connected". Go to Search, run a query → results appear (previously dead on foss). Confirm the encrypted store wrote: `adb exec-out run-as com.kofikodr.kofipod.foss.debug ls -1 ./shared_prefs` shows `kofipod_secure.xml`. Tap Disconnect → search returns to the error state. Capture the working search screen. While here, confirm the real 401 path: enter a bogus secret → expect the "rejected" copy (and fix `classifyPodcastIndexFailure` if the SDK's exception doesn't match the substring classifier).

- [ ] **Step 7: Open the PR**

```bash
git push -u origin feat/foss-podcast-index-byok
gh pr create --repo kofikodr/kofipod --base master \
  --title "FOSS: in-app Podcast Index API credentials (BYOK)" \
  --body "<summary + the Step 5/6/7/8/9 gate results>"
```

---

## Self-Review

**Spec coverage:**
- Storage `PodcastIndexCredentialStore` reusing `kofipod_secure` → Task 1 + Task 9 Step 3 + Task 10 Step 1. ✓
- `PodcastIndexConfigRepository` (StateFlow, flag-vault sync) → Task 2. ✓
- Effective creds (BYOK-over-build-time, blank fallback) → Task 3. ✓
- Reactive client without restart → Task 4 (provider) + Task 5 (API rewire). The spec said "decorator implements PodcastIndexClient"; refined to a suspend **provider** injected into `PodcastIndexApi` because `PodcastIndexClient`'s property getters aren't suspend and can't rebuild lazily. Same guarantee, cleaner seam. ✓
- Validate-before-save → Task 6 (validator) + Task 7 (VM). ✓
- Setup screen + states → Task 8. ✓
- Visibility iff build-time key blank → Task 9 Step 5. ✓
- DI per platform → Task 9 Steps 1–3. ✓
- Backup exclusion (no new rule; verify legacy) → Task 10 Step 1. ✓
- Testing matrix (config repo, resolver, provider, validator, VM, snapshots) → Tasks 2–8; gates → Task 10. ✓

**Placeholder scan:** UI body (Task 8) and the SDK stub shapes (Tasks 4–5) carry explicit "adapt from `AiSetupScreen`/inspect the SDK type" implementer notes rather than hollow placeholders, because the exact composable/SDK members must be read from source at implementation time; each note names the precise file/type to copy and the fallback if a member differs. All logic tasks (2,3,4,6,7) have complete code.

**Type consistency:** `PodcastIndexCreds(key, secret)`, `isUsable`, `PodcastIndexCredentialStore.get/set/clear`, `PodcastIndexConfigRepository.isConfigured()/currentCreds()/setCredentials()/disconnect()`, `EffectivePodcastIndexCredentials.resolve()`, `PodcastIndexClientProvider.get()`, `PodcastIndexValidator.validate() → PodcastIndexValidation{Valid,Invalid,NetworkError}`, and the VM copy helpers (`missingFieldsCopy/invalidCredsCopy/networkErrorCopy`) are used identically across tasks and tests. ✓
