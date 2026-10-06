package com.posecam.core.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The allow-list must stay in step with the backend's PoseCam profile: a path the backend rejects fails
 * permanently on the phone. `cloud/path-rules.json` is the shared source of truth; this test checks the Kotlin
 * side, and `cloud/check_backend_rules.py` checks the backend against the same file.
 */
class CloudFileRulesTest {
    private val rules = JSONObject(File("../cloud/path-rules.json").readText())

    @Test fun everyAllowedPathIsClassifiedAsTheSharedRulesSay() {
        val allowed = rules.getJSONObject("paths").getJSONArray("allowed")
        assertTrue(allowed.length() >= 10)
        for (i in 0 until allowed.length()) {
            val entry = allowed.getJSONObject(i)
            val path = entry.getString("path")
            assertEquals(path, UploadFileType.valueOf(entry.getString("type")), CloudFileRules.classify(path))
            assertEquals(path, entry.getBoolean("required"), CloudFileRules.isRequired(path))
            assertTrue(path, CloudFileRules.isUploadable(path))
        }
    }

    @Test fun everyRejectedPathIsRejected() {
        val rejected = rules.getJSONObject("paths").getJSONArray("rejected")
        for (i in 0 until rejected.length()) {
            val path = rejected.getString(i)
            assertNull(path, CloudFileRules.classify(path))
            assertFalse(path, CloudFileRules.isRequired(path))
        }
    }

    @Test fun sessionIdsFollowTheSharedRules() {
        val ids = rules.getJSONObject("sessionIds")
        for (kind in listOf("valid", "invalid")) {
            val list = ids.getJSONArray(kind)
            for (i in 0 until list.length()) {
                assertEquals(list.getString(i), kind == "valid", CloudFileRules.isValidSessionId(list.getString(i)))
            }
        }
    }

    @Test fun theIdPoseRecorderGeneratesIsAlwaysValid() {
        repeat(200) {
            val id = com.posecam.PoseRecorder.newSessionId(System.currentTimeMillis() + it * 86_400_000L)
            assertTrue(id, CloudFileRules.isValidSessionId(id))
        }
    }

    @Test fun exportsAreOptionalAndNeverHoldUpSync() {
        val export = "export/2026-09-16-14_30_52-a3f9c1-s1/posecam_export.json"
        assertEquals(UploadFileType.EXPORT, CloudFileRules.classify(export))
        assertFalse(CloudFileRules.isRequired(export))
        assertTrue(CloudFileRules.isRequired("poses.csv"))
        assertTrue(CloudFileRules.isRequired("frames-00000.zip"))
    }

    @Test fun listPlainUploadableSkipsChunksJpegsAndStrayFiles() {
        val dir = createTempDir()
        listOf("manifest.json", "poses.csv", "imu.csv", "frames-00000.zip", "scratch.tmp", "trajectory.png", "notes.txt")
            .forEach { File(dir, it).writeText("x") }
        File(dir, "frames").mkdirs()
        File(dir, "frames/000000_1.jpg").writeText("x")

        assertEquals(listOf("imu.csv", "manifest.json", "poses.csv"), CloudFileRules.listPlainUploadable(dir).map { it.name })
        dir.deleteRecursively()
    }

    @Test fun statusConstantsMatchWhatPoseRecorderReports() {
        assertEquals(CloudFileRules.STATUS_COMPLETE, com.posecam.PoseRecorder.STATUS_COMPLETE)
        assertEquals(CloudFileRules.STATUS_INCOMPLETE, com.posecam.PoseRecorder.STATUS_INCOMPLETE)
    }
}
