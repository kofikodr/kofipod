// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.auth

import android.content.IntentSender
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges Google Identity's PendingIntent-based authorization flow to a
 * coroutine API. A [ComponentActivity] hosts an [ActivityResultLauncher] and
 * registers itself here on create; [AuthService] uses [launch] to resume a
 * deferred with the user's consent result.
 *
 * Only one authorization flow can be in-flight at a time, which matches the
 * UX (the user is tapping a single switch).
 */
object AuthBridge {

    private var host: Host? = null
    private var pending: CompletableDeferred<ActivityResult>? = null

    fun attach(activity: ComponentActivity) {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            pending?.complete(result)
            pending = null
        }
        host = Host(activity, launcher)
    }

    fun detach(activity: ComponentActivity) {
        if (host?.activity === activity) {
            pending?.cancel()
            pending = null
            host = null
        }
    }

    /**
     * Launches [intentSender] on the host activity and suspends until the
     * result comes back. Throws if no host is attached.
     */
    suspend fun launch(intentSender: IntentSender): ActivityResult {
        val h = host ?: throw AuthorizationError.NoHostActivity
        val deferred = CompletableDeferred<ActivityResult>()
        pending = deferred
        h.launcher.launch(IntentSenderRequest.Builder(intentSender).build())
        return deferred.await()
    }

    private class Host(
        val activity: ComponentActivity,
        val launcher: ActivityResultLauncher<IntentSenderRequest>,
    )
}
