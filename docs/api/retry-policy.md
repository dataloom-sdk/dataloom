# DataLoom retry policy contracts

[API reference index](./README.md)

> **Status:** Partial V1 retry subsystem. Custom policy evaluation, queue and
> scheduler integration, retry-history preservation, central fail-closed
> failure protection, deterministic immediate/fixed/linear/exponential backoff,
> and an attempt budget are implemented. Jitter, elapsed-time and aggregate
> delay budgets, server hints, timeout separation, durable circuit breaking,
> manual retry, and complete observability remain V1 blockers.

## Purpose

A provider or synchronization pipeline reports a canonical `DataLoomError`.
DataLoom decides whether repeating the operation is safe and, when it is, asks a
configured `RetryPolicy` for a `RetryDecision`.

```text
Terminal synchronization result
        ↓
Canonical errors in original order
        ↓
Central fail-closed retry protection
        ├── protected error → stop the complete batch
        └── fully eligible → RetryPolicy.evaluate(...)
                                ↓
                         Retry or Stop
```

Policy evaluation is synchronous and side-effect free. It does not sleep,
access storage, call providers, read a clock, schedule work, or mutate queues.

An unmet execution constraint is not a failed attempt. Connectivity deferral
therefore bypasses retry policy, persists no failure, and preserves the queued
entry's existing attempt exactly.

## Contracts

### `RetryPolicyId`

`io.dataloom.api.identifier.RetryPolicyId` is a non-blank stable identifier.

```kotlin
val policyId = RetryPolicyId("orders-network-retry")
```

### `RetryOperation`

`io.dataloom.api.retry.RetryOperation` identifies the logical operation under
evaluation. It is an extensible value class rather than a closed enum.

```kotlin
val operation = RetryOperation("transport.push")
```

### `RetryAttempt`

`RetryAttempt.number` starts at one for the first retry evaluation after the
original operation failed. The original operation is not attempt zero.

For queued work, a genuine pipeline failure advances the persisted attempt as:

```text
(entry.retryAttempt?.number ?: 0) + 1
```

Offline deferral does not advance that number.

### `RetryEvaluationRequest`

A policy receives:

| Property | Meaning |
|---|---|
| `synchronizationRequest` | Original immutable synchronization request |
| `operation` | Logical operation being evaluated |
| `error` | Canonical, sanitized `DataLoomError` |
| `attempt` | Current retry attempt |
| `previousDelay` | Previous retry delay when available |
| `provider` | Optional provider descriptor, never the provider instance |
| `metadata` | Optional bounded, non-sensitive context |

The current queue-backed and scheduler-backed runtime paths pass
`previousDelay = null` and `provider = null`. These fields remain available for
future hint, budget, and application-policy integration.

### `RetryDecision`

```kotlin
sealed interface RetryDecision {
    data class Retry(
        val delay: SchedulingDelay,
        val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : RetryDecision

    data class Stop(
        val reason: RetryStopReason,
        val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
    ) : RetryDecision
}
```

A retry delay is a minimum requested wait. A platform scheduler may execute
later because of operating-system constraints.

### `RetryStopReason`

| Reason | Meaning |
|---|---|
| `NON_RECOVERABLE` | Repeating without correction is not expected to succeed |
| `ATTEMPT_LIMIT_REACHED` | A configured retry budget is exhausted |
| `POLICY_REJECTED` | Policy or central protection rejected automatic retry |
| `UNSUPPORTED_OPERATION` | The policy does not support the operation |

Do not persist enum ordinals. Persist stable names.

### `RetryPolicy`

```kotlin
interface RetryPolicy {
    val id: RetryPolicyId
    fun evaluate(request: RetryEvaluationRequest): RetryDecision
}
```

For the same request and configuration, evaluation must return the same
result. A policy must not block, sleep, perform I/O, call a provider, schedule
work, mutate queue state, or log sensitive context.

## Built-in deterministic retry policy

`io.dataloom.runtime.retry.StandardRetryPolicy` is DataLoom's standard
side-effect-free policy for the four mandatory deterministic backoff families.

```kotlin
val policy = StandardRetryPolicy(
    id = RetryPolicyId("orders-standard-retry"),
    strategy = RetryBackoffStrategy.Exponential(
        initialDelay = SchedulingDelay(1_000L),
        multiplier = 2,
        maximumDelay = SchedulingDelay(60_000L),
    ),
    maximumAttempts = 5,
)
```

`maximumAttempts` counts retries after the original failed operation. Attempt
one is the first retry evaluation. A value of zero disables automatic retry.
Attempt `N` is allowed only when `N <= maximumAttempts`; the next attempt stops
with `ATTEMPT_LIMIT_REACHED`.

### `RetryBackoffStrategy.Immediate`

Returns `SchedulingDelay.ZERO` for every allowed attempt.

```kotlin
RetryBackoffStrategy.Immediate
```

### `RetryBackoffStrategy.Fixed`

Returns the same configured non-negative delay for every allowed attempt.

```kotlin
RetryBackoffStrategy.Fixed(
    delay = SchedulingDelay(5_000L),
)
```

### `RetryBackoffStrategy.Linear`

Attempt one uses `initialDelay`. Each later attempt adds `increment`, clamped to
`maximumDelay`.

```text
initial + increment × (attempt - 1)
```

```kotlin
RetryBackoffStrategy.Linear(
    initialDelay = SchedulingDelay(1_000L),
    increment = SchedulingDelay(750L),
    maximumDelay = SchedulingDelay(10_000L),
)
```

`maximumDelay` must be greater than or equal to `initialDelay`. A zero increment
is valid and produces a constant initial delay.

### `RetryBackoffStrategy.Exponential`

Attempt one uses `initialDelay`. Each later attempt multiplies by `multiplier`,
clamped to `maximumDelay`.

```text
initial × multiplier^(attempt - 1)
```

```kotlin
RetryBackoffStrategy.Exponential(
    initialDelay = SchedulingDelay(500L),
    multiplier = 2,
    maximumDelay = SchedulingDelay(30_000L),
)
```

The multiplier must be at least two and `maximumDelay` must be greater than or
equal to `initialDelay`.

### Arithmetic and determinism

Linear and exponential calculations test the clamp boundary before arithmetic
that could overflow. They never wrap to a negative delay. Very large attempt
numbers terminate after a bounded calculation: linear uses a division guard,
while exponential returns as soon as the configured maximum is reached. A zero
exponential initial delay remains zero without iterating through the attempt
count.

No jitter is silently applied. The same request and immutable configuration
produce the same decision across JVM, Android, and Kotlin/Native targets.

### Defense in depth

`StandardRetryPolicy` applies the same protected-failure rules as the surrounding
runtime. Direct policy use therefore cannot retry a non-recoverable, unknown,
authentication, authorization, serialization, validation, configuration,
policy, conflict, or security failure.

## Central fail-closed protection

Every runtime retry path applies the same protection before invoking an
application-provided policy.

Automatic retry is blocked when:

1. `error.recoverability == NON_RECOVERABLE`;
2. `error.recoverability == UNKNOWN`; or
3. the error belongs to a protected category:
   - authentication;
   - authorization;
   - serialization;
   - validation;
   - configuration;
   - policy;
   - conflict; or
   - security.

`NON_RECOVERABLE` maps to `RetryStopReason.NON_RECOVERABLE`. Unknown and
protected-category errors map to `RetryStopReason.POLICY_REJECTED` until an
explicit, authorized, audited reclassification mechanism exists.

### Partial-result rule

Protection is evaluated across the complete ordered error set before custom
policy invocation. When any error is protected:

- the whole retry batch stops;
- no custom policy is called for any sibling error;
- no scheduler or queue reschedule is requested;
- the first protected error becomes the primary blocking error; and
- the decision list contains only stop decisions.

This prevents a transient network error from hiding an authentication,
validation, conflict, or security failure in the same partial result.

### Eligible errors

Errors outside the protected set reach the configured policy only when they are
explicitly marked `RECOVERABLE`. Typical eligible categories include network,
storage, queue, scheduler, provider, plugin, state, and internal failures,
subject to the producer's canonical classification.

Severity does not determine retry eligibility. Exception class names and error
message parsing are not classification mechanisms.

## Runtime integration

### Queue-backed execution

For a genuine pipeline failure:

1. the queued handler advances the persisted attempt;
2. `SynchronizationRetryEvaluator` applies central protection;
3. eligible errors are evaluated in original order;
4. the configured policy enforces its attempt budget and calculates delay;
5. the maximum requested retry delay is selected across errors;
6. availability time is calculated with overflow-safe timestamp addition; and
7. successful queue rescheduling persists the exact attempt and error.

Connectivity deferral bypasses this flow and preserves retry history.

### Scheduler-backed orchestration

`SynchronizationRetryOrchestrator` applies the same protection. It calls
`SchedulerProvider.schedule` at most once and only when:

- no protected error exists;
- at least one policy decision requests retry; and
- a scheduler is configured.

After scheduler acceptance, the runtime may emit `RetryScheduled`. Observer
failure does not reverse the accepted schedule; cancellation still propagates.

## Custom policy example

Applications may still provide a domain-specific policy through the stable
`RetryPolicy` contract:

```kotlin
class ApplicationRetryPolicy : RetryPolicy {
    override val id = RetryPolicyId("application-retry")

    override fun evaluate(request: RetryEvaluationRequest): RetryDecision {
        return if (request.attempt.number <= 2) {
            RetryDecision.Retry(SchedulingDelay(2_000L))
        } else {
            RetryDecision.Stop(RetryStopReason.ATTEMPT_LIMIT_REACHED)
        }
    }
}
```

The runtime protection boundary wraps custom policies. A custom policy cannot
opt an automatically protected error back into retry through an ordinary
`RetryDecision.Retry`.

## Cancellation and exceptions

- `SynchronizationResult.Cancelled` is not retryable.
- A thrown `CancellationException` propagates and is never translated into a
  decision or result.
- Unexpected exceptions from an eligible custom policy propagate.
- A protected batch does not invoke the custom policy, so policy exceptions
  cannot override the protection decision.

## Still required for V1

- deterministic configurable jitter and injectable randomness;
- maximum elapsed-time and aggregate-delay budgets;
- bounded provider/server retry hints;
- separated timeout semantics;
- durable closed/open/half-open circuit-breaker state;
- controlled half-open probes and concurrency limits;
- restart recovery for elapsed windows and circuit state;
- authorized, audited manual retry and reclassification;
- retry events, metrics, logs, traces, and stable reason codes;
- property, persistence, restart, failure-injection, and concurrency matrices;
- native Android, KMP Android, and KMP iOS parity qualification.

Until those gates pass, DataLoom V1 remains production-release **NO-GO**.
