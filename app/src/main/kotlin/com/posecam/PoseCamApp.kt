package com.posecam

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.posecam.core.sync.AppVisibility
import com.posecam.core.sync.CloudSync

/**
 * Exists for two reasons: WorkManager can start the process with no activity at all (so cloud upload must
 * not depend on one), and "is the app on screen?" decides whether a sync event is shown in the app or as a
 * notification. PoseCam has two activities, so a started-activity counter stands in for a single main one.
 *
 * When cloud upload is not configured (blank backend URL) this does nothing but count.
 */
class PoseCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                if (++started == 1) {
                    AppVisibility.inForeground = true
                    onVisible()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) AppVisibility.inForeground = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** The user is looking at the app again: drop notifications about things that are now visible in it. */
    private fun onVisible() {
        runCatching {
            val sync = CloudSync.get(this)
            if (sync.config.enabled) sync.notifier.dismissEventNotifications()
        }
    }
}
