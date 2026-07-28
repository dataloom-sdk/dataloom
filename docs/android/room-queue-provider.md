# Room queue provider

> **Audience:** Android developers and maintainers of durable queue behavior
> **Purpose:** Explain the current Room-backed `QueueProvider`, its state
> transitions, and its durability limits
> **Status:** Implemented Android queue foundation; not a general
> `StorageProvider` or complete V1 persistence layer

[← Android overview](README.md) ·
[Worker integration](worker-integration.md) ·
[Security and R8](security-and-r8.md)

`RoomQueueProvider` implements the shared `QueueProvider` contract with
AndroidX Room and SQLite.

## Module and setup

```kotlin
implementation(project(":dataloom-queue-room"))
```

```kotlin
val database = DataLoomDatabaseBuilder.build(context)
val queueProvider = RoomQueueProvider(database)
```

Hold the database as an application-process singleton. `RoomQueueProvider`
does not own or close the database lifecycle. Published V1 coordinates are not
available yet.

## Schema

| Property | Current value |
|---|---|
| Default database name | `dataloom-queue.db` |
| Schema version | `1` |
| Schema export | Enabled |
| Committed schema | `dataloom-queue-room/schemas/io.dataloom.queue.room.internal.DataLoomRoomDatabase/1.json` |

`QueueEntryState` and the persisted error enums use enum names, never ordinals.
Changing a persisted name is therefore a schema compatibility change.

## Queue transitions

| Operation | Required source state | Result |
|---|---|---|
| `enqueue` | New identifier | Persisted entry; duplicate identifiers fail |
| `acquire` | `PENDING` or `RETRY_WAITING`, available now | `LEASED` with the supplied lease |
| `complete` | `LEASED` with matching lease ID | `COMPLETED` |
| `reschedule` | `LEASED` with matching lease ID | `RETRY_WAITING` |
| `defer` | `LEASED` with matching lease ID | `PENDING` if attempt is null; otherwise `RETRY_WAITING` |
| `fail` | `LEASED` with matching lease ID | `FAILED` or `DEAD_LETTER` |
| `cancel` | `PENDING` or `RETRY_WAITING` | `CANCELLED` |
| `recoverExpiredLeases` | `LEASED` with expiry before `currentTime` | `PENDING` if attempt is null; otherwise `RETRY_WAITING` |

### Atomic acquisition

`acquire` runs selection and guarded updates in one Room transaction. Eligible
entries are ordered by `availableAt`, then enqueue time, then entry ID, and are
limited by `maxEntries`. Each update rechecks the entry ID, eligible state, and
availability before assigning the lease.

### Guarded lease transitions

`complete`, `defer`, `reschedule`, and `fail` include the lease ID in the SQL
predicate. A stale or mismatched lease affects zero rows and returns
`QUEUE_STALE_LEASE`.

### Expired-lease recovery

Recovery uses one SQL update and only recovers leases whose expiry is strictly
earlier than the supplied `currentTime`. It preserves `retry_attempt_number`
exactly: null history returns to `PENDING`, while retry N returns to
`RETRY_WAITING`. It clears the expired lease and last error but does not
execute the work or reset its retry budget.

### Non-retry deferral

`defer` uses one guarded SQL update. It changes availability and clears the
active lease and last error without writing `retry_attempt_number`. Repeated
connectivity deferrals therefore remain `PENDING` with a null attempt, while a
deferral after retry N remains `RETRY_WAITING` with attempt N.

## Execution and cancellation

All database operations run on `Dispatchers.IO`. Coroutine cancellation
propagates and is not converted into a provider failure. Unexpected database
exceptions become `QUEUE_DATABASE_FAILURE`.

## Migration policy

Destructive migration fallback is not enabled. SDK maintainers must add and
test an explicit Room migration whenever the schema version changes and keep
the committed JSON schema synchronized with generated output. The current
`DataLoomDatabaseBuilder` exposes only database name selection; it does not
offer a host hook for custom migrations or an encrypted
`SupportSQLiteOpenHelper.Factory`.

## Security boundary

The current database is not encrypted by DataLoom. Queue payloads, metadata,
and sanitized error fields are persisted. Applications requiring SDK-managed
encrypted queue storage need an alternative `QueueProvider` until an encrypted
construction boundary is implemented and qualified. See
[Security and R8](security-and-r8.md#data-at-rest).

## Canonical error codes

| Code | Meaning |
|---|---|
| `QUEUE_DUPLICATE_ENTRY` | The entry ID already exists |
| `QUEUE_DATABASE_FAILURE` | An unexpected database operation failed |
| `QUEUE_STALE_LEASE` | A guarded transition used a missing or stale lease |
| `QUEUE_CANCELLATION_REJECTED` | The current state cannot be cancelled |

This module supplies Android queue durability only. It does not implement
application domain storage, KMP iOS persistence, strategy selection, retry
policy, scheduling, or synchronization execution.

## Related documentation

- [Queue provider contract](../api/queue-provider.md)
- [Queue boundaries](../architecture/queue-boundaries.md)
- [WorkManager worker integration](worker-integration.md)
