package io.dataloom.storage.sqldelight

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.dataloom.storage.sqldelight.internal.DataLoomStorageDatabase

/**
 * Creates a SQLDelight storage database for iOS hosts.
 *
 * @param databaseName SQLite filename used by SQLDelight.
 */
public fun createIosSqlDelightStorageDatabase(
    databaseName: String = "dataloom-storage.db",
): SqlDelightStorageDatabase = SqlDelightStorageDatabase(
    database = DataLoomStorageDatabase(
        NativeSqliteDriver(
            schema = DataLoomStorageDatabase.Schema,
            name = databaseName,
        ),
    ),
)
