# DataLoom Foundational Public Contracts (DL-004)

This document defines the first stable, platform-independent public contracts
introduced in `dataloom-api`.

These contracts define names and semantics only. Runtime behavior such as
workflow transitions, queueing, retry, scheduling, and synchronization
execution is intentionally **not implemented** in this issue.

## Platform-independence rules

- APIs use Kotlin standard library types only.
- APIs remain valid in `commonMain` without Android or JVM-only public types.
- APIs do not include serialization, ORM, or platform annotations.
- APIs do not generate identifiers automatically.

## Identifier contracts

All canonical identifiers are immutable value classes backed by a `String`.

Validation rules:

- Value must be non-blank.
- Whitespace-only input is rejected.
- Valid input is preserved exactly as supplied.
- No locale-sensitive normalization is applied.
- `toString()` returns the underlying identifier value.

### Identifier ownership

| Identifier | Expected owner |
|---|---|
| `WorkflowId` | DataLoom runtime or host integration |
| `SynchronizationSessionId` | DataLoom runtime or host integration |
| `ChangeEventId` | Change producer |
| `ChangeSetId` | Change-set producer |
| `EntityId` | Host application/domain |
| `EntityType` | Host application/domain |
| `CorrelationId` | Request initiator or integration boundary |
| `TraceId` | Observability integration |
| `ErrorCode` | DataLoom error catalogue |

### Placeholder examples

```kotlin
val workflowId = WorkflowId("workflow-001")
val sessionId = SynchronizationSessionId("session-001")
val changeEventId = ChangeEventId("event-001")
val changeSetId = ChangeSetId("changeset-001")
val entityId = EntityId("entity-42")
val entityType = EntityType("invoice")
val correlationId = CorrelationId("corr-001")
val traceId = TraceId("trace-001")
```

## Workflow lifecycle state

`WorkflowLifecycleState` defines stable lifecycle labels:

- `CREATED`
- `VALIDATED`
- `QUEUED`
- `SCHEDULED`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`

No transition engine or state machine behavior is implemented yet.

## Synchronization direction

`SynchronizationDirection` values:

- `PUSH`: local changes are sent to a remote participant.
- `PULL`: remote changes are received locally.
- `BIDIRECTIONAL`: both directions may participate in one logical process.

## Synchronization mode

`SynchronizationMode` values:

- `FULL`: process the complete selected scope.
- `DELTA`: process only changes since an accepted baseline.

## Change operations

`ChangeOperation` values:

- `CREATE`
- `UPDATE`
- `DELETE`
- `MERGE`
- `RESTORE`

These values describe semantic intent only and are not bound to any transport
protocol.

## Workflow priority

`WorkflowPriority` values:

- `LOW`
- `NORMAL` (conceptual default)
- `HIGH`
- `CRITICAL`

Priority interpretation by the scheduler is deferred to a later issue.
