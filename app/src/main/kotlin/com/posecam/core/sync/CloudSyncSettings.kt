package com.posecam.core.sync

import android.content.Context
import androidx.work.NetworkType

/**
 * When uploads may use the network. Uploads can be multiple GB, so the default is unmetered
 * (Wi-Fi) only; the other policies exist so the setting can be exposed later without touching
 * the upload code.
 */
enum class SyncPolicy(val networkType: NetworkType?) {
    /** Default: only on unmetered networks. */
    WIFI_ONLY(NetworkType.UNMETERED),

    /** Any connected network, including mobile data. */
    ANY_NETWORK(NetworkType.CONNECTED),

    /** Nothing is scheduled automatically; only an explicit "Sync now" uploads. */
    MANUAL_ONLY(null),
}

/** Immutable view of [CloudSyncSettings] for the UI. */
data class CloudSettingsSnapshot(
    val policy: SyncPolicy,
    val retentionDays: Int,
    val autoCleanupEnabled: Boolean,
    /** Ask before a manual upload starts on a metered (mobile) network. */
    val confirmMobileData: Boolean = true,
)

/** Persistent cloud-sync preferences. */
class CloudSyncSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var policy: SyncPolicy
        get() = prefs.getString(KEY_POLICY, null)
            ?.let { name -> SyncPolicy.entries.firstOrNull { it.name == name } }
            ?: SyncPolicy.WIFI_ONLY
        set(value) = prefs.edit().putString(KEY_POLICY, value.name).apply()

    /** How long a fully synced recording stays on the phone before cleanup may remove it. */
    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
        set(value) = prefs.edit().putInt(KEY_RETENTION_DAYS, value.coerceAtLeast(0)).apply()

    /** Master switch for removing local copies of verified, synced sessions. */
    var autoCleanupEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEANUP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEANUP, value).apply()

    var confirmMobileData: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_MOBILE, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_MOBILE, value).apply()

    /** The Android 13+ notification prompt is shown once, ever; refusing it must not make the app ask again. */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, value).apply()

    fun snapshot() = CloudSettingsSnapshot(policy, retentionDays, autoCleanupEnabled, confirmMobileData)

    companion object {
        const val DEFAULT_RETENTION_DAYS = 7
        /** The choices offered in Settings. */
        val RETENTION_CHOICES_DAYS = listOf(1, 3, 7, 14, 30)
        private const val PREFS_NAME = "posecam_cloud"
        private const val KEY_POLICY = "sync_policy"
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_AUTO_CLEANUP = "auto_cleanup"
        private const val KEY_CONFIRM_MOBILE = "confirm_mobile_data"
        private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
    }
}

/** SharedPreferences-backed [AnnouncementStore]: survives the process dying between worker runs. */
class PrefsAnnouncementStore(context: Context) : AnnouncementStore {
    private val prefs = context.applicationContext.getSharedPreferences("posecam_cloud_announce", Context.MODE_PRIVATE)

    override var unannouncedSynced: Int
        get() = prefs.getInt("unannounced_synced", 0)
        set(value) = prefs.edit().putInt("unannounced_synced", value).apply()

    override var notifiedFailed: Int
        get() = prefs.getInt("notified_failed", 0)
        set(value) = prefs.edit().putInt("notified_failed", value).apply()

    override var stallSinceMs: Long?
        get() = prefs.getLong("stall_since", -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().apply { if (value == null) remove("stall_since") else putLong("stall_since", value) }.apply()

    override var stalledNotified: Boolean
        get() = prefs.getBoolean("stalled_notified", false)
        set(value) = prefs.edit().putBoolean("stalled_notified", value).apply()
}
