# Queued Synchronization Execution (DL-027)

[API reference index](./README.md)

> **Status:** Available queue-to-runtime integration foundation. Standard
> retry/circuit behavior remains incomplete; connectivity deferral is a
> distinct non-retry outcome that preserves attempt history.

## Overview

`QueuedSynchronizationExecutionHandler` is the bounded entry point that
executes a single `QueueEntry` through the synchronization pipeline and maps
the result to an exact `QueueEntryExecutionOutcome`.

It connects the durable queue processing cycle to the synchronization
coordinator and retry evaluation path:

```
DurableQueueExecutionProcessor
  -> QueuedSynchronizationExecutionHandler
     1) QueuedSynchronizationWorkResolver.resolve(entry)
     2) SynchronizationExecutionCoordinator.execute(request, bindings)
     3) map connectivity RequirementNotMet -> Deferred
     4) map SynchronizationResult to QueueEntryExecutionOutcome
        - Succeeded / Skipped    -> Completed
        - Cancelled              -> Cancelled
        - Failed / PartiallySucceeded
            -> SynchronizationRetryEvaluator.evaluate(...)
               - ShouldRetry  -> Reschedule
               - StopRetry    -> Failed
     5) return QueueEntryExecutionOutcome
```

---

## Public runtime contracts

Package: `io.dataloom.runtime.queue`

### `QueuedSynchronizationWork`

Immutable value carrying the resolved synchronization inputs for one queue
entry.

| Property | Type | Description |
|---|---|---|
| `request` | `SynchronizationRequest` | Application-owned synchronization request. |
| `bindings` | `SynchronizationProviderBindings` | Provider binding pair (storage + transport). |

### `QueuedSynchronizationWorkResolution`

Sealed result of work resolution.

| Variant | Description |
|---|---|
| `Resolved(work)` | Work successfully resolved. |
| `Rejected(error)` | Resolution failed with a canonical `DataLoomError`. |

### `QueuedSynchronizationWorkResolver`

Functional interface invoked once per entry to resolve synchronization work.

```kotlin
fun interface QueuedSynchronizationWorkResolver {
    fun resolve(entry: QueueEntry): QueuedSynchronizationWorkResolution
}
```

The application owns the implementation and is responsible for mapping queue
payload to a `SynchronizationRequest` and `SynchronizationProviderBindings`.

### `QueuedSynchronizationExecutionHandler`

Implements `QueueEntryExecutionHandler`. Accepts the following constructor
parameters:

| Parameter | Type | Description |
|---|---|---|
| `workResolver` | `QueuedSynchronizationWorkResolver` | Resolves work from each entry. |
| `executionCoordinator` | `SynchronizationExecutionCoordinator` | Runs the synchronization pipeline. |
| `retryEvaluator` | `SynchronizationRetryEvaluator` | Evaluates retry eligibility. Contains its own injected `DataLoomClock`. |
| `retryOperation` | `RetryOperation` | Stable operation name passed to retry policy. |
| `connectivityConfiguration` | `SynchronizationConnectivityConfiguration?` | Supplies the queued offline-deferral delay. |
| `clock` | `DataLoomClock?` | Computes non-retry deferral availability. |

The handler reads its optional clock only for connectivity deferral.
The completion instant for `Completed` outcomes is taken from
`SynchronizationResult.completedAt`, which was recorded by the pipeline.
Clock access for retry availability timestamps is performed by
`SynchronizationRetryEvaluator`.

---

## Retry contracts

Package: `io.dataloom.runtime.retry`

### `SynchronizationRetryEvaluation`

Sealed result of retry evaluation.

| Variant | Description |
|---|---|
| `NotRequired` | Result does not require retry evaluation (Succeeded, Skipped, Cancelled). |
| `ShouldRetry(retryAttempt, availableAt, error, decisions, selectedDelay)` | Policy approved retry. |
| `StopRetry(error, decisions)` | Policy stopped retry. |

### `SynchronizationRetryEvaluator`

Reusable evaluator that applies the configured `RetryPolicy` to any
`SynchronizationResult` and computes an overflow-safe `availableAt` timestamp.

| Parameter | Type | Description |
|---|---|---|
| `retryPolicy` | `RetryPolicy` | Policy to evaluate each error against. |
| `clock` | `DataLoomClock` | Injected clock for timestamp arithmetic. |

Method:

```kotlin
fun evaluate(
    result: SynchronizationResult,
    retryAttempt: RetryAttempt,
    retryOperation: RetryOperation,
): SynchronizationRetryEvaluation
```

Behavior:
- `Succeeded`, `Skipped`, and `Cancelled` return `NotRequired` without
  invoking the policy.
- `Failed` evaluates the single error. One `RetryDecision` is produced.
- `PartiallySucceeded` evaluates every error in order. The first error is the
  primary error. The maximum delay across all `Retry` decisions is selected.
- Any `Retry` decision produces `ShouldRetry` with an overflow-safe
  `availableAt = clock.now() + selectedDelay`.
- All `Stop` decisions produce `StopRetry`.
- The exact `RetryAttempt` is preserved in `ShouldRetry.retryAttempt`.

---

## Outcome mapping

| `SynchronizationResult` | Retry evaluation | `QueueEntryExecutionOutcome` |
|---|---|---|
| `Succeeded` | Not required | `Completed` |
| `Skipped` | Not required | `Completed` |
| `Cancelled` | Not required | `Cancelled` |
| `Failed` | `ShouldRetry` | `Reschedule` |
| `Failed` | `StopRetry` | `Failed` |
| `PartiallySucceeded` | `ShouldRetry` | `Reschedule` |
| `PartiallySucceeded` | `StopRetry` | `Failed` |
| Resolver `Rejected` | — | `Failed` |
| Coordinator `CONNECTIVITY_REQUIREMENT_NOT_MET` | No retry evaluation | `Deferred` |
| Other coordinator `Rejected` | — | `Failed` |

---

## Error behavior

- Work resolution rejection maps to `QueueEntryExecutionOutcome.Failed` with
  the exact `DataLoomError` from the resolver.
- Coordinator structural rejection (e.g. providers not initialized) maps to
  `QueueEntryExecutionOutcome.Failed` with the coordinator's canonical error.
- An unmet configured connectivity requirement maps to
  `QueueEntryExecutionOutcome.Deferred`; it creates neither a retry attempt nor
  a retry error.
- `QueueProvider` is never invoked directly by the handler.
- `SchedulerProvider` is never invoked.
- No payload or sensitive data is exposed through any outcome.

---

## Cancellation behavior

`CancellationException` from the resolver or coordinator propagates normally
and is not caught by the handler.

---

## Overflow-safe timestamp arithmetic

`availableAt` is computed as:

```kotlin
val sum = clock.now().epochMilliseconds + selectedDelay.milliseconds
val availableAtMillis = if (sum < 0L) Long.MAX_VALUE else sum
```

This prevents silent overflow to negative values when large delays are
combined with large clock values.

---

## Threading and coroutine safety

- The handler is a pure suspend function with no shared mutable state.
- The resolver, coordinator, and retry evaluator must each be safe for
  concurrent invocation from structured coroutine scopes.
- `GlobalScope` is never used.
- `CancellationException` is never swallowed.

---

## Delivery semantics

The `DurableQueueExecutionProcessor → QueuedSynchronizationExecutionHandler`
pipeline provides **at-least-once** delivery semantics, not exactly-once.

A queue entry may be processed more than once if the consumer crashes or its
lease expires after executing the pipeline but before the queue outcome
transition is recorded. Applications requiring idempotent or exactly-once
semantics must implement deduplication in their pipelines, storage providers,
or transport providers.

See [Queued Synchronization Retry Flow](../architecture/queued-synchronization-retry-flow.md)
for the distinction between queue rescheduling and `SchedulerProvider`
scheduling.

---

## Security

- No payload, credential, token, or personal data passes through any outcome
  field.
- Error fields carry canonical `DataLoomError` instances only.


## Retry budget propagation

Accepted retries may carry `RetryBudgetState`, containing only bounded timing
evidence. Queue rescheduling persists that state atomically with attempt,
availability, and error. Constraint deferral and lease recovery preserve it
without consuming budget. Initial enqueue never accepts retry budget state.
