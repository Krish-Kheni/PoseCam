package com.posecam.core.cloud

/**
 * Temporary: the backend currently runs with `AUTH_MODE=none`, so no credential is sent.
 * The installation id ([InstallationId]) is an identifier for observability only and must
 * never be treated as authentication.
 */
class NoAuthProvider : AuthProvider {
    override suspend fun getToken(): String? = null
}
