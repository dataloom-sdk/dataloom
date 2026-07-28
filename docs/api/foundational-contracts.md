# DataLoom Foundational Public Contracts (DL-004, DL-005, DL-007)

[API reference index](./README.md)

> **Status:** Available foundational contracts. Later runtime slices consume
> them, but the complete V1 strategy and production subsystem set remains open.

This document defines stable, platform-independent public contracts introduced
in `dataloom-api`.

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
| `ExecutionId` | DataLoom runtime or host integration |
| `RequestId` | Request initiator or host integration |
| `TenantId` | Host application or enterprise integration |
| `UserId` | Host authentication/domain layer |
| `RuntimeVersion` | DataLoom runtime |
| `ConfigurationVersion` | Configuration source or host integration |
| `LocaleTag` | Host application or request initiator |
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
val executionId = ExecutionId("execution-001")
val requestId = RequestId("request-001")
val tenantId = TenantId("tenant-001")
val userId = UserId("user-001")
val runtimeVersion = RuntimeVersion("runtime-1.0.0")
val configurationVersion = ConfigurationVersion("config-2026-07-21")
val localeTag = LocaleTag("en-US")
```

## Execution context and synchronization request contracts

DL-005 introduces additional immutable public contracts:

- [`ExecutionContext`](./execution-context.md)
- [`SynchronizationRequest`](./synchronization-request.md)
- [`DataLoomMetadata`](./execution-context.md#metadata-rules)

These contracts carry synchronization context and request intent only.
Runtime execution, queueing, retry, transport, persistence, and state
transitions are intentionally not implemented in this scope, except for the
transport SPI contracts documented separately.

## Provider SPI contracts

DL-007 introduces foundational provider contracts:

- [`Provider SPI`](./provider-spi.md)
- [`Provider Lifecycle and Health`](./provider-lifecycle.md)
- [`Transport Provider`](./transport-provider.md)

These contracts define provider identity, descriptor metadata, lifecycle labels,
health labels, initialization context, and operation result semantics.
Concrete providers, provider registry/discovery, and lifecycle orchestration
remain out of scope in this issue.

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

This mode is orthogonal to source priority, consistency, fallback, persistence,
and queue policy. It does not select offline-first, remote-first, cache-first,
network-only, hybrid, or adaptive behavior; ADR-0002/#102 owns the V1
synchronization-strategy contract.

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

## Payload and change contracts

DL-008 introduces payload and change-model contracts:

- [`Payload Contracts`](./payload-contracts.md)
- [`Change Model`](./change-model.md)

These contracts define opaque payload representation, entity versioning,
entity references, change events, and change sets. Serialization, storage,
transport, conflict resolution, queueing, retry, and synchronization execution
are intentionally not implemented in this scope.
