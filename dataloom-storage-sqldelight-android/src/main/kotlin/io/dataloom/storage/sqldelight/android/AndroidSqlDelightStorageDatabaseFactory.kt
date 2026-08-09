package io.dataloom.storage.sqldelight.android

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.dataloom.storage.sqldelight.SqlDelightStorageDatabase
import io.dataloom.storage.sqldelight.internal.DataLoomStorageDatabase

/**
 * Creates a SQLDelight storage database for Android hosts.
 *
 * This lives in a separate Android-only module from `dataloom-storage-sqldelight`
 * because AGP 9.0+ does not allow the classic `com.android.library` plugin in
 * the same module as `org.jetbrains.kotlin.multiplatform`. The shared module
 * stays JVM + iOS only; this module supplies the Android driver.
 *
 * @param context Android context used to open the SQLite database.
 * @param databaseName SQLite filename used by SQLDelight.
 */
public fun createAndroidSqlDelightStorageDatabase(
    context: Context,
    databaseName: String = "dataloom-storage.db",
): SqlDelightStorageDatabase {
    val driver = AndroidSqliteDriver(
        schema = DataLoomStorageDatabase.Schema,
        context = context,
        name = databaseName,
    )
    return SqlDelightStorageDatabase.fromDriver(driver)
}
