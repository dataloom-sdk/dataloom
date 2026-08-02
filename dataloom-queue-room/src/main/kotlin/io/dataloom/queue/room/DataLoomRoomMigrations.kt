package io.dataloom.queue.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Supported non-destructive migrations for the DataLoom Room database. */
public object DataLoomRoomMigrations {

    /** Adds nullable durable retry-budget columns while preserving queue history. */
    public val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN retry_window_started_at_ms INTEGER")
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN retry_last_evaluated_at_ms INTEGER")
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN retry_cumulative_delay_ms INTEGER")
        }
    }

    /** Adds the independent durable circuit-breaker state table. */
    public val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
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

    /** Adds nullable immutable workflow timeout evidence to existing queue rows. */
    public val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN workflow_started_at_ms INTEGER")
            db.execSQL("ALTER TABLE queue_entries ADD COLUMN workflow_deadline_at_ms INTEGER")
        }
    }

    /** Adds durable authorized retry-administration command and audit state. */
    public val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS retry_administration_states (
                    command_id TEXT NOT NULL,
                    queue_entry_id TEXT NOT NULL,
                    principal_id TEXT NOT NULL,
                    requested_at_ms INTEGER NOT NULL,
                    action TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    original_error_code TEXT NOT NULL,
                    original_error_category TEXT NOT NULL,
                    original_error_severity TEXT NOT NULL,
                    original_error_recoverability TEXT NOT NULL,
                    status TEXT NOT NULL,
                    authorization_id TEXT,
                    effective_recoverability TEXT,
                    updated_at_ms INTEGER NOT NULL,
                    rejection_reason_code TEXT,
                    execution_error_code TEXT,
                    execution_error_category TEXT,
                    execution_error_severity TEXT,
                    execution_error_recoverability TEXT,
                    record_version INTEGER NOT NULL,
                    PRIMARY KEY(command_id)
                )
                """.trimIndent(),
            )
        }
    }

    /** Complete ordered migration set used by [DataLoomDatabaseBuilder]. */
    public val ALL: Array<Migration>
        get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}