// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

/**
 * Lazy-initialized crash-reporting facade. Implementations should not
 * initialize the underlying SDK in their constructor — only on first
 * [enable].
 *
 * Modelled as an interface (rather than expect class) so unit tests can
 * substitute [NoOpCrashReporter].
 */
interface CrashReporter {
    fun enable()

    fun disable()

    fun isEnabled(): Boolean
}

object NoOpCrashReporter : CrashReporter {
    override fun enable() = Unit

    override fun disable() = Unit

    override fun isEnabled(): Boolean = false
}
