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
