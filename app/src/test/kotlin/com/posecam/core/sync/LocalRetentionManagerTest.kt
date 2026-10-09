package com.posecam.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class LocalRetentionManagerTest {
    private val f = SyncFixture()
    private var pressure = false
    private var enabled = true
    private var active = setOf<String>()

    private fun manager() = LocalRetentionManager(
        repository = f.repo,
        enabled = { enabled },
        retentionDays = { 7 },
        storagePressure = { pressure },
        clock = { f.now },
        isActivelyRecording = { it in active },
    )

    /** A fully uploaded + verified + SYNCED session at [syncedAt]. */
    private suspend fun syncedSession(id: String, syncedAt: Long = f.now): File {
        val dir = f.sessionDir(id)
        val files = listOf("intrinsics.json", "poses.csv").map { f.file(dir, it, "data-$it") }
        val manifest = File(dir, "manifest.json")
        val manifestFile = QueuedFile("manifest.json", manifest.absolutePath, UploadSourceKind.PLAIN, manifest.length())
        f.queueFinalized(dir, *(files + manifestFile).toTypedArray())
        f.repo.uploadsForSession(id).forEach {
            f.repo.markPreparing(it.id); f.repo.markUploading(it.id); f.repo.markUploaded(it.id); f.repo.markVerified(it.id)
        }
        f.now = syncedAt
        f.repo.markSessionSynced(id)
        f.repo.recordPublish(id, PublishState.DONE, 1, null)
        return dir
    }

    @Test
    fun keepsASyncedSessionUntilTheWebsiteHasIt() = runBlocking {
        val dir = syncedSession("s1")
        f.repo.recordPublish("s1", PublishState.PENDING, 0, null)
        f.now += TimeUnit.DAYS.toMillis(30)
        pressure = true // even a full phone does not delete what the collector cannot see on the website yet

        assertEquals(RetentionBlock.NOT_PUBLISHED, manager().blockedReason(f.repo.session("s1")!!))
        assertTrue(manager().cleanup().deletedSessions.isEmpty())
        assertTrue(dir.isDirectory)
    }

    @Test
    fun keepsASessionTheBackendFailedToPublish() = runBlocking {
        syncedSession("s1")
        f.repo.recordPublish("s1", PublishState.FAILED, 0, "ffmpeg exited 1")

        assertEquals(RetentionBlock.NOT_PUBLISHED, manager().blockedReason(f.repo.session("s1")!!))
    }

    @Test
    fun aRawOnlyPublishIsNotEnoughForARecordingWhoseExportWasMade() = runBlocking {
        syncedSession("s1")
        f.repo.markExportMissing("s1", ExportState.FAILED, "x") // any state, then:
        f.dao.updateSession(f.repo.session("s1")!!.copy(exportState = ExportState.DONE))
        f.repo.recordPublish("s1", PublishState.DONE, 0, null)

        assertEquals(RetentionBlock.NOT_PUBLISHED, manager().blockedReason(f.repo.session("s1")!!))
        f.repo.recordPublish("s1", PublishState.DONE, 1, null)
        assertNull(manager().blockedReason(f.repo.session("s1")!!))
    }

    @Test
    fun aBackendThatDoesNotReportPublishingNeverHoldsCleanupBack() = runBlocking {
        syncedSession("s1")
        f.repo.recordPublish("s1", PublishState.UNSUPPORTED, 0, null)

        assertNull(manager().blockedReason(f.repo.session("s1")!!))
    }

    @Test
    fun keepsAFreshlySyncedSessionUntilTheRetentionPeriodHasPassed() = runBlocking {
        val dir = syncedSession("s1")
        f.now += TimeUnit.DAYS.toMillis(6)

        assertTrue(manager().cleanup().deletedSessions.isEmpty())
        assertTrue(dir.exists())
    }

    @Test
    fun deletesASyncedSessionOnceRetentionHasPassedAndForgetsItsRows() = runBlocking {
        val dir = syncedSession("s1")
        f.now += TimeUnit.DAYS.toMillis(8)

        assertEquals(listOf("s1"), manager().cleanup().deletedSessions)

        assertFalse(dir.exists())
        assertNull(f.repo.session("s1"))
    }

    @Test
    fun storagePressureReclaimsSyncedSessionsEarlyOldestFirst() = runBlocking {
        val older = syncedSession("older", syncedAt = 1_000L)
        val newer = syncedSession("newer", syncedAt = 2_000L)
        f.now = 3_000L
        pressure = true
        var deletes = 0
        val oneAndDone = LocalRetentionManager(
            f.repo, { true }, { 7 }, { deletes < 1 }, { f.now },
            deleteDirectory = { deletes++; it.deleteRecursively() },
        )

        assertEquals(listOf("older"), oneAndDone.cleanup().deletedSessions)
        assertFalse(older.exists())
        assertTrue(newer.exists())
    }

    @Test
    fun neverDeletesSessionsThatAreNotFullySynced() = runBlocking {
        pressure = true
        f.now += TimeUnit.DAYS.toMillis(30)
        val states = mapOf(
            "local-only" to null,
            "pending" to UploadState.PENDING,
            "uploading" to UploadState.UPLOADING,
            "failed" to UploadState.FAILED,
        )
        val dirs = states.map { (id, state) ->
            val dir = f.sessionDir(id)
            f.queueFinalized(dir, f.file(dir, "intrinsics.json", "{}"))
            val row = f.row(id, "intrinsics.json")
            when (state) {
                UploadState.UPLOADING -> { f.repo.markPreparing(row.id); f.repo.markUploading(row.id) }
                UploadState.FAILED -> { f.repo.markPreparing(row.id); f.repo.markFailed(row.id, "x") }
                else -> Unit
            }
            dir
        }

        assertTrue(manager().cleanup().deletedSessions.isEmpty())
        assertTrue(dirs.all { it.exists() })
    }

    @Test
    fun aSyncedSessionWithAVerifiedFileResetToPendingIsNotDeleted() = runBlocking {
        val dir = syncedSession("s1")
        f.now += TimeUnit.DAYS.toMillis(30)
        f.repo.resetToPending(f.row("s1", "intrinsics.json").id, "changed")

        assertEquals(RetentionBlock.FILES_NOT_VERIFIED, manager().blockedReason(f.repo.session("s1")!!))
        assertTrue(manager().cleanup().deletedSessions.isEmpty())
        assertTrue(dir.exists())
    }

    @Test
    fun aLocalFileChangedAfterVerificationBlocksDeletion() = runBlocking {
        val dir = syncedSession("s1")
        File(dir, "poses.csv").appendText("more data written later")
        f.now += TimeUnit.DAYS.toMillis(30)

        assertEquals(RetentionBlock.LOCAL_FILE_NOT_COVERED, manager().blockedReason(f.repo.session("s1")!!))
        assertTrue(dir.exists())
    }

    @Test
    fun neverTouchesASessionBeingRecordedEvenIfItsRowsLookSynced() = runBlocking {
        val dir = syncedSession("s1")
        f.now += TimeUnit.DAYS.toMillis(30)
        active = setOf("s1")

        assertEquals(RetentionBlock.STILL_RECORDING, manager().blockedReason(f.repo.session("s1")!!))
        assertTrue(dir.exists())
    }

    @Test
    fun aDisabledSwitchDeletesNothing() = runBlocking {
        val dir = syncedSession("s1")
        f.now += TimeUnit.DAYS.toMillis(30)
        enabled = false

        assertTrue(manager().cleanup().deletedSessions.isEmpty())
        assertTrue(dir.exists())
    }

    // ---- Delete all synced ---------------------------------------------------------------------

    @Test
    fun deleteAllSyncedRemovesSafeRecordingsWithoutWaitingForTheRetentionPeriod() = runBlocking {
        val one = syncedSession("s1")
        val two = syncedSession("s2")
        enabled = false // the automatic cleanup being off is a different choice from pressing the button

        val overview = manager().syncedOverview()
        assertEquals(setOf("s1", "s2"), overview.deletableSessions.toSet())
        assertTrue(overview.deletableBytes > 0)
        assertEquals(0, overview.keptBack)

        val report = manager().deleteAllSynced()

        assertEquals(setOf("s1", "s2"), report.deletedSessions.toSet())
        assertFalse(one.exists() || two.exists())
        assertNull(f.repo.session("s1"))
    }

    @Test
    fun deleteAllSyncedKeepsWhatIsStillNeededAndSaysSo() = runBlocking {
        val safe = syncedSession("s1")
        val waiting = syncedSession("s2")
        f.repo.recordPublish("s2", PublishState.PENDING, 0, null) // not on the website yet
        val unsynced = f.sessionDir("s3")
        f.queueFinalized(unsynced, f.file(unsynced, "poses.csv", "x"))

        val overview = manager().syncedOverview()

        assertEquals(listOf("s1"), overview.deletableSessions)
        assertEquals(1, overview.keptBack)
        assertEquals(listOf("s1"), manager().deleteAllSynced().deletedSessions)
        assertFalse(safe.exists())
        assertTrue(waiting.isDirectory)
        assertTrue(unsynced.isDirectory) // never uploaded: the only copy
    }

    @Test
    fun deleteAllSyncedNeverTouchesARecordingBeingWritten() = runBlocking {
        val dir = syncedSession("s1")
        active = setOf("s1")

        assertTrue(manager().deleteAllSynced().deletedSessions.isEmpty())
        assertTrue(dir.isDirectory)
    }
}
