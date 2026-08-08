package io.dataloom.storage.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.dataloom.storage.sqldelight.internal.DataLoomStorageDatabase

/** Creates an in-memory SQLDelight storage database for JVM hosts. */
public fun createJvmSqlDelightStorageDatabase(): SqlDelightStorageDatabase {
    val driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    DataLoomStorageDatabase.Schema.create(driver)
    return SqlDelightStorageDatabase(
        database = DataLoomStorageDatabase(driver),
    )
}
