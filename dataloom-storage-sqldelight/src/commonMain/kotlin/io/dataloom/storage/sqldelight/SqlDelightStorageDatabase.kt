package io.dataloom.storage.sqldelight

import io.dataloom.storage.sqldelight.internal.DataLoomStorageDatabase

/**
 * SQLDelight-backed database handle used by [SqlDelightStorageProvider].
 *
 * This wrapper keeps SQLDelight runtime types out of the provider's public
 * constructor and API surface.
 */
public class SqlDelightStorageDatabase internal constructor(
    internal val database: DataLoomStorageDatabase,
)
