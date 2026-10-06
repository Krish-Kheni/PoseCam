package com.posecam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.annotation.DrawableRes
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.posecam.core.sync.CloudCardAction
import com.posecam.core.sync.CloudOverview
import com.posecam.core.sync.CloudSettingsSnapshot
import com.posecam.core.sync.CloudSync
import com.posecam.core.sync.CloudSyncSettings
import com.posecam.core.sync.CloudUiText
import com.posecam.core.sync.ExportState
import com.posecam.core.sync.MobileDataGuard
import com.posecam.core.sync.Pipe
import com.posecam.core.sync.SessionCloudStatus
import com.posecam.core.sync.SessionCloudSummary
import com.posecam.core.sync.SyncPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Binds cloud upload state to the Recordings screen's plain Views. It only exists when cloud upload is
 * configured: [SessionsActivity] creates none otherwise, which is what keeps that screen exactly as it was.
 *
 * `android.app.Activity` is not a LifecycleOwner, so there is no lifecycleScope: collection starts in
 * [start] (onStart) and is cancelled in [stop] (onStop).
 */
class SessionsCloudUi(
    private val activity: Activity,
    private val sync: CloudSync,
    /** Called when summaries or settings changed, so the list can redraw its second lines. */
    private val onChanged: () -> Unit,
) {
    class Action(val label: String, val run: () -> Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var summaries: Map<String, SessionCloudSummary> = emptyMap()
    private var waitingForNetwork = false
    private var settings: CloudSettingsSnapshot = sync.settingsFlow.value

    private val banner: View = activity.findViewById(R.id.cloudBanner)
    private val bannerText: TextView = activity.findViewById(R.id.cloudBannerText)
    private val bannerIcon: ImageView = activity.findViewById(R.id.cloudBannerIcon)
    private val bannerDetail: TextView = activity.findViewById(R.id.cloudBannerDetail)
    private val bannerProgress: ProgressBar = activity.findViewById(R.id.cloudBannerProgress)
    private val bannerAction: Button = activity.findViewById(R.id.cloudBannerAction)

    fun start() {
        scope.launch {
            combine(sync.observeSummaries(), sync.waitingForNetwork(), sync.settingsFlow) { s, waiting, snapshot ->
                Triple(s, waiting, snapshot)
            }.collect { (s, waiting, snapshot) ->
                summaries = s
                waitingForNetwork = waiting
                settings = snapshot
                render()
                onChanged()
            }
        }
        // Results of the user's own taps and sync events while the app is on screen (otherwise: notifications).
        scope.launch { sync.notifier.messages.collect { Toast.makeText(activity, it.text, Toast.LENGTH_LONG).show() } }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
    }

    /** Frees space held by recordings that are already safe in the cloud; no-op if nothing qualifies. */
    fun reclaimStorage() = sync.reclaimStorage()

    // ---- list ------------------------------------------------------------------------------

    /** The second line of a recording's row. */
    fun rowLine(sessionId: String): String =
        CloudUiText.rowStatus(summaries[sessionId], settings.policy, waitingForNetwork, settings.exportRotationDegrees != null)

    /** The icon drawn before a recording's row; one per cloud status, so the state reads at a glance. */
    @DrawableRes
    fun rowIcon(sessionId: String): Int =
        when (summaries[sessionId]?.displayStatus(waitingForNetwork) ?: SessionCloudStatus.LOCAL_ONLY) {
            SessionCloudStatus.LOCAL_ONLY -> R.drawable.ic_cloud_local
            SessionCloudStatus.AWAITING_PIPE -> R.drawable.ic_cloud_choose_pipe
            SessionCloudStatus.WAITING_FOR_WIFI, SessionCloudStatus.PENDING -> R.drawable.ic_cloud_queued
            SessionCloudStatus.UPLOADING -> R.drawable.ic_cloud_uploading
            SessionCloudStatus.VERIFYING -> R.drawable.ic_cloud_verifying
            SessionCloudStatus.SYNCED -> R.drawable.ic_cloud_synced
            SessionCloudStatus.FAILED -> R.drawable.ic_cloud_failed
        }

    /** "2 synced, 3 waiting" for the storage header, or empty. */
    fun storageSummary(): String = CloudUiText.storageSummary(summaries.values)

    /** Menu entries to put before the existing ones for this recording. */
    fun actionsFor(sessionId: String): List<Action> {
        val summary = summaries[sessionId]
        val status = (summary?.displayStatus(waitingForNetwork)) ?: com.posecam.core.sync.SessionCloudStatus.LOCAL_ONLY
        val action = CloudCardAction.forStatus(status, recordingInProgress = false)
        if (action == CloudCardAction.CHOOSE_PIPE) {
            // Finished recordings that were never filed (a take to redo, an older recording, a killed take).
            return Pipe.entries.map { pipe ->
                Action(CloudUiText.pipeActionLabel(pipe)) {
                    sync.coordinator.choosePipe(sessionId, pipe)
                    Toast.makeText(activity, "Filed under ${pipe.label}", Toast.LENGTH_SHORT).show()
                    ExportRotationDialog.askOnceIfUnset(activity, sync)
                }
            }
        }
        val label = CloudUiText.actionLabel(action)
        if (label != null) {
            return listOf(
                Action(label) {
                    confirmMobileData(summary?.totalBytes ?: 0) {
                        sync.coordinator.syncSession(sessionId, retryFailedFiles = action == CloudCardAction.RETRY)
                        Toast.makeText(activity, "Uploading…", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
        val info = CloudCardAction.infoMessage(status, summary, recordingInProgress = false)
        return (if (info.isEmpty()) emptyList() else listOf(Action("Cloud: $info") {})) + exportActions(sessionId, summary)
    }

    /**
     * What a synced recording can still do about its pipeline export: say why there is none (it will never appear in
     * LabelNow, which a bare "Synced" would hide), or run a crashed export / failed export upload again.
     */
    private fun exportActions(sessionId: String, summary: SessionCloudSummary?): List<Action> {
        if (summary == null || !summary.isSynced) return emptyList()
        val actions = mutableListOf<Action>()
        CloudUiText.missingExportDetail(summary)?.let { detail ->
            actions += Action("Why no pipeline export?") {
                AlertDialog.Builder(activity).setTitle("No pipeline export").setMessage(detail)
                    .setPositiveButton(R.string.close, null).show()
            }
        }
        if (summary.exportState == ExportState.FAILED || summary.exportFilesFailed > 0) {
            actions += Action("Retry pipeline export") {
                sync.coordinator.syncSession(sessionId, retryFailedFiles = true)
                Toast.makeText(activity, "Retrying the pipeline export…", Toast.LENGTH_SHORT).show()
            }
        }
        return actions
    }

    fun deleteWarning(sessionId: String): String = CloudUiText.deleteWarning(true, summaries[sessionId])

    fun onDeleted(sessionId: String) = sync.coordinator.onSessionDeletedLocally(sessionId)

    // ---- banner ----------------------------------------------------------------------------

    private fun render() {
        val overview = CloudOverview.from(summaries.values, settings.policy, waitingForNetwork)
        if (!overview.isBusy) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        bannerText.text = overview.message
        styleBanner(overview.kind)
        if (overview.kind == CloudOverview.Kind.SYNCING) {
            bannerDetail.text = overview.progressText
            bannerDetail.visibility = View.VISIBLE
            bannerProgress.visibility = View.VISIBLE
            bannerProgress.isIndeterminate = overview.progressPercent < 0
            bannerProgress.progress = overview.progressPercent.coerceAtLeast(0)
        } else {
            bannerDetail.visibility = View.GONE
            bannerProgress.visibility = View.GONE
        }
        when (overview.kind) {
            CloudOverview.Kind.FAILED -> showBannerAction("Retry") { sync.coordinator.retryFailed(null) }
            CloudOverview.Kind.WAITING -> showBannerAction("Sync now") {
                confirmMobileData(overview.totalBytes) { sync.coordinator.requestSync() }
            }
            else -> bannerAction.visibility = View.GONE
        }
    }

    /** A tinted rounded card per state: red for failed, blue while syncing, grey while waiting. */
    private fun styleBanner(kind: CloudOverview.Kind) {
        val (background, accent, icon) = when (kind) {
            CloudOverview.Kind.FAILED -> Triple(0xFFFDECEA.toInt(), 0xFFC62828.toInt(), R.drawable.ic_cloud_failed)
            CloudOverview.Kind.SYNCING -> Triple(0xFFE3F2FD.toInt(), 0xFF1565C0.toInt(), R.drawable.ic_cloud_uploading)
            else -> Triple(0xFFECEFF1.toInt(), 0xFF455A64.toInt(), R.drawable.ic_cloud_queued)
        }
        banner.background = GradientDrawable().apply {
            setColor(background)
            cornerRadius = 12 * activity.resources.displayMetrics.density
        }
        bannerIcon.setImageResource(icon)
        bannerAction.setTextColor(accent)
        bannerProgress.progressTintList = ColorStateList.valueOf(accent)
        bannerProgress.indeterminateTintList = ColorStateList.valueOf(accent)
    }

    private fun showBannerAction(label: String, run: () -> Unit) {
        bannerAction.text = label
        bannerAction.visibility = View.VISIBLE
        bannerAction.setOnClickListener { run() }
    }

    // ---- mobile data -----------------------------------------------------------------------

    /** Runs [proceed] now, or after the user agrees to spend mobile data on it. */
    private fun confirmMobileData(totalBytes: Long, proceed: () -> Unit) {
        if (!MobileDataGuard.needsConfirmation(settings.policy, sync.isMetered(), settings.confirmMobileData)) {
            proceed()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Use mobile data?")
            .setMessage(CloudUiText.mobileDataMessage(totalBytes))
            .setPositiveButton("Upload") { _, _ -> proceed() }
            .setNeutralButton("Upload, don't ask again") { _, _ ->
                sync.setConfirmMobileData(false)
                proceed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- settings --------------------------------------------------------------------------

    /** One dialog, every option visible; each change is saved the moment it is tapped. */
    /** Opened from the gear icon in the Recordings header. */
    fun showSettings() {
        val s = settings
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        fun header(text: String, hint: String) = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(4))
            addView(TextView(activity).apply { this.text = text; textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(activity).apply { this.text = hint; textSize = 13f; alpha = 0.7f })
        }

        fun radio(label: String, checked: Boolean) = RadioButton(activity).apply {
            id = View.generateViewId()
            text = label
            isChecked = checked
            setPadding(dp(4), dp(8), 0, dp(8))
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }

        content.addView(header("Upload over", "When recordings are sent to the cloud."))
        val policies = SyncPolicy.entries
        val policyHints = mapOf(
            SyncPolicy.WIFI_ONLY to "Wi-Fi only (recommended)",
            SyncPolicy.ANY_NETWORK to "Any network, including mobile data",
            SyncPolicy.MANUAL_ONLY to "Manual only: upload when I press Sync now",
        )
        val policyGroup = RadioGroup(activity)
        val policyIds = policies.map { policy -> radio(policyHints.getValue(policy), policy == s.policy).also { policyGroup.addView(it) }.id }
        policyGroup.setOnCheckedChangeListener { _, checkedId ->
            val index = policyIds.indexOf(checkedId)
            if (index >= 0) sync.setPolicy(policies[index])
        }
        content.addView(policyGroup)

        content.addView(header("Pipeline export", "Recordings are turned into the labeling format and uploaded automatically. Which way is up in the video?"))
        val rotation = TextView(activity).apply {
            text = CloudUiText.rotationLabel(sync.settings.exportRotationDegrees)
            setPadding(dp(4), dp(4), 0, dp(4))
        }
        content.addView(rotation)
        content.addView(Button(activity).apply {
            text = "Change"
            setOnClickListener { editRotation(rotation) }
        })

        content.addView(header("Free up space", "Remove a recording from this phone once the cloud has confirmed it."))
        val days = CloudSyncSettings.RETENTION_CHOICES_DAYS
        val cleanupGroup = RadioGroup(activity)
        val neverId = radio("Never delete", !s.autoCleanupEnabled).also { cleanupGroup.addView(it) }.id
        val dayIds = days.map { d ->
            radio(if (d == 1) "After 1 day" else "After $d days", s.autoCleanupEnabled && s.retentionDays == d)
                .also { cleanupGroup.addView(it) }.id
        }
        cleanupGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == neverId) {
                sync.setAutoCleanupEnabled(false)
            } else {
                val index = dayIds.indexOf(checkedId)
                if (index >= 0) {
                    sync.setAutoCleanupEnabled(true)
                    sync.setRetentionDays(days[index])
                }
            }
        }
        content.addView(cleanupGroup)

        content.addView(header("Mobile data", "Applies to manual uploads on a metered network."))
        content.addView(Switch(activity).apply {
            text = "Ask before using mobile data"
            isChecked = s.confirmMobileData
            setPadding(dp(4), dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> sync.setConfirmMobileData(checked) }
        })

        AlertDialog.Builder(activity)
            .setTitle("Cloud sync settings")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun editRotation(label: TextView) {
        ExportRotationDialog.show(activity, sync.settings.exportRotationDegrees, "Save") {
            sync.setExportRotation(it)
            label.text = CloudUiText.rotationLabel(it)
        }
    }

    // ---- notification permission -----------------------------------------------------------

    /**
     * Android 13+ needs a runtime grant for the progress and result notifications. Asked once; refusing it never
     * blocks uploads, it only hides the notifications.
     */
    fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (sync.settings.notificationPermissionAsked) return
        sync.settings.notificationPermissionAsked = true
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 2
    }
}
