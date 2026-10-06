package com.posecam.core.cloud

/**
 * Supplies the bearer token attached to backend API calls. This is the single seam for
 * device authentication: swapping [NoAuthProvider] for a real provider (e.g. a
 * `DeviceAuthProvider` that signs in the device and refreshes tokens) requires no change to
 * the upload worker, the repository or any recording code -- [CloudApiClient] simply adds
 * `Authorization: Bearer <token>` whenever a token is returned.
 *
 * The token is only ever sent to the backend API. It is never sent to S3: presigned URLs
 * carry their own signature and an extra Authorization header would invalidate them.
 */
interface AuthProvider {
    suspend fun getToken(): String?
}
