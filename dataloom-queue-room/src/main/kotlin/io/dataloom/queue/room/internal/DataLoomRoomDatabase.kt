package io.dataloom.queue.room.internal

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for DataLoom durable queue persistence.
 *
 * ## Schema version
 *
 * Current schema version is 1. Schema export is enabled; the generated schema
 * JSON is committed at `schemas/` for migration testing.
 *
 * ## Migration policy
 *
 * Destructive migration fallback is disabled. A schema migration must be
 * provided for every version increment. Use `DataLoomDatabaseBuilder` to
 * configure the `RoomDatabase.Builder`.
 *
 * ## Module boundary
 *
 * This class is internal to the `dataloom-queue-room` module. The public
 * surface is [io.dataloom.queue.room.RoomQueueProvider] and
 * [io.dataloom.queue.room.DataLoomDatabaseBuilder].
 */
@Database(
    entities = [QueueEntryEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class DataLoomRoomDatabase : RoomDatabase() {
    /** Accessor for the queue entry DAO. */
    internal abstract fun queueEntryDao(): QueueEntryDao
}
