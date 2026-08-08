package io.dataloom.storage.sqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.dataloom.storage.sqldelight.internal.DataLoomStorageDatabase

/**
 * Creates a SQLDelight storage database for JVM hosts.
 *
 * @param jdbcUrl JDBC URL used by the SQLDelight SQLite driver.
 */
public fun createJvmSqlDelightStorageDatabase(
    jdbcUrl: String = JdbcSqliteDriver.IN_MEMORY,
): SqlDelightStorageDatabase {
    val driver: JdbcSqliteDriver = JdbcSqliteDriver(jdbcUrl)
    DataLoomStorageDatabase.Schema.create(driver)
    return SqlDelightStorageDatabase(
        database = DataLoomStorageDatabase(driver),
    )
}
