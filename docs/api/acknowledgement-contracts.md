# DataLoom Acknowledgement Contracts (DL-011)

[API reference index](./README.md)

> **Status:** Available contract with outbound-pipeline integration. Complete
> V1 retry, reconciliation, and strategy policy remains open.

This document defines the platform-independent change-acknowledgement
contracts introduced in `dataloom-api` by DL-011.

These contracts represent remote acceptance or rejection of pushed change
events and the storage acknowledgement request used to record them in
application-controlled storage. The current outbound pipeline coordinates the
read, push, validation, and storage-acknowledgement path. Durable queue policy,
retry evaluation, and queue execution exist as separate runtime components,
but acknowledgement-status-to-queue reconciliation and qualified reference
storage/transport providers remain incomplete.

---

## Overview

A successful network request alone is not sufficient to safely complete an
outbound synchronization operation. DataLoom separates *transport success*
(the push call itself succeeded) from *event-level acceptance* (whether the
remote participant accepted each individual change).

Current outbound orchestration flow:

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
- `OutboundPushSynchronizationPipeline` coordinates the providers and preserves
  the acknowledgement boundary.

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

The model can represent an acknowledgement containing only a subset of an
original change set because it does not receive the original set at
construction time. The current
`OutboundPushSynchronizationPipeline`, however, validates every transport
response against the pushed batch before storage is updated. It requires the
same change-set ID, exactly one acknowledgement for every pushed event, and no
unknown event IDs. A partial acknowledgement therefore fails the current
outbound execution rather than being persisted.

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
- Rejected-event disposition is application-owned at this boundary. DataLoom
  does not currently supply a generic built-in discard, quarantine, or
  user-intervention policy.

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

## Retry, Queue, and Rejection Boundaries

- Custom retry-policy evaluation, scheduler-backed rescheduling, durable queue
  processing, and Android Room queue persistence exist as separate runtime
  components.
- The outbound pipeline records `RETRY` and `REJECTED` statuses and returns a
  partial result, but it does not translate individual event statuses into
  queue transitions or automatically re-push them.
- Standard backoff, jitter, attempt limits, server-directed delays, and circuit
  behavior remain mandatory V1 gaps.
- Rejected-event disposition is application-owned; DataLoom has no generic
  built-in discard, quarantine, or user-intervention policy.

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

## Current gaps

The following remain incomplete:

- Standard retry, jitter, limit, server-hint, and circuit policies
- Event-level acknowledgement-to-queue reconciliation and deletion rules
- Generic built-in rejected-event disposition
- Complete conflict-policy integration
- Durable restart and end-to-end reconciliation qualification

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
