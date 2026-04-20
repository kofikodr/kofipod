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
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.androidx.media3.exoplayer)
                implementation(libs.androidx.media3.session)
                implementation(libs.androidx.media3.datasource)
                implementation(libs.androidx.media3.database)
                implementation(libs.androidx.work.runtime)
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
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Kofipoddbg"
        }
        release {
            isMinifyEnabled = false
            // TODO(release): enable R8 with proguard-rules.pro after sideload-verifying a minified build
            signingConfig = signingConfigs.findByName("release")
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
        buildConfigField(STRING, "USER_AGENT", "Kofipod/$appVersionName (github.com/ebernie/kofipod)")
        buildConfigField(STRING, "VERSION_NAME", appVersionName)
    }
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
