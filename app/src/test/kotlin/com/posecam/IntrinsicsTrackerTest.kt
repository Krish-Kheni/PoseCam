package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntrinsicsTrackerTest {
    private val k = Intrinsics(500f, 501f, 320f, 240f, 640, 480)

    @Test
    fun stableIntrinsicsAreReportedUnchanged() {
        val tracker = IntrinsicsTracker()
        assertTrue(tracker.update(0, k))
        assertFalse(tracker.update(30, k))
        val json = tracker.toJson(complete = true)
        assertEquals(false, json["changed_during_recording"])
        assertEquals(500f, json["fx"])
        assertEquals(k.toJson(), json["at_end"])
    }

    @Test
    fun changesAreDetectedWithFirstFrame() {
        val tracker = IntrinsicsTracker()
        tracker.update(0, k)
        tracker.update(30, k)
        tracker.update(60, k.copy(cx = 321f))
        val json = tracker.toJson(complete = true)
        assertEquals(true, json["changed_during_recording"])
        assertEquals(2, json["distinct_values"])
        assertEquals(60L, json["first_change_frame_index"])
    }
}
