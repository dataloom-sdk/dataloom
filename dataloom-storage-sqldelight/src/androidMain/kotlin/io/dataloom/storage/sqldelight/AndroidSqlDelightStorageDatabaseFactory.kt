package io.dataloom.storage.sqldelight

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.dataloom.storage.sqldelight.internal.DataLoomStorageDatabase

/**
 * Creates a SQLDelight storage database for Android hosts.
 *
 * @param context Android context used to open the SQLite database.
 * @param databaseName SQLite filename used by SQLDelight.
 */
public fun createAndroidSqlDelightStorageDatabase(
    context: Context,
    databaseName: String = "dataloom-storage.db",
): SqlDelightStorageDatabase = SqlDelightStorageDatabase(
    database = DataLoomStorageDatabase(
        AndroidSqliteDriver(
            schema = DataLoomStorageDatabase.Schema,
            context = context,
            name = databaseName,
        ),
    ),
)
