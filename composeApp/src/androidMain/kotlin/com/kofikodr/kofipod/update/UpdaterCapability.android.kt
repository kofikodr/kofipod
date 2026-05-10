// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.update

import com.kofikodr.kofipod.BuildConfig

actual object UpdaterCapability {
    actual val enabled: Boolean = BuildConfig.UPDATER_ENABLED
}
