package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeVerdictTest {
    @Test
    fun aCleanTakePasses() {
        val v = TakeVerdict.of(seconds = 40.0, frames = 1200, trackedFrames = 1200,
            poseJumps = 0, longestGapSeconds = 0.0, droppedImages = 0)
        assertFalse(v.redo)
        assertEquals("Take looks good", v.headline)
        assertTrue(v.reasons.isEmpty())
        assertEquals("40 s, 1200 frames, 100% tracked", v.detail)
    }

    @Test
    fun anyPoseJumpMeansRedo() {
        val v = TakeVerdict.of(40.0, 1200, 1199, poseJumps = 1, longestGapSeconds = 0.0, droppedImages = 0)
        assertTrue(v.redo)
        assertTrue(v.reasons.any { "jumped once" in it })
    }

    @Test
    fun aGapLongerThanTheInterpolationCapMeansRedo() {
        val v = TakeVerdict.of(40.0, 1200, 1100, poseJumps = 0, longestGapSeconds = 3.3, droppedImages = 0)
        assertTrue(v.redo)
        assertTrue(v.reasons.any { "3.3 s" in it })
    }

    @Test
    fun shortGapsAndDroppedImagesAreReportedButNotFatal() {
        val v = TakeVerdict.of(40.0, 1200, 1197, poseJumps = 0, longestGapSeconds = 0.1, droppedImages = 2)
        assertFalse(v.redo)
        assertTrue(v.reasons.any { "filled in" in it })
        assertTrue(v.reasons.any { "no image saved" in it })
    }

    @Test
    fun aVeryShortTakeMeansRedo() {
        val v = TakeVerdict.of(3.0, 90, 90, poseJumps = 0, longestGapSeconds = 0.0, droppedImages = 0)
        assertTrue(v.redo)
        assertTrue(v.reasons.any { "3.0 s long" in it })
    }
}
