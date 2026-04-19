// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.config

import app.kofipod.BuildConfig

actual object AppInfo {
    actual val versionName: String = BuildConfig.VERSION_NAME
}
