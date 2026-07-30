package io.dataloom.queue.room

import android.content.Context
import androidx.room.Room
import io.dataloom.queue.room.internal.DataLoomRoomDatabase

/** Production-safe helper for constructing the DataLoom Room database. */
public object DataLoomDatabaseBuilder {

    public const val DEFAULT_NAME: String = "dataloom-queue.db"

    /**
     * Builds a database using application context and an explicit, non-blank
     * database name. Destructive migration fallback and main-thread queries are
     * intentionally not enabled.
     */
    public fun build(
        context: Context,
        name: String = DEFAULT_NAME,
    ): DataLoomRoomDatabase {
        require(name.isNotBlank()) { "DataLoom Room database name must not be blank." }
        val applicationContext = context.applicationContext ?: context
        return Room.databaseBuilder(
            applicationContext,
            DataLoomRoomDatabase::class.java,
            name,
        ).addMigrations(*DataLoomRoomMigrations.ALL)
            .build()
    }
}
