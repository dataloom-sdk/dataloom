# Retry Orchestration Contracts (DL-024)

**Module:** `dataloom-runtime`  
**Package:** `io.dataloom.runtime.retry`  
**Platform:** Kotlin Multiplatform (commonMain)

---

## Overview

The retry orchestration layer evaluates a terminal
[`SynchronizationResult`](synchronization-result.md) through
[`RetryPolicy`](retry-policy.md) and schedules at most one future
synchronization attempt through
[`SchedulerProvider`](scheduler-provider.md).

Retry orchestration is a deterministic, platform-independent operation. It
does not execute synchronization, process queue entries, check connectivity,
dispatch events, or initialize providers.

---

## Contracts

### `SynchronizationRetryRequest`

**Package:** `io.dataloom.runtime.retry`

Immutable request carrying all context needed by
`SynchronizationRetryOrchestrator` to evaluate retry policy and schedule a
future synchronization attempt.

```kotlin
val request = SynchronizationRetryRequest(
    synchronizationRequest = syncRequest,
    synchronizationResult = failedResult,
    retryOperation = RetryOperation("transport.push"),
    retryAttempt = RetryAttempt(1),
    scheduleId = ScheduleId("retry-001"),
)
```

| Property                   | Type                     | Description                                                  |
|----------------------------|--------------------------|--------------------------------------------------------------|
| `synchronizationRequest`   | `SynchronizationRequest` | Original request that produced the terminal result.          |
| `synchronizationResult`    | `SynchronizationResult`  | Terminal result that triggered retry evaluation.             |
| `retryOperation`           | `RetryOperation`         | Logical operation passed to `RetryPolicy.evaluate`.          |
| `retryAttempt`             | `RetryAttempt`           | Current attempt counter passed unchanged to policy.          |
| `scheduleId`               | `ScheduleId`             | Stable identifier forwarded to `ScheduleRequest`.            |

#### Construction restrictions

- Performs no retry evaluation.
- Makes no provider call.
- Performs no scheduling.
- Reads no clock.
- Generates no identifier.

#### RetryAttempt semantics

`retryAttempt` is passed to `RetryPolicy` **unchanged**. The orchestrator
does not silently increment the attempt number. Attempt advancement is the
responsibility of the future runtime or queue processor that creates the next
`SynchronizationRetryRequest`.

#### Diagnostic safety

`toString()` exposes only:

- synchronization session ID
- result variant name
- retry operation
- retry attempt number
- schedule ID

It never exposes payload bytes, checkpoint tokens, credentials, authorization
headers, encryption keys, personal data, or stack traces.

---

### `RetrySchedulingConfiguration`

**Package:** `io.dataloom.runtime.retry`

Immutable configuration governing how `SynchronizationRetryOrchestrator`
builds a `ScheduleRequest`.

```kotlin
val config = RetrySchedulingConfiguration(
    constraints = ScheduleConstraints(),
    existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
)
```

| Property               | Type                    | Description                                                  |
|------------------------|-------------------------|--------------------------------------------------------------|
| `constraints`          | `ScheduleConstraints`   | Execution constraints forwarded to every `ScheduleRequest`.  |
| `existingSchedulePolicy` | `ExistingSchedulePolicy` | Policy when a schedule with the same ID already exists.     |

Value-based equality is provided. Construction does not schedule execution,
read the clock, or generate an identifier.

---

### `RetryOrchestrationStatus`

**Package:** `io.dataloom.runtime.retry`

Canonical status values produced by `SynchronizationRetryOrchestrator`.

| Value                    | Description                                                                      |
|--------------------------|----------------------------------------------------------------------------------|
| `NOT_REQUIRED`           | Result contains no retry-evaluable failure.                                      |
| `STOPPED`                | Evaluation completed but no decision requested another attempt.                  |
| `SCHEDULED`              | Retry requested and `SchedulerProvider` accepted the schedule.                   |
| `SCHEDULER_NOT_CONFIGURED` | Retry requested but no `SchedulerProvider` was supplied.                       |
| `SCHEDULER_FAILED`       | Retry requested but `SchedulerProvider` returned a canonical failure.            |

Do not rely on enum ordinals for serialization or persistence.

`CancellationException` is never classified as a `RetryOrchestrationStatus`.
Thrown cancellation propagates normally.

---

### `RetryOrchestrationResult`

**Package:** `io.dataloom.runtime.retry`

Immutable structured result produced by a single
`SynchronizationRetryOrchestrator.evaluateAndSchedule` invocation.

```kotlin
val result: RetryOrchestrationResult = orchestrator.evaluateAndSchedule(request)
when (result.status) {
    NOT_REQUIRED -> { /* no-op */ }
    STOPPED -> { /* log decisions */ }
    SCHEDULED -> { /* use result.scheduleReceipt */ }
    SCHEDULER_NOT_CONFIGURED -> { /* handle absent scheduler */ }
    SCHEDULER_FAILED -> { /* use result.schedulerError */ }
}
```

| Property          | Type                       | Description                                                   |
|-------------------|----------------------------|---------------------------------------------------------------|
| `status`          | `RetryOrchestrationStatus` | Terminal status of this orchestration cycle.                  |
| `decisions`       | `List<RetryDecision>`      | Ordered policy decisions. Empty for `NOT_REQUIRED`.           |
| `selectedDelay`   | `SchedulingDelay?`         | Maximum delay from retry decisions. Non-null when scheduling was attempted. |
| `scheduleReceipt` | `ScheduleReceipt?`         | Provider receipt. Non-null only for `SCHEDULED`.              |
| `schedulerError`  | `DataLoomError?`           | Provider error. Non-null only for `SCHEDULER_FAILED`.         |

#### Invariants

| Status                     | `decisions` | `selectedDelay` | `scheduleReceipt` | `schedulerError` |
|----------------------------|-------------|-----------------|-------------------|-----------------|
| `NOT_REQUIRED`             | empty       | null            | null              | null            |
| `STOPPED`                  | non-empty, no Retry | null  | null              | null            |
| `SCHEDULED`                | ≥1 Retry    | non-null        | non-null          | null            |
| `SCHEDULER_NOT_CONFIGURED` | ≥1 Retry    | non-null        | null              | null            |
| `SCHEDULER_FAILED`         | ≥1 Retry    | non-null        | null              | non-null        |

Invalid combinations are rejected at construction with `IllegalArgumentException`.

The `decisions` collection is defensively copied. Caller mutation of the
original list does not affect the result. The exposed collection is read-only.

No raw `Throwable` or stack trace is exposed. `schedulerError` is a canonical
`DataLoomError` whose message must already be sanitized by the provider.

---

### `SynchronizationRetryOrchestrator`

**Package:** `io.dataloom.runtime.retry`

Platform-independent orchestrator that evaluates retry policy and schedules
at most one future synchronization attempt.

```kotlin
val orchestrator = SynchronizationRetryOrchestrator(
    retryPolicy = myRetryPolicy,
    schedulerProvider = mySchedulerProvider, // may be null
    configuration = RetrySchedulingConfiguration(
        constraints = ScheduleConstraints(),
        existingSchedulePolicy = ExistingSchedulePolicy.REPLACE,
    ),
)

val result = orchestrator.evaluateAndSchedule(retryRequest)
```

#### Constructor parameters

| Parameter           | Type                           | Description                                    |
|---------------------|--------------------------------|------------------------------------------------|
| `retryPolicy`       | `RetryPolicy`                  | Policy evaluated for each canonical error.     |
| `schedulerProvider` | `SchedulerProvider?`           | Optional platform scheduler.                   |
| `configuration`     | `RetrySchedulingConfiguration` | Scheduling configuration.                      |

#### `evaluateAndSchedule`

```kotlin
public suspend fun evaluateAndSchedule(
    request: SynchronizationRetryRequest,
): RetryOrchestrationResult
```

Evaluates `RetryPolicy` for the terminal result in `request` and schedules at
most one future synchronization attempt.

**Flow:**

1. Inspect the `SynchronizationResult` variant.
2. Return `NOT_REQUIRED` for `Succeeded`, `Skipped`, or `Cancelled`.
3. Extract canonical errors from `Failed` or `PartiallySucceeded`.
4. Evaluate `RetryPolicy` for each error in the original order.
5. Preserve ordered decisions.
6. If no decision requests retry, return `STOPPED`.
7. Determine the maximum requested `SchedulingDelay` across retry decisions.
8. If `schedulerProvider` is `null`, return `SCHEDULER_NOT_CONFIGURED`.
9. Build one `ScheduleRequest` and call `SchedulerProvider.schedule` exactly once.
10. On `Success`, return `SCHEDULED` with the exact receipt.
11. On `Failure`, return `SCHEDULER_FAILED` with the exact error.

**Cancellation:** `CancellationException` from `SchedulerProvider.schedule`
propagates normally and is never converted into a `RetryOrchestrationResult`.

---

## Eligible SynchronizationResult Variants

| Variant               | Eligible? | Errors evaluated                    |
|-----------------------|-----------|-------------------------------------|
| `Succeeded`           | No        | Returns `NOT_REQUIRED`              |
| `Skipped`             | No        | Returns `NOT_REQUIRED`              |
| `Cancelled` (result)  | No        | Returns `NOT_REQUIRED`              |
| `Failed`              | Yes       | Single `error` evaluated once       |
| `PartiallySucceeded`  | Yes       | All `errors` in original order      |

A `SynchronizationResult.Cancelled` is a terminal outcome and is not eligible
for retry. A thrown `CancellationException` is different: it propagates
normally.

---

## Error Selection

### `SynchronizationResult.Failed`

The single `error` is evaluated exactly once through `RetryPolicy.evaluate`.

### `SynchronizationResult.PartiallySucceeded`

Every error in `errors` is evaluated in the original list order. Errors are
not sorted, deduplicated, or filtered by the orchestrator. The policy itself
decides whether to retry each error.

---

## RetryPolicy Evaluation

For each eligible canonical error, a `RetryEvaluationRequest` is constructed
with:

- the exact `SynchronizationRequest`
- the exact `RetryOperation`
- the exact `DataLoomError`
- the exact `RetryAttempt`
- `previousDelay = null`
- `provider = null`

`RetryPolicy.evaluate` is invoked synchronously once per error. Decision order
matches evaluation order.

`RetryAttempt` is **not incremented** by the orchestrator.

Unexpected exceptions from `RetryPolicy` propagate normally.

---

## Decision Aggregation and Maximum-Delay Selection

When one or more decisions request retry, the **maximum** `SchedulingDelay`
across all `RetryDecision.Retry` decisions is selected.

Scheduling earlier than one of the policy decisions would violate that
policy's minimum delay guarantee.

Delay selection rules:

- Retry decisions contribute their `delay` value.
- Stop decisions do not contribute a delay value.
- Decision order does not affect maximum selection.
- No jitter, average, minimum, or random delay is introduced.
- No system clock is accessed.

When no decision requests retry, `STOPPED` is returned. Stop decisions
alongside retry decisions do not prevent scheduling — stopped errors do not
block retryable work.

---

## Single Schedule Operation

At most one `ScheduleRequest` is submitted per `evaluateAndSchedule`
invocation. The orchestrator does not schedule once per error or once per
retry decision.

---

## ScheduleRequest Construction

The `ScheduleRequest` is built with:

- `id` = `request.scheduleId` (no new ID is generated)
- `synchronizationRequest` = `request.synchronizationRequest` (unchanged)
- `delay` = selected maximum `SchedulingDelay`
- `constraints` = `configuration.constraints`
- `existingPolicy` = `configuration.existingSchedulePolicy`

The original `SynchronizationRequest` is not mutated.

---

## Missing Scheduler Behavior

When `schedulerProvider` is `null` and retry is requested:

- `SchedulerProvider.schedule` is not called.
- `SCHEDULER_NOT_CONFIGURED` is returned.
- `selectedDelay` is preserved.
- `decisions` are preserved.
- `scheduleReceipt` is `null`.
- `schedulerError` is `null`.

---

## Scheduler Failure Behavior

When `SchedulerProvider.schedule` returns `ProviderOperationResult.Failure`:

- `SCHEDULER_FAILED` is returned.
- The exact canonical `DataLoomError` from the provider is preserved in
  `schedulerError`.
- `RetryPolicy` is not re-evaluated.
- `SchedulerProvider` is not called again.
- No alternate scheduler is used.
- No queue operation occurs.
- The error is not converted into a `RetryStopReason`.

---

## Boundaries

### Durable Queue Boundary

DL-024 schedules future synchronization only through `SchedulerProvider`.
It does not call `QueueProvider`, enqueue records, acquire leases, or update
queue state. Durability guarantees depend entirely on the supplied
`SchedulerProvider` implementation.

Do not claim that scheduled retries survive process death unless the supplied
`SchedulerProvider` guarantees it.

### Connectivity Boundary

`SynchronizationRetryOrchestrator` does not call `ConnectivityProvider`. It
does not query current connectivity, delay until online, observe network
changes, or alter `ScheduleConstraints` based on runtime connectivity.

### Event-Dispatch Boundary

`SynchronizationRetryOrchestrator` does not emit events. It does not call any
observer, event dispatcher, or event persistence layer. A future event layer
may observe `RetryOrchestrationResult` to emit a `RetryScheduled` event.

---

## Performance

- Errors are evaluated sequentially.
- At most one scheduling call is made per invocation.
- No blocking operation is performed.
- No dispatcher is selected.
- No unbounded collection is allocated.
- No payload data is copied.
- No checkpoint token is read.

---

## Security

`toString()` representations in this package expose only:

- `ScheduleId`
- `RetryOperation`
- retry attempt number
- result variant name
- `ErrorCode` (from scheduler error, if applicable)
- `SchedulingDelay`

They never expose:

- `DataLoomPayload` bytes
- checkpoint token values
- credentials or authorization headers
- encryption keys
- personal data
- stack traces
- provider internal state

---

## KMP Compatibility

All types in `io.dataloom.runtime.retry` use only Kotlin standard-library and
DataLoom API types. No Android API, JVM-only API, reflection, ServiceLoader,
DI framework, `GlobalScope`, or `CoroutineScope` is used.
