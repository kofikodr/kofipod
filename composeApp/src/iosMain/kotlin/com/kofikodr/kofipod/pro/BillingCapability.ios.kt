// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

actual object BillingCapability {
    // iOS has no billing integration today.
    actual val restoreEnabled: Boolean = false
}
