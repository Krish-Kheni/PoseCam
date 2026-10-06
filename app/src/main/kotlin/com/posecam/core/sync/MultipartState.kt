package com.posecam.core.sync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persisted progress of one multipart upload. It is rewritten to Room after every part, so a
 * crash or a lost connection costs at most the part that was in flight -- never the 256 MiB
 * already sent. [checksumSha256] is the base64 SHA-256 of the part (what S3 validates); the
 * [etag] is only an opaque handle S3 needs to assemble the object, never an integrity check.
 */
data class MultipartState(
    val uploadId: String,
    val partSizeBytes: Long,
    val partCount: Int,
    val parts: List<Part> = emptyList(),
) {
    data class Part(val partNumber: Int, val etag: String, val checksumSha256: String, val sizeBytes: Long)

    fun hasPart(partNumber: Int): Boolean = parts.any { it.partNumber == partNumber }

    fun withPart(part: Part): MultipartState =
        copy(parts = (parts.filterNot { it.partNumber == part.partNumber } + part).sortedBy { it.partNumber })

    val uploadedBytes: Long get() = parts.sumOf { it.sizeBytes }

    val isComplete: Boolean get() = parts.size == partCount

    fun encode(): String = JSONObject()
        .put("uploadId", uploadId)
        .put("partSizeBytes", partSizeBytes)
        .put("partCount", partCount)
        .put(
            "parts",
            JSONArray().also { array ->
                parts.forEach {
                    array.put(
                        JSONObject()
                            .put("n", it.partNumber)
                            .put("etag", it.etag)
                            .put("sha", it.checksumSha256)
                            .put("size", it.sizeBytes),
                    )
                }
            },
        )
        .toString()

    companion object {
        /** Returns null for absent or unreadable state, which simply means "start the upload afresh". */
        fun decode(text: String?): MultipartState? {
            if (text.isNullOrBlank()) return null
            return try {
                val json = JSONObject(text)
                val array = json.optJSONArray("parts") ?: JSONArray()
                MultipartState(
                    uploadId = json.getString("uploadId"),
                    partSizeBytes = json.getLong("partSizeBytes"),
                    partCount = json.getInt("partCount"),
                    parts = (0 until array.length()).map {
                        val part = array.getJSONObject(it)
                        Part(part.getInt("n"), part.getString("etag"), part.getString("sha"), part.getLong("size"))
                    },
                )
            } catch (error: JSONException) {
                null
            }
        }
    }
}
