# Retry administration

**Status:** Authorized command coordination and production Android/Apple state
persistence are available. Android Room provides atomic queue execution;
complete Apple execution and operational assembly remain partial.

DataLoom exposes an explicit administrative boundary for manual retry and
failure reclassification. It does not weaken the normal fail-closed retry
classifier. Applications provide authorization and select the platform durable
state and execution implementations.

## Public contracts

- `RetryAdministrationRequest` is the immutable command envelope.
- `RetryAdministrationCommandId` is the durable idempotency key.
- `RetryAdministrationAuthorizer` decides whether the complete requested action
  is authorized for the named principal.
- `RetryAdministrationStateStore` persists versioned command history through an
  atomic compare-and-set contract.
- `RetryAdministrationExecutor` applies an authorized queue mutation
  idempotently by command id.
- `RetryAdministrationCoordinator` orders authorization, policy enforcement,
  durable admission, execution, and terminal recording.

The retained `RetryFailureSnapshot` contains only stable error code, category,
severity, and recoverability. It excludes exception text, payloads, credentials,
headers, provider instances, and arbitrary metadata.

## Actions

`REQUEUE` preserves the original classification. It is accepted only when the
failure is recoverable and is not in a protected category.

`RECLASSIFY_AND_REQUEUE` is a separate explicit action. Authorization applies
to that action, and the original failure snapshot remains immutable. The
executor receives `RECOVERABLE` only as the effective classification for the
new administrative attempt.

Protected categories are authentication, authorization, serialization,
validation, configuration, policy, conflict, and security. Unknown and
non-recoverable failures also require explicit reclassification.

## Generic execution ordering

```mermaid
sequenceDiagram
    participant App as Administrative caller
    participant Store as Durable command store
    participant Auth as Authorizer
    participant Coord as Coordinator
    participant Exec as Queue executor

    App->>Coord: execute immutable command
    Coord->>Store: load(commandId)
    alt terminal command already recorded
        Store-->>Coord: terminal record
        Coord-->>App: exact durable result
    else missing command
        Coord->>Auth: authorize(full request)
        Auth-->>Coord: authorized or denied
        Coord->>Store: CAS durable admission/denial
    end
    alt unauthorized or policy rejected
        Coord-->>App: durable rejection
    else authorized
        Coord->>Exec: execute authorized command
        Exec-->>Coord: applied/rejected/failed
        Coord->>Store: CAS terminal audit state
        Coord-->>App: exact terminal evidence
    end
```

## Executor requirements

Before queue mutation, an executor verifies that the target entry still exists,
is eligible for administrative retry, and retains canonical failure evidence
matching the command snapshot. A stale, forged, or mismatched command returns
`Rejected` without mutation.

A generic executor may finish before the coordinator's final command-state write
is confirmed. The coordinator therefore exposes
`ExecutionRecordingUnconfirmed`. Redelivery uses the same command id and must
not create another queue entry or consume retry history again.

## Android Room atomic execution

`RoomRetryAdministrationStateStore` persists the complete command and audit
record in the same `DataLoomRoomDatabase` used by `RoomQueueProvider`.
`RoomRetryAdministrationExecutor` uses that shared database as a stronger
platform-specific atomicity boundary.

One Room transaction:

1. loads the durable command and validates every immutable request field;
2. validates `AUTHORIZED`, the authorization id, effective recoverability, and
   defensive retry/reclassification policy;
3. loads the target queue entry and validates `FAILED` or `DEAD_LETTER` plus the
   exact stored failure code, category, severity, and recoverability;
4. changes the queue state to `PENDING` or `RETRY_WAITING` according to existing
   retry history, clears the terminal error, and makes the work available at the
   executor's observed instant; and
5. advances the same command record to versioned `SUCCEEDED`.

If either update fails, SQLite rolls the transaction back. If both commit but
the caller loses the response, replay sees the durable `SUCCEEDED` receipt and
returns `Applied` without a second queue mutation. The coordinator's attempted
old-version terminal write conflicts, then its normal reload path returns the
already durable success record.

The executor preserves synchronization request/context identity, queue metadata,
retry attempt, retry-budget state, and immutable workflow start/deadline
evidence. It does not consume another attempt, reset budgets, or extend the
workflow deadline.

Stable semantic rejections cover missing/conflicting commands, unauthorized or
mismatched authorization evidence, required reclassification, missing or
non-terminal targets, missing failure evidence, and failure-snapshot mismatch.
Database, integrity, version-exhaustion, and clock-regression outcomes use
canonical sanitized errors.

## Apple durable state

`AppleFileRetryAdministrationStateStore` is the production KMP Apple
implementation of the state-store contract. It provides process-shared exact
compare-and-set behavior, immutable-request protection, bounded strict decoding,
and crash-durable replacement through file `fsync`, atomic rename, and
parent-directory `fsync`.

See the [Apple retry-administration state-store guide](../apple/retry-administration-state-store.md)
for construction, persistence boundaries, error behavior, and platform
responsibilities.

Apple does not yet have a queue-specific executor with a command receipt in the
same durable queue mutation. That work requires an explicit migration of the
Apple queue file format and remains separate from the qualified state store.

## Current boundary

DataLoom now includes common public contracts, deterministic coordination,
production Android and Apple command-state persistence, and atomic Android Room
administrative requeue execution. Remaining work includes Apple atomic queue
execution/format migration, builder or operations-facade assembly, role and UI
integration beyond the authorizer SPI, complete administration events/metrics/
tracing, and cross-platform process-loss and high-contention qualification.
