package io.dataloom.storage.sqldelight

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.dataloom.storage.sqldelight.internal.DataLoomStorageDatabase
import kotlin.random.Random

internal actual fun createTestSqlDelightStorageDatabase(): SqlDelightStorageDatabase {
    val driver = NativeSqliteDriver(
        schema = DataLoomStorageDatabase.Schema,
        name = "dataloom-storage-test-${Random.nextInt()}.db",
    )
    return SqlDelightStorageDatabase(
        database = DataLoomStorageDatabase(driver),
    )
}
