package com.posecam.core.sync

import java.io.File

/**
 * Which files of a PoseCam session may be uploaded and how they are classified. This MUST stay in
 * step with the backend's PoseCam profile (backend/app/services/paths.py): a path the backend
 * rejects would otherwise fail permanently. `CloudFileRulesTest` pins both lists.
 *
 * The JPEGs in `frames/` are never uploaded one by one: they travel in `frames-NNNNN.zip` chunks
 * (see [FrameChunks]), which is why chunks are classified here but not listed by [listPlainUploadable].
 */
object CloudFileRules {
    private val METADATA = setOf("manifest.json", "device.json", "intrinsics.json")
    private val TABLES = setOf("poses.csv", "frame_metadata.csv")
    private const val IMU = "imu.csv"
    private val CHUNK = Regex("""^frames-[0-9]{5,6}\.zip$""")

    /** `export/<yyyy-MM-dd-HH_mm_ss>-<6 hex>-s<N>/<RGB_|AR_Pose_><stem>.<ext>`; the stem in the name must equal the folder's. */
    private val EXPORT = Regex(
        """^export/([0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}_[0-9]{2}_[0-9]{2}-[0-9a-f]{6}-s[0-9]{1,3})/""" +
            """(RGB_\1\.mp4|AR_Pose_\1\.txt|posecam_export\.json)$""",
    )

    /** `capture-YYYYMMDDTHHMMSS-xxxxxx`, exactly what PoseRecorder.newSessionId produces (the backend regex). */
    private val SESSION_ID = Regex("""^capture-[0-9]{8}T[0-9]{6}-[0-9a-f]{6}$""")

    fun classify(relativePath: String): UploadFileType? = when {
        relativePath in METADATA -> UploadFileType.METADATA
        relativePath in TABLES -> UploadFileType.TABLE
        relativePath == IMU -> UploadFileType.IMU
        CHUNK.matches(relativePath) -> UploadFileType.FRAME_CHUNK
        EXPORT.matches(relativePath) -> UploadFileType.EXPORT
        else -> null
    }

    fun isUploadable(relativePath: String): Boolean = classify(relativePath) != null

    /**
     * A session is only SYNCED (and later cleanup-eligible) once every required file is verified.
     * Pipeline exports are derived from the raw recording and can appear later, so they do not hold that up.
     */
    fun isRequired(relativePath: String): Boolean {
        val type = classify(relativePath) ?: return false
        return type != UploadFileType.EXPORT
    }

    /** The backend refuses any other id for good, so such a folder must never enter the queue. */
    fun isValidSessionId(sessionId: String): Boolean = SESSION_ID.matches(sessionId)

    /** Plain files that exist in [sessionDirectory] now. Frame chunks are planned separately by [FrameChunks]. */
    fun listPlainUploadable(sessionDirectory: File): List<File> =
        sessionDirectory.listFiles().orEmpty()
            .filter { it.isFile && isUploadable(it.name) && classify(it.name) != UploadFileType.FRAME_CHUNK }
            .sortedBy { it.name }

    /** Manifest statuses PoseCam writes once nobody is writing the session any more. */
    const val STATUS_COMPLETE = "complete"
    const val STATUS_INCOMPLETE = "incomplete"
}
