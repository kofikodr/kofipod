// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

internal actual fun currentLocaleTag(): String {
    val locale = NSLocale.currentLocale
    val language = locale.languageCode?.takeIf { it.isNotBlank() } ?: return "en-US"
    val region = locale.countryCode?.takeIf { it.isNotBlank() }
    return if (region == null) language else "$language-$region"
}
