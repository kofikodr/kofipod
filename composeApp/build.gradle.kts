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
                implementation(libs.androidx.work.runtime)
                implementation(libs.androidx.credentials)
                implementation(libs.androidx.credentials.play.services.auth)
                implementation(libs.google.identity)
                implementation(libs.play.services.auth)
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
            isMinifyEnabled = false
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
        buildConfigField(STRING, "GOOGLE_SERVER_CLIENT_ID", readSecret("GOOGLE_SERVER_CLIENT_ID"))
    }
}
