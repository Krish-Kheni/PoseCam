package com.posecam.core.cloud

/**
 * One day of the collector's upload history. [date] is `yyyy-MM-dd` in the phone's time zone. [pipes] counts the day's
 * recordings per pipe id; [live], [publishing] and [failed] say how far they are from the website (they sum to [recordings]).
 */
data class DayCount(
    val date: String,
    val recordings: Int,
    val pipes: Map<String, Int> = emptyMap(),
    val live: Int = 0,
    val publishing: Int = 0,
    val failed: Int = 0,
)

/** One recording the server has fully received, and how far it is from the website. */
data class UploadedRecording(
    val sessionId: String,
    /** [com.posecam.core.sync.Pipe.wire] it was filed under. */
    val pipe: String,
    /** The server's name for that pipe, used when the phone does not know the pipe. */
    val pipeLabel: String,
    val uploadedAt: String,
    /** `yyyy-MM-dd` in the phone's time zone: the day it is listed under. */
    val date: String,
    /** "live" | "publishing" | "publish_failed". */
    val status: String,
)

/**
 * What the backend has permanently received from this collector. It is the server's own count of synced recordings, so
 * it does not change when recordings are deleted from the phone.
 */
data class UploadStats(
    val email: String,
    val totalRecordings: Int,
    val uploadedToday: Int,
    val firstUploadAt: String?,
    /** Newest day first; days with no uploads are absent. At most a year is listed; [totalRecordings] counts all. */
    val days: List<DayCount>,
)

/** The collector's account on the labeling server: sign up, sign in, and their upload history. */
interface AccountApi {
    /** Creates the account and signs in. Throws [CloudHttpException] with the server's reason (e.g. `EMAIL_TAKEN`). */
    suspend fun register(email: String, password: String, name: String?): AuthSession

    /** Throws [CloudHttpException] `INVALID_CREDENTIALS` for a wrong email or password. */
    suspend fun login(email: String, password: String): AuthSession

    /** Needs a signed-in collector. [tzOffsetMinutes] is minutes east of UTC, so days follow the phone's clock. */
    suspend fun myStats(tzOffsetMinutes: Int): UploadStats

    /** One day's recordings, newest first. [date] is `yyyy-MM-dd` in the phone's time zone. */
    suspend fun uploadsOn(date: String, tzOffsetMinutes: Int): List<UploadedRecording>
}
