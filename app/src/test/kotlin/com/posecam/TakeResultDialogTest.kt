package com.posecam

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.posecam.core.sync.Pipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TakeResultDialogTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).create().get()

    private fun shown(redo: Boolean, cloud: Boolean, pipes: List<Pipe> = Pipe.DEFAULTS, onPipe: (Pipe) -> Unit = {}): AlertDialog {
        TakeResultDialog.show(activity, "Take looks good", "20 s, 600 frames, 100% tracked", redo, cloud, pipes, onPipe)
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun AlertDialog.label(which: Int) = getButton(which)?.text?.toString()

    private val DIALOG_BUTTON_IDS = setOf(android.R.id.button1, android.R.id.button2, android.R.id.button3)

    /** The pipe buttons of the choice dialog, in order (not the dialog's own, empty, positive/negative/neutral ones). */
    private fun AlertDialog.pipeButtons(): List<Button> {
        val found = mutableListOf<Button>()
        fun walk(view: View) {
            if (view is Button && view.id !in DIALOG_BUTTON_IDS) found += view
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(window!!.decorView.findViewById(android.R.id.content))
        return found
    }

    private fun AlertDialog.pipeLabels() = pipeButtons().map { it.text.toString() }

    @Test fun aGoodTakeWithCloudOnOffersTheThreeBuiltInPipesByDefault() {
        val dialog = shown(redo = false, cloud = true)

        assertEquals(listOf("White pipe", "Black pipe", "Black/White pipe"), dialog.pipeLabels())
        assertEquals("Take looks good", shadowOf(dialog).title.toString())
    }

    @Test fun theDialogOffersEveryPipeItIsGiven() {
        val pipes = Pipe.DEFAULTS + listOf(Pipe("pink", "Pink Pipes", 0xFFEC4899.toInt()), Pipe("green", "Green Pipes", 0xFF22C55E.toInt()))
        val dialog = shown(redo = false, cloud = true, pipes = pipes)

        assertEquals(listOf("White pipe", "Black pipe", "Black/White pipe", "Pink Pipes", "Green Pipes"), dialog.pipeLabels())
    }

    @Test fun theOriginalPipesKeepTheirIconsAndANewOneGetsADot() {
        val dialog = shown(redo = false, cloud = true, pipes = Pipe.DEFAULTS + Pipe("pink", "Pink Pipes", 0xFFEC4899.toInt()))
        val buttons = dialog.pipeButtons()

        fun icon(button: Button) = shadowOf(button.compoundDrawablesRelative[0]).createdFromResId
        assertEquals(R.drawable.ic_pipe_white, icon(buttons[0]))
        assertEquals(R.drawable.ic_pipe_black, icon(buttons[1]))
        assertEquals(R.drawable.ic_pipe_black_white, icon(buttons[2]))
        assertTrue("pink is drawn, not a resource", buttons[3].compoundDrawablesRelative[0] is GradientDrawable)
    }

    @Test fun theChoiceCannotBeSkippedWithBackOrATapOutside() {
        val dialog = shown(redo = false, cloud = true)

        assertFalse(shadowOf(dialog).isCancelable)
        // A real Back press (down + up) and a tap outside: neither may dismiss it.
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("still up after Back", dialog.isShowing)
    }

    @Test fun tappingAPipeReportsThatPipeAndDismisses() {
        val pipes = Pipe.DEFAULTS + Pipe("pink", "Pink Pipes", 0xFFEC4899.toInt())
        pipes.forEachIndexed { index, pipe ->
            val chosen = mutableListOf<Pipe>()
            val dialog = shown(redo = false, cloud = true, pipes = pipes) { chosen += it }

            dialog.pipeButtons()[index].performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(listOf(pipe), chosen)
            assertFalse(dialog.isShowing)
        }
    }

    @Test fun aTakeToRedoJustHasCloseAndNeverAsksForAPipe() {
        val chosen = mutableListOf<Pipe>()
        val dialog = shown(redo = true, cloud = true) { chosen += it }

        assertEquals("Close", dialog.label(DialogInterface.BUTTON_POSITIVE))
        assertNull(dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.takeIf { it.visibility == android.view.View.VISIBLE })
        assertTrue("no pipe buttons for a take to redo", dialog.pipeButtons().isEmpty())
        assertTrue(shadowOf(dialog).isCancelable)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        assertTrue(chosen.isEmpty())
    }

    @Test fun withCloudOffTheDialogIsExactlyWhatItAlwaysWas() {
        val chosen = mutableListOf<Pipe>()
        val dialog = shown(redo = false, cloud = false) { chosen += it }

        assertEquals("Close", dialog.label(DialogInterface.BUTTON_POSITIVE))
        assertTrue(shadowOf(dialog).isCancelable)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        assertTrue(chosen.isEmpty())
    }

    @Test fun onlyAGoodTakeWithCloudOnAsks() {
        assertTrue(TakeResultDialog.asksForPipe(redo = false, cloudEnabled = true))
        assertFalse(TakeResultDialog.asksForPipe(redo = true, cloudEnabled = true))
        assertFalse(TakeResultDialog.asksForPipe(redo = false, cloudEnabled = false))
        assertFalse(TakeResultDialog.asksForPipe(redo = true, cloudEnabled = false))
    }
}
