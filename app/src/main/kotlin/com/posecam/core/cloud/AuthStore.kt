package com.posecam.core.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A signed-in collector: who they are and the bearer token that speaks for them. */
data class AuthSession(
    val email: String,
    val name: String?,
    val token: String,
    /** Epoch millis after which the backend refuses [token]. */
    val expiresAtMs: Long,
)

/** What the app knows about the collector. [session] is null when nobody is signed in (or the token died). */
data class AuthState(
    val session: AuthSession?,
    /** The last email used on this phone, kept after sign-out or an expired token to prefill the sign-in form. */
    val lastEmail: String?,
) {
    val isSignedIn: Boolean get() = session != null

    /** True once someone has signed in on this install: from then on recording never waits for a sign-in screen. */
    val hasEverSignedIn: Boolean get() = session != null || lastEmail != null
}

/**
 * Where the token lives: the app's private SharedPreferences (no backup: `allowBackup=false`). The password is never
 * stored; only the token the backend issued for it. Signing out forgets the token but not the recordings or their
 * upload queue, which belong to the phone, not to the session.
 */
class AuthStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())

    val state: StateFlow<AuthState> = _state.asStateFlow()

    val isSignedIn: Boolean get() = current().isSignedIn

    fun token(): String? = current().session?.token

    /** Re-evaluates expiry on every read, so a token that ran out while the app sat idle is never sent. */
    fun current(): AuthState {
        val state = _state.value
        val session = state.session
        if (session != null && session.expiresAtMs <= clock()) {
            _state.value = state.copy(session = null)
        }
        return _state.value
    }

    @Synchronized
    fun signIn(session: AuthSession) {
        prefs.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_NAME, session.name)
            .putLong(KEY_EXPIRES, session.expiresAtMs)
            .apply()
        _state.value = AuthState(session, session.email)
    }

    /** The collector chose to sign out, or the backend refused the token: the token goes, the email stays for prefill. */
    @Synchronized
    fun signOut() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_NAME).remove(KEY_EXPIRES).apply()
        _state.value = AuthState(null, _state.value.lastEmail)
    }

    private fun load(): AuthState {
        val email = prefs.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        val expires = prefs.getLong(KEY_EXPIRES, 0)
        val session = if (email != null && token != null && expires > clock()) {
            AuthSession(email, prefs.getString(KEY_NAME, null), token, expires)
        } else {
            null
        }
        return AuthState(session, email)
    }

    private companion object {
        const val PREFS_NAME = "posecam_auth"
        const val KEY_TOKEN = "token"
        const val KEY_EMAIL = "email"
        const val KEY_NAME = "name"
        const val KEY_EXPIRES = "expires_at_ms"
    }
}
