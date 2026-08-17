# DataLoom Storage Provider (DL-009, DL-011)

[API reference index](./README.md)

> **Status:** Available application-storage adapter contract. It does not own
> DataLoom queue state, asset sessions, or enterprise governance.

## Purpose of `StorageProvider`

`StorageProvider` is the platform-independent adapter boundary between the
DataLoom runtime and the host application's storage architecture.

```text
DataLoom Runtime
      ↓
StorageProvider
      ↓
Application-controlled storage adapter
      ↓
Room / SQLDelight / custom storage
```

`StorageProvider` is a **synchronization adapter**, not a replacement
repository or DAO. The application repository remains the API through which UI
and business logic read and modify domain data.

```text
UI / ViewModel
      ↓
Application Repository
      ↓
Room / SQLDelight / DataStore / custom storage
```

Reference implementations are optional and interchangeable. Applications can
choose the SQLDelight reference provider, the Room reference provider where
applicable, or a custom implementation. Do not bind both SQLDelight and Room
providers to the same `StorageProvider` role in one runtime configuration.

**Package:** `io.dataloom.api.storage`

---

## Contracts

### `OutboundChangeReadRequest`

Immutable request for reading outbound synchronization changes.

| Member | Type | Required | Description |
|---|---|---|---|
| `request` | `SynchronizationRequest` | Yes | Originating synchronization request. |
| `entityTypes` | `Set<EntityType>` | No | Optional entity-type restriction. Defaults to empty set (no restriction). |
| `maxEvents` | `Int?` | No | Optional batch hint. Must be greater than zero when supplied. |

The supplied `entityTypes` set is defensively copied at construction time.
Mutating a source set after construction does not affect the request.

Construction does not access storage, enforce synchronization direction, or
perform serialization.

---

### `OutboundChangeReadResult`

Immutable sealed contract representing the result of an outbound read.

```kotlin
sealed interface OutboundChangeReadResult {
    data object NoChanges : OutboundChangeReadResult
    data class Changes(
        val changeSet: ChangeSet,
        val hasMore: Boolean,
    ) : OutboundChangeReadResult
}
```

| Variant | Description |
|---|---|
| `NoChanges` | No outbound changes are currently available in storage. |
| `Changes` | Contains a non-empty `ChangeSet` and a `hasMore` flag. |

The result does not acknowledge, delete, or mark events as synchronized.
The current outbound pipeline separately calls
`acknowledgeOutboundChanges()` after validating the transport response.
Checkpoint reads and writes belong to the inbound pipeline and are independent
of this outbound-read result.

---

### `InboundChangeApplyRequest`

Immutable request for applying inbound synchronization changes.

| Member | Type | Required | Description |
|---|---|---|---|
| `request` | `SynchronizationRequest` | Yes | Originating synchronization request. |
| `changeSet` | `ChangeSet` | Yes | Non-empty inbound change set to be applied. |

Construction does not apply changes, inspect payload contents, start a
transaction, or execute synchronization.

---

### `StorageProvider`

Platform-independent provider contract for exchanging synchronization changes
with application-controlled storage.

**Extends:** `DataLoomProvider`

| Member | Type | Description |
|---|---|---|
| `descriptor` | `ProviderDescriptor` | Must use `ProviderType.STORAGE`. |
| `readOutboundChanges(request)` | `suspend` | Reads outbound changes from storage. |
| `applyInboundChanges(request)` | `suspend` | Applies inbound changes to storage. |
| `acknowledgeOutboundChanges(request)` | `suspend` | Records a remote acknowledgement in storage (DL-011). |
| `readCheckpoint(request)` | `suspend` | Reads a stored checkpoint, or `null` when none is stored (DL-011). |
| `writeCheckpoint(request)` | `suspend` | Persists a checkpoint (DL-011). |
| `readLocalConflictCandidate(request)` | `suspend` | Reads the local counterpart of one incoming inbound change, for conflict detection only. Optional — see below. |

---

## Reading Outbound Changes

```kotlin
val readRequest = OutboundChangeReadRequest(
    request = synchronizationRequest,
    entityTypes = setOf(EntityType("invoice"), EntityType("payment")),
    maxEvents = 50,
)

when (val result = storageProvider.readOutboundChanges(readRequest)) {
    is ProviderOperationResult.Success -> when (val outcome = result.value) {
        is OutboundChangeReadResult.NoChanges -> { /* nothing to push */ }
        is OutboundChangeReadResult.Changes -> {
            val changeSet = outcome.changeSet
            val hasMore = outcome.hasMore
            // hand off changeSet to TransportProvider.pushChanges(...)
        }
    }
    is ProviderOperationResult.Failure -> { /* handle error */ }
}
```

---

## Applying Inbound Changes

```kotlin
val applyRequest = InboundChangeApplyRequest(
    request = synchronizationRequest,
    changeSet = inboundChangeSet,
)

when (val result = storageProvider.applyInboundChanges(applyRequest)) {
    is ProviderOperationResult.Success -> { /* changes applied */ }
    is ProviderOperationResult.Failure -> { /* handle error */ }
}
```

---

## Empty-Result Handling

When `readOutboundChanges` returns `OutboundChangeReadResult.NoChanges`, no
outbound changes are available. The runtime treats this as a clean state for
the current read cycle. No acknowledgement is required.

---

## Acknowledging Outbound Changes (DL-011)

```kotlin
val acknowledgementRequest = OutboundChangeAcknowledgementRequest(
    request = synchronizationRequest,
    acknowledgement = changeSetAcknowledgement,
)

when (val result = storageProvider.acknowledgeOutboundChanges(acknowledgementRequest)) {
    is ProviderOperationResult.Success -> { /* acknowledgement recorded */ }
    is ProviderOperationResult.Failure -> { /* handle error */ }
}
```

Acknowledgement handling is implementation-defined. Accepted events may be
marked synchronized or removed; retry events must remain eligible for later
processing; rejected events must remain inspectable according to application
policy. See [Acknowledgement Contracts](./acknowledgement-contracts.md).

---

## Reading and Writing Checkpoints (DL-011)

```kotlin
val readRequest = CheckpointReadRequest(
    request = synchronizationRequest,
    key = CheckpointKey("customers-pull"),
)

when (val result = storageProvider.readCheckpoint(readRequest)) {
    is ProviderOperationResult.Success -> {
        val checkpoint: SynchronizationCheckpoint? = result.value // null means no checkpoint stored
    }
    is ProviderOperationResult.Failure -> { /* handle error */ }
}

val writeRequest = CheckpointWriteRequest(
    request = synchronizationRequest,
    checkpoint = nextCheckpoint,
)

// Only after associated inbound changes have been applied successfully:
storageProvider.writeCheckpoint(writeRequest)
```

See [Checkpoint Contracts](./checkpoint-contracts.md) for the critical
apply-before-advance rule.

---

## Reading Local Conflict Candidates (opt-in, for conflict detection only)

```kotlin
val candidateRequest = LocalConflictCandidateReadRequest(
    request = synchronizationRequest,
    entity = incomingRemoteEvent.entity,
)

when (val result = storageProvider.readLocalConflictCandidate(candidateRequest)) {
    is ProviderOperationResult.Success -> when (val outcome = result.value) {
        is LocalConflictCandidateReadResult.NotFound -> { /* no local counterpart; nothing to compare */ }
        is LocalConflictCandidateReadResult.Found -> {
            val localChange = outcome.localChange // compare against the incoming remote ChangeEvent
        }
    }
    is ProviderOperationResult.Failure -> { /* treat as NotFound; never fail the batch because of this */ }
}
```

This method exists solely so the runtime (specifically
[`InboundPullSynchronizationPipeline`](./inbound-pull-pipeline.md#conflict-detection))
can compare one specific incoming inbound `ChangeEvent` against its local
counterpart for synchronization conflict detection — **it is not a general
entity read**, and the interface's own KDoc names it as a deliberate,
narrow exception to "avoid general-purpose query methods." It must never
return more than one entity's local change per call, and must never be used
for arbitrary application reads.

The default implementation returns `LocalConflictCandidateReadResult.NotFound`
unconditionally, so every `StorageProvider` implementation written before
this method existed continues to compile and behave exactly as before
without overriding it. Conflict detection during inbound pull is opt-in per
provider — a provider adopts it by overriding this method with a real local
read.

`RoomStorageProvider` (`dataloom-storage-room`) is the first reference
provider to override this method for real, demonstrating what a genuine
implementation looks like: it considers only its own *outbound* change-event
log — the local application's pending or recently-made edits — never the
inbound log. An entity with no outbound history correctly reports `NotFound`
even if it was previously synced via an inbound apply, since that case is an
ordinary remote update with no local edit to disagree with, not a conflict.
When multiple outbound events exist for the same entity, the most recently
appended one (by change-set insertion order, then in-set event order) is
returned — the underlying schema has no shared wall-clock ordering between
its outbound and inbound tables, so "most recently appended" is the only
ordering it can support without inventing a new one. See
[`RoomStorageProvider`'s own KDoc](../../dataloom-storage-room/src/main/kotlin/io/dataloom/storage/room/RoomStorageProvider.kt)
for the full reasoning.

`SqlDelightStorageProvider` (`dataloom-storage-sqldelight`) is the second
reference provider to override it, following the same outbound-only
principle. One real platform difference from Room, worth knowing rather than
papering over: this provider's own `acknowledgeOutboundChanges` *deletes*
the row for an `ACCEPTED` event instead of retaining it, so an entity whose
only outbound edit has already been accepted by the remote also correctly
reports `NotFound` — there is no still-outstanding local edit left to
compare. When multiple outbound rows remain for the same entity, the
highest `sequence` (the most recently inserted) is returned.

`FileStorageProvider` (`dataloom-storage-file`) is the third, again the
same outbound-only principle: only `outbound/` is considered, never
`inbound/`. Unlike Room and SQLDelight it has no per-entity index — the
scan walks its single ordered `outbound.idx` and keeps the last match, a
deliberate, documented cost consistent with this provider's own
low-volume/reference scope. `rejected/` is deliberately not consulted: a
`REJECTED` event moves there with no ordering information relative to any
other outbound entry, so there is no persisted evidence to say whether it
or some other outbound entry for the same entity is more recent — treating
it as no-longer-live local intent avoids inventing an ordering this schema
cannot support. Matches SQLDelight in deleting an `ACCEPTED` event from the
index rather than retaining it.

DataStore reference provider does not override this method yet; adopting
it is separate, unstarted follow-up work.

---

## Batching Through `maxEvents` and `hasMore`

- `maxEvents` is a hint to the provider to limit the number of events returned
  per read. The provider may return fewer events.
- `hasMore = true` in `OutboundChangeReadResult.Changes` indicates that another
  read may return additional changes.
- The runtime may issue repeated reads to drain remaining changes.
- Batching strategy, ordering, and continuation semantics are provider-defined.
- The current inbound pipeline coordinates opaque checkpoint reads and writes.
  Protocol-specific cursor formats remain behind the transport provider and
  are not modeled by this storage SPI.

---

## Payload Opacity

`StorageProvider` receives and delivers `DataLoomPayload` values that are
opaque to DataLoom core.

- Payloads may be plaintext or already encrypted according to application
  policy.
- Storage-provider implementations must not assume plaintext.
- DataLoom core does not automatically encrypt or decrypt payloads.
- Encryption-provider contracts will be defined separately.
- Applications remain responsible for key management and secure-storage policy.

Do not log payload content in provider implementations.

---

## Thread-Safety Expectations

The SPI does not enforce a threading model or select a dispatcher.

Implementations are responsible for documenting and enforcing their own
thread-safety guarantees.

---

## Cancellation Expectations

All `StorageProvider` operations are `suspend` functions. Implementations must
preserve coroutine cancellation and must not swallow `CancellationException`.

---

## Error Handling

All operations return `ProviderOperationResult<T>`:

- `ProviderOperationResult.Success<T>` — operation completed with value `T`.
- `ProviderOperationResult.Failure` — operation failed with a canonical
  `DataLoomError`.

Implementations must not expose raw storage exceptions through the public API.
Wrap infrastructure exceptions in a canonical `DataLoomError` before returning
a `Failure` result.

---

## Placeholder Implementation Example

The following example illustrates the minimum structure for a placeholder
`StorageProvider`. It is not a production implementation.

```kotlin
class PlaceholderStorageProvider : StorageProvider {

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = ProviderId("provider.storage.placeholder"),
        name = ProviderName("Placeholder Storage Provider"),
        type = ProviderType.STORAGE,
        version = ProviderVersion("0.1.0"),
    )

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

    override suspend fun close(): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> =
        ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> =
        ProviderOperationResult.Success(null)

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> =
        ProviderOperationResult.Success(Unit)
}
```

---

## Scope boundary and current gaps

`StorageProvider` currently owns application-facing change reads, inbound
application, outbound acknowledgement, and checkpoint reads and writes. It
does not define checkpoint deletion, partial-application semantics, a generic
transaction contract, migrations, schema ownership, or database recovery.

Durable queue persistence, ordering, leases, and queue state transitions belong
to [`QueueProvider`](./queue-provider.md), not this SPI. Retry evaluation and
rescheduling use the retry and scheduler contracts, but the complete built-in
retry and circuit-breaker behavior required for V1 is not implemented.
Idempotency persistence, conflict persistence, and other DataLoom-owned
operational state also remain mandatory V1 gaps.

---

## Related Contracts

- [`SynchronizationRequest`](./synchronization-request.md) — originating
  synchronization intent.
- [`ChangeSet`](./change-model.md#changeset) — ordered collection of change
  events.
- [`ChangeEvent`](./change-model.md#changeevent) — single synchronization
  change intent.
- [`DataLoomPayload`](./payload-contracts.md) — opaque change payload.
- [`DataLoomProvider`](./provider-spi.md#dataloomprovider) — common provider
  contract.
- [`ProviderOperationResult`](./provider-spi.md#provider-results) — provider
  result sealed contract.
- [`TransportProvider`](./transport-provider.md) — transport adapter contract.
- [`DataLoomError`](./error-model.md) — canonical error type.
- [Acknowledgement Contracts](./acknowledgement-contracts.md) — outbound
  acknowledgement contracts (DL-011).
- [Checkpoint Contracts](./checkpoint-contracts.md) — checkpoint contracts
  (DL-011).
