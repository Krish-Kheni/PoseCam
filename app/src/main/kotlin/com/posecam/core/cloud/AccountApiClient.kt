package com.posecam.core.cloud

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** OkHttp implementation of [AccountApi]. Never logs a password or a token. */
class AccountApiClient(
    config: CloudConfig,
    private val authProvider: AuthProvider,
    private val installationId: String,
    private val client: OkHttpClient = CloudApiClient.defaultClient(config),
) : AccountApi {
    private val baseUrl: HttpUrl = config.normalizedBaseUrl.toHttpUrl()

    override suspend fun register(email: String, password: String, name: String?): AuthSession {
        val body = JSONObject().put("email", email).put("password", password)
        if (!name.isNullOrBlank()) body.put("name", name.trim())
        return authenticate("register", body)
    }

    override suspend fun login(email: String, password: String): AuthSession =
        authenticate("login", JSONObject().put("email", email).put("password", password))

    override suspend fun myStats(tzOffsetMinutes: Int): UploadStats {
        val url = baseUrl.newBuilder()
            .addPathSegment("v1").addPathSegment("me").addPathSegment("stats")
            .addQueryParameter("tzOffsetMinutes", tzOffsetMinutes.toString())
            .build()
        val request = requestFor(url).get().build()
        val json = try {
            client.executeJson(request)
        } catch (error: CloudHttpException) {
            if (error.status == 401) authProvider.onUnauthorized()
            throw error
        }
        return try {
            val days = json.optJSONArray("days")
            UploadStats(
                email = json.optString("email"),
                totalRecordings = json.getInt("totalRecordings"),
                uploadedToday = json.optInt("uploadedToday", 0),
                firstUploadAt = json.optStringOrNull("firstUploadAt"),
                days = (0 until (days?.length() ?: 0)).mapNotNull { days?.optJSONObject(it) }.map(::parseDay),
            )
        } catch (error: JSONException) {
            throw CloudHttpException(502, "MALFORMED_RESPONSE", "unexpected response shape: ${error.message}")
        }
    }

    override suspend fun uploadsOn(date: String, tzOffsetMinutes: Int): List<UploadedRecording> {
        val url = baseUrl.newBuilder()
            .addPathSegment("v1").addPathSegment("me").addPathSegment("uploads")
            .addQueryParameter("date", date)
            .addQueryParameter("tzOffsetMinutes", tzOffsetMinutes.toString())
            .build()
        val json = try {
            client.executeJson(requestFor(url).get().build())
        } catch (error: CloudHttpException) {
            if (error.status == 401) authProvider.onUnauthorized()
            throw error
        }
        return try {
            parseRecordings(json.optJSONArray("recordings"))
        } catch (error: JSONException) {
            throw CloudHttpException(502, "MALFORMED_RESPONSE", "unexpected response shape: ${error.message}")
        }
    }

    private suspend fun authenticate(action: String, body: JSONObject): AuthSession {
        val url = baseUrl.newBuilder().addPathSegment("v1").addPathSegment("auth").addPathSegment(action).build()
        val request = requestFor(url).post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        val json = client.executeJson(request)
        return try {
            val user = json.getJSONObject("user")
            AuthSession(
                email = user.getString("email"),
                name = user.optStringOrNull("name"),
                token = json.getString("token"),
                expiresAtMs = parseIsoUtc(json.getString("expiresAt")),
            )
        } catch (error: Exception) {
            // A bad shape is the server's fault; the caller sees one error type either way.
            if (error is JSONException || error is java.text.ParseException) {
                throw CloudHttpException(502, "MALFORMED_RESPONSE", "unexpected response shape")
            }
            throw error
        }
    }

    private suspend fun requestFor(url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Device-Id", installationId)
            .apply { authProvider.getToken()?.let { header("Authorization", "Bearer $it") } }

    internal companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun parseDay(it: JSONObject): DayCount {
            val pipes = it.optJSONObject("pipes")
            return DayCount(
                date = it.getString("date"),
                recordings = it.getInt("recordings"),
                pipes = pipes?.keys()?.asSequence()?.associateWith { key -> pipes.optInt(key) }.orEmpty(),
                live = it.optInt("live"),
                publishing = it.optInt("publishing"),
                failed = it.optInt("failed"),
            )
        }

        fun parseRecordings(array: org.json.JSONArray?): List<UploadedRecording> =
            (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }.map {
                UploadedRecording(
                    sessionId = it.getString("sessionId"),
                    pipe = it.optString("pipe"),
                    pipeLabel = it.optString("pipeLabel"),
                    uploadedAt = it.optString("uploadedAt"),
                    date = it.getString("date"),
                    status = it.optString("status"),
                )
            }

        /** `2026-10-09T12:00:00.000Z` (any fraction of a second) to epoch millis. */
        fun parseIsoUtc(text: String): Long {
            val seconds = text.replace(Regex("\\.\\d+"), "")
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(seconds)!!.time
        }
    }
}
