// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

actual class AuthService(private val context: Context) {
    private val manager = CredentialManager.create(context)

    actual suspend fun signIn(serverClientId: String): SignInResult {
        if (serverClientId.isBlank()) throw SignInError.NotConfigured
        val option =
            GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(false)
                .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response =
            try {
                manager.getCredential(context = context, request = request)
            } catch (e: GetCredentialCancellationException) {
                throw SignInError.Cancelled
            } catch (e: GetCredentialException) {
                throw SignInError.Failed(e.message ?: e::class.simpleName ?: "Sign-in failed")
            }
        val cred =
            response.credential as? GoogleIdTokenCredential
                ?: throw SignInError.Failed("Unexpected credential type: ${response.credential::class.simpleName}")
        return SignInResult(
            idToken = cred.idToken,
            email = cred.id,
            displayName = cred.displayName ?: cred.id,
        )
    }

    actual suspend fun signOut() {
        manager.clearCredentialState(ClearCredentialStateRequest())
    }
}
