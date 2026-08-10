package io.dataloom.queue.room.internal

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for DataLoom durable queue, circuit-breaker, and administration persistence.
 *
 * The database type is public because host applications may explicitly own its
 * lifecycle and pass it to DataLoom Room-backed providers. DAOs remain internal
 * so Room/SQLite implementation details do not become part of the provider API.
 */
@Database(
    entities = [
        QueueEntryEntity::class,
        CircuitBreakerStateEntity::class,
        RetryAdministrationStateEntity::class,
        CircuitAdministrationStateEntity::class,
        DurableStateEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
public abstract class DataLoomRoomDatabase : RoomDatabase() {
    internal abstract fun queueEntryDao(): QueueEntryDao
    internal abstract fun circuitBreakerStateDao(): CircuitBreakerStateDao
    internal abstract fun retryAdministrationStateDao(): RetryAdministrationStateDao
    internal abstract fun retryAdministrationExecutionDao(): RetryAdministrationExecutionDao
    internal abstract fun circuitAdministrationStateDao(): CircuitAdministrationStateDao
    internal abstract fun circuitAdministrationExecutionDao(): CircuitAdministrationExecutionDao
    internal abstract fun durableStateDao(): DurableStateDao
}
