package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PoseJumpDetectorTest {
    private val identity = floatArrayOf(0f, 0f, 0f, 1f)
    private val frameNs = 33_000_000L

    @Test
    fun normalHandheldMotionIsNotAJump() {
        val d = PoseJumpDetector()
        assertNull(d.onTrackedFrame(0, frameNs, floatArrayOf(0f, 0f, 0f), identity))
        // 3 cm in one frame ~= 0.9 m/s
        assertNull(d.onTrackedFrame(1, 2 * frameNs, floatArrayOf(0.03f, 0f, 0f), identity))
        assertEquals(0, d.count)
    }

    @Test
    fun translationJumpIsRecorded() {
        val d = PoseJumpDetector()
        d.onTrackedFrame(0, frameNs, floatArrayOf(0f, 0f, 0f), identity)
        val jump = d.onTrackedFrame(1, 2 * frameNs, floatArrayOf(1.04f, 0f, 0f), identity)
        assertNotNull(jump)
        assertEquals(1L, jump!!.frameIndex)
        assertEquals(1.04, jump.translationM, 1e-3)
        assertEquals(1, d.count)
    }

    @Test
    fun rotationJumpIsRecorded() {
        val d = PoseJumpDetector()
        d.onTrackedFrame(0, frameNs, floatArrayOf(0f, 0f, 0f), identity)
        // 68 degrees about Y in one frame
        val half = Math.toRadians(34.0)
        val q = floatArrayOf(0f, Math.sin(half).toFloat(), 0f, Math.cos(half).toFloat())
        val jump = d.onTrackedFrame(1, 2 * frameNs, floatArrayOf(0f, 0f, 0f), q)
        assertNotNull(jump)
        assertEquals(68.0, jump!!.rotationDeg, 0.5)
    }

    @Test
    fun trackingLossBreaksContinuityWithoutAJump() {
        val d = PoseJumpDetector()
        d.onTrackedFrame(0, frameNs, floatArrayOf(0f, 0f, 0f), identity)
        d.onUntrackedFrame()
        // Large move across the untracked gap: unknown, not a jump.
        assertNull(d.onTrackedFrame(2, 3 * frameNs, floatArrayOf(2f, 0f, 0f), identity))
        assertEquals(0, d.count)
    }
}
