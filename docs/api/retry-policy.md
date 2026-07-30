# DataLoom retry policy contracts

[API reference index](./README.md)

> **Status:** Partial V1 retry subsystem. Custom policy evaluation, queue and
> scheduler integration, retry-history preservation, central fail-closed
> protection, deterministic immediate/fixed/linear/exponential backoff,
> configurable full/equal jitter, injected deterministic randomness, attempt
> limits, and durable elapsed-time/cumulative-delay budgets are implemented.
> Server hints, timeout separation, durable circuit breaking, manual retry, and
> complete observability remain V1 blockers.

## Purpose

A provider or synchronization pipeline reports a canonical `DataLoomError`.
DataLoom first decides whether repeating the operation is safe. Eligible errors
then reach a configured `RetryPolicy`, which returns either a bounded delay or a
stop decision.

```text
Terminal synchronization result
        ↓
Canonical errors in original order
        ↓
Central fail-closed retry protection
        ├── protected error → stop the complete batch
        └── fully eligible → RetryPolicy.evaluate(...)
                                ↓
                    backoff → optional jitter → Retry or Stop
```

Policy evaluation is synchronous and side-effect free. It does not sleep,
access storage, call providers, read a clock, schedule work, or mutate queues.

An unmet execution constraint is not a failed attempt. Connectivity deferral
therefore bypasses retry policy, persists no failure, and preserves the queued
entry's existing attempt exactly.

## Core contracts

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

For the same request and configuration, evaluation must return the same result.
A policy must not block, sleep, perform I/O, call a provider, schedule work,
mutate queue state, or log sensitive context.

## Built-in standard retry policy

`io.dataloom.runtime.retry.StandardRetryPolicy` provides DataLoom's standard
common-code retry behavior.

The compatibility constructor preserves exact deterministic base delays:

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

### Base backoff strategies

#### Immediate

Returns `SchedulingDelay.ZERO` for every allowed attempt.

```kotlin
RetryBackoffStrategy.Immediate
```

#### Fixed

Returns the same configured non-negative delay for every allowed attempt.

```kotlin
RetryBackoffStrategy.Fixed(
    delay = SchedulingDelay(5_000L),
)
```

#### Linear

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

`maximumDelay` must be at least `initialDelay`. A zero increment is valid and
produces a constant initial delay.

#### Exponential

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

The multiplier must be at least two and `maximumDelay` must be at least
`initialDelay`.

Linear and exponential calculations test the clamp boundary before arithmetic
that could overflow. They never wrap to a negative delay.

## Deterministic jitter

The five-argument `StandardRetryPolicy` constructor applies jitter after the
bounded base delay has been calculated:

```kotlin
val jitteredPolicy = StandardRetryPolicy(
    id = RetryPolicyId("orders-jittered-retry"),
    strategy = RetryBackoffStrategy.Exponential(
        initialDelay = SchedulingDelay(1_000L),
        multiplier = 2,
        maximumDelay = SchedulingDelay(60_000L),
    ),
    maximumAttempts = 5,
    jitterStrategy = RetryJitterStrategy.Full,
    randomSource = SeededRetryRandomSource(seed = 42L),
)
```

Jitter never increases the base delay, so the strategy's configured maximum
remains authoritative.

### `RetryJitterStrategy.None`

Preserves the exact base delay and consumes no random sample. The three-argument
policy constructor uses this mode.

### `RetryJitterStrategy.Full`

Returns a delay in the inclusive range:

```text
0..baseDelay
```

### `RetryJitterStrategy.Equal`

Preserves at least half of the base delay and jitters the remainder. For integer
milliseconds the inclusive range is:

```text
ceil(baseDelay / 2)..baseDelay
```

A zero base delay remains zero. Equal jitter on a one-millisecond base delay
also has no random window. Neither case calls the random source.

## Randomness boundary

### `RetryRandomRequest`

A jitter source receives only stable, payload-free identity:

- retry policy ID;
- workflow ID;
- synchronization session ID;
- retry operation;
- canonical sanitized error code;
- retry attempt; and
- an inclusive non-negative upper bound.

The request contains no application payload, credentials, tokens, provider
instance, exception message, arbitrary metadata, or personal data.

### `RetryRandomSource`

```kotlin
fun interface RetryRandomSource {
    fun sample(request: RetryRandomRequest): Long
}
```

A source must:

- return the same value for equal requests;
- return a value in `0..request.maximumInclusive`;
- remain non-blocking and side-effect free;
- be safe for concurrent use; and
- avoid I/O, logging identifiers, and cryptographic use.

DataLoom validates the returned range. An out-of-range result fails evaluation
with `IllegalStateException`; it is not silently clamped.

### `SeededRetryRandomSource`

`SeededRetryRandomSource` is the built-in stateless implementation. It uses a
versioned stable hash, SplitMix64 finalization, and bounded rejection sampling.
The same seed and equal request produce the same result across JVM, Android, and
Kotlin/Native.

```kotlin
val randomSource = SeededRetryRandomSource(seed = 42L)
```

The seed is non-secret configuration. This source is not a cryptographic random
number generator.

Because the source has no mutable sequence, concurrent evaluation order does not
change the result. Restoring the same seed and durable retry identity after
process restart reproduces the same jitter decision.

## Evaluation order

`StandardRetryPolicy` evaluates in this order:

1. Reject centrally protected failure classes.
2. Reject attempts beyond `maximumAttempts`.
3. Calculate and clamp the deterministic base delay.
4. Apply the configured jitter mode.
5. Validate the source result and return `Retry`.

Protected errors, exhausted attempts, no-jitter policies, zero base delays, and
zero-width equal-jitter windows do not call the random source.

## Central fail-closed protection

Every runtime retry path applies the same protection before invoking an
application-provided policy.

Automatic retry is blocked when:

1. `error.recoverability == NON_RECOVERABLE`;
2. `error.recoverability == UNKNOWN`; or
3. the error belongs to authentication, authorization, serialization,
   validation, configuration, policy, conflict, or security categories.

`NON_RECOVERABLE` maps to `RetryStopReason.NON_RECOVERABLE`. Unknown and
protected-category errors map to `RetryStopReason.POLICY_REJECTED` until an
explicit, authorized, audited reclassification mechanism exists.

For a partial result, protection is evaluated across the complete ordered error
set before policy invocation. One protected error stops the whole retry batch.
No sibling policy evaluation, scheduler call, or queue reschedule occurs.

## Durable elapsed-time and cumulative-delay budgets

`RetryBudgetConfiguration` independently limits the wall-clock retry window and
the sum of delays accepted for retry. Exact boundaries are allowed. A proposed
retry is stopped—not shortened—when its final jittered delay would exceed either
limit.

`RetryBudgetState` records the first genuine retry evaluation, the most recent
accepted evaluation, and cumulative accepted delay. Clock regression against
persisted evidence stops fail-closed with a stable reason.

Queue rescheduling persists attempt, availability, error, and budget state in one
lease-guarded transition. Connectivity deferral and expired-lease recovery
preserve the state unchanged. Schema migration 1→2 retains existing retry attempt
and availability values and initializes historical budget columns to null.

Scheduler-backed orchestration returns the next state only after scheduling is
accepted. Missing or failed scheduling never consumes budget. Direct callers own
persistence of the returned state before supplying it to the next request.

## Runtime integration

### Queue-backed execution

For a genuine pipeline failure:

1. the queued handler advances the persisted attempt;
2. `SynchronizationRetryEvaluator` applies central protection;
3. eligible errors are evaluated in original order;
4. the configured policy enforces its attempt budget and calculates delay;
5. deterministic jitter is applied when configured;
6. the maximum requested delay is selected across errors;
7. elapsed and cumulative budgets evaluate the final delay;
8. availability time is calculated with overflow-safe timestamp addition; and
9. successful queue rescheduling persists attempt, error, and budget state.

Connectivity deferral bypasses this flow and preserves retry and budget history.

### Scheduler-backed orchestration

`SynchronizationRetryOrchestrator` applies the same protection. It calls
`SchedulerProvider.schedule` at most once and only when no protected error
exists, at least one policy decision requests retry, and a scheduler is
configured.

The orchestrator treats the policy's final delay—including jitter—as an opaque
minimum delay. It applies no second jitter layer. When budgets are configured,
it returns next budget state only after scheduler acceptance.

## Cancellation and exceptions

- `SynchronizationResult.Cancelled` is not retryable.
- A thrown `CancellationException` propagates and is never translated into a
  decision or result.
- Unexpected exceptions from an eligible custom policy propagate.
- A random-source contract violation fails evaluation rather than being hidden.
- A protected batch does not invoke custom policy or random source.

## Security and privacy

Retry decisions and random requests must not contain payloads, credentials,
tokens, keys, authorization headers, checkpoint values, personal data, complete
exception messages, or unbounded-cardinality labels.

The built-in seeded source hashes stable identifiers only and does not log them.
The seed must not contain cryptographic key material.

## Still required for V1

- maximum elapsed-time and aggregate-delay budgets;
- bounded provider/server retry hints;
- separated timeout semantics;
- durable closed/open/half-open circuit-breaker state;
- controlled half-open probes and concurrency limits;
- restart recovery for durable circuit state;
- authorized, audited manual retry and reclassification;
- retry events, metrics, logs, traces, and stable reason codes;
- property, persistence, restart, failure-injection, and concurrency matrices;
- native Android, KMP Android, and KMP iOS parity qualification.

Until those gates pass, DataLoom V1 remains production-release **NO-GO**.
