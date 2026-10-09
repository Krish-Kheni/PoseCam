package com.posecam.core.cloud

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Executes the call without blocking a thread and cancels it if the coroutine is cancelled. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        },
    )
}

/**
 * Runs a control-plane call and returns its JSON body. Network trouble becomes [CloudNetworkException]; an error status
 * becomes [CloudHttpException] carrying the server's `{"error": {"code", "message", "details"}}`. Shared by every client
 * of the labeling server so they all fail the same way.
 */
internal suspend fun OkHttpClient.executeJson(request: Request): JSONObject {
    val response = try {
        newCall(request).await()
    } catch (error: IOException) {
        throw CloudNetworkException(error)
    }
    response.use {
        val text = try {
            it.body?.string().orEmpty()
        } catch (error: IOException) {
            throw CloudNetworkException(error)
        }
        if (!it.isSuccessful) throw parseCloudError(it.code, text)
        return try {
            JSONObject(text)
        } catch (error: JSONException) {
            throw CloudHttpException(502, "MALFORMED_RESPONSE", "response is not JSON")
        }
    }
}

internal fun parseCloudError(status: Int, text: String): CloudHttpException {
    val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
    return CloudHttpException(
        status = status,
        code = error?.optStringOrNull("code"),
        reason = error?.optStringOrNull("message") ?: "request failed",
        details = error?.optJSONObject("details")?.toString(),
    )
}

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
