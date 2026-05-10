// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

/**
 * iOS production binding for [PkmExportScheduler]. No-op — iOS has no
 * WorkManager equivalent, and PKM exports on iOS would require a separate
 * `BGProcessingTask` registration. The Android target carries Slice 6;
 * iOS retry is intentionally user-driven (re-tap Export) until iOS becomes
 * a primary target.
 */
class IosPkmExportScheduler : PkmExportScheduler {
    override fun enqueue() {
        // No-op on iOS.
    }
}
