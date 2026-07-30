package io.dataloom.queue.room.internal

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for DataLoom durable queue and circuit-breaker persistence.
 *
 * The database type is public because host applications may explicitly own its
 * lifecycle and pass it to DataLoom Room-backed providers. DAOs remain internal
 * so Room/SQLite implementation details do not become part of the provider API.
 */
@Database(
    entities = [QueueEntryEntity::class, CircuitBreakerStateEntity::class],
    version = 3,
    exportSchema = true,
)
public abstract class DataLoomRoomDatabase : RoomDatabase() {
    internal abstract fun queueEntryDao(): QueueEntryDao
    internal abstract fun circuitBreakerStateDao(): CircuitBreakerStateDao
}