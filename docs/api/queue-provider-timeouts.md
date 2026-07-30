# Queue-provider timeout boundaries

> **Status:** Partial V1 runtime slice. Cooperative provider-timeout enforcement
> exists for the queue-provider contract, protected queue-worker and
> queue-submission runtimes, and automatic `DataLoomBuilder` adoption. Circuit assembly, platform
> hard-interruption adapters, and complete end-to-end qualification remain open.

## Purpose

`TimeoutEnforcingQueueProvider` decorates a `QueueProvider` and applies one
explicit `RetryTimeoutKind.PROVIDER` boundary to every lifecycle and queue
operation:

- initialize;
- health;
- close;
- enqueue;
- acquire;
- complete;
- reschedule;
- defer;
- fail;
- cancel; and
- recover expired leases.

The decorator preserves the delegate descriptor and every completed success or
canonical failure exactly. It creates no background scope, selects no dispatcher,
and performs no retry.

## Basic assembly

```kotlin
val protectedQueue = TimeoutEnforcingQueueProvider(
    delegate = queueProvider,
    timeoutCoordinator = RetryTimeoutCoordinator(
        configuration = RetryTimeoutConfiguration(
            providerTimeout = SchedulingDelay(5_000L),
        ),
        clock = clock,
        executor = CoroutineRetryTimeoutExecutor(),
    ),
)
```

The timeout is a provider-operation duration. It is not reused as a connection,
request, idle, retry-policy, scheduler, or complete-workflow timeout.

## Queue-worker runtime assembly

`QueueWorkerProviderTimeoutRuntime.create(...)` constructs a
`QueueWorkerCoordinator` using the **same protected queue-provider instance** for:

1. expired-lease recovery;
2. atomic acquisition; and
3. all lease-guarded transitions performed by
   `DurableQueueExecutionProcessor`.

```kotlin
val coordinator = QueueWorkerProviderTimeoutRuntime.create(
    queueProvider = queueProvider,
    executionHandler = executionHandler,
    schedulerProvider = schedulerProvider,
    clock = clock,
    configuration = workerConfiguration,
    queueProviderTimeout = SchedulingDelay(5_000L),
)
```

Using one wrapper prevents recovery from being protected while acquisition or
transitions silently bypass the same configured boundary.

Existing `QueueWorkerCoordinator` and `DurableQueueExecutionProcessor`
constructors remain unchanged and preserve the historical direct provider path.


## DataLoomBuilder automatic assembly

`DataLoomQueueWorkerSpec` accepts an optional `queueProviderTimeout`:

```kotlin
val workerSpec = DataLoomQueueWorkerSpec(
    workResolver = workResolver,
    retryPolicy = retryPolicy,
    retryOperation = retryOperation,
    configuration = workerConfiguration,
    queueProviderTimeout = SchedulingDelay(5_000L),
)
```

When present, `DataLoomBuilder` automatically selects
`QueueWorkerProviderTimeoutRuntime` and uses one protected provider for recovery,
acquisition, and every transition. The original four-argument constructor
remains available and sets `queueProviderTimeout = null`, preserving historical
direct provider behavior.

Builder assembly performs no provider operation, clock read, queue mutation,
scheduler call, or coroutine launch.


## Queue-submission timeout assembly

`QueueSubmissionProviderTimeoutRuntime.create(...)` protects only the single
enqueue call made after encoding and structural validation:

```kotlin
val submission = QueueSubmissionProviderTimeoutRuntime.create(
    queueProvider = queueProvider,
    encoder = encoder,
    clock = clock,
    queueProviderTimeout = SchedulingDelay(5_000L),
)
```

`DataLoomBuilder.queueSubmissionConfiguration(...)` accepts a
`DataLoomQueueSubmissionSpec` and selects the same protected runtime when its
timeout is non-null. The historical `queueSubmissionEncoder(...)` method remains
direct and source compatible.

A timed-out enqueue returns `QueueProviderFailure` with the stable queue-entry ID,
`QUEUE_PROVIDER_ENQUEUE`, and `Recoverability.UNKNOWN`. It is never replayed
automatically.

## Result mapping

The protected worker uses existing queue result variants.

| Timed-out operation | Result |
|---|---|
| expired-lease recovery | `QueueWorkerRunResult.RecoveryFailed` |
| acquisition | `QueueWorkerRunResult.ProcessingFailed` with stage `ACQUISITION` |
| completion | `ProcessingFailed` with stage `COMPLETION_TRANSITION` |
| reschedule | `ProcessingFailed` with stage `RESCHEDULE_TRANSITION` |
| deferral | `ProcessingFailed` with stage `DEFERRAL_TRANSITION` |
| failure transition | `ProcessingFailed` with stage `FAILURE_TRANSITION` |
| cancellation transition | `ProcessingFailed` with stage `CANCELLATION_TRANSITION` |

`QUEUE_PROVIDER_TIMEOUT` is the stable error code. The error contains a static,
bounded diagnostic and no payload, request metadata, lease value, exception
message, stack trace, or provider-internal state.

## Durable ambiguity and recoverability

A queue mutation can commit before coroutine cancellation is observed. The
runtime therefore cannot safely claim that a timed-out operation rolled back.

Timeout errors for mutating operations use:

```text
Recoverability.UNKNOWN
```

This includes enqueue, acquire, complete, reschedule, defer, fail, cancel,
recovery, initialize, and close. The read-only health check uses
`Recoverability.RECOVERABLE`.

The decorator never automatically replays a timed-out mutation. In particular:

- acquisition is not repeated in the same processing cycle;
- a transition is not invoked a second time;
- confirmed processing counters are not incremented for an unconfirmed
  transition;
- later entries are not executed after a transition timeout; and
- lease expiry, provider-defined idempotency, and expired-lease recovery remain
  the available reconciliation boundaries in the current public SPI.

This preserves the existing at-least-once queue model without converting an
ambiguous provider timeout into an unsafe immediate retry.

## Zero timeout

A zero provider timeout fails before the delegate operation is invoked. For
acquisition this produces an acquisition-stage failure with zero acquired and
executed counts. For a transition it prevents the transition call entirely.

## Cancellation

Caller `CancellationException` propagates unchanged and is never converted into
`QUEUE_PROVIDER_TIMEOUT`.

The common coroutine timeout is cooperative. A blocking provider that does not
reach cancellation checkpoints requires a platform-specific hard-interruption
adapter. Even with hard interruption, durable reconciliation is still required
because the underlying storage transaction may already have committed.

## Construction boundary

Constructing either the decorator or `QueueWorkerProviderTimeoutRuntime` performs
no:

- provider call;
- clock read;
- queue acquisition;
- durable transition;
- scheduler call;
- coroutine launch; or
- identifier generation.

## KMP compatibility

The public surface uses Kotlin Multiplatform coroutine support and DataLoom
contracts only. It exposes no Room, SQLite, SQLDelight, Android, JVM-only, Apple
storage, dispatcher, or coroutine-scope type.

## Remaining V1 work

- queue circuit permission and outcome recording;
- platform-specific hard interruption where cooperative cancellation is
  insufficient;
- real multi-process, transaction-race, process-death, and restart
  qualification;
- operational events, metrics, structured logs, traces, redaction, and audit;
- native Android, KMP Android, and KMP iOS end-to-end evidence.
