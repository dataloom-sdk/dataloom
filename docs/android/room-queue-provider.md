# Room Queue Provider (DL-037)

`RoomQueueProvider` implements the DataLoom `QueueProvider` contract using
AndroidX Room backed by SQLite.

## Module

`dataloom-queue-room`

## Dependency

```kotlin
implementation(project(":dataloom-queue-room"))
```

## Setup

```kotlin
// Create the database (hold as singleton)
val database = DataLoomDatabaseBuilder.build(context)

// Create the provider
val provider = RoomQueueProvider(database)
```

## Schema

- Database name: `dataloom-queue.db` (default, configurable via
  `DataLoomDatabaseBuilder.build(context, name = "...")`).
- Schema version: 1.
- Schema export enabled; committed JSON at
  `schemas/io.dataloom.queue.room.internal.DataLoomRoomDatabase/1.json`.

## State storage

`QueueEntryState` is stored as the enum constant **name**, not the ordinal.
Ordinal-based storage is fragile when enum values are reordered or added.
Name-based storage is stable across SDK versions.

## Atomic acquisition

`acquire()` uses a `@Transaction` DAO function that:
1. Selects PENDING and RETRY_WAITING entries where `availableAt <= acquiredAt`,
   ordered by `availableAt ASC`, limited to `maxEntries`.
2. Updates each row to LEASED state with lease columns set, matching on
   both `entry_id` and current eligible state.

The SELECT and all UPDATEs execute within a single SQLite transaction.
Concurrent acquisition attempts cannot observe the same row twice.

## Guarded transitions

`complete()`, `reschedule()`, and `fail()` include the **lease identifier** in
the SQL `WHERE` clause. If the lease is stale or mismatched, zero rows are
affected and the provider returns a `QUEUE_STALE_LEASE` error.

## Expired-lease recovery

`recoverExpiredLeases()` issues a single SQL UPDATE that transitions LEASED
entries with `leaseExpiresAt < currentTime` back to PENDING state. The
recovered state is always PENDING for this implementation.

## Cancellation

`cancel()` transitions entries in PENDING or RETRY_WAITING state to CANCELLED.
Entries in LEASED or terminal states are refused with `QUEUE_CANCELLATION_REJECTED`.

## Thread safety

All operations dispatch to `Dispatchers.IO`. No Room operation runs on the
main thread. The database instance is thread-safe as long as it is held as a
singleton.

## Migration policy

Destructive migration fallback is disabled. Every schema version increment must
ship a corresponding `Migration` object. Never call `fallbackToDestructiveMigration()`
on a `DataLoomDatabaseBuilder`-created instance in production.

## Error codes

| Code | Description |
|---|---|
| `QUEUE_DUPLICATE_ENTRY` | Entry with same ID already exists (enqueue) |
| `QUEUE_DATABASE_FAILURE` | Unexpected database error |
| `QUEUE_STALE_LEASE` | Lease mismatch on complete/reschedule/fail |
| `QUEUE_CANCELLATION_REJECTED` | Entry cannot be cancelled in current state |
