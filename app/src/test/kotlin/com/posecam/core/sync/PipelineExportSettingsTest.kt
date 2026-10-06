package com.posecam.core.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.posecam.core.cloud.CloudHttpException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The export rotation is one persisted setting shared with the manual "Export for pipeline"; the database upgrade keeps old queues. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineExportSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val capturePrefs get() = context.getSharedPreferences("posecam", Context.MODE_PRIVATE)

    @Test
    fun theRotationIsUnsetUntilTheCollectorChoosesIt() {
        capturePrefs.edit().clear().commit()

        val settings = CloudSyncSettings(context)

        assertNull(settings.exportRotationDegrees)
        assertNull(settings.snapshot().exportRotationDegrees)
        assertEquals("Not set: pipeline exports wait until you choose", CloudUiText.rotationLabel(null))
    }

    @Test
    fun aRotationChosenInManualExportCarriesOver_andBothWritersShareOneKey() {
        capturePrefs.edit().clear().putInt("export_rotation_degrees", 90).commit() // what SessionsActivity has always saved
        val settings = CloudSyncSettings(context)

        assertEquals(90, settings.exportRotationDegrees)

        settings.exportRotationDegrees = 180
        assertEquals(180, capturePrefs.getInt("export_rotation_degrees", -1))
        assertEquals(180, settings.snapshot().exportRotationDegrees)

        settings.exportRotationDegrees = null
        assertFalse(capturePrefs.contains("export_rotation_degrees"))
    }

    @Test
    fun aValueTheExporterCouldNotApplyReadsAsUnset() {
        capturePrefs.edit().clear().putInt("export_rotation_degrees", 45).commit()

        assertNull(CloudSyncSettings(context).exportRotationDegrees)
    }

    @Test
    fun aPathTheServerRefusesIsNotRetriedButAnAuthProblemIs() {
        assertFalse(http(403, "PATH_NOT_ALLOWED").retryable)
        assertTrue(http(403, null).retryable)
        assertTrue(http(403, "FORBIDDEN").retryable)
        assertTrue(http(401).retryable)
        assertFalse(CloudHttpException(400, "INVALID_REQUEST", "pipe must be one of ...").retryable)
    }

    // ---- the database upgrade --------------------------------------------------------------------

    /** Creates the database exactly as version 1 shipped (from the exported schema), opens it with the real migration. */
    @Test
    fun anExistingQueueSurvivesTheUpgradeAndItsRecordingsAreQueuedForExport() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val v1 = JSONObject(File("schemas/com.posecam.core.sync.UploadDatabase/1.json").readText()).getJSONObject("database")
        val statements = buildList {
            val entities = v1.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                add(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) add(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = v1.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) add(setup.getString(i))
        }
        val old = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach { db.execSQL(it) }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        old.writableDatabase.apply {
            execSQL(
                "INSERT INTO cloud_sessions (sessionId, directoryPath, recordingStatus, recordingFinal, cloudCreated, pipe, permanentFailure, syncedAt, lastError, createdAt, updatedAt) " +
                    "VALUES ('capture-20260917T090000-a3f9c1', '/x', 'complete', 1, 1, 'black', 0, 5000, NULL, 1, 1)",
            )
            execSQL(
                "INSERT INTO uploads (sessionId, relativePath, localPath, fileType, kind, itemCount, required, sizeBytes, state, uploadedBytes, retryCount, verifyFailures, createdAt, updatedAt) " +
                    "VALUES ('capture-20260917T090000-a3f9c1', 'manifest.json', '/x/manifest.json', 'METADATA', 'PLAIN', 0, 1, 10, 'VERIFIED', 10, 0, 0, 1, 1)",
            )
        }
        old.close()

        val db = Room.databaseBuilder(context, UploadDatabase::class.java, name)
            .addMigrations(UploadDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val session = db.uploadDao().getSession("capture-20260917T090000-a3f9c1")!!
            assertEquals("black", session.pipe) // nothing that was there is lost
            assertEquals(5000L, session.syncedAt)
            assertEquals(ExportState.PENDING, session.exportState) // so its export is made and uploaded
            assertNull(session.exportNote)
            assertEquals(1, db.uploadDao().uploadsForSession(session.sessionId).size)
            assertEquals(listOf(session.sessionId), db.uploadDao().sessionsNeedingExport().map { it.sessionId })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
