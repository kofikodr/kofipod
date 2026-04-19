// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.auth

data class SignInResult(
    val idToken: String,
    val email: String,
    val displayName: String,
)

sealed class SignInError(message: String) : Exception(message) {
    object NotConfigured : SignInError("Sign-in is not configured (missing GOOGLE_SERVER_CLIENT_ID).")
    class Failed(override val message: String) : SignInError(message)
    object Cancelled : SignInError("Sign-in cancelled.")
}

/**
 * Token + granted scopes returned by an authorization flow. Used to call Drive REST.
 * `accessToken` is short-lived (~1h); callers must handle expiry by re-authorizing.
 */
data class DriveAuth(
    val accessToken: String,
    val grantedScopes: List<String>,
)

sealed class AuthorizationError(message: String) : Exception(message) {
    object NoHostActivity : AuthorizationError("No active Activity available to launch consent.")
    object Cancelled : AuthorizationError("Authorization cancelled.")
    class Failed(override val message: String) : AuthorizationError(message)
}

expect class AuthService {
    suspend fun signIn(serverClientId: String): SignInResult
    suspend fun authorizeDrive(serverClientId: String): DriveAuth
    suspend fun signOut()
}
