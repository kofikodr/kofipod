// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.config

/**
 * Runtime app identity. On Android this reflects `versionNameSuffix` (so debug builds
 * naturally render as "0.3.0-debug"); on iOS it falls back to the raw value from
 * [BuildKonfig] since iOS builds don't apply Android-side suffixes.
 */
expect object AppInfo {
    val versionName: String
}
