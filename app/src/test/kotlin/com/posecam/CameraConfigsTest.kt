package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraConfigsTest {
    @Test
    fun picksSizeClosestToTarget() {
        val sizes = listOf(1920 to 1080, 640 to 480, 1280 to 720)
        assertEquals(1, CameraConfigs.closestIndex(sizes, 640, 480))
        assertEquals(2, CameraConfigs.closestIndex(sizes, 1280, 720))
    }

    @Test
    fun fallsBackToNearestWhenTargetMissing() {
        assertEquals(0, CameraConfigs.closestIndex(listOf(1280 to 720, 1920 to 1080), 640, 480))
    }
}
