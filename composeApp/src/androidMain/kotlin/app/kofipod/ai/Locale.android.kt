// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import java.util.Locale

internal actual fun currentLocaleTag(): String = Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() } ?: "en-US"
