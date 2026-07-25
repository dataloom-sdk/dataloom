# Queued Synchronization Retry Flow (DL-027)

## Purpose

This document defines the retry evaluation flow that bridges a
`SynchronizationResult` returned by the synchronization coordinator to a
`QueueEntryExecutionOutcome` decision emitted by
`QueuedSynchronizationExecutionHandler`.

---

## Retry evaluation sequence

```text
QueuedSynchronizationExecutionHandler
  -> SynchronizationExecutionCoordinator.execute(request, bindings)
     -> SynchronizationResult

  result is Succeeded or Skipped
    -> QueueEntryExecutionOutcome.Completed (no retry evaluation)

  result is Cancelled
    -> QueueEntryExecutionOutcome.Cancelled (no retry evaluation)

  result is Failed or PartiallySucceeded
    -> SynchronizationRetryEvaluator.evaluate(result, retryAttempt, retryOperation)
         -> extractRetryErrors(result)       [internal]
         -> RetryPolicy.evaluate(request)    [once per error, in order]
         -> selectMaxRetryDelay(decisions)   [internal]
         -> SynchronizationRetryEvaluation

       SynchronizationRetryEvaluation.ShouldRetry
         -> QueueEntryExecutionOutcome.Reschedule(
              retryAttempt = retryAttempt,
              availableAt  = overflowSafe(clock.now() + selectedDelay),
              error        = primaryError,
            )

       SynchronizationRetryEvaluation.StopRetry
         -> QueueEntryExecutionOutcome.Failed(
              disposition = FAILED,
              error       = primaryError,
              completedAt = clock.now(),
            )
```

---

## Retry attempt accounting

The handler computes the next `RetryAttempt` before calling the evaluator:

```text
entry.retryAttempt == null -> nextAttempt = RetryAttempt(1)
entry.retryAttempt == RetryAttempt(N) -> nextAttempt = RetryAttempt(N + 1)
```

The same `RetryAttempt` value is:
- passed unchanged to `RetryPolicy.evaluate` inside the evaluator
- stored unchanged in `QueueEntryExecutionOutcome.Reschedule.retryAttempt`

---

## Error extraction

| `SynchronizationResult` | Errors evaluated |
|---|---|
| `Failed(error)` | Single error: `listOf(error)` |
| `PartiallySucceeded(errors)` | All errors in order |
| `Succeeded` / `Skipped` / `Cancelled` | None (not evaluated) |

The first error in the list is the primary error preserved in every outcome.

---

## Decision aggregation

| Decision mix | Outcome |
|---|---|
| At least one `Retry` decision | `ShouldRetry` with maximum delay |
| All decisions are `Stop` | `StopRetry` |

The maximum delay is selected across all `Retry` decisions. If two decisions
produce equal delays, either may be selected (implementation uses
`maxByOrNull`).

---

## Overflow-safe timestamp arithmetic

```text
epochMillis   = clock.now().epochMilliseconds
delayMillis   = selectedDelay.milliseconds
sum           = epochMillis + delayMillis
availableAt   = if (sum < 0L) Long.MAX_VALUE else sum
```

Wrap-around to a negative value is detected and clamped to `Long.MAX_VALUE`.

---

## Shared utility boundary

`extractRetryErrors` and `selectMaxRetryDelay` are package-internal functions
in `io.dataloom.runtime.retry`. They are reused by both
`SynchronizationRetryEvaluator` and `SynchronizationRetryOrchestrator`.

Neither function is part of the public API surface.

---

## Constraints

- `SchedulerProvider` is never invoked during retry evaluation.
- `QueueProvider` is never invoked directly by the handler or evaluator.
- The `DataLoomClock` is always injected; no system clock is read.
- `CancellationException` propagates normally and is not caught.
- Unexpected exceptions from the resolver or coordinator are not swallowed.
- No payload or credential is present in any outcome or evaluation result.

---

## Relationship to DL-024 retry orchestration

`SynchronizationRetryOrchestrator` (DL-024) and
`SynchronizationRetryEvaluator` (DL-027) share the same package-internal
error extraction and delay selection utilities. The orchestrator delegates
to the same helpers and preserves its existing decision ordering and maximum
delay selection semantics. No public DL-024 API was changed.
