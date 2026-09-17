package com.posecam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingGateTest {
    private val second = 1_000_000_000L

    @Test
    fun armsAfterContinuousTracking() {
        val gate = TrackingGate(second)
        assertFalse(gate.update(true, 10 * second))
        assertFalse(gate.update(true, 10 * second + second / 2))
        assertTrue(gate.update(true, 11 * second))
    }

    @Test
    fun lossOfTrackingResetsTheTimer() {
        val gate = TrackingGate(second)
        gate.update(true, 0)
        assertFalse(gate.update(false, second / 2))
        assertFalse(gate.update(true, second))
        assertFalse(gate.update(true, second + second / 2))
        assertTrue(gate.update(true, 2 * second))
    }
}
