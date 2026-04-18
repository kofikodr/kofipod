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

expect class AuthService {
    suspend fun signIn(serverClientId: String): SignInResult
    suspend fun signOut()
}
