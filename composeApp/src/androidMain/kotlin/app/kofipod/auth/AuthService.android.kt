// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OAuth 2 scope for Drive's hidden per-app folder (`appDataFolder`). */
private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

actual class AuthService(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)

    actual suspend fun signIn(serverClientId: String): SignInResult {
        if (serverClientId.isBlank()) throw SignInError.NotConfigured
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = try {
            credentialManager.getCredential(context = context, request = request)
        } catch (e: GetCredentialCancellationException) {
            throw SignInError.Cancelled
        } catch (e: GetCredentialException) {
            throw SignInError.Failed(e.message ?: e::class.simpleName ?: "Sign-in failed")
        }
        val cred = response.credential as? GoogleIdTokenCredential
            ?: throw SignInError.Failed("Unexpected credential type: ${response.credential::class.simpleName}")
        return SignInResult(
            idToken = cred.idToken,
            email = cred.id,
            displayName = cred.displayName ?: cred.id,
        )
    }

    actual suspend fun authorizeDrive(serverClientId: String): DriveAuth {
        if (serverClientId.isBlank()) throw SignInError.NotConfigured
        val client = Identity.getAuthorizationClient(context)
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .requestOfflineAccess(serverClientId)
            .build()

        val result: AuthorizationResult = try {
            awaitAuthorization(client, request)
        } catch (e: ApiException) {
            throw AuthorizationError.Failed(
                "Drive authorization failed: ${e.statusCode} ${e.localizedMessage ?: ""}".trim(),
            )
        }

        if (result.hasResolution()) {
            val intentSender = result.pendingIntent?.intentSender
                ?: throw AuthorizationError.Failed("Missing resolution pending intent.")
            val activityResult = AuthBridge.launch(intentSender)
            if (activityResult.resultCode != Activity.RESULT_OK) {
                throw AuthorizationError.Cancelled
            }
            val data = activityResult.data
                ?: throw AuthorizationError.Failed("Empty authorization result.")
            val resolved = try {
                client.getAuthorizationResultFromIntent(data)
            } catch (e: ApiException) {
                throw AuthorizationError.Failed(
                    "Authorization parse failed: ${e.statusCode} ${e.localizedMessage ?: ""}".trim(),
                )
            }
            return resolved.toDriveAuth()
        }
        return result.toDriveAuth()
    }

    actual suspend fun signOut() {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }

    private suspend fun awaitAuthorization(
        client: com.google.android.gms.auth.api.identity.AuthorizationClient,
        request: AuthorizationRequest,
    ): AuthorizationResult = suspendCancellableCoroutine { cont ->
        client.authorize(request)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun AuthorizationResult.toDriveAuth(): DriveAuth {
        val token = accessToken
            ?: throw AuthorizationError.Failed("Authorization returned no access token.")
        return DriveAuth(
            accessToken = token,
            grantedScopes = grantedScopes,
        )
    }
}
