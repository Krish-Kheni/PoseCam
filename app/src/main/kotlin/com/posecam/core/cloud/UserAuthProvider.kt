package com.posecam.core.cloud

/** Sends the signed-in collector's token; once the backend refuses it, forgets it so the app asks for a sign-in. */
class UserAuthProvider(private val store: AuthStore) : AuthProvider {
    override suspend fun getToken(): String? = store.token()

    override fun onUnauthorized() = store.signOut()
}
