package com.akrishna87.mail

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** "Sign in with Microsoft" via OAuth 2.0 (PKCE) for IMAP/SMTP access. */
object MicrosoftAuth {
    private const val REDIRECT_URI = "com.akrishna87.mail://oauth2redirect"
    private const val SCOPES =
        "offline_access https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send"

    private val config = AuthorizationServiceConfiguration(
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"),
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token"),
    )

    val isConfigured: Boolean get() = BuildConfig.MS_CLIENT_ID.isNotBlank()

    fun signInIntent(service: AuthorizationService, email: String): Intent {
        val request = AuthorizationRequest.Builder(
            config,
            BuildConfig.MS_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI),
        )
            .setScope(SCOPES)
            .setLoginHint(email)
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Exchanges the browser redirect for tokens and returns the serialized auth state. */
    suspend fun completeSignIn(service: AuthorizationService, data: Intent): String {
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        if (response == null) {
            throw Exception(error?.errorDescription ?: error?.error ?: "Microsoft sign-in was cancelled")
        }
        val state = AuthState(response, error)
        return suspendCancellableCoroutine { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, ex ->
                state.update(tokens, ex)
                if (tokens != null) {
                    cont.resume(state.jsonSerializeString())
                } else {
                    cont.resumeWithException(Exception(ex?.errorDescription ?: "Microsoft sign-in failed"))
                }
            }
        }
    }

    /** Returns a valid access token, refreshing it (and saving the new state) when needed. */
    suspend fun freshToken(context: Context, store: AccountStore): String {
        val state = AuthState.jsonDeserialize(store.authState)
        val service = AuthorizationService(context)
        try {
            return suspendCancellableCoroutine { cont ->
                state.performActionWithFreshTokens(service) { token, _, ex ->
                    if (token != null) {
                        cont.resume(token)
                    } else {
                        cont.resumeWithException(
                            Exception(ex?.errorDescription ?: "Microsoft sign-in expired. Please sign in again."),
                        )
                    }
                }
            }
        } finally {
            store.authState = state.jsonSerializeString()
            service.dispose()
        }
    }
}
