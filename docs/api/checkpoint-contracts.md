# DataLoom Checkpoint Contracts (DL-011)

[API reference index](./README.md)

> **Status:** Available contract with inbound-pipeline integration. Complete
> restart, migration, and cross-platform persistence qualification remains.

This document defines the platform-independent synchronization-checkpoint
contracts introduced in `dataloom-api` by DL-011.

These contracts represent opaque checkpoint identity and progress values,
and the storage read/write requests used to persist and retrieve them. The
current inbound pipeline coordinates checkpoint read, pull, apply, and safe
checkpoint advancement. Durable queue/retry/conflict integration and qualified
reference storage/transport providers remain separate work.

---

## Overview

A synchronization checkpoint records how far a pull-based synchronization
stream has progressed with the remote participant. DataLoom treats the
checkpoint's progress marker as opaque; it never interprets, compares, or
generates checkpoint values.

Current inbound synchronization flow:

```text
StorageProvider.readCheckpoint()
        ↓
TransportProvider.pullChanges(checkpoint)
        ↓
PullChangesResult (may carry a next checkpoint)
        ↓
StorageProvider.applyInboundChanges()   (only when changes were returned)
        ↓
StorageProvider.writeCheckpoint()       (only after successful apply)
```

---

## Checkpoint Keys

**Package:** `io.dataloom.api.identifier`

### `CheckpointKey`

Immutable value type that wraps a non-blank string identifying the logical
synchronization stream whose progress is being stored.

- Value must not be blank or whitespace-only.
- Valid input is preserved exactly as supplied.
- No normalization or automatic generation is applied.
- `toString()` returns the underlying value.
- The key format is application or integration defined.
- Ownership: host application or integration.

Example placeholder values:

```
customers-pull
orders-tenant-example
inventory-region-example
```

---

## Opaque Checkpoint Tokens

### `CheckpointToken`

Immutable value type that wraps a non-blank string representing an opaque
synchronization progress marker.

A token may represent:

- A delta token
- A continuation cursor
- A remote sequence
- An opaque revision
- An application-defined synchronization marker

DataLoom must treat it as opaque:

- No interpretation of token format
- No comparison of token ordering
- No normalization
- No automatic generation

`toString()` returns the underlying value. A transport provider may redact
checkpoint tokens from diagnostics. **Checkpoint tokens must not be treated
as credentials.**

---

## Checkpoint Ownership

| Concern | Owner |
|---|---|
| Checkpoint key naming | Host application or integration |
| Checkpoint token format and semantics | Remote system or application-defined contract |
| Checkpoint persistence | `StorageProvider` implementation |
| Checkpoint advancement timing | Current inbound pipeline, after successful application |
| Checkpoint interpretation | Never DataLoom core |

DataLoom core never owns or interprets the token; it only carries the
opaque value between `TransportProvider` and `StorageProvider`.

---

## `SynchronizationCheckpoint`

**Package:** `io.dataloom.api.synchronization`

Immutable model exposing:

```kotlin
public data class SynchronizationCheckpoint(
    public val key: CheckpointKey,
    public val token: CheckpointToken,
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
```

- `key` and `token` are required.
- `metadata` defaults to empty metadata.
- Equality compares all three properties by value.
- The model does not contain timestamps.
- The model does not interpret token semantics.
- The model does not expose protocol-specific cursor types.
- Construction does not advance or persist any checkpoint.

---

## Reading and Writing Checkpoints

**Package:** `io.dataloom.api.storage`

`StorageProvider` exposes:

```kotlin
public suspend fun readCheckpoint(
    request: CheckpointReadRequest,
): ProviderOperationResult<SynchronizationCheckpoint?>

public suspend fun writeCheckpoint(
    request: CheckpointWriteRequest,
): ProviderOperationResult<Unit>
```

### `CheckpointReadRequest`

```kotlin
public data class CheckpointReadRequest(
    public val request: SynchronizationRequest,
    public val key: CheckpointKey,
)
```

Construction performs no storage access.

### `CheckpointWriteRequest`

```kotlin
public data class CheckpointWriteRequest(
    public val request: SynchronizationRequest,
    public val checkpoint: SynchronizationCheckpoint,
)
```

Construction performs no persistence.

### `null` means no checkpoint stored

A `ProviderOperationResult.Success(null)` result from `readCheckpoint` means
that no checkpoint is currently stored for the requested `CheckpointKey`. This
is the expected result for the first synchronization of a stream. Checkpoint
deletion is **not** implemented in this issue.

The value objects do not write checkpoints. A direct caller—or the current
inbound pipeline—must invoke `writeCheckpoint` only when the critical rule
below is satisfied.

---

## Pull-Request Checkpoint Usage

**Package:** `io.dataloom.api.transport`

`PullChangesRequest` exposes an optional checkpoint:

```kotlin
public val checkpoint: SynchronizationCheckpoint?
```

- `null` means no prior checkpoint is supplied (for example, the first pull).
- The transport provider treats the token as opaque unless it owns the token
  format.
- Construction performs no network operation.
- Existing `entityTypes` and `maxEvents` behavior remains unchanged.

---

## Next-Checkpoint Behavior

`PullChangesResult` may return a next checkpoint on either variant:

```kotlin
public sealed interface PullChangesResult {
    public data class NoChanges(
        public val nextCheckpoint: SynchronizationCheckpoint? = null,
    ) : PullChangesResult

    public data class Changes(
        public val changeSet: ChangeSet,
        public val hasMore: Boolean,
        public val nextCheckpoint: SynchronizationCheckpoint? = null,
    ) : PullChangesResult
}
```

- A next checkpoint may be absent on either variant.
- Returning a checkpoint does not persist or activate it.
- The runtime must persist it only after the associated inbound work has
  succeeded.
- The result does not expose protocol-specific cursors.

---

## Critical Apply-Before-Advance Rule

> A next checkpoint must not be persisted until all inbound changes
> associated with that checkpoint have been applied successfully.

For a changes result:

```text
TransportProvider.pullChanges()
        ↓
StorageProvider.applyInboundChanges()
        ↓
StorageProvider.writeCheckpoint()
```

For a no-changes result:

```text
TransportProvider.pullChanges()
        ↓
StorageProvider.writeCheckpoint() when a next checkpoint exists
```

`InboundPullSynchronizationPipeline` enforces this call order today: it does
not write the returned checkpoint when inbound application fails. The
`StorageProvider` SPI does not provide one atomic apply-and-checkpoint
transaction, so applications must still make inbound application idempotent
for recovery when application succeeds but a subsequent checkpoint write
fails.

---

## No-Change Checkpoint Behavior

A `PullChangesResult.NoChanges` result may still carry a next checkpoint,
for example, when the remote participant advances a delta token even though
no changes matched. Because no inbound changes require applying, the
current inbound pipeline persists that checkpoint directly when present (no
apply step is required), but only after the pull operation itself has
completed successfully.

---

## Sensitive-Data Restrictions

- Checkpoint tokens must not be treated as credentials.
- Credentials must not be stored in checkpoint metadata.
- Checkpoint values must not be automatically logged.
- A transport provider may redact checkpoint tokens from diagnostics.
- Checkpoint metadata must not contain access tokens, passwords, encryption
  keys, private certificates, personal data, or full application payloads.

---

## Placeholder Examples

```kotlin
val checkpoint = SynchronizationCheckpoint(
    key = CheckpointKey("customers-pull"),
    token = CheckpointToken("opaque-delta-token-example"),
)

val readRequest = CheckpointReadRequest(
    request = synchronizationRequest,
    key = CheckpointKey("customers-pull"),
)

when (val result = storageProvider.readCheckpoint(readRequest)) {
    is ProviderOperationResult.Success -> {
        val priorCheckpoint: SynchronizationCheckpoint? = result.value
        // pass priorCheckpoint into PullChangesRequest
    }
    is ProviderOperationResult.Failure -> { /* handle error */ }
}
```

---

## Current gaps

The following remain outside the current implementation:

- Checkpoint deletion
- A qualified built-in Room, SQLDelight, or equivalent checkpoint store
- Cross-platform persistence migration and restart qualification
- Server-directed retry metadata

---

## Related Contracts

- [`SynchronizationRequest`](./synchronization-request.md) — originating
  synchronization intent.
- [`StorageProvider`](./storage-provider.md) — storage adapter contract.
- [`TransportProvider`](./transport-provider.md) — transport adapter contract.
- [Acknowledgement Contracts](./acknowledgement-contracts.md) — outbound
  acknowledgement contracts.
- [`DataLoomMetadata`](./execution-context.md#metadata-rules) — optional
  contextual attributes.
