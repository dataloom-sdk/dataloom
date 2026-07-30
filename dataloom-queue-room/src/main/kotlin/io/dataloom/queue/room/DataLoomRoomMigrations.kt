package io.dataloom.queue.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Supported non-destructive migrations for the DataLoom queue database. */
public object DataLoomRoomMigrations {

    /**
     * Adds nullable durable retry-budget columns.
     *
     * Existing version-1 entries have no budget state and therefore migrate
     * with all three columns null. Retry attempt and availability history are
     * preserved unchanged.
     */
    public val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE queue_entries ADD COLUMN retry_window_started_at_ms INTEGER",
            )
            database.execSQL(
                "ALTER TABLE queue_entries ADD COLUMN retry_last_evaluated_at_ms INTEGER",
            )
            database.execSQL(
                "ALTER TABLE queue_entries ADD COLUMN retry_cumulative_delay_ms INTEGER",
            )
        }
    }

    /** Complete ordered migration set used by [DataLoomDatabaseBuilder]. */
    public val ALL: Array<Migration>
        get() = arrayOf(MIGRATION_1_2)
}
