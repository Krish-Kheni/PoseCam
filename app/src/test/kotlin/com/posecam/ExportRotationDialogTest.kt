package com.posecam

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Looper
import org.junit.Assert.assertEquals
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
class ExportRotationDialogTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).create().get()

    private fun shown(current: Int?, onChosen: (Int) -> Unit): AlertDialog {
        ExportRotationDialog.show(activity, current, "Save", onChosen = onChosen)
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    @Test fun theCurrentRotationIsPreselected_andConfirmingReportsIt() {
        val chosen = mutableListOf<Int>()
        val dialog = shown(90) { chosen += it }

        assertEquals(1, dialog.listView.checkedItemPosition)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(90), chosen)
    }

    @Test fun anotherChoiceIsReportedAsTheDegreesItStandsFor() {
        val chosen = mutableListOf<Int>()
        val dialog = shown(0) { chosen += it }

        dialog.listView.setItemChecked(3, true)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(270), chosen)
    }

    @Test fun unsetStartsAtNoRotationAndCancellingSavesNothing() {
        val chosen = mutableListOf<Int>()
        val dialog = shown(null) { chosen += it }

        assertEquals(0, dialog.listView.checkedItemPosition)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(chosen.isEmpty())
    }
}
