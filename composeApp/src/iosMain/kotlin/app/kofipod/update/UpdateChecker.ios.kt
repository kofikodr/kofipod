// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.update

/**
 * iOS doesn't support sideloaded APK updates. The actual is a no-op so common
 * code stays platform-agnostic without the iOS target failing to link.
 */
actual class UpdateChecker {
    actual suspend fun check(force: Boolean): UpdateInfo? = null
}
