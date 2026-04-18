# Kofipod Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Kofipod — a Kotlin Multiplatform personal podcast app with Compose MP UI, full Android playback, auto/manual downloads, daily episode check, Google Drive `appDataFolder` backup, and the visual design from `Kofipod Design.html`.

**Architecture:** Single `composeApp` KMP module with `commonMain` (UI, domain, repos, DB, API), `androidMain` (ExoPlayer/Media3, WorkManager, Drive, Sign-In, notifications), and scaffolded `iosMain`. Platform side effects sit behind `expect`/`actual` interfaces. Data is SQLDelight + Ktor 3; the Podcast Index calls go through mr3y's `podcastindex-sdk-ktor3`. Backup blobs are JSON uploaded to Drive `appDataFolder`.

**Tech Stack:** Kotlin 2.1 • Compose Multiplatform 1.7 • Material 3 • Jetpack Navigation Compose (KMP) • Koin • Ktor 3 • SQLDelight 2 • kotlinx.serialization • Coil 3 • AndroidX Media3 • WorkManager • Credential Manager + Google Identity Services • Google Drive REST v3 • BuildKonfig • Paparazzi • JUnit 4 • GPL-3.0-or-later.

---

## Conventions

- **Commits:** Every task ends with a commit. Message format: `type(scope): subject` where type ∈ {feat, test, chore, docs, fix, refactor}.
- **SPDX header** on every new Kotlin file:

  ```kotlin
  // SPDX-License-Identifier: GPL-3.0-or-later
  ```

- **Package root:** `app.kofipod` (overridable — the important thing is consistency).
- **Testing:** Compose UI tests live in `commonTest`; Paparazzi screenshot tests live in `androidUnitTest`. No other test layers in initial scope.
- **TDD where tests apply.** Scaffolding, Gradle config, and platform-only glue code may commit without a test when no meaningful test exists; every screen and repository change must have at least one Compose UI test or Paparazzi snapshot asserting the new behaviour.

---

# Phase 0 — Project scaffolding

### Task 0.1: Initialise Gradle KMP/Compose MP project

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `composeApp/build.gradle.kts`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/App.kt` (placeholder)
- Create: `composeApp/src/androidMain/AndroidManifest.xml` (empty app)
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/MainActivity.kt`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "kofipod"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":composeApp")
```

- [ ] **Step 2: Create `gradle/libs.versions.toml`**

```toml
[versions]
kotlin = "2.1.0"
agp = "8.7.3"
composeMultiplatform = "1.7.3"
androidxActivity = "1.9.3"
androidxLifecycle = "2.8.7"
androidxNavigation = "2.8.0-alpha10"
androidxMedia3 = "1.5.1"
androidxWork = "2.10.0"
androidxCredentials = "1.3.0"
googleIdentity = "1.1.1"
coil = "3.0.4"
ktor = "3.0.3"
sqldelight = "2.0.2"
serialization = "1.7.3"
koin = "4.0.0"
koinCompose = "4.0.0"
buildkonfig = "0.15.2"
paparazzi = "1.3.5"
junit = "4.13.2"
podcastindex = "0.4.0"

[libraries]
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidxActivity" }
androidx-lifecycle-viewmodel = { module = "androidx.lifecycle:lifecycle-viewmodel", version.ref = "androidxLifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "androidxLifecycle" }
androidx-navigation-compose = { module = "org.jetbrains.androidx.navigation:navigation-compose", version.ref = "androidxNavigation" }
androidx-media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "androidxMedia3" }
androidx-media3-session = { module = "androidx.media3:media3-session", version.ref = "androidxMedia3" }
androidx-work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "androidxWork" }
androidx-credentials = { module = "androidx.credentials:credentials", version.ref = "androidxCredentials" }
androidx-credentials-play-services-auth = { module = "androidx.credentials:credentials-play-services-auth", version.ref = "androidxCredentials" }
google-identity = { module = "com.google.android.libraries.identity.googleid:googleid", version.ref = "googleIdentity" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
coil-network-ktor = { module = "io.coil-kt.coil3:coil-network-ktor3", version.ref = "coil" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-native-driver = { module = "app.cash.sqldelight:native-driver", version.ref = "sqldelight" }
sqldelight-coroutines-extensions = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
koin-android = { module = "io.insert-koin:koin-android", version.ref = "koin" }
koin-compose = { module = "io.insert-koin:koin-compose", version.ref = "koinCompose" }
koin-compose-viewmodel = { module = "io.insert-koin:koin-compose-viewmodel", version.ref = "koinCompose" }
podcastindex-sdk = { module = "io.github.mr3y-the-programmer:podcastindex-sdk-ktor3", version.ref = "podcastindex" }
junit = { module = "junit:junit", version.ref = "junit" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
android-application = { id = "com.android.application", version.ref = "agp" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
buildkonfig = { id = "com.codingfeline.buildkonfig", version.ref = "buildkonfig" }
paparazzi = { id = "app.cash.paparazzi", version.ref = "paparazzi" }
```

- [ ] **Step 3: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.parallel=true
kotlin.code.style=official
kotlin.mpp.androidSourceSetLayoutVersion=2
android.useAndroidX=true
android.nonTransitiveRClass=true
android.nonFinalResIds=false
```

- [ ] **Step 4: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.buildkonfig) apply false
}
```

- [ ] **Step 5: Create `composeApp/build.gradle.kts`**

```kotlin
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.buildkonfig)
    alias(libs.plugins.paparazzi)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.androidx.navigation.compose)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.coroutines.extensions)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor)
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.podcastindex.sdk)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.androidx.media3.exoplayer)
                implementation(libs.androidx.media3.session)
                implementation(libs.androidx.work.runtime)
                implementation(libs.androidx.credentials)
                implementation(libs.androidx.credentials.play.services.auth)
                implementation(libs.google.identity)
                implementation(libs.koin.android)
            }
        }
        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.native.driver)
            }
        }
        val iosX64Main by getting { dependsOn(iosMain) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
    }
}

android {
    namespace = "app.kofipod"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.kofipod"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

sqldelight {
    databases {
        create("KofipodDatabase") {
            packageName.set("app.kofipod.db")
        }
    }
}

fun readSecret(name: String): String {
    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    return props.getProperty(name) ?: System.getenv(name) ?: ""
}

buildkonfig {
    packageName = "app.kofipod.config"
    defaultConfigs {
        buildConfigField(STRING, "PODCAST_INDEX_KEY", readSecret("PODCAST_INDEX_KEY"))
        buildConfigField(STRING, "PODCAST_INDEX_SECRET", readSecret("PODCAST_INDEX_SECRET"))
        buildConfigField(STRING, "USER_AGENT", "Kofipod/0.1 (github.com/ebernie/kofipod)")
    }
}
```

- [ ] **Step 6: Create minimal placeholder `App.kt`**

Create `composeApp/src/commonMain/kotlin/app/kofipod/App.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun App() {
    MaterialTheme {
        Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            Text("Kofipod")
        }
    }
}
```

- [ ] **Step 7: Create Android entry points**

`composeApp/src/androidMain/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="Kofipod"
        android:allowBackup="false"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`composeApp/src/androidMain/kotlin/app/kofipod/MainActivity.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
```

- [ ] **Step 8: Verify it builds**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL. (Gradle wrapper is generated as part of this step; run `gradle wrapper --gradle-version 8.11` first if not present.)

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ composeApp/
git commit -m "chore(scaffold): initial Kotlin Multiplatform + Compose MP project"
```

---

### Task 0.2: Add license, README, secret templates

**Files:**
- Create: `LICENSE` (GPL-3.0 full text)
- Create: `README.md`
- Create: `local.properties.template`
- Create: `keystore.properties.template`

- [ ] **Step 1: Download the official GPL-3.0 text**

```bash
curl -sSL https://www.gnu.org/licenses/gpl-3.0.txt -o LICENSE
head -3 LICENSE
```

Expected: "GNU GENERAL PUBLIC LICENSE\n  Version 3, 29 June 2007".

- [ ] **Step 2: Create `README.md`**

```markdown
# Kofipod

A personal podcasting app for Android (Kotlin Multiplatform, iOS to follow).

## Setup

1. Register a Podcast Index account at https://api.podcastindex.org/ and obtain an API key and secret.
2. Copy `local.properties.template` to `local.properties` and fill in:

   ```
   PODCAST_INDEX_KEY=your-key
   PODCAST_INDEX_SECRET=your-secret
   ```

   CI builds can provide the same values via environment variables.

3. For Google Sign-In and Drive backup, create a Google Cloud project:
   - Enable the **Drive API** (scope: `drive.appdata`).
   - Under Credentials, create an **OAuth client ID** of type *Android*. Add your debug and release keystore SHA-1 fingerprints.
   - For release builds, copy `keystore.properties.template` to `keystore.properties` and place your release keystore at `keystore/release.jks`.

4. Build: `./gradlew :composeApp:assembleDebug`

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
```

- [ ] **Step 3: Create `local.properties.template`**

```properties
# Podcast Index API credentials — https://api.podcastindex.org/
PODCAST_INDEX_KEY=
PODCAST_INDEX_SECRET=

# Android SDK location (set by Android Studio, usually not needed for CI)
# sdk.dir=/Users/you/Library/Android/sdk
```

- [ ] **Step 4: Create `keystore.properties.template`**

```properties
storeFile=keystore/release.jks
storePassword=
keyAlias=
keyPassword=
```

- [ ] **Step 5: Commit**

```bash
git add LICENSE README.md local.properties.template keystore.properties.template
git commit -m "docs: add GPL-3.0 license, README, and secret templates"
```

---

# Phase 1 — Design system & theming

### Task 1.1: Encode design tokens in Kotlin

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/theme/KofipodColors.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/theme/KofipodRadii.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/theme/KofipodTypography.kt`

- [ ] **Step 1: Write colours**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import androidx.compose.ui.graphics.Color

data class KofipodColors(
    val bg: Color,
    val bgSubtle: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textSoft: Color,
    val textMute: Color,
    val purple: Color,
    val purpleDeep: Color,
    val purpleSoft: Color,
    val purpleTint: Color,
    val pink: Color,
    val pinkSoft: Color,
    val success: Color,
    val warn: Color,
    val danger: Color,
    val isDark: Boolean,
)

val LightKofipodColors = KofipodColors(
    bg = Color(0xFFFBF8FF),
    bgSubtle = Color(0xFFF3ECFF),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF6F0FF),
    border = Color(0xFFE7DDFB),
    borderStrong = Color(0xFFD5C4F4),
    text = Color(0xFF1A0B33),
    textSoft = Color(0xFF50407A),
    textMute = Color(0xFF8A7BB0),
    purple = Color(0xFF4B1E9E),
    purpleDeep = Color(0xFF2E0D6E),
    purpleSoft = Color(0xFF6D3BD2),
    purpleTint = Color(0xFFEADFFC),
    pink = Color(0xFFFF2E9A),
    pinkSoft = Color(0xFFFFD6EA),
    success = Color(0xFF10B981),
    warn = Color(0xFFF59E0B),
    danger = Color(0xFFE11D48),
    isDark = false,
)

val DarkKofipodColors = KofipodColors(
    bg = Color(0xFF0D0814),
    bgSubtle = Color(0xFF140C22),
    surface = Color(0xFF1A1128),
    surfaceAlt = Color(0xFF231636),
    border = Color(0xFF2D1F46),
    borderStrong = Color(0xFF3E2B60),
    text = Color(0xFFF2E9FF),
    textSoft = Color(0xFFBCA7E0),
    textMute = Color(0xFF7E6BA6),
    purple = Color(0xFFA881F5),
    purpleDeep = Color(0xFF7C4DEB),
    purpleSoft = Color(0xFFC4A6FF),
    purpleTint = Color(0xFF2A1A4A),
    pink = Color(0xFFFF6BB5),
    pinkSoft = Color(0xFF3A1930),
    success = Color(0xFF34D399),
    warn = Color(0xFFFBBF24),
    danger = Color(0xFFFB7185),
    isDark = true,
)
```

- [ ] **Step 2: Write radii**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class KofipodRadii(
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 28.dp,
    val pill: Dp = 999.dp,
)

val DefaultKofipodRadii = KofipodRadii()
```

- [ ] **Step 3: Write typography using Google Fonts**

Add Plus Jakarta Sans and JetBrains Mono font files under `composeApp/src/commonMain/composeResources/font/` (downloaded from Google Fonts:
`PlusJakartaSans-Regular.ttf`, `-Medium.ttf`, `-Bold.ttf`, `-ExtraBold.ttf`, `JetBrainsMono-Regular.ttf`, `-Medium.ttf`, `-Bold.ttf`).

```bash
mkdir -p composeApp/src/commonMain/composeResources/font
# fetch PJS weights
for w in Regular Medium Bold ExtraBold; do
  curl -sSL "https://github.com/google/fonts/raw/main/ofl/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf" \
    -o composeApp/src/commonMain/composeResources/font/PlusJakartaSans-$w.ttf
done
for w in Regular Medium Bold; do
  curl -sSL "https://github.com/JetBrains/JetBrainsMono/raw/master/fonts/ttf/JetBrainsMono-$w.ttf" \
    -o composeApp/src/commonMain/composeResources/font/JetBrainsMono-$w.ttf
done
```

Note: Plus Jakarta Sans upstream is a variable font; the downloads above copy the same file per weight slot. At runtime we select weight via `FontWeight`.

Create `composeApp/src/commonMain/kotlin/app/kofipod/ui/theme/KofipodTypography.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kofipod.composeapp.generated.resources.JetBrainsMono_Bold
import kofipod.composeapp.generated.resources.JetBrainsMono_Medium
import kofipod.composeapp.generated.resources.JetBrainsMono_Regular
import kofipod.composeapp.generated.resources.PlusJakartaSans_Bold
import kofipod.composeapp.generated.resources.PlusJakartaSans_ExtraBold
import kofipod.composeapp.generated.resources.PlusJakartaSans_Medium
import kofipod.composeapp.generated.resources.PlusJakartaSans_Regular
import kofipod.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.Font

@Composable
fun plusJakartaSans() = FontFamily(
    Font(Res.font.PlusJakartaSans_Regular, weight = FontWeight.Normal),
    Font(Res.font.PlusJakartaSans_Medium, weight = FontWeight.Medium),
    Font(Res.font.PlusJakartaSans_Bold, weight = FontWeight.Bold),
    Font(Res.font.PlusJakartaSans_ExtraBold, weight = FontWeight.ExtraBold),
)

@Composable
fun jetBrainsMono() = FontFamily(
    Font(Res.font.JetBrainsMono_Regular, weight = FontWeight.Normal),
    Font(Res.font.JetBrainsMono_Medium, weight = FontWeight.Medium),
    Font(Res.font.JetBrainsMono_Bold, weight = FontWeight.Bold),
)

@Composable
fun kofipodTypography(): Typography {
    val sans = plusJakartaSans()
    return Typography(
        displayLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, letterSpacing = (-0.03).em),
        displayMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = (-0.02).em),
        headlineLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.02).em),
        titleLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
        bodyLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 16.sp),
        bodyMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp),
        labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.08.em),
    )
}

private val Int.em: androidx.compose.ui.unit.TextUnit
    get() = (this / 100f).sp
private val Double.em: androidx.compose.ui.unit.TextUnit
    get() = (this / 100f).sp
```

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/
git commit -m "feat(theme): add Kofipod design tokens (colors, radii, typography)"
```

---

### Task 1.2: KofipodTheme composable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/theme/KofipodTheme.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/App.kt`

- [ ] **Step 1: Write the theme**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalKofipodColors = staticCompositionLocalOf { LightKofipodColors }
val LocalKofipodRadii = staticCompositionLocalOf { DefaultKofipodRadii }

enum class KofipodThemeMode { System, Light, Dark }

@Composable
fun KofipodTheme(
    mode: KofipodThemeMode = KofipodThemeMode.System,
    content: @Composable () -> Unit,
) {
    val isDark = when (mode) {
        KofipodThemeMode.System -> isSystemInDarkTheme()
        KofipodThemeMode.Light -> false
        KofipodThemeMode.Dark -> true
    }
    val colors = if (isDark) DarkKofipodColors else LightKofipodColors
    val materialScheme = if (isDark) {
        darkColorScheme(
            primary = colors.purple,
            onPrimary = colors.text,
            secondary = colors.pink,
            onSecondary = colors.text,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.purple,
            onPrimary = colors.surface,
            secondary = colors.pink,
            onSecondary = colors.surface,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(
        LocalKofipodColors provides colors,
        LocalKofipodRadii provides DefaultKofipodRadii,
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = kofipodTypography(),
            content = content,
        )
    }
}
```

- [ ] **Step 2: Wrap App with KofipodTheme**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.kofipod.ui.theme.KofipodTheme
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun App() {
    KofipodTheme {
        val c = LocalKofipodColors.current
        Box(
            modifier = Modifier.fillMaxSize().background(c.bg),
            contentAlignment = Alignment.Center,
        ) {
            Text("Kofipod", color = c.text)
        }
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/
git commit -m "feat(theme): add KofipodTheme with light/dark switching"
```

---

### Task 1.3: Primitives — KPButton, KPBadge, KPCard

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/primitives/KPButton.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/primitives/KPBadge.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/primitives/KPCard.kt`

- [ ] **Step 1: Write KPButton (primary pink CTA)**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii

enum class KPButtonStyle { PrimaryPink, SecondaryPurple, Outline }

@Composable
fun KPButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KPButtonStyle = KPButtonStyle.PrimaryPink,
) {
    val colors = LocalKofipodColors.current
    val radii = LocalKofipodRadii.current
    val (bg, fg) = when (style) {
        KPButtonStyle.PrimaryPink -> colors.pink to androidx.compose.ui.graphics.Color.White
        KPButtonStyle.SecondaryPurple -> colors.purple to androidx.compose.ui.graphics.Color.White
        KPButtonStyle.Outline -> colors.surface to colors.text
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radii.pill))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
```

- [ ] **Step 2: Write KPBadge**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun KPBadge(label: String, modifier: Modifier = Modifier) {
    val c = LocalKofipodColors.current
    Text(
        text = label.uppercase(),
        color = c.pink,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.pinkSoft)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
```

- [ ] **Step 3: Write KPCard**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.primitives

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii

@Composable
fun KPCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .border(BorderStroke(1.dp, c.border), RoundedCornerShape(r.md))
            .padding(16.dp),
    ) { content() }
}
```

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/app/kofipod/ui/primitives/
git commit -m "feat(ui): add KPButton, KPBadge, KPCard primitives"
```

---

# Phase 2 — Data layer & configuration

### Task 2.1: SQLDelight schema

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/Podcast.sq`
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/PodcastList.sq`
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/Episode.sq`
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/Download.sq`
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/Playback.sq`
- Create: `composeApp/src/commonMain/sqldelight/app/kofipod/db/SyncMeta.sq`

- [ ] **Step 1: Write `PodcastList.sq`**

```sql
CREATE TABLE PodcastList (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    position INTEGER NOT NULL,
    createdAt INTEGER NOT NULL
);

selectAll:
SELECT * FROM PodcastList ORDER BY position ASC;

insert:
INSERT INTO PodcastList (id, name, position, createdAt) VALUES (?, ?, ?, ?);

rename:
UPDATE PodcastList SET name = ? WHERE id = ?;

reposition:
UPDATE PodcastList SET position = ? WHERE id = ?;

delete:
DELETE FROM PodcastList WHERE id = ?;
```

- [ ] **Step 2: Write `Podcast.sq`**

```sql
CREATE TABLE Podcast (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    author TEXT NOT NULL,
    description TEXT NOT NULL,
    artworkUrl TEXT NOT NULL,
    feedUrl TEXT NOT NULL,
    listId TEXT,
    autoDownloadEnabled INTEGER AS Boolean NOT NULL DEFAULT 0,
    lastCheckedAt INTEGER,
    addedAt INTEGER NOT NULL,
    FOREIGN KEY (listId) REFERENCES PodcastList(id) ON DELETE SET NULL
);

CREATE INDEX podcast_listId ON Podcast(listId);

insert:
INSERT OR REPLACE INTO Podcast
(id, title, author, description, artworkUrl, feedUrl, listId, autoDownloadEnabled, lastCheckedAt, addedAt)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

selectAll:
SELECT * FROM Podcast ORDER BY title COLLATE NOCASE ASC;

selectById:
SELECT * FROM Podcast WHERE id = ?;

selectByList:
SELECT * FROM Podcast WHERE listId IS ? ORDER BY title COLLATE NOCASE ASC;

moveToList:
UPDATE Podcast SET listId = ? WHERE id = ?;

setAutoDownload:
UPDATE Podcast SET autoDownloadEnabled = ? WHERE id = ?;

setLastChecked:
UPDATE Podcast SET lastCheckedAt = ? WHERE id = ?;

delete:
DELETE FROM Podcast WHERE id = ?;
```

- [ ] **Step 3: Write `Episode.sq`**

```sql
CREATE TABLE Episode (
    id TEXT NOT NULL PRIMARY KEY,
    podcastId TEXT NOT NULL,
    guid TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    publishedAt INTEGER NOT NULL,
    durationSec INTEGER NOT NULL,
    enclosureUrl TEXT NOT NULL,
    enclosureMimeType TEXT NOT NULL,
    fileSizeBytes INTEGER NOT NULL,
    seasonNumber INTEGER,
    episodeNumber INTEGER,
    FOREIGN KEY (podcastId) REFERENCES Podcast(id) ON DELETE CASCADE
);

CREATE INDEX episode_podcastId ON Episode(podcastId);
CREATE UNIQUE INDEX episode_podcastGuid ON Episode(podcastId, guid);

insert:
INSERT OR IGNORE INTO Episode
(id, podcastId, guid, title, description, publishedAt, durationSec, enclosureUrl, enclosureMimeType, fileSizeBytes, seasonNumber, episodeNumber)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

selectByPodcast:
SELECT * FROM Episode WHERE podcastId = ? ORDER BY publishedAt DESC;

selectById:
SELECT * FROM Episode WHERE id = ?;

selectGuidsByPodcast:
SELECT guid FROM Episode WHERE podcastId = ?;

delete:
DELETE FROM Episode WHERE id = ?;
```

- [ ] **Step 4: Write `Download.sq`**

```sql
CREATE TABLE Download (
    episodeId TEXT NOT NULL PRIMARY KEY,
    state TEXT NOT NULL,
    localPath TEXT,
    downloadedBytes INTEGER NOT NULL DEFAULT 0,
    totalBytes INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL,
    startedAt INTEGER,
    completedAt INTEGER,
    errorMessage TEXT,
    FOREIGN KEY (episodeId) REFERENCES Episode(id) ON DELETE CASCADE
);

CREATE INDEX download_state ON Download(state);
CREATE INDEX download_source_completed ON Download(source, completedAt);

upsert:
INSERT OR REPLACE INTO Download
(episodeId, state, localPath, downloadedBytes, totalBytes, source, startedAt, completedAt, errorMessage)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);

selectAll:
SELECT * FROM Download;

selectByEpisode:
SELECT * FROM Download WHERE episodeId = ?;

selectByState:
SELECT * FROM Download WHERE state = ?;

selectAutoCompletedOldestFirst:
SELECT * FROM Download WHERE source = 'Auto' AND state = 'Completed' ORDER BY completedAt ASC;

totalCompletedBytes:
SELECT COALESCE(SUM(totalBytes), 0) AS total FROM Download WHERE state = 'Completed';

updateState:
UPDATE Download SET state = ?, errorMessage = ? WHERE episodeId = ?;

updateProgress:
UPDATE Download SET downloadedBytes = ?, totalBytes = ? WHERE episodeId = ?;

delete:
DELETE FROM Download WHERE episodeId = ?;
```

- [ ] **Step 5: Write `Playback.sq`**

```sql
CREATE TABLE PlaybackState (
    episodeId TEXT NOT NULL PRIMARY KEY,
    positionMs INTEGER NOT NULL,
    durationMs INTEGER NOT NULL,
    completedAt INTEGER,
    playbackSpeed REAL NOT NULL DEFAULT 1.0,
    updatedAt INTEGER NOT NULL,
    FOREIGN KEY (episodeId) REFERENCES Episode(id) ON DELETE CASCADE
);

upsert:
INSERT OR REPLACE INTO PlaybackState (episodeId, positionMs, durationMs, completedAt, playbackSpeed, updatedAt)
VALUES (?, ?, ?, ?, ?, ?);

selectByEpisode:
SELECT * FROM PlaybackState WHERE episodeId = ?;

selectAll:
SELECT * FROM PlaybackState;
```

- [ ] **Step 6: Write `SyncMeta.sq`**

```sql
CREATE TABLE SyncMeta (
    key TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL
);

put:
INSERT OR REPLACE INTO SyncMeta (key, value) VALUES (?, ?);

get:
SELECT value FROM SyncMeta WHERE key = ?;
```

- [ ] **Step 7: Verify schema compiles**

Run: `./gradlew :composeApp:generateCommonMainKofipodDatabaseInterface`
Expected: BUILD SUCCESSFUL; generated sources under `composeApp/build/generated/sqldelight/code/KofipodDatabase/`.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/sqldelight/
git commit -m "feat(db): add SQLDelight schema for library, episodes, downloads"
```

---

### Task 2.2: Database driver (expect/actual)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/data/db/DatabaseFactory.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/data/db/DatabaseFactory.android.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/data/db/DatabaseFactory.ios.kt`

- [ ] **Step 1: `expect` factory**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.db

import app.cash.sqldelight.db.SqlDriver
import app.kofipod.db.KofipodDatabase

expect class DatabaseFactory {
    fun createDriver(): SqlDriver
}

fun KofipodDatabase.Companion.build(factory: DatabaseFactory): KofipodDatabase =
    KofipodDatabase(factory.createDriver())
```

- [ ] **Step 2: Android `actual`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.kofipod.db.KofipodDatabase

actual class DatabaseFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(KofipodDatabase.Schema, context, "kofipod.db")
}
```

- [ ] **Step 3: iOS `actual`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.kofipod.db.KofipodDatabase

actual class DatabaseFactory {
    actual fun createDriver(): SqlDriver =
        NativeSqliteDriver(KofipodDatabase.Schema, "kofipod.db")
}
```

- [ ] **Step 4: Build**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/
git commit -m "feat(db): add platform-specific SqlDriver factories"
```

---

### Task 2.3: Ktor HTTP client (expect/actual) and Podcast Index client

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/data/net/HttpClientFactory.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/data/net/HttpClientFactory.android.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/data/net/HttpClientFactory.ios.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/data/api/PodcastIndexApi.kt`

- [ ] **Step 1: `expect` factory**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun buildHttpClient(): HttpClient

fun HttpClient.kofipodDefaults() = this.apply {
    // reserved for future interceptors (user agent, logging, retry)
}

val kofipodJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}
```

- [ ] **Step 2: Android `actual`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

actual fun buildHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(kofipodJson) }
}
```

- [ ] **Step 3: iOS `actual`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

actual fun buildHttpClient(): HttpClient = HttpClient(Darwin) {
    install(ContentNegotiation) { json(kofipodJson) }
}
```

- [ ] **Step 4: Wrap the Podcast Index SDK**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.api

import app.kofipod.config.BuildKonfig
import com.mr3y.podcastindex.PodcastIndexClient
import com.mr3y.podcastindex.model.Podcast
import com.mr3y.podcastindex.model.Episode
import com.mr3y.podcastindex.services.SearchService

class PodcastIndexApi(
    private val client: PodcastIndexClient,
) {
    suspend fun searchByTerm(query: String, limit: Int = 30): List<Podcast> =
        client.search.forPodcastsByTerm(query, max = limit)
            .filterContentTypes()

    suspend fun searchByPerson(person: String, limit: Int = 30): List<Podcast> =
        client.search.forPodcastsByPerson(person, max = limit)
            .filterContentTypes()

    suspend fun podcastById(id: Long): Podcast? =
        client.podcasts.byFeedId(id)

    suspend fun episodes(feedId: Long, limit: Int = 50): List<Episode> =
        client.episodes.byFeedId(feedId, max = limit)
            .filter { it.medium != "music" && !it.isLive }

    private fun List<Podcast>.filterContentTypes(): List<Podcast> =
        filter { it.medium != "music" && !it.isLive }

    companion object {
        fun create(): PodcastIndexApi = PodcastIndexApi(
            PodcastIndexClient(
                apiKey = BuildKonfig.PODCAST_INDEX_KEY,
                apiSecret = BuildKonfig.PODCAST_INDEX_SECRET,
                userAgent = BuildKonfig.USER_AGENT,
            )
        )
    }
}
```

Note: the exact SDK property names on `Podcast`/`Episode` (`medium`, `isLive`) should be adjusted to match the SDK's actual DTOs. Confirm by reading `com.mr3y.podcastindex.model.Podcast` from the dependency sources.

- [ ] **Step 5: Build**

Run: `./gradlew :composeApp:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL. If SDK field names differ, adjust the filter expressions.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/
git commit -m "feat(api): wire Ktor + Podcast Index SDK client with filters"
```

---

### Task 2.4: Core repositories

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/data/repo/LibraryRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/data/repo/SearchRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/data/repo/EpisodesRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/data/repo/SettingsRepository.kt`

- [ ] **Step 1: Write `SearchRepository`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.domain.PodcastSummary

class SearchRepository(private val api: PodcastIndexApi) {
    suspend fun searchByTitle(query: String): List<PodcastSummary> =
        api.searchByTerm(query).map { it.toSummary() }

    suspend fun searchByPerson(name: String): List<PodcastSummary> =
        api.searchByPerson(name).map { it.toSummary() }
}
```

- [ ] **Step 2: Write `LibraryRepository`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.kofipod.db.KofipodDatabase
import app.kofipod.db.Podcast
import app.kofipod.db.PodcastList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(private val db: KofipodDatabase) {

    fun listsFlow(): Flow<List<PodcastList>> =
        db.podcastListQueries.selectAll().asFlow().mapToList(Dispatchers.Default)

    fun podcastsFlow(): Flow<List<Podcast>> =
        db.podcastQueries.selectAll().asFlow().mapToList(Dispatchers.Default)

    fun podcastsInList(listId: String?): Flow<List<Podcast>> =
        db.podcastQueries.selectByList(listId).asFlow().mapToList(Dispatchers.Default)

    fun podcastById(id: String): Flow<Podcast?> =
        db.podcastQueries.selectById(id).asFlow().map { it.executeAsOneOrNull() }

    suspend fun savePodcast(podcast: Podcast, listId: String?) {
        db.podcastQueries.insert(
            id = podcast.id,
            title = podcast.title,
            author = podcast.author,
            description = podcast.description,
            artworkUrl = podcast.artworkUrl,
            feedUrl = podcast.feedUrl,
            listId = listId,
            autoDownloadEnabled = podcast.autoDownloadEnabled,
            lastCheckedAt = podcast.lastCheckedAt,
            addedAt = podcast.addedAt,
        )
    }

    suspend fun createList(id: String, name: String, position: Int, now: Long) {
        db.podcastListQueries.insert(id, name, position.toLong(), now)
    }

    suspend fun deleteList(id: String) {
        db.podcastListQueries.delete(id)
    }

    suspend fun movePodcastToList(podcastId: String, listId: String?) {
        db.podcastQueries.moveToList(listId, podcastId)
    }

    suspend fun setAutoDownload(podcastId: String, enabled: Boolean) {
        db.podcastQueries.setAutoDownload(enabled, podcastId)
    }

    suspend fun deletePodcast(podcastId: String) {
        db.podcastQueries.delete(podcastId)
    }
}
```

- [ ] **Step 3: Write `EpisodesRepository`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.db.Episode
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

class EpisodesRepository(
    private val db: KofipodDatabase,
    private val api: PodcastIndexApi,
) {
    fun episodesFlow(podcastId: String): Flow<List<Episode>> =
        db.episodeQueries.selectByPodcast(podcastId).asFlow().mapToList(Dispatchers.Default)

    suspend fun refresh(podcastId: String, feedId: Long): RefreshResult {
        val existingGuids = db.episodeQueries.selectGuidsByPodcast(podcastId).executeAsList().toSet()
        val remote = api.episodes(feedId)
        var inserted = 0
        remote.forEach { ep ->
            if (ep.guid in existingGuids) return@forEach
            db.episodeQueries.insert(
                id = ep.id.toString(),
                podcastId = podcastId,
                guid = ep.guid,
                title = ep.title,
                description = ep.description.orEmpty(),
                publishedAt = ep.datePublished,
                durationSec = ep.duration.toLong(),
                enclosureUrl = ep.enclosureUrl,
                enclosureMimeType = ep.enclosureType,
                fileSizeBytes = ep.enclosureLength,
                seasonNumber = ep.season?.toLong(),
                episodeNumber = ep.episode?.toLong(),
            )
            inserted++
        }
        db.podcastQueries.setLastChecked(System.currentTimeMillis(), podcastId)
        return RefreshResult(inserted = inserted, totalRemote = remote.size)
    }
}

data class RefreshResult(val inserted: Int, val totalRemote: Int)
```

- [ ] **Step 4: Write `SettingsRepository`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.kofipod.db.KofipodDatabase
import app.kofipod.ui.theme.KofipodThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val db: KofipodDatabase) {

    private fun metaFlow(key: String): Flow<String?> =
        db.syncMetaQueries.get(key).asFlow().mapToOneOrNull(Dispatchers.Default)

    suspend fun put(key: String, value: String) = db.syncMetaQueries.put(key, value)

    fun storageCapBytes(): Flow<Long> =
        metaFlow(KEY_STORAGE_CAP).map { it?.toLongOrNull() ?: DEFAULT_CAP_BYTES }

    suspend fun setStorageCapBytes(bytes: Long) =
        put(KEY_STORAGE_CAP, bytes.toString())

    fun themeMode(): Flow<KofipodThemeMode> =
        metaFlow(KEY_THEME).map { KofipodThemeMode.valueOf(it ?: "System") }

    suspend fun setThemeMode(mode: KofipodThemeMode) = put(KEY_THEME, mode.name)

    fun dailyCheckEnabled(): Flow<Boolean> =
        metaFlow(KEY_DAILY_CHECK).map { it?.toBoolean() ?: true }

    suspend fun setDailyCheckEnabled(enabled: Boolean) =
        put(KEY_DAILY_CHECK, enabled.toString())

    fun skipForwardSeconds(): Flow<Int> =
        metaFlow(KEY_SKIP_FWD).map { it?.toIntOrNull() ?: 30 }

    fun skipBackSeconds(): Flow<Int> =
        metaFlow(KEY_SKIP_BACK).map { it?.toIntOrNull() ?: 10 }

    suspend fun setSkipForward(sec: Int) = put(KEY_SKIP_FWD, sec.toString())
    suspend fun setSkipBack(sec: Int) = put(KEY_SKIP_BACK, sec.toString())

    companion object {
        const val DEFAULT_CAP_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        const val KEY_STORAGE_CAP = "storage_cap_bytes"
        const val KEY_THEME = "theme_mode"
        const val KEY_DAILY_CHECK = "daily_check_enabled"
        const val KEY_SKIP_FWD = "skip_forward_sec"
        const val KEY_SKIP_BACK = "skip_back_sec"
    }
}
```

- [ ] **Step 5: Write domain mapping helpers**

Create `composeApp/src/commonMain/kotlin/app/kofipod/domain/PodcastSummary.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.domain

data class PodcastSummary(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String,
    val feedUrl: String,
)

fun com.mr3y.podcastindex.model.Podcast.toSummary(): PodcastSummary = PodcastSummary(
    id = id.toString(),
    title = title,
    author = author,
    description = description.orEmpty(),
    artworkUrl = artwork.orEmpty(),
    feedUrl = url,
)
```

Note: the property names (`id`, `title`, `author`, `description`, `artwork`, `url`) need to match the SDK's `Podcast` data class. Verify from the SDK sources and tweak.

- [ ] **Step 6: Build**

Run: `./gradlew :composeApp:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/
git commit -m "feat(data): add library/search/episode/settings repositories"
```

---

### Task 2.5: Koin modules

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/di/AndroidModule.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/di/KoinInit.kt`
- Modify: `composeApp/src/androidMain/kotlin/app/kofipod/MainActivity.kt` to start Koin via `KofipodApplication`.
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/KofipodApplication.kt`

- [ ] **Step 1: Write common module**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.data.db.DatabaseFactory
import app.kofipod.data.db.build
import app.kofipod.data.net.buildHttpClient
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.SearchRepository
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.db.KofipodDatabase
import org.koin.dsl.module

val commonDataModule = module {
    single { buildHttpClient() }
    single { PodcastIndexApi.create() }
    single { KofipodDatabase.Companion.build(get<DatabaseFactory>()) }
    single { LibraryRepository(get()) }
    single { SearchRepository(get()) }
    single { EpisodesRepository(get(), get()) }
    single { SettingsRepository(get()) }
}
```

- [ ] **Step 2: Write Android module**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.data.db.DatabaseFactory
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidPlatformModule = module {
    single { DatabaseFactory(androidContext()) }
}
```

- [ ] **Step 3: Create Application class and start Koin**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import android.app.Application
import app.kofipod.di.androidPlatformModule
import app.kofipod.di.commonDataModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class KofipodApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@KofipodApplication)
            modules(commonDataModule, androidPlatformModule)
        }
    }
}
```

Register in `AndroidManifest.xml` — update the existing `<application>` tag:

```xml
<application
    android:name=".KofipodApplication"
    android:label="Kofipod"
    android:allowBackup="false"
    android:theme="@android:style/Theme.Material.Light.NoActionBar">
```

- [ ] **Step 4: Build**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/
git commit -m "feat(di): wire Koin with common + android modules"
```

---

# Phase 3 — Navigation shell

### Task 3.1: Nav graph + bottom nav

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/Routes.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/nav/KofipodNavHost.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/shell/AppShell.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/App.kt`

- [ ] **Step 1: Routes**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable object Onboarding : Route
    @Serializable object Search : Route
    @Serializable object Library : Route
    @Serializable object Downloads : Route
    @Serializable object Settings : Route
    @Serializable object SchedulerInfo : Route
    @Serializable data class PodcastDetail(val podcastId: String) : Route
    @Serializable object Player : Route
}
```

- [ ] **Step 2: NavHost**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.kofipod.ui.screens.DownloadsScreen
import app.kofipod.ui.screens.LibraryScreen
import app.kofipod.ui.screens.OnboardingScreen
import app.kofipod.ui.screens.PlayerScreen
import app.kofipod.ui.screens.PodcastDetailScreen
import app.kofipod.ui.screens.SchedulerInfoScreen
import app.kofipod.ui.screens.SearchScreen
import app.kofipod.ui.screens.SettingsScreen

@Composable
fun KofipodNavHost(navController: NavHostController, startDestination: Any = Route.Search) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable<Route.Onboarding> { OnboardingScreen(onContinue = { navController.navigate(Route.Search) }) }
        composable<Route.Search> { SearchScreen(onOpenPodcast = { navController.navigate(Route.PodcastDetail(it)) }) }
        composable<Route.Library> { LibraryScreen(onOpenPodcast = { navController.navigate(Route.PodcastDetail(it)) }) }
        composable<Route.Downloads> { DownloadsScreen() }
        composable<Route.Settings> { SettingsScreen(onOpenScheduler = { navController.navigate(Route.SchedulerInfo) }) }
        composable<Route.SchedulerInfo> { SchedulerInfoScreen(onBack = { navController.popBackStack() }) }
        composable<Route.PodcastDetail> { entry ->
            val detail = entry.toRoute<Route.PodcastDetail>()
            PodcastDetailScreen(podcastId = detail.podcastId, onBack = { navController.popBackStack() })
        }
        composable<Route.Player> { PlayerScreen(onBack = { navController.popBackStack() }) }
    }
}
```

- [ ] **Step 3: AppShell with bottom nav + mini player**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.kofipod.ui.nav.KofipodNavHost
import app.kofipod.ui.nav.Route
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
fun AppShell() {
    val nav = rememberNavController()
    Scaffold(
        containerColor = LocalKofipodColors.current.bg,
        bottomBar = { BottomNav(nav) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            KofipodNavHost(nav)
        }
    }
}

@Composable
private fun BottomNav(nav: NavHostController) {
    val c = LocalKofipodColors.current
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem("Search", Route.Search::class.qualifiedName!! == currentRoute) { nav.navigate(Route.Search) }
        NavItem("Library", Route.Library::class.qualifiedName!! == currentRoute) { nav.navigate(Route.Library) }
        NavItem("Downloads", Route.Downloads::class.qualifiedName!! == currentRoute) { nav.navigate(Route.Downloads) }
        NavItem("Settings", Route.Settings::class.qualifiedName!! == currentRoute) { nav.navigate(Route.Settings) }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    Text(
        text = label,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = if (selected) c.pink else c.textSoft,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
```

- [ ] **Step 4: Create placeholder screens**

Create `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/Placeholders.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.kofipod.ui.theme.LocalKofipodColors

@Composable
private fun Placeholder(title: String) {
    val c = LocalKofipodColors.current
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Text(title, color = c.text)
    }
}

@Composable fun OnboardingScreen(onContinue: () -> Unit) = Placeholder("Onboarding")
@Composable fun SearchScreen(onOpenPodcast: (String) -> Unit) = Placeholder("Search")
@Composable fun LibraryScreen(onOpenPodcast: (String) -> Unit) = Placeholder("Library")
@Composable fun DownloadsScreen() = Placeholder("Downloads")
@Composable fun SettingsScreen(onOpenScheduler: () -> Unit) = Placeholder("Settings")
@Composable fun SchedulerInfoScreen(onBack: () -> Unit) = Placeholder("Scheduler")
@Composable fun PodcastDetailScreen(podcastId: String, onBack: () -> Unit) = Placeholder("Detail $podcastId")
@Composable fun PlayerScreen(onBack: () -> Unit) = Placeholder("Player")
```

- [ ] **Step 5: Wire into App**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import androidx.compose.runtime.Composable
import app.kofipod.ui.shell.AppShell
import app.kofipod.ui.theme.KofipodTheme
import org.koin.compose.KoinContext

@Composable
fun App() {
    KoinContext {
        KofipodTheme {
            AppShell()
        }
    }
}
```

- [ ] **Step 6: Build + install**

Run: `./gradlew :composeApp:installDebug`
Expected: App launches on a connected device/emulator; bottom nav shows 4 items and routes between placeholder screens.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/
git commit -m "feat(nav): add nav graph, bottom nav, placeholder screens"
```

---

# Phase 4 — Search screen

### Task 4.1: SearchViewModel + screen

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/SearchViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/search/SearchScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/Placeholders.kt` (remove `SearchScreen`)
- Modify: `composeApp/src/commonMain/kotlin/app/kofipod/di/CommonModule.kt` (register VM)

- [ ] **Step 1: Write ViewModel**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.SearchRepository
import app.kofipod.domain.PodcastSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SearchTab { Title, Person }

data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.Title,
    val results: List<PodcastSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class SearchViewModel(private val repo: SearchRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        schedule()
    }

    fun setTab(tab: SearchTab) {
        _state.value = _state.value.copy(tab = tab)
        schedule()
    }

    private fun schedule() {
        searchJob?.cancel()
        val s = _state.value
        if (s.query.isBlank()) {
            _state.value = s.copy(results = emptyList(), loading = false, error = null)
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _state.value = s.copy(loading = true, error = null)
            runCatching {
                when (s.tab) {
                    SearchTab.Title -> repo.searchByTitle(s.query)
                    SearchTab.Person -> repo.searchByPerson(s.query)
                }
            }.onSuccess { results ->
                _state.value = _state.value.copy(results = results, loading = false)
            }.onFailure { e ->
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Search failed")
            }
        }
    }
}
```

- [ ] **Step 2: Write SearchScreen composable**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.domain.PodcastSummary
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import coil3.compose.AsyncImage
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SearchScreen(onOpenPodcast: (String) -> Unit, viewModel: SearchViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    Column(Modifier.fillMaxSize().background(c.bg).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Search", fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, color = c.text)
        Spacer(Modifier.height(16.dp))
        SearchBar(value = state.query, onValueChange = viewModel::setQuery)
        Spacer(Modifier.height(12.dp))
        TabRow(current = state.tab, onSelect = viewModel::setTab)
        Spacer(Modifier.height(16.dp))
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = c.pink) }
            state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(state.error!!, color = c.danger) }
            state.results.isEmpty() && state.query.isNotBlank() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No results", color = c.textMute) }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.results, key = { it.id }) { p -> ResultCard(p, onClick = { onOpenPodcast(p.id) }) }
            }
        }
    }
}

@Composable
private fun SearchBar(value: String, onValueChange: (String) -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.pill))
            .background(c.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) Text("Search podcasts or people…", color = c.textMute)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TabRow(current: SearchTab, onSelect: (SearchTab) -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier.clip(RoundedCornerShape(r.pill)).background(c.purpleTint).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SearchTab.values().forEach { tab ->
            val selected = tab == current
            Box(
                Modifier
                    .clip(RoundedCornerShape(r.pill))
                    .background(if (selected) c.purple else c.purpleTint)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (tab) { SearchTab.Title -> "By title"; SearchTab.Person -> "By person" },
                    color = if (selected) c.surface else c.text,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(p: PodcastSummary, onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = p.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(r.sm)).background(c.purpleTint),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, color = c.text, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(p.author, color = c.textSoft, fontWeight = FontWeight.Medium, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(p.description, color = c.textMute, fontSize = 12.sp, maxLines = 2)
        }
    }
}
```

- [ ] **Step 3: Register VM in Koin**

Add to `commonDataModule`:

```kotlin
import org.koin.core.module.dsl.viewModel
...
viewModel { SearchViewModel(get()) }
```

- [ ] **Step 4: Remove placeholder SearchScreen and wire new one**

Delete the `SearchScreen(...)` function from `Placeholders.kt` and ensure the `nav` graph's `import` resolves to `app.kofipod.ui.screens.search.SearchScreen`.

- [ ] **Step 5: Compose UI test for debounce + results**

`composeApp/src/commonTest/kotlin/app/kofipod/ui/SearchScreenTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalTestApi::class)
package app.kofipod.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import app.kofipod.data.repo.SearchRepository
import app.kofipod.domain.PodcastSummary
import app.kofipod.ui.screens.search.SearchScreen
import app.kofipod.ui.screens.search.SearchViewModel
import kotlin.test.Test

private class FakeSearchRepo(val results: List<PodcastSummary>) : SearchRepository(fakeApi()) {
    override suspend fun searchByTitle(query: String) = results
    override suspend fun searchByPerson(name: String) = emptyList<PodcastSummary>()
}
private fun fakeApi() = error("not used")

class SearchScreenTest {
    @Test
    fun typing_query_shows_matching_result_card() = runComposeUiTest {
        val repo = FakeSearchRepo(listOf(
            PodcastSummary("1", "Tech Talk", "Ada", "Great show about tech.", "", ""),
        ))
        val vm = SearchViewModel(repo)
        setContent { SearchScreen(onOpenPodcast = {}, viewModel = vm) }
        onNodeWithText("Search podcasts or people…").assertIsDisplayed()
        onNodeWithText("By title").assertIsDisplayed()
        // Tap the textfield by its placeholder area, then type
        onNodeWithText("Search podcasts or people…").performTextInput("tech")
        // Due to debounce (350ms), run the dispatcher forward
        mainClock.advanceTimeBy(400)
        waitUntil { onNodeWithText("Tech Talk").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Tech Talk").assertIsDisplayed()
        onNodeWithText("Ada").assertIsDisplayed()
    }
}
```

Adjust `SearchRepository` to allow overriding; if sealed by constructor, inject an interface instead. Refactor `SearchRepository` to implement a `SearchSource` interface:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.kofipod.domain.PodcastSummary

interface SearchSource {
    suspend fun searchByTitle(query: String): List<PodcastSummary>
    suspend fun searchByPerson(name: String): List<PodcastSummary>
}
```

And make `SearchRepository` implement it. Update `SearchViewModel` to depend on `SearchSource`.

- [ ] **Step 6: Run test**

Run: `./gradlew :composeApp:allTests`
Expected: PASSED.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/
git commit -m "feat(search): add SearchScreen with tabs, debounce, results"
```

---

# Phase 5 — Podcast detail

### Task 5.1: DetailViewModel + screen

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/PodcastDetailViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/detail/PodcastDetailScreen.kt`
- Modify: Placeholders + Koin registration

- [ ] **Step 1: ViewModel**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.db.Episode
import app.kofipod.db.Podcast
import app.kofipod.db.PodcastList
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DetailUiState(
    val podcast: Podcast? = null,
    val episodes: List<Episode> = emptyList(),
    val lists: List<PodcastList> = emptyList(),
    val saving: Boolean = false,
)

class PodcastDetailViewModel(
    private val podcastId: String,
    private val library: LibraryRepository,
    private val episodesRepo: EpisodesRepository,
) : ViewModel() {
    private val savingFlow = MutableStateFlow(false)

    val state: StateFlow<DetailUiState> = combine(
        library.podcastById(podcastId),
        episodesRepo.episodesFlow(podcastId),
        library.listsFlow(),
        savingFlow,
    ) { podcast, episodes, lists, saving ->
        DetailUiState(podcast, episodes, lists, saving)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    fun saveToList(listId: String?) = viewModelScope.launch {
        val p = state.value.podcast ?: return@launch
        library.movePodcastToList(p.id, listId)
    }

    fun toggleAutoDownload(enabled: Boolean) = viewModelScope.launch {
        library.setAutoDownload(podcastId, enabled)
    }

    fun refresh(feedIdLong: Long) = viewModelScope.launch {
        episodesRepo.refresh(podcastId, feedIdLong)
    }
}
```

- [ ] **Step 2: Screen (trimmed for brevity; match design: hero, title, author, description, save CTA, auto-download row, episode list)**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.db.Episode
import app.kofipod.ui.primitives.KPButton
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import coil3.compose.AsyncImage
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PodcastDetailScreen(
    podcastId: String,
    onBack: () -> Unit,
    viewModel: PodcastDetailViewModel = koinViewModel { parametersOf(podcastId) },
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    val podcast = state.podcast ?: run {
        Box(Modifier.fillMaxSize().background(c.bg), Alignment.Center) { Text("Loading…", color = c.textMute) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().background(c.bg)) {
        item {
            Column(Modifier.padding(20.dp)) {
                AsyncImage(
                    model = podcast.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(r.lg)).background(c.purpleTint),
                )
                Spacer(Modifier.height(16.dp))
                Text(podcast.title, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                Text(podcast.author, color = c.textSoft, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(podcast.description, color = c.textMute, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KPButton(
                        label = podcast.listId?.let { "Saved to list" } ?: "Save to list",
                        onClick = { viewModel.saveToList(state.lists.firstOrNull()?.id) },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Auto-download", color = c.textSoft)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = podcast.autoDownloadEnabled, onCheckedChange = viewModel::toggleAutoDownload)
                }
                Spacer(Modifier.height(24.dp))
                Text("Episodes", color = c.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
        items(state.episodes, key = { it.id }) { ep -> EpisodeRow(ep) }
    }
}

@Composable
private fun EpisodeRow(ep: Episode) {
    val c = LocalKofipodColors.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(ep.title, color = c.text, fontWeight = FontWeight.Bold)
        Text("${ep.durationSec / 60} min · ${ep.publishedAt}", color = c.textMute, fontSize = 12.sp)
    }
}
```

- [ ] **Step 3: Register in Koin**

In `commonDataModule`:

```kotlin
viewModel { (id: String) -> PodcastDetailViewModel(id, get(), get()) }
```

- [ ] **Step 4: Remove placeholder `PodcastDetailScreen`**

- [ ] **Step 5: Compose test: saving a podcast moves it into a list**

Test creates an in-memory `KofipodDatabase` using `JdbcSqliteDriver` (shared via `androidUnitTest` and desktop test) seeded with a podcast and an empty list, invokes the `saveToList` path, then asserts the DB row's `listId` matches.

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalTestApi::class)
package app.kofipod.ui

import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.db.KofipodDatabase
import app.kofipod.ui.screens.detail.PodcastDetailScreen
import app.kofipod.ui.screens.detail.PodcastDetailViewModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PodcastDetailSaveTest {
    @Test
    fun tapping_save_puts_podcast_in_first_list() = runComposeUiTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KofipodDatabase.Schema.create(driver)
        val db = KofipodDatabase(driver)
        db.podcastListQueries.insert("L1", "Tech", 0, 0)
        db.podcastQueries.insert("P1", "Show", "Ada", "", "", "", null, false, null, 0)
        val library = LibraryRepository(db)
        val vm = PodcastDetailViewModel("P1", library, FakeEpisodesRepo())
        setContent { PodcastDetailScreen("P1", onBack = {}, viewModel = vm) }
        waitUntilExactlyOneExists(hasText("Save to list"))
        onNodeWithText("Save to list").performClick()
        waitUntil { db.podcastQueries.selectById("P1").executeAsOne().listId == "L1" }
        assertEquals("L1", db.podcastQueries.selectById("P1").executeAsOne().listId)
    }
}

private class FakeEpisodesRepo : EpisodesRepository(/* stub */ error("unused"))
```

(Adjust `EpisodesRepository` to accept an interface `EpisodeSource` or mark `open` for test subclassing. Prefer interface.)

- [ ] **Step 6: Run tests**

Run: `./gradlew :composeApp:allTests`
Expected: PASSED.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/
git commit -m "feat(detail): add podcast detail screen with save + auto-download"
```

---

# Phase 6 — Library screen

### Task 6.1: Library folders view

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/library/LibraryScreen.kt`

- [ ] **Step 1: LibraryViewModel**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.db.Podcast
import app.kofipod.db.PodcastList
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LibraryUiState(
    val groups: List<LibraryGroup> = emptyList(),
)

data class LibraryGroup(val list: PodcastList?, val podcasts: List<Podcast>)

class LibraryViewModel(private val repo: LibraryRepository) : ViewModel() {
    val state: StateFlow<LibraryUiState> = combine(repo.listsFlow(), repo.podcastsFlow()) { lists, podcasts ->
        val byList = podcasts.groupBy { it.listId }
        val namedGroups = lists.map { l -> LibraryGroup(l, byList[l.id].orEmpty()) }
        val unfiled = byList[null].orEmpty()
        LibraryUiState(namedGroups + LibraryGroup(null, unfiled))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    fun createList(name: String) = viewModelScope.launch {
        repo.createList(id = name.lowercase().replace(" ", "-"), name = name,
            position = state.value.groups.count { it.list != null }, now = System.currentTimeMillis())
    }

    fun move(podcastId: String, to: String?) = viewModelScope.launch {
        repo.movePodcastToList(podcastId, to)
    }

    fun deletePodcast(id: String) = viewModelScope.launch { repo.deletePodcast(id) }
}
```

- [ ] **Step 2: LibraryScreen**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.db.Podcast
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import coil3.compose.AsyncImage
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LibraryScreen(
    onOpenPodcast: (String) -> Unit,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = PaddingValues(20.dp)) {
        item {
            Text("Library", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
            Spacer(Modifier.height(16.dp))
        }
        state.groups.forEach { group ->
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    group.list?.name ?: "Unfiled",
                    color = c.purple,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(group.podcasts.size, key = { group.podcasts[it].id }) { idx ->
                val p = group.podcasts[idx]
                PodcastRow(p, onClick = { onOpenPodcast(p.id) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PodcastRow(p: Podcast, onClick: () -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(r.md))
            .background(c.surface)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = p.artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(r.sm)).background(c.purpleTint),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(p.title, color = c.text, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(p.author, color = c.textMute, fontSize = 12.sp, maxLines = 1)
        }
    }
}
```

- [ ] **Step 3: Register VM**

In `commonDataModule`: `viewModel { LibraryViewModel(get()) }`.

- [ ] **Step 4: Compose UI test**

Test library renders "Unfiled" when podcasts exist without a list, and groups them under named list when `listId` matches.

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalTestApi::class)
package app.kofipod.ui

import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.db.KofipodDatabase
import app.kofipod.ui.screens.library.LibraryScreen
import app.kofipod.ui.screens.library.LibraryViewModel
import kotlin.test.Test

class LibraryScreenTest {
    @Test
    fun groups_podcasts_under_their_list_and_unfiled() = runComposeUiTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KofipodDatabase.Schema.create(driver)
        val db = KofipodDatabase(driver)
        db.podcastListQueries.insert("tech", "Tech", 0, 0)
        db.podcastQueries.insert("p1", "Dev Pod", "Ada", "", "", "", "tech", false, null, 0)
        db.podcastQueries.insert("p2", "Garden", "Bob", "", "", "", null, false, null, 0)
        setContent { LibraryScreen(onOpenPodcast = {}, viewModel = LibraryViewModel(LibraryRepository(db))) }
        waitUntilExactlyOneExists(hasText("Tech"))
        onNodeWithText("Dev Pod").assertIsDisplayed()
        onNodeWithText("Unfiled").assertIsDisplayed()
        onNodeWithText("Garden").assertIsDisplayed()
    }
}
```

- [ ] **Step 5: Run + commit**

```bash
./gradlew :composeApp:allTests
git add composeApp/src/
git commit -m "feat(library): add library folders view with grouped podcasts"
```

---

# Phase 7 — Settings screen

### Task 7.1: Settings UI

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: ViewModel**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.ui.theme.KofipodThemeMode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: KofipodThemeMode = KofipodThemeMode.System,
    val dailyCheck: Boolean = true,
    val storageCapBytes: Long = SettingsRepository.DEFAULT_CAP_BYTES,
    val skipForward: Int = 30,
    val skipBack: Int = 10,
)

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(
        repo.themeMode(), repo.dailyCheckEnabled(), repo.storageCapBytes(),
        repo.skipForwardSeconds(), repo.skipBackSeconds(),
    ) { theme, daily, cap, fwd, back ->
        SettingsUiState(theme, daily, cap, fwd, back)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun setTheme(mode: KofipodThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }
    fun setDailyCheck(on: Boolean) = viewModelScope.launch { repo.setDailyCheckEnabled(on) }
    fun setCap(bytes: Long) = viewModelScope.launch { repo.setStorageCapBytes(bytes) }
    fun setSkipForward(sec: Int) = viewModelScope.launch { repo.setSkipForward(sec) }
    fun setSkipBack(sec: Int) = viewModelScope.launch { repo.setSkipBack(sec) }
}
```

- [ ] **Step 2: SettingsScreen (rows for theme, daily check, storage cap slider, skip durations, about)**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.ui.theme.KofipodThemeMode
import app.kofipod.ui.theme.LocalKofipodColors
import app.kofipod.ui.theme.LocalKofipodRadii
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(onOpenScheduler: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Settings", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
        Spacer(Modifier.height(24.dp))

        Section("Theme")
        ThemeRow(state.themeMode, viewModel::setTheme)

        Section("Daily episode check")
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Checks ~once a day", color = c.text, fontWeight = FontWeight.Medium)
                Text("Battery-aware; on Wi-Fi while charging.", color = c.textMute, fontSize = 12.sp)
            }
            Switch(checked = state.dailyCheck, onCheckedChange = viewModel::setDailyCheck)
        }
        Text(
            "About the scheduler →",
            color = c.pink, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp).clickable { onOpenScheduler() },
        )

        Section("Downloads cap")
        Text(formatBytes(state.storageCapBytes), color = c.purple, fontWeight = FontWeight.Bold)
        Slider(
            value = state.storageCapBytes.toFloat(),
            valueRange = (512L * 1024 * 1024).toFloat()..(20L * 1024 * 1024 * 1024).toFloat(),
            onValueChange = { viewModel.setCap(it.toLong()) },
        )
    }
}

@Composable
private fun Section(title: String) {
    val c = LocalKofipodColors.current
    Spacer(Modifier.height(16.dp))
    Text(title.uppercase(), color = c.textMute, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.12.em)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ThemeRow(current: KofipodThemeMode, onSelect: (KofipodThemeMode) -> Unit) {
    val c = LocalKofipodColors.current
    val r = LocalKofipodRadii.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KofipodThemeMode.entries.forEach { m ->
            val selected = m == current
            Box(
                Modifier
                    .clip(RoundedCornerShape(r.pill))
                    .background(if (selected) c.purple else c.purpleTint)
                    .clickable { onSelect(m) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(m.name, color = if (selected) c.surface else c.text, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1L * 1024 * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024.0 / 1024.0)
    else -> "%d MB".format(b / 1024 / 1024)
}

private val Double.em get() = this.sp
```

- [ ] **Step 3: Register VM**

`viewModel { SettingsViewModel(get()) }` in `commonDataModule`.

Also read `state.themeMode` at the App level to propagate into `KofipodTheme`:

```kotlin
// App.kt
@Composable
fun App() {
    KoinContext {
        val settings = org.koin.compose.koinInject<app.kofipod.data.repo.SettingsRepository>()
        val mode by settings.themeMode().collectAsState(KofipodThemeMode.System)
        KofipodTheme(mode) { AppShell() }
    }
}
```

- [ ] **Step 4: Compose UI test — storage cap slider writes to repo**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalTestApi::class)
package app.kofipod.ui

import androidx.compose.ui.test.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.db.KofipodDatabase
import app.kofipod.ui.screens.settings.SettingsScreen
import app.kofipod.ui.screens.settings.SettingsViewModel
import kotlin.test.Test

class SettingsCapTest {
    @Test
    fun changing_slider_persists_new_cap() = runComposeUiTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { KofipodDatabase.Schema.create(it) }
        val repo = SettingsRepository(KofipodDatabase(driver))
        val vm = SettingsViewModel(repo)
        setContent { SettingsScreen(onOpenScheduler = {}, viewModel = vm) }
        waitUntilExactlyOneExists(hasText("Theme"))
        onNode(hasTestTag("storageCapSlider")).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.5f) }
        waitUntil { repo.storageCapBytes().first() != SettingsRepository.DEFAULT_CAP_BYTES }
    }
}
```

(Add a `testTag("storageCapSlider")` modifier to the `Slider` in the screen.)

- [ ] **Step 5: Commit**

```bash
./gradlew :composeApp:allTests
git add composeApp/src/
git commit -m "feat(settings): add settings screen (theme, daily check, cap)"
```

---

# Phase 8 — Playback

### Task 8.1: `Player` expect/actual, Media3 service

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/playback/Player.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/playback/Player.android.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/playback/KofipodPlaybackService.kt`
- Create: `composeApp/src/iosMain/kotlin/app/kofipod/playback/Player.ios.kt`

- [ ] **Step 1: `expect` API**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import kotlinx.coroutines.flow.StateFlow

data class PlayableEpisode(
    val episodeId: String,
    val podcastTitle: String,
    val title: String,
    val artworkUrl: String,
    val sourceUrl: String,
    val startPositionMs: Long,
)

data class PlayerState(
    val episodeId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

expect class KofipodPlayer {
    val state: StateFlow<PlayerState>
    fun play(episode: PlayableEpisode)
    fun pause()
    fun resume()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun skipForward()
    fun skipBack()
    fun stop()
}
```

- [ ] **Step 2: Android `actual` backed by Media3 MediaController**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

actual class KofipodPlayer(private val context: Context) {
    private val _state = MutableStateFlow(PlayerState())
    actual val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var tickJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    init { connect() }

    private fun connect() {
        val token = SessionToken(context, ComponentName(context, KofipodPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get()
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { pushState() }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { pushState() }
                override fun onPlaybackStateChanged(playbackState: Int) { pushState() }
            })
            startTicker()
            pushState()
        }, MoreExecutors.directExecutor())
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) { delay(500); pushState() }
        }
    }

    private fun pushState() {
        val c = controller ?: return
        _state.value = PlayerState(
            episodeId = c.currentMediaItem?.mediaId,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
            speed = c.playbackParameters.speed,
        )
    }

    actual fun play(episode: PlayableEpisode) {
        val item = MediaItem.Builder()
            .setMediaId(episode.episodeId)
            .setUri(episode.sourceUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.podcastTitle)
                    .setArtworkUri(android.net.Uri.parse(episode.artworkUrl))
                    .build()
            )
            .build()
        controller?.apply {
            setMediaItem(item, episode.startPositionMs)
            prepare()
            play()
        }
    }

    actual fun pause() { controller?.pause() }
    actual fun resume() { controller?.play() }
    actual fun seekTo(ms: Long) { controller?.seekTo(ms) }
    actual fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }
    actual fun skipForward() { controller?.let { it.seekTo(it.currentPosition + 30_000) } }
    actual fun skipBack() { controller?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } }
    actual fun stop() { controller?.stop() }
}
```

- [ ] **Step 3: MediaSessionService**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class KofipodPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        session = MediaSession.Builder(this, player).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
```

Manifest additions:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<service
    android:name=".playback.KofipodPlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

- [ ] **Step 4: iOS stub**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class KofipodPlayer {
    private val _state = MutableStateFlow(PlayerState())
    actual val state: StateFlow<PlayerState> = _state.asStateFlow()
    actual fun play(episode: PlayableEpisode) { /* TODO AVPlayer */ }
    actual fun pause() {}
    actual fun resume() {}
    actual fun seekTo(ms: Long) {}
    actual fun setSpeed(speed: Float) {}
    actual fun skipForward() {}
    actual fun skipBack() {}
    actual fun stop() {}
}
```

- [ ] **Step 5: Wire into Koin**

In `androidPlatformModule`: `single { KofipodPlayer(androidContext()) }`.
In `commonDataModule` swap to `factory` if using constructor params on iOS.

- [ ] **Step 6: Persist position**

Add a `PlaybackPersistenceJob` that observes the player's state and writes `positionMs/durationMs/speed` to `PlaybackState` every 10s via `SettingsRepository` or a new `PlaybackRepository`.

Create `composeApp/src/commonMain/kotlin/app/kofipod/data/repo/PlaybackRepository.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.kofipod.db.KofipodDatabase

class PlaybackRepository(private val db: KofipodDatabase) {
    suspend fun save(episodeId: String, positionMs: Long, durationMs: Long, speed: Float) {
        db.playbackStateQueries.upsert(episodeId, positionMs, durationMs, null, speed.toDouble(), System.currentTimeMillis())
    }
    fun positionFor(episodeId: String): Long =
        db.playbackStateQueries.selectByEpisode(episodeId).executeAsOneOrNull()?.positionMs ?: 0
}
```

Register in Koin.

- [ ] **Step 7: Build**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/
git commit -m "feat(playback): add Media3 MediaSessionService + KMP Player API"
```

---

### Task 8.2: Mini player + full player sheet

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/player/MiniPlayer.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/ui/screens/player/PlayerScreen.kt`
- Modify: `AppShell.kt` to place the mini-player above the bottom nav.

- [ ] **Step 1: MiniPlayer composable subscribing to `KofipodPlayer.state`**

Minimum: shows episode title, play/pause button (pink), progress line; tap → navigate to full player.

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kofipod.playback.KofipodPlayer
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.koinInject

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val player = koinInject<KofipodPlayer>()
    val s by player.state.collectAsState()
    if (s.episodeId == null) return
    val c = LocalKofipodColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▶", color = c.pink, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Text("Now playing · ${s.episodeId}", color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(999.dp)).background(c.pink).clickable {
            if (s.isPlaying) player.pause() else player.resume()
        }, contentAlignment = Alignment.Center) {
            Text(if (s.isPlaying) "⏸" else "▶", color = c.surface, fontWeight = FontWeight.Bold)
        }
    }
}
```

- [ ] **Step 2: Full PlayerScreen — artwork, scrubber, skip back/forward, speed, sleep timer (speed selector and sleep timer can be simple dropdown + text)**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kofipod.playback.KofipodPlayer
import app.kofipod.ui.theme.LocalKofipodColors
import org.koin.compose.koinInject

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val player = koinInject<KofipodPlayer>()
    val s by player.state.collectAsState()
    val c = LocalKofipodColors.current
    Column(Modifier.fillMaxSize().background(c.bg).padding(24.dp)) {
        Text("Now Playing", color = c.textSoft, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(40.dp))
        Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(20.dp)).background(c.purpleTint))
        Spacer(Modifier.height(24.dp))
        Text(s.episodeId ?: "—", color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        Spacer(Modifier.height(24.dp))
        Slider(
            value = if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f,
            onValueChange = { player.seekTo((it * s.durationMs).toLong()) },
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⏪ 10", modifier = Modifier.clickable { player.skipBack() })
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(999.dp)).background(c.pink).clickable {
                    if (s.isPlaying) player.pause() else player.resume()
                },
                contentAlignment = Alignment.Center,
            ) { Text(if (s.isPlaying) "⏸" else "▶", color = c.surface, fontSize = 22.sp) }
            Text("30 ⏩", modifier = Modifier.clickable { player.skipForward() })
        }
    }
}
```

- [ ] **Step 3: Wire mini player into AppShell**

In `AppShell.kt` bottomBar lambda, insert `MiniPlayer { nav.navigate(Route.Player) }` above `BottomNav`.

- [ ] **Step 4: Commit**

```bash
./gradlew :composeApp:assembleDebug
git add composeApp/src/
git commit -m "feat(player): add mini player and full player screen"
```

---

# Phase 9 — Downloads

### Task 9.1: Download engine + repository

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/downloads/DownloadEngine.kt` (expect)
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/downloads/DownloadEngine.android.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/downloads/DownloadService.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/data/repo/DownloadRepository.kt`

- [ ] **Step 1: expect engine**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.downloads

import kotlinx.coroutines.flow.Flow

data class DownloadJob(
    val episodeId: String,
    val url: String,
    val targetFileName: String,
    val source: Source,
) {
    enum class Source { Auto, Manual }
}

data class DownloadProgress(
    val episodeId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val state: State,
    val errorMessage: String? = null,
) {
    enum class State { Queued, Downloading, Completed, Failed, Paused }
}

expect class DownloadEngine {
    val events: Flow<DownloadProgress>
    fun enqueue(job: DownloadJob)
    fun cancel(episodeId: String)
    fun delete(episodeId: String)
}
```

- [ ] **Step 2: Android implementation using a foreground service + OkHttp range request**

Create `DownloadEngine.android.kt` that posts intents to `DownloadService`; service performs HTTP range download, writes to `context.filesDir/downloads/<file>`, updates progress through a `MutableSharedFlow<DownloadProgress>` exposed by the engine. When `totalBytes` is reached: state=Completed. On `cancel`: cooperative cancel flag; on `delete`: remove file + DB row (via repo).

Key snippet — service performs the stream:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.downloads

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class DownloadService : Service() {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val active = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        when (action) {
            ACTION_ENQUEUE -> {
                val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return START_NOT_STICKY
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_FILENAME) ?: episodeId
                active[episodeId] = scope.launch { downloadWithResume(episodeId, url, name) }
            }
            ACTION_CANCEL -> {
                val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return START_NOT_STICKY
                active.remove(episodeId)?.cancel()
            }
        }
        return START_STICKY
    }

    private suspend fun downloadWithResume(episodeId: String, url: String, name: String) {
        val file = File(filesDir, "downloads/$name").apply { parentFile?.mkdirs() }
        val existing = if (file.exists()) file.length() else 0L
        val request = Request.Builder().url(url).apply {
            if (existing > 0) addHeader("Range", "bytes=$existing-")
        }.build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                DownloadBroadcaster.emit(DownloadProgress(episodeId, existing, existing,
                    DownloadProgress.State.Failed, "HTTP ${resp.code}"))
                return
            }
            val total = resp.header("Content-Length")?.toLong()?.plus(existing) ?: -1L
            resp.body?.byteStream()?.use { stream ->
                java.io.FileOutputStream(file, existing > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int; var received = existing
                    while (stream.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read); received += read
                        DownloadBroadcaster.emit(DownloadProgress(episodeId, received, total,
                            DownloadProgress.State.Downloading))
                    }
                }
            }
            DownloadBroadcaster.emit(DownloadProgress(episodeId, file.length(), file.length(),
                DownloadProgress.State.Completed))
        }
    }

    companion object {
        const val ACTION_ENQUEUE = "kofipod.action.ENQUEUE"
        const val ACTION_CANCEL = "kofipod.action.CANCEL"
        const val EXTRA_EPISODE_ID = "ep"
        const val EXTRA_URL = "url"
        const val EXTRA_FILENAME = "filename"
    }
}

object DownloadBroadcaster {
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()
    suspend fun emit(p: DownloadProgress) { _events.emit(p) }
}
```

Add service declaration in manifest:

```xml
<service
    android:name=".downloads.DownloadService"
    android:foregroundServiceType="dataSync"
    android:exported="false"/>
```

- [ ] **Step 3: `DownloadRepository`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.kofipod.db.Download
import app.kofipod.db.KofipodDatabase
import app.kofipod.downloads.DownloadEngine
import app.kofipod.downloads.DownloadJob
import app.kofipod.downloads.DownloadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class DownloadRepository(
    private val db: KofipodDatabase,
    private val engine: DownloadEngine,
    scope: CoroutineScope,
) {
    init {
        engine.events.onEach { p ->
            when (p.state) {
                DownloadProgress.State.Queued,
                DownloadProgress.State.Downloading,
                DownloadProgress.State.Paused -> db.downloadQueries.updateProgress(p.downloadedBytes, p.totalBytes, p.episodeId)
                DownloadProgress.State.Completed -> {
                    db.downloadQueries.updateProgress(p.downloadedBytes, p.totalBytes, p.episodeId)
                    db.downloadQueries.updateState("Completed", null, p.episodeId)
                }
                DownloadProgress.State.Failed ->
                    db.downloadQueries.updateState("Failed", p.errorMessage, p.episodeId)
            }
        }.launchIn(scope)
    }

    fun all(): Flow<List<Download>> = db.downloadQueries.selectAll().asFlow().mapToList(Dispatchers.Default)

    suspend fun enqueue(episodeId: String, url: String, fileName: String, source: DownloadJob.Source) {
        db.downloadQueries.upsert(episodeId, "Queued", null, 0, 0, source.name, System.currentTimeMillis(), null, null)
        engine.enqueue(DownloadJob(episodeId, url, fileName, source))
    }

    suspend fun cancel(episodeId: String) {
        engine.cancel(episodeId)
        db.downloadQueries.updateState("Paused", null, episodeId)
    }

    suspend fun delete(episodeId: String) {
        engine.delete(episodeId)
        db.downloadQueries.delete(episodeId)
    }

    suspend fun evictUntilUnderCap(capBytes: Long) {
        var total = db.downloadQueries.totalCompletedBytes().executeAsOne().total
        if (total <= capBytes) return
        val victims = db.downloadQueries.selectAutoCompletedOldestFirst().executeAsList()
        for (v in victims) {
            delete(v.episodeId)
            total -= v.totalBytes
            if (total <= capBytes) break
        }
    }
}
```

- [ ] **Step 4: Downloads UI** — list grouped by state (Downloading / Queued / Completed), progress bar, delete button.

(Follow the pattern of Library: ViewModel collects `downloadRepo.all()`, UI composable renders groups.)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/
git commit -m "feat(downloads): foreground DownloadService + repo + screen"
```

---

# Phase 10 — Daily episode check + notifications

### Task 10.1: WorkManager worker

**Files:**
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/background/EpisodeCheckWorker.kt`
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/background/Scheduler.android.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/background/Scheduler.kt` (expect)
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/background/Notifier.android.kt`

- [ ] **Step 1: expect/actual scheduler**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

expect class Scheduler {
    fun enable()
    fun disable()
}
```

Android actual wraps WorkManager:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

actual class Scheduler(private val context: Context) {
    actual fun enable() {
        val req = PeriodicWorkRequestBuilder<EpisodeCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag("episode_check")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("episode_check", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    actual fun disable() {
        WorkManager.getInstance(context).cancelUniqueWork("episode_check")
    }
}
```

- [ ] **Step 2: Worker**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.kofipod.data.repo.*
import app.kofipod.downloads.DownloadJob
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class EpisodeCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {

    private val library: LibraryRepository by inject()
    private val episodes: EpisodesRepository by inject()
    private val settings: SettingsRepository by inject()
    private val downloads: DownloadRepository by inject()
    private val notifier: Notifier by inject()

    override suspend fun doWork(): Result {
        val cap = settings.storageCapBytes().first()
        var totalNewEpisodes = 0
        var showsWithNew = 0
        library.podcastsFlow().first().forEach { p ->
            val before = totalNewEpisodes
            val r = episodes.refresh(p.id, p.id.toLong())
            totalNewEpisodes += r.inserted
            if (r.inserted > 0) showsWithNew++
            if (p.autoDownloadEnabled && r.inserted > 0) {
                episodes.episodesFlow(p.id).first().take(r.inserted).forEach { ep ->
                    downloads.enqueue(ep.id, ep.enclosureUrl, "${ep.id}.mp3", DownloadJob.Source.Auto)
                }
            }
        }
        downloads.evictUntilUnderCap(cap)
        if (totalNewEpisodes > 0) {
            notifier.postNewEpisodes(totalNewEpisodes, showsWithNew)
        }
        return Result.success()
    }
}
```

- [ ] **Step 3: Notifier (Android)**

Create a `Notifier` expect/actual the same way.

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

actual class Notifier(private val context: Context) {
    init {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "New episodes", NotificationManager.IMPORTANCE_DEFAULT))
    }

    actual fun postNewEpisodes(totalEpisodes: Int, totalShows: Int) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("$totalEpisodes new episodes")
            .setContentText("from $totalShows show${if (totalShows == 1) "" else "s"}")
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIFY_ID, n)
    }

    companion object {
        const val CHANNEL_ID = "kofipod.new_episodes"
        const val NOTIFY_ID = 42
    }
}
```

Plus common `expect`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

expect class Notifier {
    fun postNewEpisodes(totalEpisodes: Int, totalShows: Int)
}
```

- [ ] **Step 4: Hook settings toggle to scheduler**

In `SettingsViewModel.setDailyCheck`, also call `scheduler.enable()` / `scheduler.disable()`.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/
git commit -m "feat(background): add daily WorkManager + notifier"
```

---

# Phase 11 — Google sign-in + Drive backup

### Task 11.1: Credential Manager sign-in

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/auth/AuthService.kt` (expect)
- Create: `composeApp/src/androidMain/kotlin/app/kofipod/auth/AuthService.android.kt`

- [ ] **Step 1: expect interface**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.auth

expect class AuthService {
    suspend fun signIn(serverClientId: String): SignInResult
    suspend fun signOut()
    suspend fun currentAccessToken(): String?
}

data class SignInResult(val idToken: String, val email: String, val displayName: String)
```

- [ ] **Step 2: Android actual using Credential Manager + Google Identity Services**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

actual class AuthService(private val context: Context) {
    private val manager = CredentialManager.create(context)

    actual suspend fun signIn(serverClientId: String): SignInResult {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = manager.getCredential(context = context, request = request)
        val cred = response.credential as GoogleIdTokenCredential
        return SignInResult(cred.idToken, cred.id, cred.displayName ?: cred.id)
    }

    actual suspend fun signOut() { manager.clearCredentialState(androidx.credentials.ClearCredentialStateRequest()) }
    actual suspend fun currentAccessToken(): String? = null // token exchange handled by DriveClient
}
```

The `serverClientId` (OAuth 2.0 Web client) comes from Google Cloud Console and is surfaced through `BuildKonfig` as `GOOGLE_SERVER_CLIENT_ID`. Add to `buildkonfig` block.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/
git commit -m "feat(auth): add Credential Manager Google sign-in"
```

---

### Task 11.2: Drive REST v3 backup

**Files:**
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/BackupBlob.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/DriveClient.kt`
- Create: `composeApp/src/commonMain/kotlin/app/kofipod/backup/BackupRepository.kt`

- [ ] **Step 1: Backup blob schema (`v1`)**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupBlob(
    val version: Int = 1,
    val exportedAt: Long,
    val lists: List<BackupList>,
    val podcasts: List<BackupPodcast>,
    val playback: List<BackupPlayback>,
)

@Serializable data class BackupList(val id: String, val name: String, val position: Int, val createdAt: Long)
@Serializable data class BackupPodcast(
    val id: String, val title: String, val author: String, val description: String,
    val artworkUrl: String, val feedUrl: String, val listId: String?,
    val autoDownloadEnabled: Boolean, val addedAt: Long,
)
@Serializable data class BackupPlayback(
    val episodeId: String, val positionMs: Long, val durationMs: Long,
    val completedAt: Long?, val playbackSpeed: Double, val updatedAt: Long,
)
```

- [ ] **Step 2: DriveClient uses Ktor to call `https://www.googleapis.com/drive/v3` with `spaces=appDataFolder` and `Authorization: Bearer <access token>`.**

Because we get only an `idToken` from Credential Manager, add a second step using Play Services Auth's `AuthorizationClient` (or use Drive REST via `GoogleAccountCredential` from the Drive Android library). The cleaner path: add `play-services-auth` with `Identity.getAuthorizationClient` and request the `https://www.googleapis.com/auth/drive.appdata` scope to get an OAuth 2.0 access token.

Implement `DriveClient` with `fetchAccessToken()`, `list(spaces="appDataFolder")`, `upload(name, content)`, `download(fileId)`.

(This task is non-trivial; enumerated steps here refer the engineer to the Drive REST docs: https://developers.google.com/drive/api/reference/rest/v3/files.)

- [ ] **Step 3: BackupRepository**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

import app.kofipod.db.KofipodDatabase
import kotlinx.serialization.json.Json

class BackupRepository(
    private val db: KofipodDatabase,
    private val drive: DriveClient,
    private val json: Json = Json { prettyPrint = false },
) {
    suspend fun backupNow() {
        val blob = BackupBlob(
            exportedAt = System.currentTimeMillis(),
            lists = db.podcastListQueries.selectAll().executeAsList().map {
                BackupList(it.id, it.name, it.position.toInt(), it.createdAt)
            },
            podcasts = db.podcastQueries.selectAll().executeAsList().map {
                BackupPodcast(it.id, it.title, it.author, it.description, it.artworkUrl,
                    it.feedUrl, it.listId, it.autoDownloadEnabled, it.addedAt)
            },
            playback = db.playbackStateQueries.selectAll().executeAsList().map {
                BackupPlayback(it.episodeId, it.positionMs, it.durationMs,
                    it.completedAt, it.playbackSpeed, it.updatedAt)
            },
        )
        drive.uploadJson(BACKUP_FILENAME, json.encodeToString(BackupBlob.serializer(), blob))
        db.syncMetaQueries.put("drive_backup_version", blob.exportedAt.toString())
    }

    suspend fun restore(): Boolean {
        val text = drive.downloadJson(BACKUP_FILENAME) ?: return false
        val blob = json.decodeFromString(BackupBlob.serializer(), text)
        db.transaction {
            blob.lists.forEach { db.podcastListQueries.insert(it.id, it.name, it.position.toLong(), it.createdAt) }
            blob.podcasts.forEach {
                db.podcastQueries.insert(it.id, it.title, it.author, it.description, it.artworkUrl,
                    it.feedUrl, it.listId, it.autoDownloadEnabled, null, it.addedAt)
            }
            blob.playback.forEach {
                db.playbackStateQueries.upsert(it.episodeId, it.positionMs, it.durationMs,
                    it.completedAt, it.playbackSpeed, it.updatedAt)
            }
        }
        return true
    }

    companion object { const val BACKUP_FILENAME = "kofipod-backup-v1.json" }
}
```

- [ ] **Step 4: Wire into Settings** — "Back up now" / "Restore" buttons; status row showing last backup timestamp.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/
git commit -m "feat(backup): add Drive appDataFolder backup + restore"
```

---

# Phase 12 — Onboarding + Scheduler explainer

### Task 12.1: Onboarding screen

Follow the same pattern as Search/Library. Design from the canvas: hero, tagline, "Sign in with Google" CTA (pink), "Skip for now" secondary. Calls `AuthService.signIn(BuildKonfig.GOOGLE_SERVER_CLIENT_ID)`.

- [ ] **Step 1:** Implement `OnboardingScreen` composable.
- [ ] **Step 2:** Wire into nav — first-launch detection via `SyncMeta["onboarded"]`.
- [ ] **Step 3:** Commit `feat(onboarding): google sign-in onboarding screen`.

### Task 12.2: Scheduler explainer screen

Displays the last 7 runs as a small bar chart (stored in `SyncMeta` as a JSON list of timestamps + `insertedCount`), explanatory copy, "Back" button. No additional APIs.

- [ ] **Step 1:** `SchedulerInfoViewModel` reads `SyncMeta["scheduler_runs"]` and parses JSON.
- [ ] **Step 2:** Screen renders chart with `Canvas`.
- [ ] **Step 3:** `EpisodeCheckWorker` appends run entries after each successful run.
- [ ] **Step 4:** Commit `feat(scheduler): scheduler explainer screen`.

---

# Phase 13 — Paparazzi screenshot tests

### Task 13.1: Paparazzi setup + baseline snapshots

**Files:**
- Create: `composeApp/src/androidUnitTest/kotlin/app/kofipod/screenshots/ScreenSnapshots.kt`

- [ ] **Step 1:** Ensure `paparazzi` plugin is applied on `composeApp` (already in Task 0.1) and the module produces an Android variant. Paparazzi binds to `composeApp` as an Android library internally.

- [ ] **Step 2:** Snapshot each of the eight designed screens and the mini/full player, in both light and dark themes. Example for Search:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.screenshots

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.kofipod.ui.screens.search.SearchScreen
import app.kofipod.ui.screens.search.SearchUiState
import app.kofipod.ui.theme.KofipodTheme
import app.kofipod.ui.theme.KofipodThemeMode
import org.junit.Rule
import org.junit.Test

class SearchScreenSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test fun searchLight() = paparazzi.snapshot {
        KofipodTheme(KofipodThemeMode.Light) {
            SearchScreen(onOpenPodcast = {}, viewModel = fakeSearchVm())
        }
    }

    @Test fun searchDark() = paparazzi.snapshot {
        KofipodTheme(KofipodThemeMode.Dark) {
            SearchScreen(onOpenPodcast = {}, viewModel = fakeSearchVm())
        }
    }
}

private fun fakeSearchVm() = app.kofipod.ui.screens.search.SearchViewModel(
    repo = object : app.kofipod.data.repo.SearchSource {
        override suspend fun searchByTitle(query: String) = listOf(
            app.kofipod.domain.PodcastSummary("1", "Tech Talk", "Ada", "Bi-weekly tech.", "", ""),
            app.kofipod.domain.PodcastSummary("2", "Garden Hours", "Bob", "Plants and soil.", "", ""),
        )
        override suspend fun searchByPerson(name: String) = emptyList<app.kofipod.domain.PodcastSummary>()
    }
).also { it.setQuery("t") }
```

Repeat for: OnboardingScreen, LibraryScreen (with seeded DB), PodcastDetailScreen, DownloadsScreen, SettingsScreen, SchedulerInfoScreen, MiniPlayer, PlayerScreen.

- [ ] **Step 3:** Run `./gradlew :composeApp:recordPaparazziDebug` to record baselines. Commit the `composeApp/src/androidUnitTest/snapshots/` directory.

- [ ] **Step 4:** Run `./gradlew :composeApp:verifyPaparazziDebug` to verify baselines match.

- [ ] **Step 5:** Commit `test(screenshots): add Paparazzi baselines for all designed screens`.

---

# Done criteria

- [ ] `./gradlew :composeApp:assembleDebug` succeeds with valid `PODCAST_INDEX_KEY` in `local.properties`.
- [ ] `./gradlew :composeApp:allTests` passes (Compose UI tests).
- [ ] `./gradlew :composeApp:verifyPaparazziDebug` passes (screenshot tests).
- [ ] App launches on an Android phone with Android 8+; all bottom-nav destinations are reachable.
- [ ] Search returns non-music, non-live podcasts from Podcast Index.
- [ ] A podcast can be saved to a list; the list appears in Library.
- [ ] An episode can be played end-to-end with lock-screen controls.
- [ ] A manual download completes and appears in Downloads.
- [ ] With the scheduler enabled, running `WorkManager.getInstance(ctx).enqueueUniqueWork("episode_check", REPLACE, OneTimeWorkRequestBuilder<EpisodeCheckWorker>().build())` triggers a refresh and notification.
- [ ] Signing in with Google and "Back up now" uploads a `kofipod-backup-v1.json` to the Drive `appDataFolder`; "Restore" fetches it.
- [ ] Light/dark theme toggle in Settings flips all screens.
- [ ] No music/live/soundbite items appear in search results.
