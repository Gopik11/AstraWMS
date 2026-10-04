package com.astrawms.mobile.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.astrawms.mobile.SettingsStore
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.connectivity.ConnectionBuilder
import org.json.JSONArray
import org.json.JSONObject

/** Who is signed in, from the access token (ADR-0010/0012). The services validate the token; this is for the UI. */
data class Session(
    val userName: String,
    val tenant: String,
    val roles: Set<String>,
    /** null = all sites (`*`). */
    val sites: List<String>?,
) {
    fun hasRole(vararg any: String) = any.any { it in roles }
}

/**
 * Sign-in with the identity provider (Keycloak realm `astrawms`, public client `astra-mobile`): OpenID Connect
 * authorization code flow with PKCE in a browser tab (AppAuth), as the web UI does (ADR-0013); passkeys work there too
 * (ADR-0023). The AuthState (refresh token included) is kept in encrypted preferences, so the operator signs in once
 * per shift; access tokens are refreshed before they expire.
 */
class AuthManager(private val context: Context, private val settings: SettingsStore) {

    private val prefs = EncryptedSharedPreferences.create(
        "astra_auth",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private var authState: AuthState = prefs.getString(STATE, null)
        ?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() } ?: AuthState()

    private val sessionState = MutableStateFlow(sessionOf(authState))
    val session: StateFlow<Session?> = sessionState.asStateFlow()

    /** Plain HTTP is accepted for local and test gateways (debug builds allow cleartext traffic only there). */
    private val connections = ConnectionBuilder { uri ->
        (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = false
        }
    }

    private val service by lazy {
        AuthorizationService(context, AppAuthConfiguration.Builder().setConnectionBuilder(connections).build())
    }

    /** The issuer: the setting, else the authority the gateway gives the web UI in /config.json. */
    suspend fun issuer(): String = withContext(Dispatchers.IO) {
        val s = settings.current
        if (s.issuerOverride.isNotBlank()) return@withContext s.issuerOverride
        require(s.gatewayUrl.isNotBlank()) { "Set the gateway URL first" }
        val conn = URL(s.gatewayUrl.trimEnd('/') + "/config.json").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text).getString("authority").trimEnd('/')
        } finally {
            conn.disconnect()
        }
    }

    /** The browser intent that signs the operator in; its result goes to [completeSignIn]. */
    suspend fun signInIntent(): Intent {
        val issuer = issuer()
        val config = suspendCoroutine { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(issuer), { cfg, ex ->
                if (cfg != null) cont.resume(cfg) else cont.resumeWithException(ex ?: IllegalStateException("No OIDC configuration at $issuer"))
            }, connections)
        }
        authState = AuthState(config)
        val request = AuthorizationRequest.Builder(config, settings.current.clientId, ResponseTypeValues.CODE, Uri.parse(REDIRECT))
            .setScope("openid")
            .setPrompt("login")
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Exchanges the authorization code for tokens (PKCE verifier included by AppAuth). */
    suspend fun completeSignIn(data: Intent?) {
        val response = data?.let { AuthorizationResponse.fromIntent(it) }
        val error = data?.let { AuthorizationException.fromIntent(it) }
        authState.update(response, error)
        if (response == null) throw error ?: IllegalStateException("Sign-in was cancelled")
        suspendCoroutine { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, ex ->
                authState.update(tokens, ex)
                if (tokens != null) cont.resume(Unit) else cont.resumeWithException(ex ?: IllegalStateException("No tokens"))
            }
        }
        persist()
    }

    /** A valid access token, refreshed when needed; null when not signed in or the refresh token is gone. */
    suspend fun accessToken(): String? {
        if (!authState.isAuthorized) return null
        return try {
            suspendCoroutine { cont ->
                authState.performActionWithFreshTokens(service) { token, _, ex ->
                    if (ex != null) cont.resumeWithException(ex) else cont.resume(token)
                }
            }.also { persist() }
        } catch (e: AuthorizationException) {
            if (e.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR) signOutLocally()   // refresh token expired
            null
        }
    }

    /** The browser intent that ends the identity provider session, or null when that is not possible. */
    fun signOutIntent(): Intent? {
        val config = authState.authorizationServiceConfiguration
        val idToken = authState.idToken
        signOutLocally()
        if (config?.endSessionEndpoint == null || idToken == null) return null
        val request = EndSessionRequest.Builder(config).setIdTokenHint(idToken)
            .setPostLogoutRedirectUri(Uri.parse(REDIRECT)).build()
        return service.getEndSessionRequestIntent(request)
    }

    fun signOutLocally() {
        authState = AuthState()
        prefs.edit().remove(STATE).apply()
        sessionState.value = null
    }

    private fun persist() {
        prefs.edit().putString(STATE, authState.jsonSerializeString()).apply()
        sessionState.value = sessionOf(authState)
    }

    companion object {
        const val REDIRECT = "com.astrawms.mobile:/oauth2redirect"
        private const val STATE = "auth_state"
        private val ROLES = setOf("RECEIVER", "PICKER", "INV_ANALYST", "INV_MANAGER", "SUPERVISOR", "QA_MANAGER",
            "SOLUTION_ADMIN")

        fun sessionOf(state: AuthState): Session? {
            val token = state.accessToken ?: return null
            return runCatching {
                val payload = token.split(".")[1]
                val c = JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
                val roles = c.optJSONObject("realm_access")?.optJSONArray("roles")?.strings().orEmpty()
                    .filter { it in ROLES }.toSet()
                val sites = when (val v = c.opt("wms_sites")) {
                    is JSONArray -> v.strings()
                    is String -> v.split(",")
                    else -> emptyList()
                }.map { it.trim() }.filter { it.isNotEmpty() }
                Session(
                    userName = c.optString("preferred_username", c.optString("sub")),
                    tenant = c.optString("tenant_id"),
                    roles = roles,
                    sites = if ("*" in sites) null else sites,
                )
            }.getOrNull()
        }

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    }
}
