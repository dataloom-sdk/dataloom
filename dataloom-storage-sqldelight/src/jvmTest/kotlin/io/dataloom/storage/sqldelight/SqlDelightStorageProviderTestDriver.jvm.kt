package io.dataloom.storage.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.dataloom.storage.sqldelight.internal.DataLoomStorageDatabase

internal actual fun createTestSqlDelightStorageDatabase(): SqlDelightStorageDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    DataLoomStorageDatabase.Schema.create(driver)
    return SqlDelightStorageDatabase(
        database = DataLoomStorageDatabase(driver),
    )
}
