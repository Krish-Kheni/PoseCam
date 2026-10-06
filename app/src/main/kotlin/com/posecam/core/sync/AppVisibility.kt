package com.posecam.core.sync

/** Whether the app UI is on screen, so sync events become a snackbar instead of a notification. */
object AppVisibility {
    @Volatile
    var inForeground: Boolean = false
}
