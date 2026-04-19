// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

actual class Scheduler {
    actual fun enable(wifiOnly: Boolean) { /* TODO BGTaskScheduler */ }

    actual fun disable() {}
}
