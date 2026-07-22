# DataLoom Retry Boundaries (DL-013)

This document describes the architectural boundaries for retry evaluation in
DataLoom. It defines which components are responsible for which retry-related
concerns and what is explicitly outside the scope of the current implementation.

**The retry engine is not implemented.** The contracts introduced by DL-013
define the evaluation boundary only. No retry execution, sleeping, or
scheduling is performed by any DL-013 contract.

---

## Policy Responsibility

`RetryPolicy` is responsible for:

- Accepting a `RetryEvaluationRequest`
- Returning a `RetryDecision` (either `Retry` or `Stop`)
- Evaluating already-available information synchronously and deterministically
- Remaining platform-independent and free of I/O

`RetryPolicy` must not:

- Execute the failed operation
- Block the current thread or sleep
- Access network services
- Access application storage
- Schedule work
- Mutate queue state
- Call providers
- Refresh credentials
- Wait for connectivity
- Automatically log sensitive context
- Catch or translate coroutine cancellation

---

## Runtime Responsibility

The DataLoom runtime (introduced in a later issue) is responsible for:

- Invoking `RetryPolicy.evaluate()` with the appropriate `RetryEvaluationRequest`
- Acting on the returned `RetryDecision`
- Creating and incrementing `RetryAttempt` values
- Passing `previousDelay` to subsequent evaluations
- Enforcing attempt limits
- Routing `RetryDecision.Retry` to the scheduler
- Routing `RetryDecision.Stop` to workflow termination or failure handling

---

## Scheduler Responsibility

`SchedulerProvider` is responsible for:

- Accepting a schedule request from the runtime after a `RetryDecision.Retry`
- Delegating to the platform scheduler (WorkManager, AlarmManager, etc.)
- Not deciding whether an operation is recoverable
- Not evaluating retry policy

The conceptual flow:

```text
RetryPolicy.evaluate()
        ↓
RetryDecision.Retry(delay)
        ↓
DataLoom runtime
        ↓
SchedulerProvider.schedule(...)
```

`RetryPolicy` must not call `SchedulerProvider` directly.

---

## Queue Responsibility

The durable queue is responsible for:

- Storing retry attempts in queue records (deferred to a later issue)
- Managing queue entry state transitions (deferred)
- Recovering expired leases

`RetryDecision` does not interact with the queue:

- A `RetryDecision.Retry` does not insert work into the queue.
- A `RetryDecision.Stop` does not remove work from the queue.
- Attempt limits will be enforced by the runtime in a later issue.
- Failed and rejected work must remain inspectable according to future queue
  policy.
- Queue state transitions are not part of DL-013.

---

## Provider Responsibility

Providers are responsible for:

- Executing the actual operation
- Returning `ProviderOperationResult.Success` or `ProviderOperationResult.Failure`
- Mapping platform-specific exceptions to canonical `DataLoomError` values
- Not deciding retry policy

The conceptual flow:

```text
ProviderOperationResult.Failure
        ↓
DataLoomError
        ↓
RetryPolicy.evaluate(RetryEvaluationRequest)
```

Provider-level failures and event-level acknowledgement retries
(`ChangeAcknowledgementStatus.RETRY`) are distinct. Provider-level retry
evaluation uses `RetryPolicy`. Event-level acknowledgement retry orchestration
is deferred to a later issue.

---

## WorkManager Boundary

The Android WorkManager integration belongs in a dedicated Android adapter
introduced in a later issue.

```text
RetryPolicy
        ↓
RetryDecision.Retry
        ↓
Shared runtime
        ↓
SchedulerProvider
        ↓
WorkManagerSchedulerProvider
        ↓
WorkManager
```

The DL-013 contracts must not expose:

- `Result.retry()`
- `BackoffPolicy`
- `WorkRequest`
- `WorkerParameters`
- WorkManager backoff constants

The Android integration may adapt canonical retry decisions to WorkManager
internally in a later issue.

---

## Kotlin Multiplatform Boundary

- Retry policy contracts reside in `commonMain` of `dataloom-api`.
- Retry decisions are reusable across Android and future KMP platforms.
- Platform scheduling remains provider-specific.
- Backoff calculation is platform-neutral.
- Provider interfaces are preferred over platform scheduler APIs.
- Platform limitations may affect whether requested timing can be honored.
- A requested delay is an intent, not an exact execution guarantee.

---

## Backoff Semantics

`RetryDecision.Retry.delay` is the canonical output of backoff evaluation.

Future application policies may implement:

| Strategy           | Conceptual Formula                                   |
|--------------------|------------------------------------------------------|
| Immediate          | `delay = 0`                                          |
| Fixed              | `delay = configured delay`                           |
| Linear             | `delay = initial delay + attempt-based increment`    |
| Exponential        | `delay` grows according to the attempt number        |
| Custom application | Application-defined formula                          |

**DL-013 does not implement any of these algorithms.** The following are
explicitly deferred:

- Fixed-delay policy
- Exponential-backoff policy
- Linear-backoff policy
- Jitter calculation
- Random-number generation
- Multiplier contracts
- Arithmetic overflow handling
- Maximum-delay clamping

Built-in policy implementations will reside in `dataloom-core` in a later issue.

---

## Attempt-Limit Boundary

- Attempt-limit enforcement belongs to the runtime, not the policy contract.
- A `RetryPolicy` may return `RetryDecision.Stop(ATTEMPT_LIMIT_REACHED)` based
  on configuration passed through its constructor.
- The `RetryAttempt` value in `RetryEvaluationRequest` allows the policy to
  observe the current attempt number.
- Persisting retry counts in durable queue records is deferred to a later issue.

---

## Cancellation Rules

- Coroutine cancellation must not be converted into a `RetryDecision`.
- `RetryPolicy.evaluate()` must not catch or translate `CancellationException`.
- `CancellationException` must propagate normally outside the retry policy.
- The runtime is responsible for handling cancellation above the policy
  evaluation boundary.

---

## Security Restrictions

- Retry metadata must not include credentials, authentication tokens,
  encryption keys, payload bytes, or personal data.
- Errors must not reveal secrets.
- Policy implementations must not automatically log sensitive context.
- Examples and tests must use placeholder values.
- Provider descriptors must not expose internal credentials.

---

## What Is Not Implemented in DL-013

The following are explicitly out of scope:

- Retry execution
- Sleeping or delaying
- Queue rescheduling
- WorkManager retries
- Runtime orchestration
- Fixed, linear, or exponential policy implementations
- Jitter calculation
- Random-number generation
- Persistent retry records
- Concrete Android or KMP integrations
- Event-level acknowledgement retry orchestration
- Attempt-limit enforcement in the runtime
