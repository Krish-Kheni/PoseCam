package com.posecam.core.cloud

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The last upload history the server sent, kept so "My uploads" still has something to show with no signal. It is only
 * ever a copy: the server's count is the record, and a fresh answer replaces this one.
 */
class UploadStatsCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(stats: UploadStats, savedAtMs: Long) {
        prefs.edit().putString(KEY_STATS, encode(stats, savedAtMs)).apply()
    }

    /** The cached history of [email], or null if none (or it belongs to someone else who used this phone). */
    fun load(email: String): CachedStats? = prefs.getString(KEY_STATS, null)?.let(::decode)?.takeIf { it.stats.email == email }

    /** One day's list, kept per account and day so a day opened before still opens with no signal. */
    fun saveDay(email: String, date: String, recordings: List<UploadedRecording>) {
        val editor = prefs.edit().putString("day:$email:$date", encodeRecordings(recordings))
        // A hundred recordings a day add up: keep the most recent weeks, not every day ever opened.
        (prefs.all.keys.filter { it.startsWith("day:") } + "day:$email:$date").distinct()
            .sortedByDescending { it.substringAfterLast(':') }
            .drop(MAX_CACHED_DAYS)
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun loadDay(email: String, date: String): List<UploadedRecording>? =
        prefs.getString("day:$email:$date", null)?.let(::decodeRecordings)

    data class CachedStats(val stats: UploadStats, val savedAtMs: Long)

    internal companion object {
        private const val PREFS_NAME = "posecam_stats"
        private const val KEY_STATS = "stats"
        private const val MAX_CACHED_DAYS = 30

        fun encode(stats: UploadStats, savedAtMs: Long): String = JSONObject()
            .put("savedAtMs", savedAtMs)
            .put("email", stats.email)
            .put("totalRecordings", stats.totalRecordings)
            .put("uploadedToday", stats.uploadedToday)
            .put("firstUploadAt", stats.firstUploadAt ?: JSONObject.NULL)
            .put(
                "days",
                JSONArray().also { array ->
                    stats.days.forEach {
                        array.put(
                            JSONObject().put("date", it.date).put("recordings", it.recordings)
                                .put("pipes", JSONObject(it.pipes)).put("live", it.live)
                                .put("publishing", it.publishing).put("failed", it.failed),
                        )
                    }
                },
            )
            .toString()

        fun decode(text: String): CachedStats? = try {
            val json = JSONObject(text)
            val days = json.getJSONArray("days")
            CachedStats(
                UploadStats(
                    email = json.getString("email"),
                    totalRecordings = json.getInt("totalRecordings"),
                    uploadedToday = json.optInt("uploadedToday", 0),
                    firstUploadAt = json.optStringOrNull("firstUploadAt"),
                    days = (0 until days.length()).map { AccountApiClient.parseDay(days.getJSONObject(it)) },
                ),
                json.optLong("savedAtMs", 0),
            )
        } catch (error: JSONException) {
            null
        }

        fun encodeRecordings(recordings: List<UploadedRecording>): String = JSONArray().also { array ->
            recordings.forEach {
                array.put(
                    JSONObject().put("sessionId", it.sessionId).put("pipe", it.pipe).put("pipeLabel", it.pipeLabel)
                        .put("uploadedAt", it.uploadedAt).put("date", it.date).put("status", it.status),
                )
            }
        }.toString()

        fun decodeRecordings(text: String): List<UploadedRecording>? = try {
            AccountApiClient.parseRecordings(JSONArray(text))
        } catch (error: JSONException) {
            null
        }
    }
}
