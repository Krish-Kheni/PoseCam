package com.posecam.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadKickTest {
    private fun kick(
        signedIn: Boolean = true, policy: SyncPolicy = SyncPolicy.WIFI_ONLY, network: Boolean = true,
        running: Boolean = false, since: Long = 60_000, work: Boolean = true,
    ) = UploadKick.shouldKick(signedIn, policy, network, running, since, work)

    @Test fun startsAStalledQueue() = assertTrue(kick())

    @Test fun neverWhenSignedOutOrManualOrOffPolicyNetwork() {
        assertFalse(kick(signedIn = false))
        assertFalse(kick(policy = SyncPolicy.MANUAL_ONLY))
        assertFalse(kick(network = false)) // Wi-Fi only on mobile data stays put
    }

    @Test fun neverWhileAPassIsRunningOrWithNothingToSend() {
        assertFalse(kick(running = true))
        assertFalse(kick(work = false))
    }

    @Test fun notMoreOftenThanEveryHalfMinute() {
        assertFalse(kick(since = 10_000))
        assertTrue(kick(since = 30_000))
    }
}
