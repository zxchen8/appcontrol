package com.plearn.appcontrol.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppControlDatabaseMigrationTest {
    @Test
    fun migration1To2ShouldAddTaskRunArtifactsColumnWithoutDroppingExistingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-1-2-test.db"
        context.deleteDatabase(databaseName)

        createVersion1Database(context, databaseName).close()

        val migratedHelper = createOpenHelper(
            context = context,
            name = databaseName,
            callback = object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    AppControlDatabase.MIGRATION_1_2.migrate(db)
                }
            },
        )

        migratedHelper.writableDatabase.use { database ->
            database.query("PRAGMA table_info(task_runs)").use { cursor ->
                var foundArtifactsColumn = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "artifactsJson") {
                        foundArtifactsColumn = true
                        assertEquals("TEXT", cursor.getString(2))
                        assertEquals(1, cursor.getInt(3))
                    }
                }
                assertEquals(true, foundArtifactsColumn)
            }

            database.query("SELECT runId, artifactsJson FROM task_runs").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("run-a", cursor.getString(0))
                assertEquals("{}", cursor.getString(1))
            }
        }

        migratedHelper.close()
        context.deleteDatabase(databaseName)
    }

    private fun createVersion1Database(context: Context, databaseName: String): SupportSQLiteOpenHelper {
        val helper = createOpenHelper(
            context = context,
            name = databaseName,
            callback = object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys=OFF")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS task_runs (
                            runId TEXT NOT NULL PRIMARY KEY,
                            sessionId TEXT,
                            cycleNo INTEGER,
                            taskId TEXT NOT NULL,
                            credentialSetId TEXT,
                            credentialProfileId TEXT,
                            credentialAlias TEXT,
                            status TEXT NOT NULL,
                            startedAt INTEGER NOT NULL,
                            finishedAt INTEGER,
                            durationMs INTEGER,
                            triggerType TEXT NOT NULL,
                            errorCode TEXT,
                            message TEXT
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO task_runs (
                            runId,
                            sessionId,
                            cycleNo,
                            taskId,
                            credentialSetId,
                            credentialProfileId,
                            credentialAlias,
                            status,
                            startedAt,
                            finishedAt,
                            durationMs,
                            triggerType,
                            errorCode,
                            message
                        ) VALUES (
                            'run-a',
                            NULL,
                            NULL,
                            'task-a',
                            NULL,
                            NULL,
                            NULL,
                            'failed',
                            100,
                            120,
                            20,
                            'manual',
                            'RUNNER_STEP_FAILED',
                            'failed before migration'
                        )
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            },
        )
        helper.writableDatabase.close()
        return helper
    }

    private fun createOpenHelper(
        context: Context,
        name: String,
        callback: SupportSQLiteOpenHelper.Callback,
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(callback)
            .build(),
    )
}