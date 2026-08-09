package io.dataloom.storage.sqldelight

import app.cash.sqldelight.db.SqlDriver
import io.dataloom.storage.sqldelight.internal.DataLoomStorageDatabase

/**
 * SQLDelight-backed database handle used by [SqlDelightStorageProvider].
 *
 * This wrapper keeps SQLDelight runtime types out of the provider's public
 * constructor and API surface.
 */
public class SqlDelightStorageDatabase internal constructor(
    internal val database: DataLoomStorageDatabase,
) {
    public companion object {
        /**
         * Wraps an already-opened SQLDelight [driver] for this schema.
         *
         * This is the construction path for platform-specific driver modules
         * (for example, an Android-only module supplying [driver] via
         * `AndroidSqliteDriver`) that live outside this module and therefore
         * cannot use the `internal` primary constructor directly. [driver]
         * must already be configured for the [DataLoomStorageDatabase.Schema]
         * — this factory does not run schema creation.
         */
        public fun fromDriver(driver: SqlDriver): SqlDelightStorageDatabase =
            SqlDelightStorageDatabase(DataLoomStorageDatabase(driver))
    }
}
