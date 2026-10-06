package com.posecam.core.sync

import com.posecam.core.cloud.MultipartPartInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class UploadProcessorTest {
    private val f = SyncFixture()

    private suspend fun queueSession(id: String, vararg files: Pair<String, String>): File {
        val dir = f.sessionDir(id)
        val queued = files.map { (path, content) -> f.file(dir, path, content) }
        f.queueFinalized(dir, *queued.toTypedArray(), created = false)
        return dir
    }

    // ---- happy path --------------------------------------------------------------------------

    @Test
    fun uploadsEveryFileVerifiesItAndThenMarksTheSessionSynced() = runBlocking {
        queueSession("s1", "poses.csv" to "frames", "manifest.json" to "{}")

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertEquals(1, f.api.callsTo("createSession"))
        assertEquals(2, f.s3.puts.size)
        assertEquals(setOf("poses.csv", "manifest.json"), f.api.verified.toSet())
        assertTrue(f.repo.uploadsForSession("s1").all { it.state == UploadState.VERIFIED })
        assertNotNull(f.repo.session("s1")!!.syncedAt)
        assertEquals(1, f.api.completedSessions.size)
        // The digest is computed on demand and sent to the backend as the S3 checksum header.
        assertEquals(f.sha("frames"), f.row("s1", "poses.csv").sha256)
        val put = f.s3.puts.first { it.length == "frames".length.toLong() }
        assertEquals(f.sha("frames"), put.headers["x-amz-checksum-sha256"])
    }

    @Test
    fun aSessionStillRecordingIsNeverMarkedSyncedEvenWhenItsClosedFilesAreVerified() = runBlocking {
        val dir = f.sessionDir("s1", status = "recording")
        // A closed segment is queued while recording continues; the session is not final.
        f.repo.enqueue(f.file(dir, "poses.csv", "frames"))
        f.repo.registerSession("s1", dir)
        f.repo.markSessionCreated("s1")

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertEquals(UploadState.VERIFIED, f.row("s1", "poses.csv").state)
        assertEquals(0, f.api.callsTo("completeSession"))
        assertNull(f.repo.session("s1")!!.syncedAt)
    }

    @Test
    fun theOptionalPreviewNeitherHoldsUpSyncNorIsSkipped() = runBlocking {
        val dir = queueSession("s1", "manifest.json" to "{}")
        // preview.mp4 appears after the recording ended (generated later) and is optional.
        f.repo.enqueue(f.file(dir, "export/2026-09-17-09_00_00-a3f9c1-s1/posecam_export.json", "mp4"))

        f.processor.runQueue()

        assertEquals(UploadState.VERIFIED, f.row("s1", "export/2026-09-17-09_00_00-a3f9c1-s1/posecam_export.json").state)
        assertNotNull(f.repo.session("s1")!!.syncedAt)
        assertEquals(setOf("manifest.json", "export/2026-09-17-09_00_00-a3f9c1-s1/posecam_export.json"), f.api.completedSessions.single().map { it.relativePath }.toSet())
    }

    @Test
    fun anAlreadyVerifiedFileIsNotUploadedAgain() = runBlocking {
        queueSession("s1", "manifest.json" to "{}")
        f.api.alreadyVerified = setOf("manifest.json")

        f.processor.runQueue()

        assertEquals(0, f.s3.puts.size)
        assertEquals(UploadState.VERIFIED, f.row("s1", "manifest.json").state)
    }

    @Test
    fun theRunReportCountsSessionsThatBecameSynced() = runBlocking {
        queueSession("s1", "manifest.json" to "{}")
        queueSession("s2", "manifest.json" to "{}")

        val report = f.processor.runQueueReport()
        assertEquals(2, report.sessionsSynced)
        assertEquals(QueueRunResult.DONE, report.result)

        // Nothing newly synced the second time, so nothing would be announced again.
        assertEquals(0, f.processor.runQueueReport().sessionsSynced)
    }

    @Test
    fun aLargeSinglePutShowsProgressWhileItIsStillInFlight() = runBlocking {
        val dir = queueSession("s1", "manifest.json" to "{}")
        File(dir, "manifest.json").writeText("12345678") // 8 bytes, below the fake multipart threshold
        var seenWhileInFlight = -1L
        val reporting = object : S3Transport {
            override suspend fun put(url: String, headers: Map<String, String>, file: File, offset: Long, length: Long, onProgress: (Long) -> Unit): S3PutResult {
                onProgress(5) // 5 of 8 bytes sent
                kotlinx.coroutines.delay(200) // the transfer is still running; the ticker persists the progress
                seenWhileInFlight = f.row("s1", "manifest.json").uploadedBytes
                return S3PutResult("e")
            }
        }
        val processor = UploadProcessor(f.repo, f.api, reporting, FileHasher(), { "install-1" }, "test", progressIntervalMs = 20)

        processor.runQueue()

        assertEquals(5L, seenWhileInFlight)
        assertEquals(UploadState.VERIFIED, f.row("s1", "manifest.json").state)
    }

    // ---- failures ----------------------------------------------------------------------------

    @Test
    fun aDeadNetworkAbortsTheRunKeepsEverythingQueuedAndSucceedsLater() = runBlocking {
        queueSession("s1", "manifest.json" to "{}", "intrinsics.json" to "{}")
        f.api.failOnce("createSession", NETWORK_DOWN)

        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())
        assertTrue(f.repo.uploadsForSession("s1").all { it.state == UploadState.PENDING })
        assertEquals(0, f.s3.puts.size)

        // Network returns.
        assertEquals(QueueRunResult.DONE, f.processor.runQueue())
        assertTrue(f.repo.uploadsForSession("s1").all { it.state == UploadState.VERIFIED })
        assertNotNull(f.repo.session("s1")!!.syncedAt)
    }

    @Test
    fun networkLossMidUploadKeepsTheFileRetryableAndCountsTheRetry() = runBlocking {
        queueSession("s1", "manifest.json" to "{}")
        f.s3.failNext(NETWORK_DOWN)

        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())

        val row = f.row("s1", "manifest.json")
        assertEquals(UploadState.UPLOADING, row.state)
        assertEquals(1, row.retryCount)
        assertNotNull(row.lastError)

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())
        assertEquals(UploadState.VERIFIED, f.row("s1", "manifest.json").state)
    }

    @Test
    fun temporaryFailuresRetryForeverAndNeverBecomeFailed() = runBlocking {
        queueSession("s1", "manifest.json" to "{}")
        f.api.failTimes("requestUpload", http(503), 25)

        repeat(25) { assertEquals(QueueRunResult.RETRY, f.processor.runQueue()) }

        val row = f.row("s1", "manifest.json")
        assertEquals(25, row.retryCount)
        assertTrue(row.state.isRunnable)
        assertEquals(QueueRunResult.DONE, f.processor.runQueue())
        assertEquals(UploadState.VERIFIED, f.row("s1", "manifest.json").state)
    }

    @Test
    fun oneBrokenFileDoesNotStarveTheOthersInTheSameRun() = runBlocking {
        queueSession("s1", "manifest.json" to "{}", "intrinsics.json" to "{}")
        // Only the first file hits a server error; the second must still upload.
        f.api.failOnce("requestUpload", http(500))

        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())

        assertEquals(1, f.repo.uploadsForSession("s1").count { it.state == UploadState.VERIFIED })
        assertEquals(1, f.repo.uploadsForSession("s1").count { it.retryCount == 1 })
    }

    @Test
    fun aPermanentErrorBecomesFailedWithAUsefulMessage() = runBlocking {
        queueSession("s1", "manifest.json" to "{}")
        f.api.failOnce("requestUpload", http(400, "INVALID_PATH"))

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        val row = f.row("s1", "manifest.json")
        assertEquals(UploadState.FAILED, row.state)
        assertTrue(row.lastError!!.contains("INVALID_PATH"))
        assertEquals(0, f.api.callsTo("completeSession"))
        // Failed files do not spin: a second run leaves it alone until the user retries.
        f.processor.runQueue()
        assertEquals(1, f.api.callsTo("requestUpload"))
    }

    @Test
    fun aFileThatDisappearsBeforeUploadIsFailedNotCrashed() = runBlocking {
        val dir = queueSession("s1", "manifest.json" to "{}")
        File(dir, "manifest.json").delete()

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        val row = f.row("s1", "manifest.json")
        assertEquals(UploadState.FAILED, row.state)
        assertEquals("Local file is missing", row.lastError)
        assertEquals(0, f.s3.puts.size)
    }

    @Test
    fun aFileWhoseSizeChangedIsRebaselinedAndTheNewContentIsUploaded() = runBlocking {
        val dir = queueSession("s1", "manifest.json" to "{}")
        File(dir, "manifest.json").writeText("""{"a":1}""") // still below the fake multipart threshold

        f.processor.runQueue()

        val row = f.row("s1", "manifest.json")
        assertEquals(UploadState.VERIFIED, row.state)
        assertEquals(File(dir, "manifest.json").length(), row.sizeBytes)
        assertEquals(File(dir, "manifest.json").length(), f.s3.puts.single().length)
    }

    @Test
    fun repeatedVerificationFailuresEndInFailedNotAnEndlessLoop() = runBlocking {
        queueSession("s1", "manifest.json" to "{}")
        f.api.failTimes("verify", http(409, "VERIFICATION_FAILED", """{"reason":"CHECKSUM_MISMATCH"}"""), 3)

        repeat(3) { f.processor.runQueue() }

        assertEquals(UploadState.FAILED, f.row("s1", "manifest.json").state)
        assertEquals(3, f.s3.puts.size) // re-uploaded each time, then gave up
    }

    @Test
    fun aSingleVerificationFailureRequeuesTheFileForAnotherUpload() = runBlocking {
        queueSession("s1", "manifest.json" to "{}")
        f.api.failOnce("verify", http(409, "VERIFICATION_FAILED", """{"reason":"SIZE_MISMATCH"}"""))

        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())
        assertEquals(UploadState.PENDING, f.row("s1", "manifest.json").state)

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())
        assertEquals(UploadState.VERIFIED, f.row("s1", "manifest.json").state)
    }

    @Test
    fun aMissingCloudSessionIsRecreatedBeforeUploadsContinue() = runBlocking {
        queueSession("s1", "manifest.json" to "{}")
        f.api.failOnce("requestUpload", http(404, "SESSION_NOT_FOUND"))

        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())
        assertEquals(1, f.api.callsTo("createSession"))
        assertEquals(false, f.repo.session("s1")!!.cloudCreated)

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())
        assertEquals(2, f.api.callsTo("createSession"))
        assertEquals(UploadState.VERIFIED, f.row("s1", "manifest.json").state)
    }

    @Test
    fun aSessionTheBackendRefusesIsMarkedFailedAndItsFilesAreLeftAlone() = runBlocking {
        queueSession("s1", "manifest.json" to "{}")
        f.api.failOnce("createSession", http(400, "INVALID_SESSION_ID"))

        f.processor.runQueue()

        assertTrue(f.repo.session("s1")!!.permanentFailure)
        assertEquals(0, f.api.callsTo("requestUpload"))
        assertEquals(SessionCloudStatus.FAILED, summaryOf("s1").status)
    }

    @Test
    fun theBackendDisagreeingAtCompletionRequeuesTheFilesItNamesAndRecovers() = runBlocking {
        queueSession("s1", "manifest.json" to "{}", "intrinsics.json" to "{}")
        f.api.failOnce("completeSession", http(409, "SESSION_INCOMPLETE", """{"missing":[],"unverified":["intrinsics.json"]}"""))

        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())
        assertEquals(UploadState.PENDING, f.row("s1", "intrinsics.json").state)
        assertEquals(UploadState.VERIFIED, f.row("s1", "manifest.json").state)

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())
        assertNotNull(f.repo.session("s1")!!.syncedAt)
    }

    @Test
    fun anUploadedButUnverifiedFileResumesAtVerificationWithoutSendingBytesAgain() = runBlocking {
        queueSession("s1", "manifest.json" to "{}")
        f.api.failOnce("verify", NETWORK_DOWN)
        f.processor.runQueue()
        assertEquals(UploadState.UPLOADED, f.row("s1", "manifest.json").state)
        val putsBefore = f.s3.puts.size

        f.processor.runQueue()

        assertEquals(putsBefore, f.s3.puts.size)
        assertEquals(UploadState.VERIFIED, f.row("s1", "manifest.json").state)
    }

    // ---- multipart ---------------------------------------------------------------------------

    private val big = "ABCDEFGHIJKLMNOPQRSTUVWXYZ" // 26 bytes; part size 4 => 7 parts

    @Test
    fun largeFilesUseMultipartWithPerPartChecksumsAndCompleteInOrder() = runBlocking {
        queueSession("s1", "poses.csv" to big)

        f.processor.runQueue()

        assertEquals(7, f.s3.puts.size)
        assertEquals(big, String(f.s3.puts.sortedBy { it.offset }.flatMap { it.content.toList() }.toByteArray()))
        val completed = f.api.multipartCompletedParts!!
        assertEquals((1..7).toList(), completed.map { it.partNumber })
        // Each part carries its own base64 SHA-256 -- never the ETag -- as the integrity value.
        val firstPart = f.s3.puts.first { it.offset == 0L }
        assertEquals(base64Sha("ABCD"), firstPart.headers["x-amz-checksum-sha256"])
        assertEquals(base64Sha("ABCD"), completed.first().checksumSha256)
        assertEquals(UploadState.VERIFIED, f.row("s1", "poses.csv").state)
        assertNull(f.row("s1", "poses.csv").multipartState) // cleared once verified
    }

    @Test
    fun aMultipartUploadResumesFromThePersistedPartsAfterAFailureInsteadOfRestarting() = runBlocking {
        queueSession("s1", "poses.csv" to big)
        // Parts 1 and 2 succeed; the network dies sending part 3.
        var sent = 0
        val original = f.s3
        f.s3.etagFor = { "\"e$it\"" }
        // Fail the third PUT.
        val failingTransport = object : S3Transport {
            override suspend fun put(url: String, headers: Map<String, String>, file: File, offset: Long, length: Long, onProgress: (Long) -> Unit): S3PutResult {
                if (++sent == 3) throw NETWORK_DOWN
                return original.put(url, headers, file, offset, length, onProgress)
            }
        }
        val processor = UploadProcessor(f.repo, f.api, failingTransport, FileHasher(), { "install-1" }, "test")

        assertEquals(QueueRunResult.RETRY, processor.runQueue())

        val interrupted = f.row("s1", "poses.csv")
        assertEquals(UploadState.UPLOADING, interrupted.state)
        val saved = MultipartState.decode(interrupted.multipartState)!!
        assertEquals(listOf(1, 2), saved.parts.map { it.partNumber })
        assertEquals(8L, interrupted.uploadedBytes)
        val uploadId = interrupted.multipartUploadId

        // "Process restart": a new processor over the same persisted queue.
        original.puts.clear()
        f.api.partUrlRequests.clear()
        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertEquals(1, f.api.callsTo("startMultipart")) // not started again
        assertEquals(uploadId, f.row("s1", "poses.csv").multipartUploadId)
        assertEquals((3..7).toList(), f.api.partUrlRequests) // only the missing parts
        assertEquals(5, original.puts.size)
        assertEquals((1..7).toList(), f.api.multipartCompletedParts!!.map { it.partNumber })
        assertEquals(UploadState.VERIFIED, f.row("s1", "poses.csv").state)
    }

    @Test
    fun partsTheBackendAlreadyHoldsAreNotUploadedAgainWhenLocalStateWasLost() = runBlocking {
        queueSession("s1", "poses.csv" to big)
        f.api.uploadedPartsOnServer = mapOf(
            1 to MultipartPartInfo(1, 4, "\"srv1\"", base64Sha("ABCD")),
            // Server's part 2 has a different checksum than the local bytes, so it must be re-sent.
            2 to MultipartPartInfo(2, 4, "\"srv2\"", base64Sha("WRONG")),
        )

        f.processor.runQueue()

        assertEquals(listOf(2, 3, 4, 5, 6, 7), f.api.partUrlRequests)
        assertEquals("\"srv1\"", f.api.multipartCompletedParts!!.first().etag)
    }

    @Test
    fun anExpiredMultipartUploadRestartsTheFileFromScratch() = runBlocking {
        queueSession("s1", "poses.csv" to big)
        f.api.failOnce("completeMultipart", http(404, "UPLOAD_NOT_FOUND"))

        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())
        val row = f.row("s1", "poses.csv")
        assertEquals(UploadState.PENDING, row.state)
        assertNull(row.multipartUploadId)

        f.api.nextUploadId = "upload-2"
        assertEquals(QueueRunResult.DONE, f.processor.runQueue())
        assertEquals(2, f.api.callsTo("startMultipart"))
    }

    @Test
    fun aMultipartStartRefusedBecauseTheBackendAlreadyHasTheObjectJustVerifies() = runBlocking {
        queueSession("s1", "poses.csv" to big)
        f.api.failOnce("startMultipart", http(409, "UPLOAD_CONFLICT", """{"reason":"ALREADY_UPLOADED"}"""))

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertEquals(0, f.s3.puts.size)
        assertEquals(UploadState.VERIFIED, f.row("s1", "poses.csv").state)
    }

    @Test
    fun aMultipartConflictOtherThanAlreadyDoneRestartsTheFile() = runBlocking {
        queueSession("s1", "poses.csv" to big)
        f.api.failOnce("completeMultipart", http(409, "UPLOAD_CONFLICT", """{"s3Code":"InvalidPart"}"""))

        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())

        assertEquals(UploadState.PENDING, f.row("s1", "poses.csv").state)
        assertEquals(QueueRunResult.DONE, f.processor.runQueue())
    }

    @Test
    fun aSegmentThatGrowsMidUploadAbandonsTheStaleParts() = runBlocking {
        val dir = queueSession("s1", "poses.csv" to big)
        var sent = 0
        val original = f.s3
        val growing = object : S3Transport {
            override suspend fun put(url: String, headers: Map<String, String>, file: File, offset: Long, length: Long, onProgress: (Long) -> Unit): S3PutResult {
                if (++sent == 2) File(dir, "poses.csv").appendText("MORE")
                return original.put(url, headers, file, offset, length, onProgress)
            }
        }
        val processor = UploadProcessor(f.repo, f.api, growing, FileHasher(), { "install-1" }, "test")

        assertEquals(QueueRunResult.RETRY, processor.runQueue())

        val row = f.row("s1", "poses.csv")
        assertEquals(UploadState.PENDING, row.state)
        assertEquals(30L, row.sizeBytes)
        assertNull(row.multipartState)
        assertEquals(QueueRunResult.DONE, f.processor.runQueue())
        assertEquals(UploadState.VERIFIED, f.row("s1", "poses.csv").state)
    }

    // ---- UI-facing roll-up -------------------------------------------------------------------

    @Test
    fun recordingAndCloudStatusAreIndependent() = runBlocking {
        // The manifest says "complete": false (a killed take) while the cloud state is SYNCED.
        val dir = f.sessionDir("s1", status = "incomplete")
        val manifest = File(dir, "manifest.json")
        f.repo.finalizeSession(
            "s1", dir, "incomplete",
            listOf(QueuedFile("manifest.json", manifest.absolutePath, UploadSourceKind.PLAIN, manifest.length())),
        )
        f.repo.markSessionCreated("s1")

        f.processor.runQueue()

        assertEquals(SessionCloudStatus.SYNCED, summaryOf("s1").status)
        assertTrue(manifest.readText().contains("\"complete\": false"))
        assertEquals("incomplete", f.repo.session("s1")!!.recordingStatus)
        assertEquals("incomplete", f.api.completedStatuses.single())
    }

    private fun summaryOf(sessionId: String): SessionCloudSummary {
        val aggregate = f.dao.aggregates().firstOrNull { it.sessionId == sessionId }
        return SessionCloudSummary.from(aggregate, runBlocking { f.repo.session(sessionId) })
    }

    private fun base64Sha(text: String): String =
        Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
