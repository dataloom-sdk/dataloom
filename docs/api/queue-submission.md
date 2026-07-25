# Queue Submission (DL-034)

## Overview

The queue-submission capability allows applications to submit synchronization
work to the durable queue. DataLoom orchestrates the submission flow—encoding,
structural validation, and enqueue—without defining a mandatory serialization
format.

---

## Package

`io.dataloom.runtime.submission`

---

## Public contracts

- `QueuedSynchronizationSubmission` — immutable submission value
- `QueuedSynchronizationWorkEncoder` — application-owned encoding contract
- `QueuedSynchronizationWorkEncodingResult` — sealed encoding result
- `QueueSubmissionFailureStage` — failure-stage identifier enum
- `QueueSubmissionResult` — sealed submission result
- `DataLoomQueueSubmission` — narrow public submission capability

---

## `QueuedSynchronizationSubmission`

Immutable value carrying the application-supplied inputs for one queue
submission.

| Property       | Type                        | Description                                                    |
|----------------|-----------------------------|----------------------------------------------------------------|
| `queueEntryId` | `QueueEntryId`              | Unique identifier supplied explicitly by the caller.           |
| `work`         | `QueuedSynchronizationWork` | The synchronization work to enqueue (request + bindings).      |
| `availableAt`  | `DataLoomInstant`           | Instant at which the entry becomes eligible for acquisition.   |

### Explicit identifiers

The caller supplies `queueEntryId` and `availableAt` explicitly. DataLoom
does not generate identifiers or read the system clock on behalf of the
caller. Explicit identifiers make queue submission deterministic and allow
applications to apply their own idempotency strategy.

### No encoding at construction

Construction does not encode `work`, call any `QueueProvider`, read the
clock, generate identifiers, or perform any I/O.

---

## `QueuedSynchronizationWorkEncoder`

Application-owned functional interface that converts a
`QueuedSynchronizationSubmission` into a
`QueuedSynchronizationWorkEncodingResult`.

```kotlin
fun interface QueuedSynchronizationWorkEncoder {
    fun encode(
        submission: QueuedSynchronizationSubmission,
    ): QueuedSynchronizationWorkEncodingResult
}
```

### Application ownership

DataLoom does not define JSON, Protocol Buffers, Java serialization, Kotlin
Serialization, or any other serialization format. The application chooses
its own encoding strategy independently.

### Encoder and resolver compatibility

The encoder used for submission must be compatible with the
`QueuedSynchronizationWorkResolver` used by queued execution. DataLoom does
not verify payload round-trip compatibility during build or submission.
Applications are responsible for ensuring that:

```
encode(submission.work)
    → durable queue storage
    → resolver.resolve(queueEntry)
    → equivalent QueuedSynchronizationWork
```

### Unexpected exceptions

Unexpected programming exceptions from encoder implementations propagate
normally. DataLoom does not convert unexpected exceptions into
`QueuedSynchronizationWorkEncodingResult.Rejected`.

---

## `QueuedSynchronizationWorkEncodingResult`

Sealed result produced by `QueuedSynchronizationWorkEncoder.encode`.

| Variant    | Description                                                               |
|------------|---------------------------------------------------------------------------|
| `Encoded`  | Encoding succeeded; carries the `QueueEnqueueRequest`.                    |
| `Rejected` | Encoding failed with a canonical `DataLoomError`; no enqueue performed.   |

```kotlin
sealed interface QueuedSynchronizationWorkEncodingResult {
    data class Encoded(val request: QueueEnqueueRequest) : …
    data class Rejected(val error: DataLoomError) : …
}
```

---

## `QueueSubmissionFailureStage`

Closed set of failure stages for `QueueSubmissionResult.QueueProviderFailure`.

| Value                        | Meaning                                              |
|------------------------------|------------------------------------------------------|
| `ENCODING`                   | Failure in the encoder (unexpected exception).        |
| `ENCODED_REQUEST_VALIDATION` | Encoded request did not pass structural validation.   |
| `QUEUE_PROVIDER_ENQUEUE`     | Provider returned `ProviderOperationResult.Failure`.  |

Enum ordinals must not be persisted or compared by ordinal value.
`CancellationException` is not represented as a failure stage.

---

## `QueueSubmissionResult`

Sealed result produced by `DataLoomQueueSubmission.submit`.

| Variant                | Description                                                              |
|------------------------|--------------------------------------------------------------------------|
| `Enqueued`             | Entry persisted successfully; preserves `QueueEntryId` and provider result. |
| `EncodingRejected`     | Encoder rejected the submission; preserves canonical `DataLoomError`.    |
| `ContractViolation`    | Encoded request violated structural correspondence; no enqueue.           |
| `QueueProviderFailure` | Provider returned a canonical failure; preserves `DataLoomError`.        |

```kotlin
sealed interface QueueSubmissionResult {
    data class Enqueued(
        val queueEntryId: QueueEntryId,
        val providerResult: ProviderOperationResult.Success<Unit>,
    ) : …

    data class EncodingRejected(
        val error: DataLoomError,
    ) : …

    data class ContractViolation(
        val error: DataLoomError,
        val queueEntryId: QueueEntryId?,
    ) : …

    data class QueueProviderFailure(
        val error: DataLoomError,
        val queueEntryId: QueueEntryId,
        val failureStage: QueueSubmissionFailureStage,
    ) : …
}
```

---

## `DataLoomQueueSubmission`

Narrow public capability for submitting synchronization work to the durable
queue.

```kotlin
interface DataLoomQueueSubmission {
    suspend fun submit(
        submission: QueuedSynchronizationSubmission,
    ): QueueSubmissionResult
}
```

Accessible through `DataLoom.queueSubmission` when configured.

### Submission flow

Each `submit` call performs:

1. Encodes the submission using `QueuedSynchronizationWorkEncoder` exactly once.
2. On encoding rejection → returns `EncodingRejected`; no provider call.
3. Validates structural correspondence between encoded request and submission.
4. On validation failure → returns `ContractViolation`; no provider call.
5. Calls `QueueProvider.enqueue` exactly once.
6. On success → returns `Enqueued`.
7. On provider failure → returns `QueueProviderFailure`.

---

## DataLoom facade integration

`DataLoom.queueSubmission` is the public entry point:

```kotlin
public interface DataLoom {
    val queueSubmission: DataLoomQueueSubmission?
}
```

- `null` when `DataLoomBuilder.queueSubmissionEncoder` was not supplied or
  when a valid `QueueProvider` binding was absent.
- Non-null when a `QueuedSynchronizationWorkEncoder` and a valid queue
  provider binding are both configured.

Queue submission and queue worker are independently configurable. Either,
both, or neither capability may be present.

---

## Builder configuration

```kotlin
val dataLoom = DataLoomBuilder()
    .runtimeDependencies(runtimeDeps)
    .providers(storageProvider, transportProvider, queueProvider)
    .defaultProviderBindings(
        SynchronizationProviderBindings(
            storageProviderId = ProviderId("storage"),
            transportProviderId = ProviderId("transport"),
            queueProviderId = ProviderId("queue"),
        ),
    )
    .queueSubmissionEncoder(myEncoder)
    .build()
```

Build rules:
- `queueSubmissionEncoder` is optional.
- When no encoder is supplied, `queueSubmission` is `null`.
- When encoder is supplied, a valid `QueueProvider` binding must be present.
- Queue provider ID must resolve to a registered `QueueProvider` with
  `ProviderType.QUEUE`.
- Build performs no encoding, no enqueue, and no clock read.

---

## Usage example

```kotlin
val queueEntryId = QueueEntryId("order-sync-abc123")
val availableAt = clock.now()

val submission = QueuedSynchronizationSubmission(
    queueEntryId = queueEntryId,
    work = QueuedSynchronizationWork(
        request = synchronizationRequest,
        bindings = providerBindings,
    ),
    availableAt = availableAt,
)

when (val result = dataLoom.queueSubmission?.submit(submission)) {
    is QueueSubmissionResult.Enqueued -> {
        // Entry persisted. Trigger worker when appropriate.
    }
    is QueueSubmissionResult.EncodingRejected -> {
        // Encoder rejected. Check result.error.
    }
    is QueueSubmissionResult.ContractViolation -> {
        // Encoded request mismatch. Fix encoder.
    }
    is QueueSubmissionResult.QueueProviderFailure -> {
        // Provider failure. Check result.error and result.failureStage.
    }
    null -> {
        // Queue submission not configured.
    }
}
```

---

## Encoded-request validation

Before calling `QueueProvider.enqueue`, DataLoom validates structural
correspondence between the `QueuedSynchronizationSubmission` and the
`QueueEnqueueRequest` produced by the encoder:

| Check                                        | Failure result       |
|----------------------------------------------|----------------------|
| Encoded `QueueEntryId` matches submission    | `ContractViolation`  |
| Encoded `availableAt` matches submission     | `ContractViolation`  |
| Encoded entry state is `PENDING`             | `ContractViolation`  |
| Encoded entry has no lease                   | `ContractViolation`  |
| Encoded entry has no retry attempt           | `ContractViolation`  |

DataLoom does not inspect, decode, or log encoded payload bytes.
DataLoom does not silently correct encoder output.

---

## Idempotency boundary

- `QueueEntryId` should remain stable when the application retries the same
  logical submission.
- `QueueProvider` determines duplicate-ID behavior.
- DataLoom invokes `enqueue` at most once per `submit` call.
- DataLoom does not automatically retry provider failure.
- An unknown external failure may require application-level reconciliation.

Exactly-once enqueue semantics are not guaranteed.

---

## Provider lifecycle requirement

The application must call `DataLoom.initialize()` before submitting queue
work. `DataLoomQueueSubmission` does not initialize providers automatically.

---

## No automatic behavior

`DataLoomQueueSubmission.submit` does not:

- Start `QueueWorkerCoordinator`.
- Call `SchedulerProvider`.
- Execute `SynchronizationExecutionCoordinator`.
- Initialize providers.
- Invoke `RetryPolicy`.
- Check connectivity.
- Call `QueueProvider.acquire`.
- Process queue entries.
- Recover leases.
- Retry `QueueProvider.enqueue` automatically.

The host application is responsible for triggering the queue worker after
submission when appropriate.

---

## Security and payload opacity

- DataLoom does not inspect or interpret encoded payload content.
- `DataLoomQueueSubmission` does not expose encoded payload bytes.
- Diagnostic fields include only safe values: `QueueEntryId`,
  `QueueEntryState`, `ErrorCode`, and result variant.
- `QueueProvider` is not exposed through `DataLoom.queueSubmission`.

---

## KMP compatibility

All contracts in `io.dataloom.runtime.submission` use Kotlin standard-library
and DataLoom API types only. Safe for use in Kotlin Multiplatform common code.
No Android API, JVM-only API, reflection, or serialization dependency is
introduced.

---

## Dependencies

- DL-015 — `QueueProvider` and enqueue contracts
- DL-017 — `DataLoomInstant`, `QueueEntryId`
- DL-027 — `QueuedSynchronizationWork` and resolver boundary
- DL-032 — Queue-worker boundary
- DL-033 — `DataLoom` facade and builder
