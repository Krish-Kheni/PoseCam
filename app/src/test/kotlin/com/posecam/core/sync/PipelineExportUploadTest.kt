package com.posecam.core.sync

import com.posecam.PipelineExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A filed recording's pipeline export (`RGB_<stem>.mp4` + `AR_Pose_<stem>.txt`) is made on the phone, queued as `export/<stem>/...`
 * rows, and uploaded with the raw files. Failing to export must never fail the session, and must never be silent.
 */
class PipelineExportUploadTest {
    private val f = SyncFixture()
    private val id = SyncFixture.SESSION
    private val stem1 = "2026-09-17-09_00_05-a3f9c1-s1"
    private val stem2 = "2026-09-17-09_01_30-a3f9c1-s2"

    private var rotation: Int? = 90
    private val exportCalls = mutableListOf<Int>()
    private var exporterBehavior: (File) -> List<File> = { root -> listOf(stem1, stem2).map { segment(root, it) } }

    /** What PipelineExporter leaves behind for one clean segment. */
    private fun segment(root: File, stem: String): File {
        val folder = File(root, stem).also { it.mkdirs() }
        File(folder, "RGB_$stem.mp4").writeBytes(ByteArray(5_000) { it.toByte() })
        File(folder, "AR_Pose_$stem.txt").writeText("pose-lines-of-$stem")
        File(folder, "posecam_export.json").writeText("{\"stem\":\"$stem\"}")
        return folder
    }

    private val exporter = SessionExporter { _, root, degrees, onProgress ->
        exportCalls += degrees
        root.deleteRecursively() // PipelineExporter empties its output root first
        root.mkdirs()
        onProgress(0, 100)
        exporterBehavior(root)
    }

    private val stage = PipelineExportStage(f.repo, f.staging, exporter, { rotation }, isRecording = { f.recording })
    private val processor = UploadProcessor(
        f.repo, f.api, f.s3, FileHasher(), { "install-1" }, "test", f.materializer,
        isRecording = { f.recording }, exportStage = stage,
    )

    init {
        f.api.multipartThreshold = Long.MAX_VALUE // exports and chunks here are tiny single PUTs
        f.dao.defaultExportState = ExportState.PENDING // production: a new recording's export is still to be made
    }

    private suspend fun filedRecording(): File {
        val dir = f.sessionDir(id)
        listOf("poses.csv", "intrinsics.json").forEach { f.file(dir, it, "data-$it") }
        f.frames(dir, 12)
        f.repo.finalizeSession(id, dir, "complete", UploadPlan.forSession(id, dir, f.staging))
        return dir
    }

    private suspend fun summary() = SessionCloudSummary.from(
        f.dao.aggregates().firstOrNull { it.sessionId == id }, f.repo.session(id), f.dao.exportAggregates().firstOrNull { it.sessionId == id },
    )

    // ---- the happy path ------------------------------------------------------------------------

    @Test
    fun aFiledRecordingUploadsOneRowPerFilePerSegmentUnderExportStem_thenSyncs() = runBlocking {
        filedRecording()
        f.repo.markSessionCreated(id)

        assertEquals(QueueRunResult.DONE, processor.runQueue())

        val exportPaths = f.repo.uploadsForSession(id).filter { it.fileType == UploadFileType.EXPORT }.map { it.relativePath }.sorted()
        assertEquals(
            listOf(
                "export/$stem1/AR_Pose_$stem1.txt", "export/$stem1/RGB_$stem1.mp4", "export/$stem1/posecam_export.json",
                "export/$stem2/AR_Pose_$stem2.txt", "export/$stem2/RGB_$stem2.mp4", "export/$stem2/posecam_export.json",
            ),
            exportPaths,
        )
        assertTrue(exportPaths.all { CloudFileRules.classify(it) == UploadFileType.EXPORT })
        assertTrue(f.repo.uploadsForSession(id).all { it.state == UploadState.VERIFIED })
        assertEquals(exportPaths.toSet(), f.api.verified.filter { it.startsWith("export/") }.toSet())
        assertNotNull(f.repo.session(id)!!.syncedAt)
        assertEquals(ExportState.DONE, f.repo.session(id)!!.exportState)
        assertEquals(listOf(90), exportCalls)
        assertEquals("Synced", CloudUiText.rowStatus(summary(), SyncPolicy.WIFI_ONLY, false))
    }

    @Test
    fun exportRowsAreNotRequiredAndDoNotCountTowardTheSessionTotals() = runBlocking {
        filedRecording()
        f.repo.markSessionCreated(id)
        stage.runPending()

        val exports = f.repo.uploadsForSession(id).filter { it.kind == UploadSourceKind.EXPORT }
        assertEquals(6, exports.size)
        assertTrue(exports.none { it.required })
        assertTrue(f.dao.aggregates().single().totalFiles == f.repo.uploadsForSession(id).count { it.required })
    }

    @Test
    fun theSmallExportJumpsTheQueueAheadOfTheRawData() = runBlocking {
        filedRecording()
        f.repo.markSessionCreated(id)

        stage.runPending()

        val order = f.repo.runnable().map { it.relativePath }
        assertTrue(order.take(6).all { it.startsWith("export/") })
        assertTrue(order.drop(6).none { it.startsWith("export/") })
    }

    @Test
    fun stagedExportBytesAreDeletedOnceVerified() = runBlocking {
        filedRecording()
        f.repo.markSessionCreated(id)

        processor.runQueue()

        assertFalse("nothing left in staging: ${f.staging.exportRoot(id)}", f.staging.exportRoot(id).exists())
        assertFalse(File(File(f.root, "upload-staging"), id).exists())
    }

    @Test
    fun theUploadedBytesAreTheExportedBytes() = runBlocking {
        filedRecording()
        f.repo.markSessionCreated(id)

        processor.runQueue()

        val put = f.s3.puts.first { it.url.endsWith("RGB_$stem1.mp4") }
        assertEquals(5_000, put.content.size)
        assertEquals(ByteArray(5_000) { it.toByte() }.toList(), put.content.toList())
    }

    @Test
    fun theExportIsMadeBeforeTheSessionExistsInTheCloudToo() = runBlocking {
        filedRecording() // not created yet: nothing is runnable, but the export can already be made

        stage.runPending()

        assertEquals(ExportState.DONE, f.repo.session(id)!!.exportState)
        assertTrue(f.repo.runnable().isEmpty())
        f.repo.markSessionCreated(id)
        assertEquals(6, f.repo.runnable().count { it.relativePath.startsWith("export/") })
    }

    // ---- rotation is a setting -----------------------------------------------------------------

    @Test
    fun nothingIsExportedUntilARotationHasBeenChosen() = runBlocking {
        rotation = null
        filedRecording()
        f.repo.markSessionCreated(id)

        assertFalse("a pass with nothing to do must not start a foreground service", stage.hasWork())
        processor.runQueue()

        assertTrue(exportCalls.isEmpty())
        assertEquals(ExportState.PENDING, f.repo.session(id)!!.exportState)
        assertNotNull("the raw upload carries on", f.repo.session(id)!!.syncedAt)
        assertEquals(
            "Synced — set the video rotation to make the pipeline export",
            CloudUiText.rowStatus(summary(), SyncPolicy.WIFI_ONLY, false, exportRotationSet = false),
        )

        rotation = 270
        assertTrue(stage.hasWork())
        processor.runQueue()
        assertEquals(listOf(270), exportCalls)
        assertEquals(ExportState.DONE, f.repo.session(id)!!.exportState)
        assertEquals(6, f.repo.uploadsForSession(id).count { it.state == UploadState.VERIFIED && it.fileType == UploadFileType.EXPORT })
    }

    // ---- what is exported ----------------------------------------------------------------------

    @Test
    fun aRecordingNobodyHasFiledIsNeitherExportedNorUploaded() = runBlocking {
        f.dao.defaultPipe = null
        filedRecording()

        processor.runQueue()

        assertTrue(exportCalls.isEmpty())
        assertEquals(0, f.api.calls.size)
        assertEquals(ExportState.PENDING, f.repo.session(id)!!.exportState)
    }

    @Test
    fun onlyTheTappedSessionIsExportedOnAManualSessionUpload() = runBlocking {
        val other = "capture-20260917T091000-b00002"
        filedRecording()
        val dir2 = f.sessionDir(other)
        f.repo.finalizeSession(other, dir2, "complete", emptyList())
        f.repo.markSessionCreated(id)

        processor.runQueue(onlySessionId = id)

        assertEquals(ExportState.DONE, f.repo.session(id)!!.exportState)
        assertEquals(ExportState.PENDING, f.repo.session(other)!!.exportState)
    }

    // ---- failures never fail the session -------------------------------------------------------

    @Test
    fun anOffProtocolRecordingStillSyncsItsRawFilesAndSaysItHasNoExport() = runBlocking {
        exporterBehavior = {
            throw PipelineExporter.ExportException(
                "This recording was recorded at 640x360, not the team's 640x480. It cannot be mixed with the rest of the dataset, so it is not exported.",
                PipelineExporter.Reason.OFF_PROTOCOL,
            )
        }
        filedRecording()
        f.repo.markSessionCreated(id)

        assertEquals(QueueRunResult.DONE, processor.runQueue())

        assertNotNull("SYNCED without the export", f.repo.session(id)!!.syncedAt)
        assertEquals(SessionCloudStatus.SYNCED, summary().status)
        assertTrue(f.repo.uploadsForSession(id).none { it.kind == UploadSourceKind.EXPORT })
        assertEquals(ExportState.OFF_PROTOCOL, f.repo.session(id)!!.exportState)
        assertEquals("Synced — no pipeline export (off protocol)", CloudUiText.rowStatus(summary(), SyncPolicy.WIFI_ONLY, false))
        assertTrue(CloudUiText.missingExportDetail(summary())!!.contains("640x360"))
        assertFalse("not retried on every run", stage.hasWork())
        assertFalse(f.staging.exportRoot(id).exists())
    }

    @Test
    fun aKilledTakeOrNoCleanStretchIsMissingToo_andNotRetried() = runBlocking {
        exporterBehavior = { throw PipelineExporter.ExportException("This recording did not stop cleanly and cannot be exported.") }
        filedRecording()
        f.repo.markSessionCreated(id)

        processor.runQueue()
        processor.runQueue()

        assertEquals(1, exportCalls.size)
        assertEquals(ExportState.NOT_EXPORTABLE, f.repo.session(id)!!.exportState)
        assertEquals("Synced — no pipeline export (cannot be exported)", CloudUiText.rowStatus(summary(), SyncPolicy.WIFI_ONLY, false))
        assertNotNull(f.repo.session(id)!!.syncedAt)
    }

    @Test
    fun aCrashedExportLeavesTheSessionSyncedAndCanBeRetried() = runBlocking {
        var fail = true
        exporterBehavior = { root ->
            if (fail) throw java.io.IOException("No space left on device")
            listOf(segment(root, stem1))
        }
        filedRecording()
        f.repo.markSessionCreated(id)

        assertEquals(QueueRunResult.DONE, processor.runQueue())

        assertNotNull(f.repo.session(id)!!.syncedAt)
        assertEquals(ExportState.FAILED, f.repo.session(id)!!.exportState)
        assertEquals("No space left on device", f.repo.session(id)!!.exportNote)
        assertEquals("Synced — pipeline export failed, tap to retry", CloudUiText.rowStatus(summary(), SyncPolicy.WIFI_ONLY, false))
        assertFalse("a crash is not retried on its own", stage.hasWork())

        fail = false
        f.repo.retryFailed(id) // what "Retry pipeline export" does
        assertEquals(ExportState.PENDING, f.repo.session(id)!!.exportState)
        processor.runQueue()

        assertEquals(ExportState.DONE, f.repo.session(id)!!.exportState)
        assertEquals(3, f.repo.uploadsForSession(id).count { it.kind == UploadSourceKind.EXPORT && it.state == UploadState.VERIFIED })
    }

    @Test
    fun anExportFileThatCannotBeUploadedDoesNotUnsyncTheSession_butIsShown() = runBlocking {
        filedRecording()
        f.repo.markSessionCreated(id)
        f.api.failOnceFor("requestUpload", "export/$stem1/RGB_$stem1.mp4", http(403, "PATH_NOT_ALLOWED"))

        processor.runQueue()

        assertNotNull(f.repo.session(id)!!.syncedAt)
        val failed = f.repo.uploadsForSession(id).single { it.state == UploadState.FAILED }
        assertEquals("export/$stem1/RGB_$stem1.mp4", failed.relativePath)
        assertEquals(SessionCloudStatus.SYNCED, summary().status)
        assertEquals("Synced — pipeline export upload failed, tap to retry", CloudUiText.rowStatus(summary(), SyncPolicy.WIFI_ONLY, false))
    }

    @Test
    fun anExportFolderTheBackendWouldRefuseIsNeverHalfQueued() = runBlocking {
        exporterBehavior = { root -> listOf(segment(root, "2026-09-17-09_00_05-A3F9C1-s1")) } // uppercase hash
        filedRecording()
        f.repo.markSessionCreated(id)

        processor.runQueue()

        assertTrue(f.repo.uploadsForSession(id).none { it.kind == UploadSourceKind.EXPORT })
        assertEquals(ExportState.FAILED, f.repo.session(id)!!.exportState)
        assertNotNull(f.repo.session(id)!!.syncedAt)
    }

    @Test
    fun anExportMissingOneOfItsThreeFilesIsNeverQueued() = runBlocking {
        exporterBehavior = { root -> listOf(segment(root, stem1).also { File(it, "posecam_export.json").delete() }) }
        filedRecording()
        f.repo.markSessionCreated(id)

        processor.runQueue()

        assertTrue(f.repo.uploadsForSession(id).none { it.kind == UploadSourceKind.EXPORT })
        assertEquals(ExportState.FAILED, f.repo.session(id)!!.exportState)
    }

    // ---- never while recording -----------------------------------------------------------------

    @Test
    fun aTakeThatStartsMidExportStopsItAndLeavesNothingBehind() = runBlocking {
        val interrupting = SessionExporter { _, root, _, onProgress ->
            segment(root, stem1)
            f.recording = true // the collector pressed Record; the next progress tick must notice
            onProgress(30, 100)
            emptyList() // not reached: the callback throws
        }
        val interruptedStage = PipelineExportStage(f.repo, f.staging, interrupting, { rotation }, isRecording = { f.recording })
        filedRecording()
        f.repo.markSessionCreated(id)

        interruptedStage.runPending()

        assertFalse("half-written output is deleted", f.staging.exportRoot(id).exists())
        assertEquals(ExportState.PENDING, f.repo.session(id)!!.exportState)
        assertTrue(f.repo.uploadsForSession(id).none { it.kind == UploadSourceKind.EXPORT })

        f.recording = false // Stop
        stage.runPending()
        assertEquals(ExportState.DONE, f.repo.session(id)!!.exportState)
    }

    @Test
    fun nothingIsExportedWhileATakeIsBeingRecorded() = runBlocking {
        filedRecording()
        f.repo.markSessionCreated(id)
        f.recording = true

        assertEquals(QueueRunResult.DONE, processor.runQueue())

        assertTrue(exportCalls.isEmpty())
    }

    // ---- staging lost --------------------------------------------------------------------------

    @Test
    fun stagedExportFilesThatVanishAreExportedAgainRatherThanFailedForGood() = runBlocking {
        filedRecording()
        f.repo.markSessionCreated(id)
        stage.runPending()
        f.staging.exportRoot(id).deleteRecursively() // storage cleared before the export was uploaded

        assertEquals(QueueRunResult.RETRY, processor.runQueue())
        assertEquals(ExportState.PENDING, f.repo.session(id)!!.exportState)
        assertTrue(f.repo.uploadsForSession(id).none { it.kind == UploadSourceKind.EXPORT })

        assertEquals(QueueRunResult.DONE, processor.runQueue())
        assertEquals(6, f.repo.uploadsForSession(id).count { it.kind == UploadSourceKind.EXPORT && it.state == UploadState.VERIFIED })
    }

    // ---- retention -----------------------------------------------------------------------------

    private fun retention() = LocalRetentionManager(f.repo, { true }, { 7 }, { true }, { f.now }, isActivelyRecording = { false })

    @Test
    fun aSyncedRecordingIsKeptWhileItsExportIsStillToBeMade() = runBlocking {
        rotation = null
        filedRecording()
        f.repo.markSessionCreated(id)
        processor.runQueue()
        assertNotNull(f.repo.session(id)!!.syncedAt)

        assertEquals(RetentionBlock.EXPORT_PENDING, retention().blockedReason(f.repo.session(id)!!))
        assertTrue(retention().cleanup().deletedSessions.isEmpty())
    }

    @Test
    fun aSyncedRecordingIsKeptUntilItsExportIsVerifiedInTheCloud() = runBlocking {
        filedRecording()
        f.repo.markSessionCreated(id)
        stage.runPending()
        // Raw files verified and synced, export not yet uploaded.
        f.repo.uploadsForSession(id).filter { it.required }.forEach {
            f.repo.markPreparing(it.id); f.repo.markUploading(it.id); f.repo.markUploaded(it.id); f.repo.markVerified(it.id)
        }
        f.repo.markSessionSynced(id)
        // The frames are covered by the verified chunk, so only the export is holding it.
        assertEquals(RetentionBlock.EXPORT_NOT_UPLOADED, retention().blockedReason(f.repo.session(id)!!))

        processor.runQueue()

        assertNull(retention().blockedReason(f.repo.session(id)!!))
    }

    @Test
    fun aRecordingWithNoPossibleExportIsNotHeldBackByIt() = runBlocking {
        exporterBehavior = { throw PipelineExporter.ExportException("off", PipelineExporter.Reason.OFF_PROTOCOL) }
        filedRecording()
        f.repo.markSessionCreated(id)

        processor.runQueue()

        assertNull(retention().blockedReason(f.repo.session(id)!!))
    }

    // ---- the plan ------------------------------------------------------------------------------

    @Test
    fun theExportPlanListsExactlyThreeFilesPerSegmentInStemOrder() {
        val root = File(f.root, "plan").also { it.mkdirs() }
        segment(root, stem2)
        segment(root, stem1)

        val plan = UploadPlan.forExport(root)

        assertEquals(6, plan.size)
        assertEquals(
            listOf(
                "export/$stem1/RGB_$stem1.mp4", "export/$stem1/AR_Pose_$stem1.txt", "export/$stem1/posecam_export.json",
                "export/$stem2/RGB_$stem2.mp4", "export/$stem2/AR_Pose_$stem2.txt", "export/$stem2/posecam_export.json",
            ),
            plan.map { it.relativePath },
        )
        assertTrue(plan.all { it.kind == UploadSourceKind.EXPORT && File(it.localPath).isFile && it.sizeBytes == File(it.localPath).length() })
    }

    @Test
    fun theStemFormatIsTheOneThePipelineExporterWrites() {
        // PipelineExporter names folders SessionExport.stem(...): UTC "yyyy-MM-dd-HH_mm_ss" + the session's hex + "-s<N>".
        val written = com.posecam.SessionExport.stem(1_789_635_605_000L, id, 0)
        assertEquals("2026-09-17-09_00_05-a3f9c1-s1", written)
        assertEquals(UploadFileType.EXPORT, CloudFileRules.classify("export/$written/RGB_$written.mp4"))
        assertEquals(UploadFileType.EXPORT, CloudFileRules.classify("export/$written/AR_Pose_$written.txt"))
        assertEquals(UploadFileType.EXPORT, CloudFileRules.classify("export/$written/posecam_export.json"))
        // The server refuses a filename whose stem differs from its folder's.
        assertNull(CloudFileRules.classify("export/$written/RGB_other-stem.mp4"))
    }
}
