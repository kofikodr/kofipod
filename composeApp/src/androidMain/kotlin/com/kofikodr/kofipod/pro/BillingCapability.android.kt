// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

import com.kofikodr.kofipod.BuildConfig

actual object BillingCapability {
    actual val restoreEnabled: Boolean = BuildConfig.BILLING_ENABLED
}
