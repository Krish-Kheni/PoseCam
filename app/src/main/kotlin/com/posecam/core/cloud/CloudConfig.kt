package com.posecam.core.cloud

import com.posecam.BuildConfig

/**
 * The only place the backend location lives. [baseUrl] comes from the per-build-type
 * `CLOUD_BASE_URL` BuildConfig field (see app/build.gradle.kts); blank means cloud sync is
 * disabled and the app behaves exactly as an offline-only recorder.
 */
data class CloudConfig(
    val baseUrl: String,
    val apiConnectTimeoutSeconds: Long = 15,
    val apiReadTimeoutSeconds: Long = 60,
    val apiWriteTimeoutSeconds: Long = 60,
    /** S3 PUTs carry one multipart part or a file below the multipart threshold, on possibly slow Wi-Fi. */
    val transferTimeoutSeconds: Long = 180,
    /** Debug builds may talk to a local http:// dev server; release builds only ever use https. */
    val allowCleartext: Boolean = BuildConfig.DEBUG,
) {
    val enabled: Boolean get() = baseUrl.isNotBlank() && (allowCleartext || baseUrl.trim().startsWith("https://"))

    val normalizedBaseUrl: String get() = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }

    companion object {
        fun fromBuildConfig(): CloudConfig = CloudConfig(baseUrl = BuildConfig.CLOUD_BASE_URL)
    }
}
