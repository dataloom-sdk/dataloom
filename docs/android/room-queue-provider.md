# Room queue, circuit, and administration persistence

> **Audience:** Android developers and maintainers of durable queue and circuit behavior  
> **Purpose:** Explain the Room-backed queue, circuit, and administration
> adapters, their migrations, atomicity, and durability limits
> **Status:** Implemented Android persistence foundation; not a general
> `StorageProvider` or complete cross-platform V1 persistence layer

[← Android overview](README.md) ·
[Worker integration](worker-integration.md) ·
[Security and R8](security-and-r8.md)

The `dataloom-queue-room` module contains production adapters over one
application-owned AndroidX Room database:

- `RoomQueueProvider` implements the shared `QueueProvider` contract.
- `RoomCircuitBreakerStateStore` implements the shared
  `CircuitBreakerStateStore` contract.
- `RoomRetryAdministrationStateStore` and `RoomRetryAdministrationExecutor`
  persist and atomically execute authorized manual retries.
- `RoomCircuitAdministrationStateStore` and `RoomCircuitAdministrationExecutor`
  persist and atomically execute authorized circuit operations.

## Module and setup

```kotlin
implementation(project(":dataloom-queue-room"))
```

```kotlin
val database = DataLoomDatabaseBuilder.build(context)
val queueProvider = RoomQueueProvider(database)
val circuitStateStore = RoomCircuitBreakerStateStore(database)
val circuitAdministrationStore = RoomCircuitAdministrationStateStore(database)
val circuitAdministrationExecutor = RoomCircuitAdministrationExecutor(database, clock)
```

Hold the database as an application-process singleton. Neither adapter owns or
closes the database lifecycle. Published V1 coordinates are not available yet.

## Schema

| Property | Current value |
|---|---|
| Default database name | `dataloom-queue.db` |
| Schema version | `7` |
| Schema export | Enabled |
| Committed schema | `dataloom-queue-room/schemas/io.dataloom.queue.room.internal.DataLoomRoomDatabase/7.json` |
| Tables | `queue_entries`, `circuit_breaker_states`, `retry_administration_states`, `circuit_administration_states` |

Persisted enum-like values use stable names, never ordinals. Changing a
persisted name is therefore a compatibility change.

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

## Retry-budget persistence

A budgeted retry stores three bounded timing values:

- the first genuine retry evaluation instant;
- the most recent accepted retry evaluation instant; and
- cumulative delay accepted for durable retry transitions.

A successful `reschedule` writes attempt, availability, sanitized error, and all
budget values in one lease-guarded update. Partial budget state is rejected when
mapping persisted rows. Initial enqueue cannot fabricate budget history.

## Circuit-state persistence

Each circuit record stores only bounded operational evidence:

- the explicit scope kind and its typed identifiers;
- `CLOSED`, `OPEN`, or `HALF_OPEN` phase name;
- failure count and failure-window start;
- open deadline;
- probe generation, in-flight marker, and exclusive probe-lease deadline;
- last update time; and
- a non-negative compare-and-set record version.

The primary key is a deterministic, length-prefixed encoding of the explicit
`CircuitBreakerScope`. Length prefixes prevent delimiter collisions. There is no
implicit scope inheritance or fallback in the database layer.

### Atomic compare-and-set

`RoomCircuitBreakerStateStore.compareAndSet` executes in one Room transaction:

- a null expected version inserts only when the scope is absent and starts at
  record version zero;
- expected version N updates only the matching row and advances to N + 1; and
- a missing or mismatched version returns `Conflict` with the current durable
  record when one exists.

The store never overwrites a newer record. Version exhaustion fails closed as a
non-recoverable state error.

## Circuit-administration persistence and execution

`RoomCircuitAdministrationStateStore` persists exact immutable request input,
authorization, bounded terminal evidence, and the resulting circuit-state
record through command-scoped compare-and-set. Reuse of a command identifier
with different immutable input returns the current record as a conflict.

`RoomCircuitAdministrationExecutor` uses one Room transaction to validate the
durable command and authorization, load the exact circuit scope, apply the
requested transition, and advance the command to `SUCCEEDED` with the resulting
circuit version. A repeated command replays that receipt without changing the
circuit again. Manual transitions preserve monotonic probe generations; reset
clears operational failure-window state but cannot revive a stale probe permit.

### Integrity validation

Every loaded row is reconstructed through the public circuit scope and state
invariants. Invalid scope shapes, unknown persisted names, mismatched scope keys,
negative versions, and invalid phase fields fail closed as
`CIRCUIT_ROOM_STATE_CORRUPT`. Raw row values, SQL, paths, and exception messages
are not exposed.

## Execution and cancellation

Room owns its database dispatcher. Coroutine cancellation propagates and is not
converted into a provider failure. Unexpected database exceptions become a
sanitized recoverable database error. Persisted-state integrity and version
exhaustion are non-recoverable.

## Migration policy

Destructive migration fallback is not enabled. `DataLoomDatabaseBuilder`
installs the complete ordered migration set from `DataLoomRoomMigrations.ALL`.

`MIGRATION_1_2` adds nullable retry-window, last-evaluation, and cumulative-delay
columns. Existing retry attempt and availability values are preserved; historical
budget fields remain null because version 1 did not record that evidence.

`MIGRATION_2_3` adds the independent `circuit_breaker_states` table. It does not
rewrite or delete `queue_entries`; existing attempts, availability, leases,
errors, metadata, and retry-budget evidence remain unchanged.

`MIGRATION_3_4` adds nullable immutable workflow start/deadline evidence.
`MIGRATION_4_5` adds durable retry-administration command state.
`MIGRATION_5_6` adds durable circuit-administration command, authorization,
result, and redacted failure evidence without rewriting queue, circuit, or retry
administration rows. `MIGRATION_6_7` appends seven nullable strategy-decision
columns to `queue_entries`; legacy rows remain null and are never assigned the
current strategy configuration.

Instrumented migration tests validate every adjacent migration through version
7, preserve representative queue and circuit rows, verify each new table and
strategy column group, and reopen the current database through the production
migration set.

SDK maintainers must add and test an explicit migration whenever the schema
changes and keep committed JSON schemas synchronized with generated output. The
Android validation workflow derives the current Room version from generated KSP
code and verifies the matching committed schema, rather than assuming a fixed
version.

The builder still does not expose a host hook for custom migrations or an
encrypted `SupportSQLiteOpenHelper.Factory`.

## Security boundary

The current database is not encrypted by DataLoom. Queue requests, metadata, sanitized error fields, bounded retry timing,
workflow deadlines, and bounded strategy-decision identity are persisted. Circuit
rows contain only bounded scope and state-machine evidence; they do not contain
payload bytes, credentials, tokens, raw headers, exception text, or provider
instances.

Applications requiring SDK-managed encrypted persistence need alternative
provider/store implementations until an encrypted construction boundary is
implemented and qualified. See [Security and R8](security-and-r8.md#data-at-rest).

## Canonical error codes

| Code | Meaning |
|---|---|
| `QUEUE_DUPLICATE_ENTRY` | The entry ID already exists |
| `QUEUE_DATABASE_FAILURE` | An unexpected queue database operation failed |
| `QUEUE_STALE_LEASE` | A guarded transition used a missing or stale lease |
| `QUEUE_CANCELLATION_REJECTED` | The current state cannot be cancelled |
| `CIRCUIT_ROOM_DATABASE_FAILURE` | An unexpected circuit database operation failed |
| `CIRCUIT_ROOM_STATE_CORRUPT` | A durable circuit row failed invariant validation |
| `CIRCUIT_STATE_VERSION_EXHAUSTED` | The compare-and-set record version cannot advance |
| `CIRCUIT_ADMIN_ROOM_DATABASE_FAILURE` | A circuit-administration command-store operation failed |
| `CIRCUIT_ADMIN_ROOM_STATE_CORRUPT` | Circuit-administration audit state failed validation |
| `CIRCUIT_ADMIN_ROOM_EXECUTOR_DATABASE_FAILURE` | Atomic circuit administration failed in Room |
| `CIRCUIT_ADMIN_ROOM_EXECUTOR_STATE_CORRUPT` | Command or circuit state failed executor validation |
| `CIRCUIT_ADMIN_STATE_VERSION_EXHAUSTED` | A command or circuit version cannot advance safely |
| `CIRCUIT_ADMIN_EXECUTION_CLOCK_REGRESSION` | Execution time regressed behind durable evidence |

This module supplies Android queue, circuit, and administration durability. It
does not implement application domain storage, KMP iOS persistence, strategy
selection, retry policy, scheduling, or synchronization execution.

## Related documentation

- [Queue provider contract](../api/queue-provider.md)
- [Queue model](../api/queue-model.md)
- [Circuit breaker](../api/circuit-breaker.md)
- [Retry policy](../api/retry-policy.md)
- [Queue boundaries](../architecture/queue-boundaries.md)
- [WorkManager worker integration](worker-integration.md)

## Schema version 7 strategy decision

Room schema version 7 adds seven nullable columns containing the bounded
strategy-decision identity. `MIGRATION_6_7` preserves every existing queue,
retry, circuit, and administration row and leaves the new columns null for
legacy work. Partially populated decision columns are corrupt durable state and
fail closed; no default strategy is inferred.
