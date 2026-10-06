package com.posecam.core.cloud

import android.content.Context
import java.util.UUID

/**
 * A random id generated on first launch and persisted locally. It lets the backend group
 * sessions per installation for observability/auditing. It is NOT a secret and NOT
 * authentication -- anyone can send any value.
 */
class InstallationId(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(): String {
        prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ID, created).apply()
        return created
    }

    private companion object {
        const val PREFS_NAME = "posecam_cloud"
        const val KEY_ID = "installation_id"
    }
}
