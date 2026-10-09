package com.posecam.core.sync

/** Every user-facing string of the Recordings screen's cloud features, as pure functions so they are unit-tested. */
object CloudUiText {
    /** The second line of a recording's row. */
    fun rowStatus(
        summary: SessionCloudSummary?,
        policy: SyncPolicy,
        waitingForNetwork: Boolean,
        /** False until the collector has chosen the export rotation: exports wait for it. */
        exportRotationSet: Boolean = true,
    ): String {
        if (summary == null) return "On this phone only"
        return when (val status = summary.displayStatus(waitingForNetwork)) {
            SessionCloudStatus.LOCAL_ONLY -> "On this phone only"
            SessionCloudStatus.AWAITING_PIPE -> "Choose a pipe to upload"
            SessionCloudStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
            SessionCloudStatus.PENDING ->
                if (policy == SyncPolicy.MANUAL_ONLY) "Not uploaded (manual mode)" else "Queued for upload"
            SessionCloudStatus.UPLOADING -> CloudCardAction.infoMessage(status, summary, recordingInProgress = false)
            SessionCloudStatus.VERIFYING -> "Verifying…"
            SessionCloudStatus.SYNCED -> syncedLine(summary, exportRotationSet)
            SessionCloudStatus.FAILED -> "Upload failed, tap to retry"
        }
    }

    /**
     * "Synced", plus what is wrong with the pipeline export if anything. A recording without an export will never appear
     * in LabelNow, so a bare "Synced" would hide exactly the thing the collector needs to know.
     */
    fun syncedLine(summary: SessionCloudSummary, exportRotationSet: Boolean = true): String = when {
        summary.exportState == ExportState.OFF_PROTOCOL -> "Synced — no pipeline export (off protocol)"
        summary.exportState == ExportState.NOT_EXPORTABLE -> "Synced — no pipeline export (cannot be exported)"
        summary.exportState == ExportState.FAILED -> "Synced — pipeline export failed, tap to retry"
        summary.exportState == ExportState.PENDING ->
            if (exportRotationSet) "Synced — making the pipeline export…" else "Synced — set the video rotation to make the pipeline export"
        summary.exportFilesFailed > 0 -> "Synced — pipeline export upload failed, tap to retry"
        summary.exportFilesOpen > 0 -> "Synced — uploading the pipeline export…"
        else -> publishLine(summary)
    }

    /**
     * Everything is uploaded and the export is in: the last step is the backend putting the recording on the website. The
     * collector only sees "Done" once that has really happened (an export with no sets yet is still on its way).
     */
    private fun publishLine(summary: SessionCloudSummary): String = when (summary.publishState) {
        PublishState.UNSUPPORTED -> "Synced"
        PublishState.FAILED -> "Publishing failed — ${summary.publishNote ?: "unknown error"}"
        PublishState.DONE ->
            if (summary.publishedSets > 0) "Done — live on website" else "Uploaded — publishing to website…"
        PublishState.PENDING -> "Uploaded — publishing to website…"
    }

    /** The dialog behind "Why isn't it on the website?"; null unless the backend gave up publishing it. */
    fun publishFailedDetail(summary: SessionCloudSummary): String? =
        if (summary.publishState != PublishState.FAILED) null
        else "The recording is safely uploaded, but the server could not put it on the website:\n\n" +
            (summary.publishNote ?: "unknown error")

    /** The dialog behind "Why no pipeline export?": the exporter's own words, which name what was wrong with the recording. */
    fun missingExportDetail(summary: SessionCloudSummary): String? = when (summary.exportState) {
        ExportState.OFF_PROTOCOL, ExportState.NOT_EXPORTABLE ->
            (summary.exportNote ?: "This recording cannot be exported.") +
                "\n\nIt is uploaded as raw data, but it will not appear in LabelNow."
        ExportState.FAILED -> "Making the pipeline export failed: ${summary.exportNote ?: "unknown error"}"
        else -> null
    }

    /** What the Cloud sync settings show for the export rotation. */
    fun rotationLabel(degrees: Int?): String = when (degrees) {
        null -> "Not set: pipeline exports wait until you choose"
        0 -> "No rotation (phone mounted sideways)"
        90 -> "90° (phone mounted upright)"
        else -> "$degrees°"
    }

    /** [rotationLabel] for a settings row's second line, where the long form would wrap. */
    fun rotationShortLabel(degrees: Int?): String = when (degrees) {
        null -> "Not set"
        0 -> "Sideways (no rotation)"
        90 -> "Upright (90\u00b0)"
        else -> "$degrees\u00b0"
    }

    /** The action menu label for [CloudCardAction.START] / [CloudCardAction.RETRY]; null for an info-only state. */
    fun actionLabel(action: CloudCardAction): String? = when (action) {
        CloudCardAction.START -> "Upload to cloud now"
        CloudCardAction.RETRY -> "Retry failed upload"
        CloudCardAction.CHOOSE_PIPE, CloudCardAction.INFO -> null // CHOOSE_PIPE has one entry per pipe: see [pipeActionLabel]
    }

    /** "Upload as White pipe" / "Upload as Black pipe". */
    fun pipeActionLabel(pipe: Pipe): String = "Upload as ${pipe.label}"

    /** The line the end-of-take dialog shows once the collector has chosen. */
    fun pipeChosenMessage(pipe: Pipe, policy: SyncPolicy, networkOk: Boolean): String = when {
        policy == SyncPolicy.MANUAL_ONLY -> "${pipe.label}: upload is manual, use Recordings to start it."
        networkOk -> "Uploading to ${pipe.label} (${policyShort(policy)})."
        else -> "${pipe.label}: waiting for ${if (policy == SyncPolicy.WIFI_ONLY) "Wi-Fi" else "a network"} to upload."
    }

    private fun policyShort(policy: SyncPolicy) = if (policy == SyncPolicy.WIFI_ONLY) "Wi-Fi only" else "any network"

    /** What the Delete dialog says: the real cloud state instead of "make sure it has been shared". */
    fun deleteWarning(cloudEnabled: Boolean, summary: SessionCloudSummary?): String {
        if (!cloudEnabled) return "This cannot be undone. Make sure it has been shared or saved first."
        return if (summary?.isLiveOnWebsite == true) {
            "This recording is live on the website and safe in the cloud, so deleting it only frees space on this phone."
        } else if (summary?.isSynced == true) {
            "This recording is synced to the cloud, so deleting it only frees space on this phone. " +
                "It may not be on the website yet."
        } else {
            "Not uploaded yet: this deletes the only copy. This cannot be undone."
        }
    }

    fun policyLabel(policy: SyncPolicy): String = when (policy) {
        SyncPolicy.WIFI_ONLY -> "Wi-Fi only"
        SyncPolicy.ANY_NETWORK -> "Any network"
        SyncPolicy.MANUAL_ONLY -> "Manual only"
    }

    fun retentionLabel(enabled: Boolean, days: Int): String =
        if (!enabled) "off" else if (days == 1) "after 1 day" else "after $days days"

    /** "2 synced, 3 waiting" for the storage header; empty when there is nothing to say. */
    fun storageSummary(summaries: Collection<SessionCloudSummary>): String {
        val synced = summaries.count { it.status == SessionCloudStatus.SYNCED }
        val waiting = summaries.count {
            it.status == SessionCloudStatus.PENDING || it.status == SessionCloudStatus.UPLOADING ||
                it.status == SessionCloudStatus.VERIFYING || it.status == SessionCloudStatus.WAITING_FOR_WIFI
        }
        val failed = summaries.count { it.status == SessionCloudStatus.FAILED }
        val needPipe = summaries.count { it.status == SessionCloudStatus.AWAITING_PIPE }
        return listOfNotNull(
            "$synced synced".takeIf { synced > 0 },
            "$waiting waiting".takeIf { waiting > 0 },
            "$failed failed".takeIf { failed > 0 },
            "$needPipe need a pipe".takeIf { needPipe > 0 },
        ).joinToString(", ")
    }

    /** The mobile-data confirmation: the byte estimate the user is about to spend. */
    fun mobileDataMessage(totalBytes: Long): String =
        "You are on mobile data. Uploading " +
            (if (totalBytes > 0) "about ${CloudOverview.formatBytes(totalBytes)}" else "this recording") +
            " may use a lot of it."
}
