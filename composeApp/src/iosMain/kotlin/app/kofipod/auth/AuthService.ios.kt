// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.auth

actual class AuthService {
    actual suspend fun signIn(serverClientId: String): SignInResult {
        throw SignInError.NotConfigured
    }

    actual suspend fun authorizeDrive(serverClientId: String): DriveAuth {
        throw AuthorizationError.Failed("Drive authorization is not implemented on iOS yet.")
    }

    actual suspend fun signOut() {}
}
