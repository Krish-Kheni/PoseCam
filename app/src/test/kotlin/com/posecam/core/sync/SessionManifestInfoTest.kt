package com.posecam.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionManifestInfoTest {
    private fun dir(manifest: String?): File {
        val dir = createTempDir()
        if (manifest != null) File(dir, "manifest.json").writeText(manifest)
        return dir
    }

    @Test fun readsTheFieldsPoseRecorderWrites() {
        val info = SessionManifestInfo.read(
            dir(
                """{
  "format_version": "posecam-5",
  "session_id": "capture-20260916T143052-a3f9c1",
  "start_wall_time_utc": "2026-09-16T09:00:00.000Z",
  "stop_wall_time_utc": null,
  "complete": true,
  "frame_count": 4512
}""",
            ),
        )!!

        assertEquals("capture-20260916T143052-a3f9c1", info.sessionId)
        assertTrue(info.complete)
        assertEquals("complete", info.recordingStatus)
        assertEquals("2026-09-16T09:00:00.000Z", info.startWallTimeUtc)
        assertEquals(4512L, info.frameCount)
    }

    @Test fun aKilledTakeIsIncomplete() {
        val info = SessionManifestInfo.read(dir("""{"session_id": "capture-20260916T143052-a3f9c1", "complete": false}"""))!!
        assertFalse(info.complete)
        assertEquals("incomplete", info.recordingStatus)
    }

    @Test fun theFolderNameIsTheFallbackIdAndAMissingCompleteMeansIncomplete() {
        val folder = dir("""{"frame_count": 1}""")
        val info = SessionManifestInfo.read(folder)!!
        assertEquals(folder.name, info.sessionId)
        assertEquals("incomplete", info.recordingStatus)
        assertNull(info.startWallTimeUtc)
    }

    @Test fun aNullStartTimeIsNotTheStringNull() {
        assertNull(SessionManifestInfo.read(dir("""{"start_wall_time_utc": null, "complete": true}"""))!!.startWallTimeUtc)
    }

    @Test fun missingOrUnreadableManifestsAreNotSessions() {
        assertNull(SessionManifestInfo.read(dir(null)))
        assertNull(SessionManifestInfo.read(dir("{not json")))
        assertNull(SessionManifestInfo.read(dir("")))
    }
}
