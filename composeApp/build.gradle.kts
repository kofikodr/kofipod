import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.INT
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.buildkonfig)
    alias(libs.plugins.paparazzi)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.sentry.android)
}

ktlint {
    filter {
        exclude { it.file.absolutePath.contains("${layout.buildDirectory.get()}") }
        exclude { it.file.name.endsWith(".kts") && it.file.name != "build.gradle.kts" }
    }
}

detekt {
    buildUponDefaultConfig = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "src/commonMain/kotlin",
            "src/androidMain/kotlin",
            "src/iosMain/kotlin",
            "src/commonTest/kotlin",
            "src/test/kotlin",
        ),
    )
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
                implementation(libs.androidx.navigation.compose)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.sqldelight.coroutines.extensions)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor)
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.podcastindex.sdk)
                implementation(libs.xmlutil.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.okhttp)
                // Direct, pinned: ArtworkProvider's SSRF guard uses okhttp3.* on a
                // security-critical path, so it must not depend on Ktor's transitive OkHttp.
                implementation(libs.okhttp)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.requery.sqlite.android)
                implementation(libs.androidx.media3.exoplayer)
                implementation(libs.androidx.media3.session)
                implementation(libs.androidx.media3.datasource)
                implementation(libs.androidx.media3.database)
                implementation(libs.androidx.media3.transformer)
                implementation(libs.androidx.media3.effect)
                implementation(libs.androidx.work.runtime)
                implementation(libs.androidx.palette)
                implementation(libs.androidx.security.crypto)
                implementation(libs.androidx.documentfile)
                implementation(libs.koin.android)
                implementation(libs.aptabase)
                implementation(libs.sentry.kmp)
            }
        }
        val androidPlay by creating {
            dependsOn(androidMain)
            dependencies {
                implementation(libs.google.play.billing)
            }
        }
        val androidFoss by creating {
            dependsOn(androidMain)
            // No flavor-specific dependencies. The FOSS flavor stays
            // proprietary-code-free so F-Droid will accept it.
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
        val androidUnitTest by getting {
            dependencies {
                // `kotlin("test")` on JVM transitively pulls `kotlin-test-junit` → `junit:junit`,
                // so a separate `libs.junit` declaration would just duplicate the dependency.
                implementation(kotlin("test"))
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTestJUnit4)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.runner)
            }
        }
    }
}

dependencies {
    debugImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
}

val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    require(f.exists()) { "version.properties missing at repo root" }
    f.inputStream().use { load(it) }
}
val appVersionName: String = versionProps.getProperty("VERSION_NAME")
    ?: error("VERSION_NAME missing in version.properties")
val appVersionCode: Int = (versionProps.getProperty("VERSION_CODE")
    ?: error("VERSION_CODE missing in version.properties")).toInt()

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.kofikodr.kofipod"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kofikodr.kofipod"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Kofipod"
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            // play flavor is the revenue product; no applicationIdSuffix so it
            // matches what's uploaded to Play Console.
            manifestPlaceholders["appLabel"] = "Kofipod"
            // Play Store policy forbids self-updaters. Hard-coded here (not via
            // local.properties) so the play AAB is incapable of ever offering
            // a GitHub-Releases sideload install, regardless of build env.
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
            // Play Billing is wired in this flavor; "Restore purchase" is meaningful.
            buildConfigField("boolean", "BILLING_ENABLED", "true")
            // SHA-256 of the reviewer unlock code. Play-only: FOSS is already Pro
            // and must not ship this revenue-build reviewer secret.
            buildConfigField("String", "REVIEWER_UNLOCK_HASH", buildConfigStringLiteral(readSecret("REVIEWER_UNLOCK_HASH")))
            // Podcast Index credentials are account-bound secrets. Keep them
            // scoped to the Play flavor so public FOSS builds do not ship them.
            buildConfigField("String", "PODCAST_INDEX_KEY", buildConfigStringLiteral(readSecret("PODCAST_INDEX_KEY")))
            buildConfigField("String", "PODCAST_INDEX_SECRET", buildConfigStringLiteral(readSecret("PODCAST_INDEX_SECRET")))
            // Diagnostics keys point at the maintainer's accounts and belong
            // only in the Play/revenue flavor.
            buildConfigField("String", "SENTRY_DSN", buildConfigStringLiteral(readSecret("SENTRY_DSN")))
            buildConfigField("String", "APTABASE_APP_KEY", buildConfigStringLiteral(readSecret("APTABASE_APP_KEY")))
        }
        create("foss") {
            dimension = "distribution"
            // foss flavor unconditionally unlocks Pro and excludes Play Billing.
            // Use a distinct package so a foss build can be installed alongside
            // a play build for verification.
            applicationIdSuffix = ".foss"
            versionNameSuffix = "-foss"
            manifestPlaceholders["appLabel"] = "Kofipod (FOSS)"
            // GitHub Releases is the primary distribution channel for foss;
            // the in-app updater polls and offers the latest APK.
            buildConfigField("boolean", "UPDATER_ENABLED", "true")
            // No Play Billing in this flavor — there is nothing to restore.
            buildConfigField("boolean", "BILLING_ENABLED", "false")
            // FOSS is unconditionally Pro via FossBillingClientPort and must not
            // embed the Play reviewer unlock secret.
            buildConfigField("String", "REVIEWER_UNLOCK_HASH", "\"\"")
            // FOSS self-builders can inject their own credentials in forks; the
            // published FOSS APK must not embed the Play account credentials.
            buildConfigField("String", "PODCAST_INDEX_KEY", "\"\"")
            buildConfigField("String", "PODCAST_INDEX_SECRET", "\"\"")
            // Public FOSS builds must not report into the maintainer's
            // diagnostics accounts.
            buildConfigField("String", "SENTRY_DSN", "\"\"")
            buildConfigField("String", "APTABASE_APP_KEY", "\"\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                val storePath = keystoreProps.getProperty("storeFile")
                    ?: error("storeFile missing in keystore.properties")
                storeFile = rootProject.file(storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: error("storePassword missing in keystore.properties")
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: error("keyAlias missing in keystore.properties")
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: error("keyPassword missing in keystore.properties")
            }
        }
    }
    buildTypes {
        debug {
            // Distinct package so debug installs coexist with a production release on the same device.
            // Note: we deliberately do NOT override the appLabel placeholder here. Setting it on the
            // debug build-type silently overrides the per-flavor value, hiding the "(FOSS)" suffix
            // from the launcher on foss debug. Use the package id suffix to tell debug from release.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            // TODO(release): enable R8 with proguard-rules.pro after sideload-verifying a minified build
            signingConfig = signingConfigs.findByName("release")
        }
    }
    applicationVariants.all {
        val variant = this
        // Distinct launcher label per variant so debug installs don't collide visually with
        // release on the same device. Set here (not in buildTypes.debug) because a buildType
        // placeholder silently overrides the per-flavor value, which would drop the "(FOSS)"
        // suffix from foss-debug.
        val flavorLabel = if (variant.flavorName == "foss") "Kofipod (FOSS)" else "Kofipod"
        val label = if (variant.buildType.name == "debug") "$flavorLabel debug" else flavorLabel
        variant.mergedFlavor.manifestPlaceholders["appLabel"] = label
        if (variant.buildType.name == "release") {
            variant.outputs.all {
                val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                output.outputFileName =
                    "kofipod-${variant.flavorName}-${variant.versionName}-${variant.versionCode}-${variant.buildType.name}.apk"
            }
        }
    }
}

// Fail fast if a release artifact is built without a signing config. The `release`
// build type is configured on EVERY Gradle invocation (assembleDebug, tests, IDE sync),
// so this guard can't live inside `buildTypes.release` without breaking debug
// development for contributors who have no keystore. Instead inspect the actual task
// graph and fail only when a release-producing task is scheduled. Mirrors the keystore
// guard in scripts/release.sh for the case where the gradle task is invoked directly.
// Excludes lint*Release and *ReleaseUnitTest/*ReleaseAndroidTest (those are debug-signed
// or need no signing).
val releaseArtifactTask = Regex("""^(assemble|bundle|install|package)(Play|Foss)?Release$""")
// Capture primitives (not live `project`/`android` references) so the closure stays
// configuration-cache compatible — Gradle 9 enables the config cache by default and
// would reject a serialized `Project` extension. `android.signingConfigs` is fully
// populated here because this block runs after the `android { }` block above.
val releaseSigningConfigured = android.signingConfigs.findByName("release") != null
val thisProjectPath = project.path
gradle.taskGraph.whenReady {
    val buildingReleaseArtifact = allTasks.any {
        it.project.path == thisProjectPath && releaseArtifactTask.matches(it.name)
    }
    if (buildingReleaseArtifact && !releaseSigningConfigured) {
        throw GradleException(
            "keystore.properties is required for release builds, but none was found — " +
                "a release APK/AAB must not be silently debug-signed. " +
                "See the 'Release' section of README.md for keystore setup.",
        )
    }
}

sqldelight {
    databases {
        create("KofipodDatabase") {
            packageName.set("com.kofikodr.kofipod.db")
            // SQLite 3.24 dialect enables ON CONFLICT DO UPDATE (UPSERT syntax),
            // required for FTS5-compatible upserts in TranscriptCache and
            // EpisodeAiSummary. The default 3.18 dialect rejects this syntax.
            dialect("app.cash.sqldelight:sqlite-3-24-dialect:2.0.2")
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

fun buildConfigStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun ByteArray.containsByteSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (needle.size > size) return false

    for (start in 0..(size - needle.size)) {
        var matched = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}

fun podcastIndexSecrets(): List<Pair<String, String>> =
    listOf(
        "PODCAST_INDEX_KEY" to readSecret("PODCAST_INDEX_KEY"),
        "PODCAST_INDEX_SECRET" to readSecret("PODCAST_INDEX_SECRET"),
    )

fun configuredPodcastIndexSecrets(): List<Pair<String, String>> =
    podcastIndexSecrets().filter { (_, value) -> value.isNotBlank() }

fun diagnosticsSecrets(): List<Pair<String, String>> =
    listOf(
        "SENTRY_DSN" to readSecret("SENTRY_DSN"),
        "APTABASE_APP_KEY" to readSecret("APTABASE_APP_KEY"),
    )

fun configuredDiagnosticsSecrets(): List<Pair<String, String>> =
    diagnosticsSecrets().filter { (_, value) -> value.isNotBlank() }

buildkonfig {
    packageName = "com.kofikodr.kofipod.config"
    defaultConfigs {
        buildConfigField(STRING, "USER_AGENT", "Kofipod/$appVersionName (github.com/kofikodr/kofipod)")
        buildConfigField(STRING, "VERSION_NAME", appVersionName)
        buildConfigField(INT, "VERSION_CODE", appVersionCode.toString())
        // Flavor-specific Android secrets (reviewer unlock hash, Podcast Index
        // credentials, and diagnostics keys) live in AGP BuildConfig, not shared
        // BuildKonfig.
    }
}

fun scanApkForPodcastIndexSecrets(
    apk: File,
    secrets: List<Pair<String, String>>,
): List<String> {
    val secretBytes = secrets.map { (name, value) -> name to value.toByteArray(Charsets.UTF_8) }
    val matches = mutableListOf<String>()
    ZipFile(apk).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            secretBytes.forEach { (name, value) ->
                if (bytes.containsByteSequence(value)) {
                    matches += "$name in ${entry.name}"
                }
            }
        }
    }
    return matches
}

tasks.register("verifyFossReleaseExcludesPodcastIndexSecrets") {
    description = "Assemble the FOSS release APK and fail if configured Podcast Index secrets are embedded."
    group = "verification"
    dependsOn("assembleFossRelease")

    doLast {
        val secrets = configuredPodcastIndexSecrets()
        if (secrets.isEmpty()) {
            logger.lifecycle("No Podcast Index credentials configured; FOSS release APK secret scan skipped.")
            return@doLast
        }

        val apkDir = layout.buildDirectory.dir("outputs/apk/foss/release").get().asFile
        val apk = apkDir.listFiles()
            ?.singleOrNull { file -> file.extension == "apk" }
            ?: error("Expected exactly one FOSS release APK in ${apkDir.absolutePath}")
        val matches = scanApkForPodcastIndexSecrets(apk, secrets)
        check(matches.isEmpty()) {
            "FOSS release APK contains Podcast Index secret values: ${matches.joinToString()}"
        }
    }
}

tasks.register("verifyPlayDebugIncludesPodcastIndexSecrets") {
    description = "Assemble the Play debug APK and verify configured Podcast Index secrets reach that flavor."
    group = "verification"
    dependsOn("assemblePlayDebug")

    doLast {
        val secrets = podcastIndexSecrets()
        val missingSecrets = secrets.filter { (_, value) -> value.isBlank() }.map { (name, _) -> name }
        if (missingSecrets.isNotEmpty()) {
            val allowMissingSecrets =
                providers.gradleProperty("allowMissingPodcastIndexSecrets").orNull.toBoolean()
            check(allowMissingSecrets) {
                "Podcast Index credentials are required for Play verification; missing: ${missingSecrets.joinToString()}. " +
                    "Set -PallowMissingPodcastIndexSecrets=true only for fork/no-secret builds."
            }
            logger.lifecycle("No Podcast Index credentials configured; Play debug APK secret scan explicitly skipped.")
            return@doLast
        }

        val apkDir = layout.buildDirectory.dir("outputs/apk/play/debug").get().asFile
        val apk = apkDir.listFiles()
            ?.singleOrNull { file -> file.extension == "apk" }
            ?: error("Expected exactly one Play debug APK in ${apkDir.absolutePath}")
        val matches = scanApkForPodcastIndexSecrets(apk, secrets).map { it.substringBefore(" in ") }.toSet()
        val missing = secrets.map { (name, _) -> name }.filterNot { it in matches }
        check(missing.isEmpty()) {
            "Play debug APK is missing configured Podcast Index values for: ${missing.joinToString()}"
        }
    }
}

fun scanApkForDiagnosticsSecrets(
    apk: File,
    secrets: List<Pair<String, String>>,
): List<String> {
    val secretBytes = secrets.map { (name, value) -> name to value.toByteArray(Charsets.UTF_8) }
    val matches = mutableListOf<String>()
    ZipFile(apk).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            secretBytes.forEach { (name, value) ->
                if (bytes.containsByteSequence(value)) {
                    matches += "$name in ${entry.name}"
                }
            }
        }
    }
    return matches
}

tasks.register("verifyFossReleaseExcludesDiagnosticsSecrets") {
    description = "Assemble the FOSS release APK and fail if configured diagnostics secrets are embedded."
    group = "verification"
    dependsOn("assembleFossRelease")

    doLast {
        val secrets = configuredDiagnosticsSecrets()
        if (secrets.isEmpty()) {
            logger.lifecycle("No diagnostics secrets configured; FOSS release APK secret scan skipped.")
            return@doLast
        }

        val apkDir = layout.buildDirectory.dir("outputs/apk/foss/release").get().asFile
        val apk = apkDir.listFiles()
            ?.singleOrNull { file -> file.extension == "apk" }
            ?: error("Expected exactly one FOSS release APK in ${apkDir.absolutePath}")
        val matches = scanApkForDiagnosticsSecrets(apk, secrets)
        check(matches.isEmpty()) {
            "FOSS release APK contains diagnostics secret values: ${matches.joinToString()}"
        }
    }
}

tasks.register("verifyPlayDebugIncludesDiagnosticsSecrets") {
    description = "Assemble the Play debug APK and verify configured diagnostics secrets reach that flavor."
    group = "verification"
    dependsOn("assemblePlayDebug")

    doLast {
        val secrets = diagnosticsSecrets()
        val missingSecrets = secrets.filter { (_, value) -> value.isBlank() }.map { (name, _) -> name }
        if (missingSecrets.isNotEmpty()) {
            val allowMissingSecrets =
                providers.gradleProperty("allowMissingDiagnosticsSecrets").orNull.toBoolean()
            check(allowMissingSecrets) {
                "Diagnostics secrets are required for Play verification; missing: ${missingSecrets.joinToString()}. " +
                    "Set -PallowMissingDiagnosticsSecrets=true only for fork/no-secret builds."
            }
            logger.lifecycle("No diagnostics secrets configured; Play debug APK secret scan explicitly skipped.")
            return@doLast
        }

        val apkDir = layout.buildDirectory.dir("outputs/apk/play/debug").get().asFile
        val apk = apkDir.listFiles()
            ?.singleOrNull { file -> file.extension == "apk" }
            ?: error("Expected exactly one Play debug APK in ${apkDir.absolutePath}")
        val matches = scanApkForDiagnosticsSecrets(apk, secrets).map { it.substringBefore(" in ") }.toSet()
        val missing = secrets.map { (name, _) -> name }.filterNot { it in matches }
        check(missing.isEmpty()) {
            "Play debug APK is missing configured diagnostics values for: ${missing.joinToString()}"
        }
    }
}

// Sentry Gradle plugin uploads R8 mapping files to GlitchTip on release
// builds, enabling deobfuscated stack traces. Disabled when DSN or auth
// token are unset (forks without secrets, F-Droid, debug-only iteration).
sentry {
    val dsn = readSecret("SENTRY_DSN")
    val token = readSecret("SENTRY_AUTH_TOKEN")
    val canUpload = dsn.isNotBlank() && token.isNotBlank()

    autoUploadProguardMapping.set(canUpload)
    includeProguardMapping.set(canUpload)
    autoUploadNativeSymbols.set(false)
    uploadNativeSymbols.set(false)
    telemetry.set(false) // self-hosted GlitchTip — don't phone home to Sentry SaaS
    autoInstallation { enabled.set(false) } // SDK deps managed manually via libs.versions.toml
    tracingInstrumentation { enabled.set(false) }

    if (canUpload) {
        url.set(deriveUploadUrl(dsn))
        authToken.set(token)
        // Defaults match the maintainer's GlitchTip instance. Forks can
        // override via local.properties or env (SENTRY_ORG, SENTRY_PROJECT).
        // Note: GlitchTip locks the org slug to the first user's account
        // name, so renaming the org in the UI does not change the slug.
        org.set(readSecret("SENTRY_ORG").ifBlank { "kofikodr" })
        projectName.set(readSecret("SENTRY_PROJECT").ifBlank { "kofipod-android" })
    }
}

fun deriveUploadUrl(dsn: String): String {
    val withoutScheme = dsn.substringAfter("://")
    val host = withoutScheme.substringAfter("@").substringBefore("/")
    return "https://$host"
}

tasks.register("bumpVersion") {
    description = "Bump version.properties (VERSION_CODE +1, VERSION_NAME per -Ptype=patch|minor|major)"
    group = "release"
    doLast {
        val type = (project.findProperty("type") as? String) ?: "patch"
        val file = rootProject.file("version.properties")
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val oldName = props.getProperty("VERSION_NAME")
            ?: error("VERSION_NAME missing in version.properties")
        val oldCode = (props.getProperty("VERSION_CODE")
            ?: error("VERSION_CODE missing in version.properties")).toInt()
        val parts = oldName.split(".").map { it.toInt() }.toMutableList()
        require(parts.size == 3) {
            "VERSION_NAME must be semver MAJOR.MINOR.PATCH (was: $oldName)"
        }
        when (type) {
            "major" -> { parts[0] += 1; parts[1] = 0; parts[2] = 0 }
            "minor" -> { parts[1] += 1; parts[2] = 0 }
            "patch" -> { parts[2] += 1 }
            else -> error("Unknown -Ptype=$type, expected patch|minor|major")
        }
        val newName = parts.joinToString(".")
        val newCode = oldCode + 1
        file.writeText("VERSION_NAME=$newName\nVERSION_CODE=$newCode\n")
        println("Bumped $oldName ($oldCode) → $newName ($newCode)")
    }
}
