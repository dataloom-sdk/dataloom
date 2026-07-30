package io.dataloom.queue.room

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies non-destructive queue and circuit schema migrations. */
@RunWith(AndroidJUnit4::class)
class DataLoomRoomMigrationTest {

    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DataLoomRoomDatabase::class.java,
    )

    @Test
    fun version1RetryEntryMigratesToVersion2WithoutLosingHistory() {
        val version1 = migrationTestHelper.createDatabase(TEST_DATABASE_1_2, 1)
        version1.execSQL(
            """
            INSERT INTO queue_entries (
                entry_id, workflow_id, session_id, direction, mode, priority,
                exec_execution_id, exec_correlation_id, state,
                enqueued_at_ms, available_at_ms, retry_attempt_number
            ) VALUES (
                'entry-001', 'workflow-001', 'session-001', 'PUSH', 'DELTA', 'NORMAL',
                'execution-001', 'correlation-001', 'RETRY_WAITING',
                1000, 5000, 2
            )
            """.trimIndent(),
        )
        version1.close()

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            TEST_DATABASE_1_2,
            2,
            true,
            DataLoomRoomMigrations.MIGRATION_1_2,
        )
        val cursor = migrated.query(
            """
            SELECT retry_attempt_number, available_at_ms,
                   retry_window_started_at_ms, retry_last_evaluated_at_ms,
                   retry_cumulative_delay_ms
            FROM queue_entries WHERE entry_id = 'entry-001'
            """.trimIndent(),
        )
        try {
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
            assertEquals(5_000L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        } finally {
            cursor.close()
            migrated.close()
        }

        openCurrentDatabase(TEST_DATABASE_1_2)
    }

    @Test
    fun version2QueueEntryMigratesToVersion3WithoutLosingRetryState() {
        val version2 = migrationTestHelper.createDatabase(TEST_DATABASE_2_3, 2)
        version2.execSQL(
            """
            INSERT INTO queue_entries (
                entry_id, workflow_id, session_id, direction, mode, priority,
                exec_execution_id, exec_correlation_id, state,
                enqueued_at_ms, available_at_ms, retry_attempt_number,
                retry_window_started_at_ms, retry_last_evaluated_at_ms,
                retry_cumulative_delay_ms
            ) VALUES (
                'entry-002', 'workflow-002', 'session-002', 'PULL', 'DELTA', 'HIGH',
                'execution-002', 'correlation-002', 'RETRY_WAITING',
                2000, 7000, 3, 2500, 3000, 4500
            )
            """.trimIndent(),
        )
        version2.close()

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            TEST_DATABASE_2_3,
            3,
            true,
            DataLoomRoomMigrations.MIGRATION_2_3,
        )
        val queueCursor = migrated.query(
            """
            SELECT retry_attempt_number, available_at_ms,
                   retry_window_started_at_ms, retry_last_evaluated_at_ms,
                   retry_cumulative_delay_ms
            FROM queue_entries WHERE entry_id = 'entry-002'
            """.trimIndent(),
        )
        val tableCursor = migrated.query(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'table' AND name = 'circuit_breaker_states'
            """.trimIndent(),
        )
        try {
            assertTrue(queueCursor.moveToFirst())
            assertEquals(3, queueCursor.getInt(0))
            assertEquals(7_000L, queueCursor.getLong(1))
            assertEquals(2_500L, queueCursor.getLong(2))
            assertEquals(3_000L, queueCursor.getLong(3))
            assertEquals(4_500L, queueCursor.getLong(4))
            assertTrue(tableCursor.moveToFirst())
            assertEquals("circuit_breaker_states", tableCursor.getString(0))
        } finally {
            queueCursor.close()
            tableCursor.close()
            migrated.close()
        }

        openCurrentDatabase(TEST_DATABASE_2_3)
    }

    private fun openCurrentDatabase(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(
            context,
            DataLoomRoomDatabase::class.java,
            name,
        ).addMigrations(*DataLoomRoomMigrations.ALL)
            .build()
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DATABASE_1_2 = "dataloom-room-migration-1-2-test"
        const val TEST_DATABASE_2_3 = "dataloom-room-migration-2-3-test"
    }
}
