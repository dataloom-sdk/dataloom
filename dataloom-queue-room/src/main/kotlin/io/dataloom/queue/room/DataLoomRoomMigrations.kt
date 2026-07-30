package io.dataloom.queue.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Supported non-destructive migrations for the DataLoom Room database. */
public object DataLoomRoomMigrations {

    /** Adds nullable durable retry-budget columns while preserving queue history. */
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

    /** Adds the independent durable circuit-breaker state table. */
    public val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS circuit_breaker_states (
                    scope_key TEXT NOT NULL,
                    scope_kind TEXT NOT NULL,
                    provider_id TEXT,
                    operation TEXT,
                    tenant_id TEXT,
                    workflow_id TEXT,
                    phase TEXT NOT NULL,
                    consecutive_failures INTEGER NOT NULL,
                    failure_window_started_at_ms INTEGER,
                    open_until_ms INTEGER,
                    probe_generation INTEGER NOT NULL,
                    probe_in_flight INTEGER NOT NULL,
                    probe_lease_until_ms INTEGER,
                    updated_at_ms INTEGER NOT NULL,
                    record_version INTEGER NOT NULL,
                    PRIMARY KEY(scope_key)
                )
                """.trimIndent(),
            )
        }
    }

    /** Complete ordered migration set used by [DataLoomDatabaseBuilder]. */
    public val ALL: Array<Migration>
        get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}