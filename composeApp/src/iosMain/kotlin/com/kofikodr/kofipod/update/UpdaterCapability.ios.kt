// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.update

actual object UpdaterCapability {
    // iOS has no sideload-APK install path; App Store handles updates.
    actual val enabled: Boolean = false
}
