// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import app.kofipod.config.BuildKonfig
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb as SentryBreadcrumb

/**
 * Android-side crash reporter using Sentry KMP SDK. Compatible with
 * GlitchTip, which speaks the Sentry wire protocol — point the DSN at
 * a GlitchTip instance.
 *
 * If the DSN is empty (e.g. F-Droid build, fork without secrets),
 * [enable] is a permanent no-op.
 *
 * Sentry KMP 0.15.0 init does not need a Context — it auto-discovers
 * the Application context via ContentProvider on Android.
 */
actual class CrashReporter {

    private var enabled = false

    actual fun enable() {
        if (enabled) return
        if (BuildKonfig.SENTRY_DSN.isBlank()) return
        Sentry.init { options ->
            options.dsn = BuildKonfig.SENTRY_DSN
            options.release = BuildKonfig.VERSION_NAME
            options.environment = "release"
            options.attachStackTrace = true
            options.attachThreads = true
            options.attachScreenshot = false
            options.attachViewHierarchy = false
            options.beforeBreadcrumb = { crumb -> adapt(crumb) }
            options.beforeSend = { event ->
                event.message?.let { msg ->
                    msg.message = msg.message?.let(CrashReporterScrubber::scrubMessage)
                    msg.formatted = msg.formatted?.let(CrashReporterScrubber::scrubMessage)
                }
                val scrubbedExceptions = event.exceptions.map { ex ->
                    ex.copy(value = ex.value?.let(CrashReporterScrubber::scrubMessage))
                }
                event.exceptions.clear()
                event.exceptions.addAll(scrubbedExceptions)
                event
            }
        }
        enabled = true
    }

    actual fun disable() {
        if (!enabled) return
        Sentry.close()
        enabled = false
    }

    actual fun isEnabled(): Boolean = enabled

    private fun adapt(crumb: SentryBreadcrumb): SentryBreadcrumb? {
        val rawData = crumb.getData()?.mapValues { (_, v) -> v.toString() }.orEmpty()
        val pure = Breadcrumb(
            category = crumb.category.orEmpty(),
            message = crumb.message.orEmpty(),
            data = rawData,
        )
        val scrubbed = CrashReporterScrubber.scrubBreadcrumb(pure) ?: return null
        crumb.message = scrubbed.message
        scrubbed.data.forEach { (k, v) -> crumb.setData(k, v) }
        return crumb
    }
}
