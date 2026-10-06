package com.posecam.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.posecam.core.cloud.InstallationId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadSchedulerTest {
    private lateinit var context: Context
    private lateinit var settings: CloudSyncSettings
    private lateinit var scheduler: WorkManagerUploadScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        settings = CloudSyncSettings(context).also { it.policy = SyncPolicy.WIFI_ONLY }
        scheduler = WorkManagerUploadScheduler(context, settings)
    }

    private fun infos(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerUploadScheduler.UNIQUE_WORK_NAME).get()

    @Test
    fun defaultsToWifiOnlyAndSchedulesUnmeteredWork() = runBlocking {
        assertEquals(SyncPolicy.WIFI_ONLY, CloudSyncSettings(context).policy)

        scheduler.schedule()

        val work = infos().single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state) // not runnable: no unmetered network in the test env
        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
    }

    @Test
    fun repeatedKicksAreCoalescedIntoOneWaitingWorkerSoNoDuplicatesPileUp() = runBlocking {
        repeat(10) { scheduler.schedule() }

        assertEquals(1, infos().size)
    }

    @Test
    fun theNetworkPolicyIsPluggable() = runBlocking {
        settings.policy = SyncPolicy.ANY_NETWORK
        scheduler.reschedule()
        assertEquals(NetworkType.CONNECTED, infos().last { !it.state.isFinished }.constraints.requiredNetworkType)

        settings.policy = SyncPolicy.WIFI_ONLY
        scheduler.reschedule()
        assertEquals(NetworkType.UNMETERED, infos().last { !it.state.isFinished }.constraints.requiredNetworkType)
    }

    @Test
    fun manualOnlySchedulesNothingAutomaticallyButSyncNowStillWorks() = runBlocking {
        settings.policy = SyncPolicy.MANUAL_ONLY

        scheduler.schedule()
        assertTrue(infos().isEmpty())

        scheduler.syncNow()
        // An explicit "Sync now" runs on any connected network regardless of the policy.
        assertEquals(NetworkType.CONNECTED, infos().single { !it.state.isFinished }.constraints.requiredNetworkType)
    }

    @Test
    fun uploadingOneSessionUsesItsOwnChainAndLeavesTheMainChainAlone() = runBlocking {
        scheduler.schedule() // main chain waiting for Wi-Fi

        scheduler.syncNow("session-1")

        val manual = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerUploadScheduler.MANUAL_WORK_NAME).get()
        assertEquals(NetworkType.CONNECTED, manual.single().constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, infos().single().state) // untouched
        // A waiting manual run must not stop the main chain from being scheduled later.
        scheduler.schedule()
        assertEquals(1, infos().size)
    }

    @Test
    fun settingsAndTheInstallationIdPersist() {
        settings.retentionDays = 14
        settings.autoCleanupEnabled = false
        val first = InstallationId(context).get()

        assertEquals(14, CloudSyncSettings(context).retentionDays)
        assertEquals(false, CloudSyncSettings(context).autoCleanupEnabled)
        assertEquals(first, InstallationId(context).get())
        UUID.fromString(first) // random UUID, generated once
        assertNotEquals("", first)
    }
}
