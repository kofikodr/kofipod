// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.drive

import app.kofipod.auth.AuthService
import app.kofipod.auth.AuthorizationError
import app.kofipod.config.BuildKonfig
import app.kofipod.data.repo.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Produces a Drive access token for each Drive REST call.
 *
 * Behaviour:
 *  - Returns the cached token from [SettingsRepository] when present.
 *  - If no token is cached, triggers a silent re-authorize via [AuthService]
 *    (no consent UI shown if the user previously granted the scope).
 *  - Serializes concurrent refreshes with a mutex so parallel calls don't
 *    stampede the authorization endpoint.
 *
 * Forced refresh (after a 401) is done by calling [refresh].
 */
class DriveTokenProvider(
    private val auth: AuthService,
    private val settings: SettingsRepository,
    private val http: HttpClient,
) {
    private val mutex = Mutex()

    /** Returns a token, fetching one via silent authorize if not cached. */
    suspend fun get(): String {
        settings.getDriveAccessTokenNow()?.let { return it }
        return refresh()
    }

    /**
     * Forces a new token by calling [AuthService.authorizeDrive] again. Used
     * both for the initial fetch and for 401 recovery. When the user has
     * already granted the `drive.appdata` scope, this returns silently
     * without prompting.
     */
    suspend fun refresh(): String =
        mutex.withLock {
            val clientId = BuildKonfig.GOOGLE_SERVER_CLIENT_ID
            if (clientId.isBlank()) {
                throw AuthorizationError.Failed(
                    "GOOGLE_SERVER_CLIENT_ID is not configured.",
                )
            }
            val drive = auth.authorizeDrive(clientId)
            settings.setDriveAccessToken(drive.accessToken)
            drive.accessToken
        }

    /**
     * Revokes the current access token with Google's OAuth revocation
     * endpoint and clears it from local storage. Best-effort — if the
     * revocation call fails (network, token already invalid) we still wipe
     * the local copy so the user is signed out from this device.
     */
    suspend fun signOut() {
        val token = settings.getDriveAccessTokenNow()
        if (!token.isNullOrBlank()) {
            runCatching {
                http.post("https://oauth2.googleapis.com/revoke") {
                    parameter("token", token)
                }
            }
        }
        settings.setDriveAccessToken(null)
    }

    /** Clears the cached token without revoking (used for local-only resets). */
    fun clear() {
        settings.setDriveAccessToken(null)
    }
}
