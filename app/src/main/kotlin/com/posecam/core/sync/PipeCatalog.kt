package com.posecam.core.sync

import android.content.Context
import com.posecam.core.cloud.CloudApi
import com.posecam.core.cloud.CloudException
import com.posecam.core.cloud.CloudLog
import com.posecam.core.cloud.PipeInfo
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Where the last good pipe list is kept, so the end-of-take dialog works with no network. */
interface PipeStore {
    fun load(): String?
    fun save(json: String)
}

class PrefsPipeStore(context: Context) : PipeStore {
    private val prefs = context.applicationContext.getSharedPreferences("posecam_pipes", Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(json: String) { prefs.edit().putString(KEY, json).apply() }

    private companion object { const val KEY = "pipes_v1" }
}

/**
 * The pipes the collector can file a take under. The backend decides the list; the phone only caches it.
 *
 * [current] never touches the network (the end-of-take dialog must open at once, in the field, offline) and never
 * returns an empty list: with nothing cached, or a cache it cannot read, it is [Pipe.DEFAULTS]. [refresh] replaces the
 * cache, and only ever with a non-empty valid list, so a bad response cannot leave the collector with no pipes.
 */
class PipeCatalog(private val api: CloudApi, private val store: PipeStore) {
    fun current(): List<Pipe> = store.load()?.let { runCatching { parse(it) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: Pipe.DEFAULTS

    /** Fetches the list and caches it. Returns whether the cache was updated; failures are logged and swallowed. */
    suspend fun refresh(): Boolean {
        val fresh = try {
            api.listPipes().toPipes()
        } catch (error: CloudException) {
            CloudLog.i("pipes_refresh_failed", "reason" to (error.message ?: error.javaClass.simpleName))
            return false
        }
        if (fresh.isEmpty()) return false
        store.save(serialize(fresh))
        return true
    }

    companion object {
        /** Valid, de-duplicated by wire id, in the backend's `order`. Anything malformed is dropped, not guessed at. */
        internal fun List<PipeInfo>.toPipes(): List<Pipe> = sortedBy { it.order }
            .filter { Pipe.isValidWire(it.id) && it.label.isNotBlank() }
            .distinctBy { it.id }
            .map { Pipe(it.id, it.label.trim(), parseColor(it.color)) }

        /** "#rrggbb" -> opaque ARGB; null for anything else. Avoids android.graphics.Color so it runs in plain JVM tests. */
        internal fun parseColor(value: String?): Int? {
            val hex = value?.takeIf { it.length == 7 && it[0] == '#' }?.substring(1) ?: return null
            return hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        }

        internal fun serialize(pipes: List<Pipe>): String = JSONArray().also { array ->
            pipes.forEach { array.put(JSONObject().put("id", it.wire).put("label", it.label).apply { it.color?.let { c -> put("color", c) } }) }
        }.toString()

        @Throws(JSONException::class)
        internal fun parse(json: String): List<Pipe> {
            val array = JSONArray(json)
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val label = item.optString("label")
                if (!Pipe.isValidWire(id) || label.isBlank()) return@mapNotNull null
                Pipe(id, label, if (item.has("color")) item.getInt("color") else null)
            }.distinctBy { it.wire }
        }
    }
}
