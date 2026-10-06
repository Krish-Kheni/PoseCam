package com.posecam

import android.app.Activity
import android.app.AlertDialog
import com.posecam.core.sync.CloudSync
import com.posecam.core.sync.CloudSyncSettings

/**
 * "Which way is up?": the rotation the pipeline's gripper detector needs, which depends on how the phone sits on the mount.
 * One dialog for every place that edits it: the manual "Export for pipeline" in Recordings, the Cloud sync settings, and
 * the one-time question that precedes the first automatic export.
 */
object ExportRotationDialog {
    private val VALUES = CloudSyncSettings.ROTATION_CHOICES.toIntArray()
    private val LABELS = arrayOf(
        "No rotation — phone mounted sideways (landscape)",
        "90° — phone mounted upright (portrait)",
        "180°",
        "270°",
    )

    /** [current] is preselected (null selects "no rotation" without saving anything until the user confirms). */
    fun show(activity: Activity, current: Int?, confirmLabel: String, message: String? = null, onChosen: (Int) -> Unit) {
        val selected = VALUES.indexOf(current ?: 0).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("Which way is up?")
            .apply { if (message != null) setMessage(message) }
            .setSingleChoiceItems(LABELS, selected, null)
            .setPositiveButton(confirmLabel) { dialog, _ ->
                onChosen(VALUES[(dialog as AlertDialog).listView.checkedItemPosition.coerceAtLeast(0)])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Pipeline exports are made automatically once a recording is filed under a pipe, with this rotation, and nothing is
     * exported until it is known. So the first time a collector files a recording they are asked once; the answer lives in
     * Cloud sync settings from then on. Declining only postpones the exports (the raw upload is unaffected).
     */
    fun askOnceIfUnset(activity: Activity, sync: CloudSync) {
        if (!sync.config.enabled || sync.settings.exportRotationDegrees != null) return
        show(
            activity, current = null, confirmLabel = "Save",
            message = "Recordings are turned into the labeling format automatically, with the video rotated this way. " +
                "You choose once; change it later in Cloud sync settings.",
        ) { sync.setExportRotation(it) }
    }
}
