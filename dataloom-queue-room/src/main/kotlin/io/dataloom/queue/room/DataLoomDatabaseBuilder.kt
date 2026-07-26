package io.dataloom.queue.room

import android.content.Context
import androidx.room.Room
import io.dataloom.queue.room.internal.DataLoomRoomDatabase

/**
 * Builder for the DataLoom Room database instance.
 *
 * ## Usage
 *
 * ```kotlin
 * val database = DataLoomDatabaseBuilder.build(context)
 * val provider = RoomQueueProvider(database)
 * ```
 *
 * ## Migration policy
 *
 * Destructive migration fallback is disabled. Every schema version increment
 * must ship a corresponding `Migration` object. Calling
 * [RoomDatabase.Builder.fallbackToDestructiveMigration] on the builder is
 * intentionally not configured here.
 *
 * ## Database name
 *
 * The default database name is `dataloom-queue.db`. Use [build] with a custom
 * name for testing or multi-database scenarios.
 *
 * @see RoomQueueProvider
 */
public object DataLoomDatabaseBuilder {

    /** Default database file name. */
    public const val DEFAULT_NAME: String = "dataloom-queue.db"

    /**
     * Creates and returns a [DataLoomRoomDatabase] instance.
     *
     * The returned instance should be held as a singleton by the host
     * application and passed to [RoomQueueProvider].
     *
     * @param context Android application context.
     * @param name database file name. Defaults to [DEFAULT_NAME].
     * @return configured [DataLoomRoomDatabase] instance.
     */
    public fun build(
        context: Context,
        name: String = DEFAULT_NAME,
    ): DataLoomRoomDatabase = Room
        .databaseBuilder(context, DataLoomRoomDatabase::class.java, name)
        .build()
}
