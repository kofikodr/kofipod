plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.buildkonfig) apply false
}

tasks.register<Exec>("installGitHooks") {
    description = "Point git at scripts/git-hooks/ so pre-commit runs ktlintFormat + detekt."
    group = "git hooks"
    commandLine("git", "config", "core.hooksPath", "scripts/git-hooks")
}
