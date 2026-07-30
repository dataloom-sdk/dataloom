# Room queue provider

> **Audience:** Android developers and maintainers of durable queue behavior  
> **Purpose:** Explain the current Room-backed `QueueProvider`, its state
> transitions, migrations, and durability limits  
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
| Schema version | `2` |
| Schema export | Enabled |
| Committed schema | `dataloom-queue-room/schemas/io.dataloom.queue.room.internal.DataLoomRoomDatabase/2.json` |

`QueueEntryState`, persisted error enums, and retry stop reasons use stable
names, never ordinals. Changing a persisted name is therefore a compatibility
change.

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
`RETRY_WAITING`. It clears the expired lease and last error, but does not execute
the work or reset persisted retry-budget state.

### Non-retry deferral

`defer` uses one guarded SQL update. It changes availability and clears the
active lease and last error without writing `retry_attempt_number` or retry-
budget columns. Repeated connectivity deferrals therefore remain `PENDING` with
a null attempt, while a deferral after retry N remains `RETRY_WAITING` with
attempt N and the same budget state.

## Retry budget persistence

A budgeted retry stores three bounded timing values:

- the first genuine retry evaluation instant;
- the most recent accepted retry evaluation instant; and
- cumulative delay accepted for durable retry transitions.

A successful `reschedule` writes attempt, availability, sanitized error, and all
budget values in one lease-guarded update. Partial budget state is rejected when
mapping persisted rows. Initial enqueue cannot fabricate budget history.

## Execution and cancellation

All database operations run on `Dispatchers.IO`. Coroutine cancellation
propagates and is not converted into a provider failure. Unexpected database
exceptions become `QUEUE_DATABASE_FAILURE`.

## Migration policy

Destructive migration fallback is not enabled. `DataLoomDatabaseBuilder`
installs the supported migration set from `DataLoomRoomMigrations.ALL`.

`MIGRATION_1_2` adds nullable retry-window, last-evaluation, and cumulative-delay
columns. Existing retry attempt and availability values are preserved; historical
budget fields remain null because version 1 did not record that evidence. The
instrumented migration test opens a version-1 database, migrates it, verifies the
preserved row, validates schema version 2, and reopens the current database.

SDK maintainers must add and test an explicit migration whenever the schema
changes and keep committed JSON schemas synchronized with generated output. The
builder still does not expose a host hook for custom migrations or an encrypted
`SupportSQLiteOpenHelper.Factory`.

## Security boundary

The current database is not encrypted by DataLoom. Queue payloads, metadata,
sanitized error fields, and bounded retry timing evidence are persisted.
Applications requiring SDK-managed encrypted queue storage need an alternative
`QueueProvider` until an encrypted construction boundary is implemented and
qualified. See [Security and R8](security-and-r8.md#data-at-rest).

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
- [Queue model](../api/queue-model.md)
- [Retry policy](../api/retry-policy.md)
- [Queue boundaries](../architecture/queue-boundaries.md)
- [WorkManager worker integration](worker-integration.md)
