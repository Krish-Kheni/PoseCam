package com.posecam.core.cloud

/**
 * Supplies the bearer token attached to backend API calls. This is the single seam for
 * authentication: [UserAuthProvider] hands over the signed-in collector's token, and nothing else (the upload worker,
 * the repository, recording) knows about it -- [CloudApiClient] simply adds
 * `Authorization: Bearer <token>` whenever a token is returned.
 *
 * The token is only ever sent to the backend API. It is never sent to S3: presigned URLs
 * carry their own signature and an extra Authorization header would invalidate them.
 */
interface AuthProvider {
    suspend fun getToken(): String?

    /** The backend answered 401: the token is expired or revoked and must not be sent again. */
    fun onUnauthorized() = Unit
}
