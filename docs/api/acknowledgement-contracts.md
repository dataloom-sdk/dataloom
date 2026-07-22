# DataLoom Acknowledgement Contracts (DL-011)

This document defines the platform-independent change-acknowledgement
contracts introduced in `dataloom-api` by DL-011.

These contracts represent remote acceptance or rejection of pushed change
events and the storage acknowledgement request used to record them in
application-controlled storage. Runtime orchestration, durable queue
processing, retry execution, and concrete storage or transport providers are
**not implemented** in this issue.

---

## Overview

A successful network request alone is not sufficient to safely complete an
outbound synchronization operation. DataLoom separates *transport success*
(the push call itself succeeded) from *event-level acceptance* (whether the
remote participant accepted each individual change).

Conceptual flow (runtime orchestration is deferred):

```text
StorageProvider.readOutboundChanges()
        ↓
TransportProvider.pushChanges()
        ↓
ChangeSetAcknowledgement
        ↓
StorageProvider.acknowledgeOutboundChanges()
```

- Transport must not directly modify storage.
- Storage must not perform transport.
- The DataLoom runtime will coordinate the two providers in a later issue.

---

## Change Acknowledgement Statuses

**Package:** `io.dataloom.api.synchronization`

### `ChangeAcknowledgementStatus`

Closed enumeration describing how a remote participant responded to a single
pushed change event:

| Value | Meaning |
|---|---|
| `ACCEPTED` | The remote participant accepted the change for the synchronization contract. |
| `RETRY` | The change was not accepted, but a later attempt may succeed. |
| `REJECTED` | The change was not accepted, and repeating it unchanged is not expected to succeed. |

Rules:

- Do not rely on enum ordinals; compare by name or value.
- This contract does not implement retry behavior, queue deletion, or map
  statuses directly to HTTP status codes.

---

## Event Acknowledgement

### `ChangeEventAcknowledgement`

```kotlin
public data class ChangeEventAcknowledgement(
    public val eventId: ChangeEventId,
    public val status: ChangeAcknowledgementStatus,
    public val error: DataLoomError? = null,
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
```

- `eventId` and `status` are required.
- `error` is optional. `ACCEPTED` does not require an error. `RETRY` and
  `REJECTED` *may* include a canonical `DataLoomError`, but neither status
  is required to carry one in this issue.
- Provider-specific exception types must never be exposed through `error`.
- `metadata` defaults to empty metadata and must not contain payload content.
- Construction performs no retry, deletion, or storage/queue update.

---

## Change-Set Acknowledgement

### `ChangeSetAcknowledgement`

```kotlin
public class ChangeSetAcknowledgement(
    public val changeSetId: ChangeSetId,
    events: List<ChangeEventAcknowledgement>,
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
```

- `changeSetId` is required.
- `events` must contain at least one acknowledgement; empty collections are
  rejected at construction time.
- Duplicate `ChangeEventId` values across `events` are rejected.
- The supplied `events` list is defensively copied; the exposed `events`
  property is read-only and preserves declared order.
- `metadata` defaults to empty metadata.
- Construction performs no queue or database mutation.

### Partial Acknowledgement

A `ChangeSetAcknowledgement` does not need to contain every event from the
original change set. A remote participant may acknowledge a subset of events
in a single response (for example, when it processes events individually or
enforces a partial-batch limit). Completeness validation — deciding whether
every originally pushed event has an acknowledgement — is a **runtime
responsibility** deferred to a later issue.

---

## Outbound Acknowledgement Request

**Package:** `io.dataloom.api.synchronization`

### `OutboundChangeAcknowledgementRequest`

```kotlin
public data class OutboundChangeAcknowledgementRequest(
    public val request: SynchronizationRequest,
    public val acknowledgement: ChangeSetAcknowledgement,
)
```

- Both properties are required.
- Construction performs no storage operation.
- It does not automatically delete local changes and does not implement
  retry handling.

---

## Recording Acknowledgements in Storage

**Package:** `io.dataloom.api.storage`

`StorageProvider` exposes:

```kotlin
public suspend fun acknowledgeOutboundChanges(
    request: OutboundChangeAcknowledgementRequest,
): ProviderOperationResult<Unit>
```

Acknowledgement handling is implementation-defined:

- Accepted events may be marked synchronized or removed according to the
  application adapter.
- `RETRY` events must remain eligible for later processing.
- `REJECTED` events must remain inspectable according to application policy.
- The contract does not dictate SQL, Room, DataStore, or file operations.
- Rejected-event policy is application configurable in future work.

---

## Provider-Level Failure vs. Event-Level Status

These are two distinct failure dimensions:

| Dimension | Represented by | Meaning |
|---|---|---|
| Provider-level failure | `ProviderOperationResult.Failure` | The whole provider call failed (for example, connectivity failure). No `ChangeSetAcknowledgement` was returned. |
| Event-level status | `ChangeAcknowledgementStatus.RETRY` / `REJECTED` | The provider call succeeded, but the remote participant did not accept a specific event. |

A successful `TransportProvider.pushChanges()` call (a
`ProviderOperationResult.Success`) may still contain event-level `RETRY` or
`REJECTED` acknowledgements. Callers must inspect both the provider result
and, when successful, the acknowledgement statuses within it.

Event-level errors use `DataLoomError`. Provider-specific exceptions must
never escape public contracts. Coroutine cancellation must never be
converted into a normal failure.

---

## Retry and Rejection Boundaries

- Retry timing is not defined in this issue.
- Server-directed retry delays are deferred.
- No retry engine, queue implementation, or automatic re-push is
  implemented.
- Rejected-event application policy (for example, discard, quarantine, or
  surface to the user) is deferred to future work.

---

## Push Operation Signature Change

`TransportProvider.pushChanges` now returns a `ChangeSetAcknowledgement` on
success instead of `Unit`:

```kotlin
public suspend fun pushChanges(
    request: PushChangesRequest,
): ProviderOperationResult<ChangeSetAcknowledgement>
```

- Transport implementations map remote responses to canonical
  acknowledgement statuses.
- Protocol-specific response types must not escape the provider.
- The provider must not acknowledge local storage directly and must not
  implement queue deletion.

---

## Placeholder Examples

```kotlin
val acknowledgement = ChangeSetAcknowledgement(
    changeSetId = ChangeSetId("changeset-example"),
    events = listOf(
        ChangeEventAcknowledgement(
            eventId = ChangeEventId("event-example-1"),
            status = ChangeAcknowledgementStatus.ACCEPTED,
        ),
        ChangeEventAcknowledgement(
            eventId = ChangeEventId("event-example-2"),
            status = ChangeAcknowledgementStatus.RETRY,
        ),
    ),
)

val acknowledgementRequest = OutboundChangeAcknowledgementRequest(
    request = synchronizationRequest,
    acknowledgement = acknowledgement,
)

when (val result = storageProvider.acknowledgeOutboundChanges(acknowledgementRequest)) {
    is ProviderOperationResult.Success -> { /* acknowledgement recorded */ }
    is ProviderOperationResult.Failure -> { /* handle provider-level error */ }
}
```

---

## Deferred Behavior

The following are explicitly deferred to later issues:

- Retry engine and retry timing
- Durable queue processing
- Queue deletion rules
- Rejected-event application policy
- Runtime orchestration linking push, acknowledgement, and storage
- Conflict resolution

---

## Related Contracts

- [`SynchronizationRequest`](./synchronization-request.md) — originating
  synchronization intent.
- [`ChangeSet`](./change-model.md#changeset) — ordered collection of change
  events.
- [`StorageProvider`](./storage-provider.md) — storage adapter contract.
- [`TransportProvider`](./transport-provider.md) — transport adapter contract.
- [Checkpoint Contracts](./checkpoint-contracts.md) — inbound checkpoint
  contracts.
- [`DataLoomError`](./error-model.md) — canonical error type.
