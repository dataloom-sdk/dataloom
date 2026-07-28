# DataLoom Conflict Contracts (DL-014)

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. Custom contracts exist; built-in policies,
> precedence, decision application, persistence, audit, and loop protection do
> not.

This document defines the conflict-detection and conflict-resolution public
contracts introduced in `dataloom-api` by DL-014.

These contracts represent conflict data, detection requests and results, and
resolution requests and decisions. Exact custom detector/resolver
orchestration now exists in `dataloom-runtime`; storage mutation, durable
unresolved-conflict handling, queue/retry integration, audit, and built-in
conflict strategies remain incomplete.

---

## Overview

DataLoom coordinates conflict detection and resolution, while the host
application owns its schema-specific merge knowledge. That application
boundary does not replace the mandatory V1 built-in policy framework and safe
standard resolution strategies.

Current and target flow:

```text
Local change + Remote change
        ↓
ConflictDetector.detect(ConflictDetectionRequest)
        ↓
ConflictDetectionResult
        ├── NoConflict → continue normal synchronization
        └── ConflictDetected
                  ↓
           ConflictResolver.resolve(ConflictResolutionRequest)
                  ↓
       ConflictResolutionDecision
          UseLocal / UseRemote / Merge / Defer / Fail
                  ↓
       V1 runtime applies or durably defers the decision (not implemented)
```

---

## Conflict Identifiers

**Package:** `io.dataloom.api.identifier`

### `ConflictId`

Immutable value type that wraps a non-blank string identifying a specific
detected synchronization conflict.

- Value must not be blank or whitespace-only.
- Valid input is preserved exactly as supplied.
- No normalization or automatic generation is applied.
- `toString()` returns the underlying value.
- Ownership: conflict producer (application or integration).

Example placeholder values:

```
conflict-001
inv-conflict-2026-07-01
order-conflict-batch-3
```

### `ConflictDetectorId`

Immutable value type that wraps a non-blank string identifying a
[`ConflictDetector`](#conflict-detector) implementation.

- Value must not be blank or whitespace-only.
- Valid input is preserved exactly as supplied.
- No normalization or automatic generation is applied.
- `toString()` returns the underlying value.
- Ownership: conflict-detector implementor or host application.

Example placeholder values:

```
entity-version-detector
application-order-detector
default-conflict-detector
```

### `ConflictResolverId`

Immutable value type that wraps a non-blank string identifying a
[`ConflictResolver`](#conflict-resolver) implementation.

- Value must not be blank or whitespace-only.
- Valid input is preserved exactly as supplied.
- No normalization or automatic generation is applied.
- `toString()` returns the underlying value.
- Ownership: conflict-resolver implementor or host application.

Example placeholder values:

```
client-preferred-resolver
server-preferred-resolver
application-merge-resolver
```

---

## Conflict Types

**Package:** `io.dataloom.api.conflict`
**Type:** `ConflictType` (enum class)

Each value represents a distinct classification of a detected synchronization
conflict.

| Value | Semantics |
|---|---|
| `CONCURRENT_CHANGE` | Local and remote participants independently changed the same entity. |
| `VERSION_MISMATCH` | The supplied entity versions do not satisfy the application's expected version relationship. |
| `UPDATE_DELETE` | A local update conflicts with a remote deletion. |
| `DELETE_UPDATE` | A local deletion conflicts with a remote update. |
| `CREATE_COLLISION` | A create operation conflicts with an existing remote or local entity. |
| `CUSTOM` | A domain-specific conflict that does not map cleanly to another canonical type. |

Ordinal values must not be used for persistence or serialization.
Compare conflict types by name or by direct reference.

---

## Synchronization Conflict

**Package:** `io.dataloom.api.conflict`
**Type:** `SynchronizationConflict` (data class)

Canonical immutable model for a detected synchronization conflict.

### Members

| Member | Type | Required | Description |
|---|---|---|---|
| `id` | `ConflictId` | Yes | Unique conflict identifier. Ownership: conflict producer. |
| `type` | `ConflictType` | Yes | Conflict classification. |
| `entity` | `EntityReference` | Yes | Canonical entity involved in the conflict. |
| `localChange` | `ChangeEvent` | Yes | Local participant's change. |
| `remoteChange` | `ChangeEvent` | Yes | Remote participant's change. |
| `metadata` | `DataLoomMetadata` | No | Optional contextual attributes. Defaults to empty. |

### Rules

- `localChange` and `remoteChange` must reference the same entity type and ID.
- `entity` must match the entity type and ID referenced by both changes.
- Entity versions may differ between `localChange`, `remoteChange`, and `entity`.
- Construction rejects changes referencing different entities.
- Construction does not inspect payload content, interpret version ordering,
  resolve the conflict, persist data, or generate identifiers.

---

## Detection Request

**Package:** `io.dataloom.api.conflict`
**Type:** `ConflictDetectionRequest` (data class)

Immutable request supplied to a [`ConflictDetector`](#conflict-detector).

### Members

| Member | Type | Required | Description |
|---|---|---|---|
| `synchronizationRequest` | `SynchronizationRequest` | Yes | Originating synchronization request. |
| `localChange` | `ChangeEvent` | Yes | Local participant's change. |
| `remoteChange` | `ChangeEvent` | Yes | Remote participant's change. |
| `metadata` | `DataLoomMetadata` | No | Optional contextual attributes. Defaults to empty. |

### Rules

- `localChange` and `remoteChange` must reference the same entity type and ID.
- Construction rejects changes referencing different entities.
- Construction does not perform detection, access storage, or call providers.

---

## Detection Result

**Package:** `io.dataloom.api.conflict`
**Type:** `ConflictDetectionResult` (sealed interface)

### Variants

```kotlin
sealed interface ConflictDetectionResult {
    data object NoConflict : ConflictDetectionResult
    data class ConflictDetected(
        val conflict: SynchronizationConflict,
    ) : ConflictDetectionResult
}
```

- `NoConflict` — no conflict was detected; normal synchronization may proceed.
- `ConflictDetected` — a conflict was detected; carry the canonical conflict.

Creating a result does not mutate storage, queues, or workflow state.

---

## Conflict Detector

**Package:** `io.dataloom.api.conflict`
**Type:** `ConflictDetector` (interface)

```kotlin
interface ConflictDetector {
    val id: ConflictDetectorId
    fun detect(request: ConflictDetectionRequest): ConflictDetectionResult
}
```

### Contract

- Detection is synchronous.
- Detection is deterministic for the same input.
- Detection must not query storage, call network services, wait for user input,
  sleep, schedule background work, apply changes, or persist decisions.
- The detector evaluates already-available change information only.
- Opaque payload content must not be inspected by generic detectors.
- Entity version is opaque; DataLoom does not assume numeric ordering.
- No conflict identifier is generated by the detector.

### Placeholder example

```kotlin
class EntityVersionDetector : ConflictDetector {
    override val id: ConflictDetectorId = ConflictDetectorId("entity-version-detector")

    override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult {
        val local = request.localChange.entity.version
        val remote = request.remoteChange.entity.version
        return if (local != null && remote != null && local != remote) {
            ConflictDetectionResult.ConflictDetected(
                conflict = SynchronizationConflict(
                    id = ConflictId("conflict-${request.localChange.id.value}"),
                    type = ConflictType.VERSION_MISMATCH,
                    entity = request.localChange.entity,
                    localChange = request.localChange,
                    remoteChange = request.remoteChange,
                ),
            )
        } else {
            ConflictDetectionResult.NoConflict
        }
    }
}
```

> **Note:** This example uses application-supplied identifiers. DataLoom does
> not generate conflict identifiers automatically.

---

## Resolution Request

**Package:** `io.dataloom.api.conflict`
**Type:** `ConflictResolutionRequest` (data class)

Immutable request supplied to a [`ConflictResolver`](#conflict-resolver).

### Members

| Member | Type | Required | Description |
|---|---|---|---|
| `synchronizationRequest` | `SynchronizationRequest` | Yes | Originating synchronization request. |
| `conflict` | `SynchronizationConflict` | Yes | The detected conflict to resolve. |
| `metadata` | `DataLoomMetadata` | No | Optional contextual attributes. Defaults to empty. |

### Rules

- Construction does not execute resolution, mutate storage, or call transport
  providers.

---

## Resolution Decision

**Package:** `io.dataloom.api.conflict`
**Type:** `ConflictResolutionDecision` (sealed interface)

### Variants

#### `UseLocal`

The runtime should use the conflict's local change as the resolution candidate.

```kotlin
data class UseLocal(
    val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : ConflictResolutionDecision
```

#### `UseRemote`

The runtime should use the conflict's remote change as the resolution candidate.

```kotlin
data class UseRemote(
    val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : ConflictResolutionDecision
```

#### `Merge`

The application resolver supplies a resolved `ChangeEvent`.

```kotlin
data class Merge(
    val expectedEntity: EntityReference,
    val resolvedChange: ChangeEvent,
    val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : ConflictResolutionDecision
```

- `resolvedChange` must reference the same entity type and ID as `expectedEntity`.
- The resolver supplies `request.conflict.entity` as `expectedEntity`.
- A different entity version may be supplied.
- DataLoom does not inspect or generate the merged payload.
- Merge semantics are owned by the application resolver.
- Construction rejects resolved changes referencing a different entity.

#### `Defer`

The conflict remains unresolved for future application or runtime handling.

```kotlin
data class Defer(
    val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : ConflictResolutionDecision
```

- A deferred conflict is not automatically a retry decision.
- Creating this decision does not enqueue or persist the conflict.

#### `Fail`

The conflict cannot be resolved under the current policy.

```kotlin
data class Fail(
    val error: DataLoomError,
    val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
) : ConflictResolutionDecision
```

- The canonical `DataLoomError` is required.
- A failed resolution is not automatically retryable.
- Creating this decision does not automatically fail the workflow.
- Error messages must not expose credentials, keys, or sensitive payload content.

### Common requirements

- All variants are immutable.
- Metadata defaults to `DataLoomMetadata.Empty`.
- Decisions provide value-based equality.
- Decisions do not mutate storage, transport, queues, or workflow state.
- Decisions do not automatically retry.
- Metadata must not contain credentials, keys, payloads, or personal data.

---

## Conflict Resolver

**Package:** `io.dataloom.api.conflict`
**Type:** `ConflictResolver` (interface)

```kotlin
interface ConflictResolver {
    val id: ConflictResolverId
    fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision
}
```

### Contract

- Resolution is synchronous.
- Resolution is deterministic for the same input and configuration.
- Resolution must not access databases, call network services, wait for user
  input, sleep, schedule background work, apply changes, modify queues, or
  persist decisions.
- Opaque payload content must not be automatically inspected or logged.
- Entity version is opaque; DataLoom does not assume numeric ordering.

### Placeholder example

```kotlin
class ServerPreferredResolver : ConflictResolver {
    override val id: ConflictResolverId = ConflictResolverId("server-preferred-resolver")

    override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
        return ConflictResolutionDecision.UseRemote()
    }
}
```

### Application ownership

Examples of application-owned resolution policies include:

```
Client wins
Server wins
Latest application version wins
Merge selected fields
Reject conflicting financial operations
Require user review
Custom domain resolver
```

DataLoom does not assume one policy is correct for every application.

---

## Synchronous Evaluation Rationale

Both detection and resolution are synchronous because they operate on
already-available change information. They should not:

- Query storage
- Call remote services
- Refresh authentication
- Wait for user input
- Sleep
- Schedule background work
- Apply changes
- Persist decisions

This keeps the contracts:

- **Deterministic** — same input always produces the same result.
- **Fast** — no I/O or blocking operations.
- **Testable** — no infrastructure dependencies needed.
- **Multiplatform** — safe in Kotlin Multiplatform common code.
- **Infrastructure-independent** — decoupled from runtime scheduling.

Interactive or asynchronous conflict resolution is not implemented.

---

## Application Ownership

> DataLoom coordinates conflict detection and resolution, while the host
> application owns domain-specific conflict rules and merge behavior.

The application is responsible for:

- Supplying `ConflictDetector` implementations with domain-specific logic.
- Supplying `ConflictResolver` implementations with domain-specific policies.
- Generating conflict identifiers (`ConflictId`).
- Interpreting entity version semantics.
- Controlling payload serialization for payload-aware resolvers.

DataLoom is responsible for:

- Coordinating the current detection and resolution workflow.
- Providing the contracts, identifiers, and models defined in this issue.
- Preserving payload and version opacity.
- Providing the V1 built-in policy, persistence, audit, precedence,
  convergence, and loop-protection engine; this work remains incomplete.

---

## Payload Boundary

- `DataLoomPayload` is opaque within DataLoom core.
- Generic detectors must not inspect payload content.
- Application resolvers may interpret payloads only through
  application-controlled serialization outside DataLoom core.
- DataLoom does not provide JSON, protobuf, or field-level merge logic.
- Merged payloads are created by the host application.
- Payload content must not be logged automatically.

---

## Version Boundary

- `EntityVersion` is opaque within DataLoom core.
- DataLoom must not assume numeric ordering.
- DataLoom must not assume timestamps.
- DataLoom must not assume ETag semantics.
- Application detectors may interpret versions according to their own contract.
- Version-comparison utilities are deferred to a future issue.

---

## Current implementation gaps

The following are not implemented in the current repository:

- Built-in client-wins resolver
- Built-in server-wins resolver
- Timestamp-based resolver
- Version-vector comparison
- Field-level merging
- JSON merging
- CRDT support
- Interactive user resolution
- Conflict persistence
- Conflict queue
- Conflict history
- Conflict metrics
- Conflict retry orchestration
- Provider-reported conflict conversion
- Partial change-set conflict handling
- Automatic conflict ID generation
- Runtime application of decisions
- Runtime conflict orchestration

---

## Related Contracts

- [`ChangeEvent`](./change-model.md#changeevent) — carries local and remote changes.
- [`EntityReference`](./change-model.md#entityreference) — identifies an entity by type, ID, and optional version.
- [`DataLoomPayload`](./payload-contracts.md#dataloompayload) — opaque payload.
- [`EntityVersion`](./change-model.md#entityversion) — opaque entity version.
- [`SynchronizationRequest`](./synchronization-request.md) — originating synchronization intent.
- [`DataLoomError`](./error-model.md) — canonical error type.
- [`DataLoomMetadata`](./execution-context.md#metadata-rules) — optional contextual attributes.
