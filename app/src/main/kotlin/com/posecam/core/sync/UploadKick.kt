package com.posecam.core.sync

/**
 * Decides when the app, while on screen, should start the upload queue itself instead of trusting that WorkManager
 * will. WorkManager runs a job only when *its* idea of the network matches the constraint, and that idea can lag or
 * differ from what the phone is actually doing (a Wi-Fi it has not validated, a stale callback), which left "N sessions
 * queued for upload" on screen with nothing happening until the app process was restarted. The kick respects the
 * collector's policy using the app's own reading of the network.
 */
object UploadKick {
    const val EVERY_MS = 30_000L

    fun shouldKick(
        signedIn: Boolean,
        policy: SyncPolicy,
        networkSatisfiesPolicy: Boolean,
        passRunning: Boolean,
        sinceLastKickMs: Long,
        hasWork: Boolean,
    ): Boolean = signedIn && policy != SyncPolicy.MANUAL_ONLY && networkSatisfiesPolicy && !passRunning &&
        sinceLastKickMs >= EVERY_MS && hasWork
}
