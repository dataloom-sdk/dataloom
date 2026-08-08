package io.dataloom.storage.room

import android.content.Context
import androidx.room.Room
import io.dataloom.storage.room.internal.DataLoomStorageRoomDatabase

/** Production-safe helper for constructing the DataLoom storage Room database. */
public object DataLoomStorageDatabaseBuilder {

    public const val DEFAULT_NAME: String = "dataloom-storage.db"

    /**
     * Builds a database using application context and an explicit, non-blank
     * database name. Destructive migration fallback and main-thread queries are
     * intentionally not enabled.
     */
    public fun build(
        context: Context,
        name: String = DEFAULT_NAME,
    ): DataLoomStorageRoomDatabase {
        require(name.isNotBlank()) { "DataLoom storage database name must not be blank." }
        val applicationContext = context.applicationContext ?: context
        return Room.databaseBuilder(
            applicationContext,
            DataLoomStorageRoomDatabase::class.java,
            name,
        ).addMigrations(*DataLoomStorageRoomMigrations.ALL)
            .build()
    }
}
