package com.posecam.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartStateTest {
    @Test fun roundTripsThroughItsPersistedForm() {
        val state = MultipartState("up", 16, 3)
            .withPart(MultipartState.Part(2, "\"e2\"", "c2", 16))
            .withPart(MultipartState.Part(1, "\"e1\"", "c1", 16))

        val decoded = MultipartState.decode(state.encode())!!

        assertEquals(listOf(1, 2), decoded.parts.map { it.partNumber })
        assertEquals(32L, decoded.uploadedBytes)
        assertTrue(decoded.hasPart(2))
        assertFalse(decoded.isComplete)
    }

    @Test fun re_adding_a_part_replaces_it() {
        val state = MultipartState("up", 4, 2).withPart(MultipartState.Part(1, "a", "x", 4)).withPart(MultipartState.Part(1, "b", "y", 4))
        assertEquals("b", state.parts.single().etag)
    }

    @Test fun corruptStateMeansStartOver() {
        assertNull(MultipartState.decode("{not json"))
        assertNull(MultipartState.decode(null))
        assertNull(MultipartState.decode(""))
    }
}

class UploadStateTest {
    @Test fun verifiedOnlyReopensForAChangedFile() {
        assertTrue(UploadState.VERIFIED.canTransitionTo(UploadState.PENDING))
        assertFalse(UploadState.VERIFIED.canTransitionTo(UploadState.UPLOADING))
    }

    @Test fun uploadedIsReachedOnlyFromUploadingAndVerifiedOnlyFromUploadedOrPreparing() {
        assertFalse(UploadState.PENDING.canTransitionTo(UploadState.UPLOADED))
        assertTrue(UploadState.UPLOADING.canTransitionTo(UploadState.UPLOADED))
        assertTrue(UploadState.UPLOADED.canTransitionTo(UploadState.VERIFIED))
        assertFalse(UploadState.UPLOADING.canTransitionTo(UploadState.VERIFIED))
    }

    @Test fun failedFilesWaitForAnExplicitRetry() {
        assertFalse(UploadState.FAILED.isRunnable)
        assertTrue(UploadState.FAILED.canTransitionTo(UploadState.PENDING))
        assertFalse(UploadState.FAILED.canTransitionTo(UploadState.UPLOADING))
    }
}
