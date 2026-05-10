import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.BOOLEAN
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.INT
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
    }
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
    namespace = "app.kofipod"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.kofipod"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["appLabel"] = "Kofipod"
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            // play flavor is the revenue product; no applicationIdSuffix so it
            // matches what's uploaded to Play Console.
            manifestPlaceholders["appLabel"] = "Kofipod"
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

sqldelight {
    databases {
        create("KofipodDatabase") {
            packageName.set("app.kofipod.db")
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

buildkonfig {
    packageName = "app.kofipod.config"
    defaultConfigs {
        buildConfigField(STRING, "PODCAST_INDEX_KEY", readSecret("PODCAST_INDEX_KEY"))
        buildConfigField(STRING, "PODCAST_INDEX_SECRET", readSecret("PODCAST_INDEX_SECRET"))
        buildConfigField(STRING, "USER_AGENT", "Kofipod/$appVersionName (github.com/kofikodr/kofipod)")
        buildConfigField(STRING, "VERSION_NAME", appVersionName)
        buildConfigField(INT, "VERSION_CODE", appVersionCode.toString())
        buildConfigField(STRING, "SENTRY_DSN", readSecret("SENTRY_DSN"))
        buildConfigField(STRING, "APTABASE_APP_KEY", readSecret("APTABASE_APP_KEY"))
        // Sideload-update channel (GitHub Releases). Default true for the GitHub
        // build; flip to "false" via local.properties or env when packaging the
        // Play Store flavor (Play forbids self-updaters).
        buildConfigField(BOOLEAN, "UPDATER_ENABLED", readSecret("UPDATER_ENABLED").ifBlank { "true" })
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
