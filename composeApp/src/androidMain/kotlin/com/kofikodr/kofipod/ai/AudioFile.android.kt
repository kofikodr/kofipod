// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import io.ktor.util.cio.readChannel
import io.ktor.utils.io.ByteReadChannel
import java.io.File

internal actual fun openLocalFileChannel(path: String): ByteReadChannel = File(path).readChannel()

internal actual fun audioFallbackSupported(): Boolean = true
