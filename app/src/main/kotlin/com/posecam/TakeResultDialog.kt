package com.posecam

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.posecam.core.sync.Pipe

/**
 * The dialog shown when a take ends. Without cloud upload it is exactly what PoseCam always showed: a verdict and Close.
 * With cloud upload on, a take that "looks good" asks which pipe to file it under; the upload starts only after that
 * choice, and into that pipe's folder. A take that should be redone keeps Close: it is not uploaded automatically, but can
 * still be filed later from Recordings.
 *
 * The pipes come from the backend ([com.posecam.core.sync.PipeCatalog]), so there is one button per pipe, however many:
 * a dialog's three built-in buttons would cap it at three.
 */
object TakeResultDialog {
    /** Whether this dialog must make the collector choose a pipe. */
    fun asksForPipe(redo: Boolean, cloudEnabled: Boolean): Boolean = cloudEnabled && !redo

    fun show(
        activity: Activity,
        headline: String,
        body: String,
        redo: Boolean,
        cloudEnabled: Boolean,
        pipes: List<Pipe> = Pipe.DEFAULTS,
        onPipe: (Pipe) -> Unit,
    ) {
        val dialog = AlertDialog.Builder(activity).setTitle(headline)
        if (!asksForPipe(redo, cloudEnabled)) {
            dialog.setMessage(body).setPositiveButton(R.string.close, null).show()
            return
        }
        // Not cancelable on purpose: tapping outside or pressing Back must not skip the choice.
        dialog.setCancelable(false)
        lateinit var shown: AlertDialog
        dialog.setView(pipeChoiceView(activity, body, pipes) { pipe ->
            shown.dismiss()
            onPipe(pipe)
        })
        shown = dialog.show()
    }

    private fun pipeChoiceView(activity: Activity, body: String, pipes: List<Pipe>, onPipe: (Pipe) -> Unit): ScrollView {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        column.addView(
            TextView(activity).apply {
                text = body
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                setPadding(dp(4), 0, dp(4), dp(12))
            },
        )
        pipes.forEach { pipe ->
            column.addView(
                Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = pipe.label
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setCompoundDrawablesRelativeWithIntrinsicBounds(iconFor(activity, pipe), null, null, null)
                    compoundDrawablePadding = dp(8)
                    setOnClickListener { onPipe(pipe) }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        return ScrollView(activity).apply { addView(column) }
    }

    /** The three original pipes keep their drawn rings; any other pipe gets a dot in the colour the backend gave it. */
    private fun iconFor(activity: Activity, pipe: Pipe): Drawable? {
        val resource = when (pipe.wire) {
            Pipe.WHITE.wire -> R.drawable.ic_pipe_white
            Pipe.BLACK.wire -> R.drawable.ic_pipe_black
            Pipe.BLACK_WHITE.wire -> R.drawable.ic_pipe_black_white
            else -> null
        }
        if (resource != null) return activity.getDrawable(resource)
        val density = activity.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(pipe.color ?: 0xFF9CA3AF.toInt())
            setStroke((2 * density).toInt(), 0x66808080)
            setSize((24 * density).toInt(), (24 * density).toInt())
        }
    }
}
