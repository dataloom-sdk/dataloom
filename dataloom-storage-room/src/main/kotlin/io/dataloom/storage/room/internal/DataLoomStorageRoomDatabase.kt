package io.dataloom.storage.room.internal

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for DataLoom generic storage-provider persistence.
 *
 * The database type is public because host applications may explicitly own its
 * lifecycle and pass it to DataLoom Room-backed providers. DAOs remain internal
 * so Room/SQLite implementation details do not become part of the provider API.
 */
@Database(
    entities = [
        OutboundChangeSetEntity::class,
        OutboundChangeEventEntity::class,
        InboundChangeSetEntity::class,
        InboundChangeEventEntity::class,
        StorageCheckpointEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
public abstract class DataLoomStorageRoomDatabase : RoomDatabase() {
    internal abstract fun outboundChangeDao(): OutboundChangeDao
    internal abstract fun inboundChangeDao(): InboundChangeDao
    internal abstract fun storageCheckpointDao(): StorageCheckpointDao
}
