# Queue Submission (DL-034)

[API reference index](./README.md)

> **Status:** Available queue-submission foundation with separately governed
> enqueue timeout and additive circuit-aware execution. Applications still own
> work encoding; builder circuit policy and complete qualification remain open.

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
- `DataLoomQueueSubmissionSpec` — builder configuration with optional enqueue timeout
- `QueueSubmissionProviderTimeoutRuntime` — standalone timeout-protected assembly
- `CircuitBreakerQueueSubmission` — preflight-before-permission circuit path
- `CircuitBreakerQueueSubmissionResult` — enriched local and circuit result model

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

## `CircuitBreakerQueueSubmission`

The additive circuit-aware path shares the same local encoding and structural
validation, but returns `CircuitBreakerQueueSubmissionResult` rather than
collapsing circuit evidence into `QueueSubmissionResult`.

```kotlin
val circuitSubmission = CircuitBreakerQueueSubmission(
    encoder = myEncoder,
    queueOperationAdapter = queueCircuitAdapter,
    scope = CircuitBreakerScope.providerOperation(
        providerId = queueProvider.descriptor.id,
        operation = QueueCircuitOperation.ENQUEUE.retryOperation,
    ),
)
```

Ordering is deliberate:

1. encode and validate locally;
2. return `EncodingRejected` or `ContractViolation` without circuit access;
3. request circuit permission only after preflight succeeds; and
4. preserve the full `CircuitBreakerExecutionResult<Unit>` in
   `EnqueueEvaluated`.

Preflight-before-permission prevents invalid input from reserving a half-open
probe. An `Executed` result proves enqueue ran exactly once and keeps the later
`CircuitBreakerRecordResult` visible. A recording failure must not be treated as
proof that enqueue did not occur.

This path is currently assembled explicitly and is not exposed through
`DataLoom.queueSubmission`; builder circuit-policy assembly remains open.

See [Circuit-aware queue submission](./circuit-queue-submission.md).

---

## DataLoom facade integration

`DataLoom.queueSubmission` is the public entry point:

```kotlin
public interface DataLoom {
    val queueSubmission: DataLoomQueueSubmission?
}
```

- `null` when neither queue-submission builder method was supplied or when
  a valid `QueueProvider` binding was absent.
- Non-null when an encoder or `DataLoomQueueSubmissionSpec` and a valid queue
  provider binding are configured.

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
    .queueSubmissionConfiguration(
        DataLoomQueueSubmissionSpec(
            encoder = myEncoder,
            queueProviderTimeout = SchedulingDelay(5_000L),
        ),
    )
    .build()
```

The historical `.queueSubmissionEncoder(myEncoder)` method remains available and
selects a null timeout.

Build rules:
- both queue-submission methods are optional;
- when neither is supplied, `queueSubmission` is `null`;
- when both are called, the most recent call is effective;
- when configured, a valid `QueueProvider` binding must be present;
- Queue provider ID must resolve to a registered `QueueProvider` with
  `ProviderType.QUEUE`.
- Build performs no encoding, enqueue, timeout execution, or clock read.

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
`QueueEnqueueRequest` produced by the encoder.

**Correspondence checks** (performed by `DefaultDataLoomQueueSubmission`):

| Check                                        | Failure result       |
|----------------------------------------------|----------------------|
| Encoded `QueueEntryId` matches submission    | `ContractViolation`  |
| Encoded `availableAt` matches submission     | `ContractViolation`  |

**Construction invariants** (enforced by `QueueEnqueueRequest` constructor):

| Constraint                                   | Failure behavior                      |
|----------------------------------------------|---------------------------------------|
| Encoded entry state must be `PENDING`        | `IllegalArgumentException` propagates |
| Encoded entry must have no lease             | `IllegalArgumentException` propagates |
| Encoded entry must have no retry attempt     | `IllegalArgumentException` propagates |

`QueueEnqueueRequest` enforces the state, lease, and retry-attempt invariants
at construction time. If the encoder attempts to construct a `QueueEnqueueRequest`
that violates these invariants, `IllegalArgumentException` is thrown and propagates
normally from the encoder — the `QueueProvider` is never called.

DataLoom does not inspect, decode, or log encoded payload bytes.
DataLoom does not silently correct encoder output.

---

## Enqueue timeout boundary

A configured timeout is applied after encoding and structural validation, and
only to the single `QueueProvider.enqueue` invocation. Zero rejects before
provider invocation. Positive timeouts use cooperative cancellation.

Because enqueue may commit before cancellation is observed, timeout returns
`QueueProviderFailure` with code `QUEUE_PROVIDER_TIMEOUT`, failure stage
`QUEUE_PROVIDER_ENQUEUE`, and `Recoverability.UNKNOWN`. The exact stable
`QueueEntryId` is preserved and no automatic replay occurs.

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

## Strategy-decision correspondence

When `QueuedSynchronizationWork.strategyDecision` is non-null, the
application-owned encoder must place the exact same value in
`QueueEnqueueRequest.entry.strategyDecision`. Submission preflight rejects a
changed, dropped, or invented decision before timeout, circuit, or queue-provider
policy executes. This prevents configuration changes or encoder behavior from
silently changing an already accepted strategy.
