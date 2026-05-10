// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.config

actual object AppInfo {
    actual val versionName: String = BuildKonfig.VERSION_NAME
    actual val versionCode: Int = BuildKonfig.VERSION_CODE
    actual val isDebugBuild: Boolean = false
}
