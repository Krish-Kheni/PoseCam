package com.posecam.core.cloud

import android.util.Log

/**
 * Consistent `event=<name> key=value` log lines for everything cloud-related. Callers pass
 * identifiers and counters only -- never presigned URLs, tokens or other secrets.
 */
object CloudLog {
    private const val TAG = "PoseCamCloud"

    fun i(event: String, vararg fields: Pair<String, Any?>) = Log.i(TAG, format(event, fields))

    fun w(event: String, vararg fields: Pair<String, Any?>) = Log.w(TAG, format(event, fields))

    fun e(event: String, error: Throwable? = null, vararg fields: Pair<String, Any?>) {
        val line = format(event, fields)
        if (error != null) Log.e(TAG, line, error) else Log.e(TAG, line)
    }

    internal fun format(event: String, fields: Array<out Pair<String, Any?>>): String =
        buildString {
            append("event=").append(event)
            fields.forEach { (key, value) -> append(' ').append(key).append('=').append(value ?: "null") }
        }
}
