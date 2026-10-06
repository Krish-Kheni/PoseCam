package com.posecam.core.sync

import org.json.JSONObject
import java.io.File

/**
 * The few manifest facts cloud sync needs. PoseCam writes `"complete": true/false` (false while a
 * take is running and forever after a crash), plus `session_id`, `start_wall_time_utc` and `frame_count`.
 * Parsed with org.json, not regexes (the Recordings list's regexes are brittle; this must not be).
 */
data class SessionManifestInfo(
    val sessionId: String,
    val complete: Boolean,
    val startWallTimeUtc: String?,
    val frameCount: Long,
) {
    /**
     * What the backend is told. A killed take is "incomplete": uploaded as evidence, never as a
     * deliverable (PoseCam's own `pull_captures.sh` treats it the same way).
     */
    val recordingStatus: String
        get() = if (complete) CloudFileRules.STATUS_COMPLETE else CloudFileRules.STATUS_INCOMPLETE

    companion object {
        /** Null when the manifest is missing or unreadable: such a folder is not (yet) a session. */
        fun read(directory: File): SessionManifestInfo? {
            val manifest = File(directory, "manifest.json")
            if (!manifest.isFile) return null
            return runCatching {
                val json = JSONObject(manifest.readText(Charsets.UTF_8))
                SessionManifestInfo(
                    sessionId = json.optString("session_id").takeIf { it.isNotEmpty() } ?: directory.name,
                    complete = json.optBoolean("complete", false),
                    startWallTimeUtc = json.optString("start_wall_time_utc").takeIf { it.isNotEmpty() && it != "null" },
                    frameCount = json.optLong("frame_count", 0),
                )
            }.getOrNull()
        }
    }
}
