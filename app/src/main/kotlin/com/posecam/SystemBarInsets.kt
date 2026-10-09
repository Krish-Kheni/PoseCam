package com.posecam

import android.app.Activity
import android.view.ViewGroup

/**
 * Targeting a recent SDK draws content edge to edge, so a screen's top rows sit under the action bar and the status
 * bar. Pads the activity's layout by exactly what covers it (the inset already includes the action bar), keeping the
 * layout's own 16dp padding on top of that.
 */
@Suppress("DEPRECATION")
fun Activity.keepBelowSystemBars() {
    val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    val base = (16 * resources.displayMetrics.density).toInt()
    root.setOnApplyWindowInsetsListener { view, insets ->
        view.setPadding(
            base + insets.systemWindowInsetLeft,
            base + insets.systemWindowInsetTop,
            base + insets.systemWindowInsetRight,
            base + insets.systemWindowInsetBottom,
        )
        insets
    }
    root.requestApplyInsets()
}
