package com.posecam

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Looper
import android.view.KeyEvent
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

    private fun shown(redo: Boolean, cloud: Boolean, onPipe: (Pipe) -> Unit = {}): AlertDialog {
        TakeResultDialog.show(activity, "Take looks good", "20 s, 600 frames, 100% tracked", redo, cloud, onPipe)
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun AlertDialog.label(which: Int) = getButton(which)?.text?.toString()

    @Test fun aGoodTakeWithCloudOnOffersExactlyTheThreePipes() {
        val dialog = shown(redo = false, cloud = true)

        assertEquals("White pipe", dialog.label(DialogInterface.BUTTON_POSITIVE))
        assertEquals("Black pipe", dialog.label(DialogInterface.BUTTON_NEGATIVE))
        assertEquals("Black/White pipe", dialog.label(DialogInterface.BUTTON_NEUTRAL))
        assertEquals("Take looks good", shadowOf(dialog).title.toString())
    }

    @Test fun everyPipeButtonCarriesItsOwnIcon() {
        val dialog = shown(redo = false, cloud = true)

        fun icon(which: Int) = shadowOf(dialog.getButton(which).compoundDrawablesRelative[0]).createdFromResId
        assertEquals(R.drawable.ic_pipe_white, icon(DialogInterface.BUTTON_POSITIVE))
        assertEquals(R.drawable.ic_pipe_black, icon(DialogInterface.BUTTON_NEGATIVE))
        assertEquals(R.drawable.ic_pipe_black_white, icon(DialogInterface.BUTTON_NEUTRAL))
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

    @Test fun whitePipeReportsWhiteAndDismisses() {
        val chosen = mutableListOf<Pipe>()
        val dialog = shown(redo = false, cloud = true) { chosen += it }

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(Pipe.WHITE), chosen)
        assertFalse(dialog.isShowing)
    }

    @Test fun blackPipeReportsBlackAndDismisses() {
        val chosen = mutableListOf<Pipe>()
        val dialog = shown(redo = false, cloud = true) { chosen += it }

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(Pipe.BLACK), chosen)
        assertFalse(dialog.isShowing)
    }

    @Test fun blackWhitePipeReportsBlackWhiteAndDismisses() {
        val chosen = mutableListOf<Pipe>()
        val dialog = shown(redo = false, cloud = true) { chosen += it }

        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(Pipe.BLACK_WHITE), chosen)
        assertFalse(dialog.isShowing)
    }

    @Test fun aTakeToRedoJustHasCloseAndNeverAsksForAPipe() {
        val chosen = mutableListOf<Pipe>()
        val dialog = shown(redo = true, cloud = true) { chosen += it }

        assertEquals("Close", dialog.label(DialogInterface.BUTTON_POSITIVE))
        assertNull(dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.takeIf { it.visibility == android.view.View.VISIBLE })
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
