package io.dataloom.queue.room.internal

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for DataLoom durable queue persistence.
 *
 * The database type is public because host applications may explicitly own its
 * lifecycle and pass it to `RoomQueueProvider`. The DAO remains internal so
 * Room/SQLite implementation details do not become part of the provider API.
 */
@Database(
    entities = [QueueEntryEntity::class],
    version = 1,
    exportSchema = true,
)
public abstract class DataLoomRoomDatabase : RoomDatabase() {
    internal abstract fun queueEntryDao(): QueueEntryDao
}
