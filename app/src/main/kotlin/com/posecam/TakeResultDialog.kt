package com.posecam

import android.app.Activity
import android.app.AlertDialog
import com.posecam.core.sync.Pipe

/**
 * The dialog shown when a take ends. Without cloud upload it is exactly what PoseCam always showed: a verdict and Close.
 * With cloud upload on, a take that "looks good" asks which pipe to file it under (White pipe / Black pipe); the upload
 * starts only after that choice, and into that pipe's folder. A take that should be redone keeps Close: it is not uploaded
 * automatically, but can still be filed later from Recordings.
 */
object TakeResultDialog {
    /** Whether this dialog must make the collector choose a pipe. */
    fun asksForPipe(redo: Boolean, cloudEnabled: Boolean): Boolean = cloudEnabled && !redo

    fun show(activity: Activity, headline: String, body: String, redo: Boolean, cloudEnabled: Boolean, onPipe: (Pipe) -> Unit) {
        val dialog = AlertDialog.Builder(activity).setTitle(headline).setMessage(body)
        if (asksForPipe(redo, cloudEnabled)) {
            // Not cancelable on purpose: tapping outside or pressing Back must not skip the choice.
            dialog.setCancelable(false)
                .setPositiveButton(Pipe.WHITE.label) { _, _ -> onPipe(Pipe.WHITE) }
                .setNegativeButton(Pipe.BLACK.label) { _, _ -> onPipe(Pipe.BLACK) }
        } else {
            dialog.setPositiveButton(R.string.close, null)
        }
        val shown = dialog.show()
        if (asksForPipe(redo, cloudEnabled)) {
            val gap = (8 * activity.resources.displayMetrics.density).toInt()
            shown.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_pipe_white, 0, 0, 0)
                compoundDrawablePadding = gap
            }
            shown.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_pipe_black, 0, 0, 0)
                compoundDrawablePadding = gap
            }
        }
    }
}
