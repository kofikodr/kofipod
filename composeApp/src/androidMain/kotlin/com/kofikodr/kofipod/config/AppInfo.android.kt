// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.config

import com.kofikodr.kofipod.BuildConfig

actual object AppInfo {
    actual val versionName: String = BuildConfig.VERSION_NAME
    actual val versionCode: Int = BuildConfig.VERSION_CODE
    actual val isDebugBuild: Boolean = BuildConfig.DEBUG
}
