package io.dataloom.queue.room.internal

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Flat, generic Room representation of one durable-state record.
 *
 * [namespace] plus [scopeKey] form the composite primary key, so multiple
 * domains (each with their own [io.dataloom.api.state.DurableStateStore]
 * instance) can share one [DataLoomRoomDatabase] without their encoded scope
 * keys ever colliding with each other.
 */
@Entity(tableName = "durable_states", primaryKeys = ["namespace", "scope_key"])
internal data class DurableStateEntity(
    @ColumnInfo(name = "namespace")
    val namespace: String,
    @ColumnInfo(name = "scope_key")
    val scopeKey: String,
    @ColumnInfo(name = "state_payload")
    val statePayload: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "record_version")
    val recordVersion: Long,
)
