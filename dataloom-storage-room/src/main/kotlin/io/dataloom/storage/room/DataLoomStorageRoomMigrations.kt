package io.dataloom.storage.room

import androidx.room.migration.Migration

/** Supported non-destructive migrations for the DataLoom storage Room database. */
public object DataLoomStorageRoomMigrations {

    /** Complete ordered migration set used by [DataLoomStorageDatabaseBuilder]. */
    public val ALL: Array<Migration>
        get() = emptyArray()
}
