# DataLoom Retry Policy Contracts (DL-013)

This document describes the public retry-policy contracts introduced in DL-013.
These contracts allow the future DataLoom runtime to evaluate whether a failed
synchronization operation should be retried and, when retrying, how long to wait.

---

## Purpose

Provider operations may fail with canonical `DataLoomError` values. The
DataLoom runtime needs a policy contract that can evaluate those failures and
produce one of two decisions:

- **Retry** after a specific delay
- **Stop** retrying

This document defines that decision boundary.

---

## Conceptual Runtime Flow

```text
Provider operation fails
        ↓
DataLoomError
        ↓
RetryPolicy.evaluate(RetryEvaluationRequest)
        ↓
RetryDecision
        ├── Retry after delay
        └── Stop retrying
```

The runtime engine that executes this flow is introduced in a later issue.

---

## Contracts

### `RetryPolicyId`

**Package:** `io.dataloom.api.identifier`

Canonical identifier for a `RetryPolicy` implementation.

```kotlin
val id = RetryPolicyId("default-network-policy")
```

| Property    | Type     | Description                            |
|-------------|----------|----------------------------------------|
| `value`     | `String` | Non-blank policy identifier string.    |

- Value must not be blank or whitespace-only.
- Valid input is preserved exactly as supplied.
- No normalization or automatic generation is applied.
- `toString()` returns the underlying value.

Example placeholder values:

```
default-network-policy
critical-upload-policy
manual-only-policy
```

---

### `RetryOperation`

**Package:** `io.dataloom.api.retry`

Identifies the logical operation being evaluated by a `RetryPolicy`.

```kotlin
val operation = RetryOperation("transport.push")
```

| Property    | Type     | Description                               |
|-------------|----------|-------------------------------------------|
| `value`     | `String` | Non-blank logical operation identifier.   |

- Value must not be blank or whitespace-only.
- Valid input is preserved exactly as supplied.
- No normalization or automatic generation is applied.
- `toString()` returns the underlying value.
- The type is extensible: new operations can be added without changing the
  public API.

Example placeholder values:

```
transport.push
transport.pull
storage.read-outbound
storage.apply-inbound
storage.write-checkpoint
provider.initialize
scheduler.schedule
queue.acquire
```

These are illustrative only and must not be treated as an exhaustive list.

---

### `RetryAttempt`

**Package:** `io.dataloom.api.retry`

Immutable counter representing the retry attempt number.

```kotlin
val attempt = RetryAttempt(1) // first retry evaluation
```

| Member  | Type  | Description                                      |
|---------|-------|--------------------------------------------------|
| `number` | `Int` | Attempt number. Must be greater than zero.       |

- `number` must be greater than zero. Zero and negative values are rejected.
- Attempt number `1` represents the first retry evaluation after the original
  operation failed. The initial provider operation is not attempt zero.
- Construction does not read the clock, sleep, or schedule work.
- The DataLoom runtime supplies and manages attempt values. A later issue
  introduces attempt creation and increment behavior.

---

### `RetryStopReason`

**Package:** `io.dataloom.api.retry`

A closed enumeration of reasons why a `RetryPolicy` stopped retrying.

```kotlin
RetryStopReason.NON_RECOVERABLE
RetryStopReason.ATTEMPT_LIMIT_REACHED
RetryStopReason.POLICY_REJECTED
RetryStopReason.UNSUPPORTED_OPERATION
```

| Variant                  | Semantics                                                                          |
|--------------------------|------------------------------------------------------------------------------------|
| `NON_RECOVERABLE`        | The canonical error indicates repeating the operation without correction will not succeed. |
| `ATTEMPT_LIMIT_REACHED`  | The configured retry-attempt budget has been exhausted.                            |
| `POLICY_REJECTED`        | The policy decided the operation should not be retried.                            |
| `UNSUPPORTED_OPERATION`  | The policy does not support retry evaluation for the supplied logical operation.   |

**Important:** Do not rely on enum ordinals for serialization or persistence.

**Coroutine cancellation:** `CancellationException` must not be converted into a
`RetryStopReason`. Cancellation must propagate normally.

---

### `RetryEvaluationRequest`

**Package:** `io.dataloom.api.retry`

Immutable model carrying all information a `RetryPolicy` needs to produce a
`RetryDecision`.

```kotlin
val request = RetryEvaluationRequest(
    synchronizationRequest = syncRequest,
    operation = RetryOperation("transport.push"),
    error = dataLoomError,
    attempt = RetryAttempt(1),
    previousDelay = null,
    provider = null,
)
```

| Property                   | Type                     | Default                  | Required |
|----------------------------|--------------------------|--------------------------|----------|
| `synchronizationRequest`   | `SynchronizationRequest` | —                        | Yes      |
| `operation`                | `RetryOperation`         | —                        | Yes      |
| `error`                    | `DataLoomError`          | —                        | Yes      |
| `attempt`                  | `RetryAttempt`           | —                        | Yes      |
| `previousDelay`            | `SchedulingDelay?`       | `null`                   | No       |
| `provider`                 | `ProviderDescriptor?`    | `null`                   | No       |
| `metadata`                 | `DataLoomMetadata`       | `DataLoomMetadata.Empty` | No       |

#### `previousDelay`

Allows future linear, exponential, or custom policies to consider the delay
used during the preceding retry. A `null` value means no prior retry delay is
available. The model does not calculate a new delay.

#### `provider`

The optional provider descriptor allows a policy to distinguish between
storage, transport, scheduler, connectivity, or other provider failures. The
policy must not initialize, close, or interact with the provider.

#### Construction restrictions

Construction does not:

- Evaluate policy
- Schedule work
- Access storage
- Query providers
- Mutate queues
- Read the system clock
- Inspect payload content
- Increment the attempt

---

### `RetryDecision`

**Package:** `io.dataloom.api.retry`

Sealed contract representing the outcome of a `RetryPolicy` evaluation.

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

#### `Retry`

The policy requests the failed operation be retried after `delay`.

- `delay` is required. A zero-millisecond delay represents an immediate retry
  request.
- `metadata` defaults to `DataLoomMetadata.Empty`.
- Creating this variant does not sleep, schedule work, mutate queues, or
  execute the operation.
- `delay` is the canonical output of backoff evaluation. Future built-in
  policy implementations may compute this value using immediate, fixed, linear,
  exponential, or custom formulas. No algorithm is implemented by this contract.

#### `Stop`

The policy requests that retrying be abandoned.

- `reason` is required. Identifies why the policy decided to stop.
- `metadata` defaults to `DataLoomMetadata.Empty`.
- Creating this variant does not automatically fail the workflow, remove queued
  work, or modify runtime state.
- The DataLoom runtime acts on this decision after receiving it.

#### Common requirements

- Both variants are immutable.
- Both variants provide value-based equality.
- `metadata` must not contain credentials, keys, payloads, or personal data.

---

### `RetryPolicy`

**Package:** `io.dataloom.api.retry`

Platform-independent interface for retry evaluation.

```kotlin
interface RetryPolicy {
    val id: RetryPolicyId

    fun evaluate(
        request: RetryEvaluationRequest,
    ): RetryDecision
}
```

#### `id`

Stable identifier for this retry policy, used by the runtime for diagnostics
and configuration.

#### `evaluate`

Synchronous, deterministic evaluation of whether the failed operation should
be retried.

For the same `request` and policy configuration, `evaluate` must always return
the same `RetryDecision`.

**Restrictions:** `evaluate` must not:

- Block the current thread
- Sleep
- Access network services
- Access application storage
- Schedule work
- Mutate queues
- Execute provider operations
- Automatically log sensitive context
- Catch or translate coroutine cancellation

---

## Why Evaluation Is Synchronous

Retry-policy evaluation is deliberately synchronous. A policy should calculate
a decision using already-available information. It must not:

- Make network requests
- Query a database
- Refresh credentials
- Call providers
- Sleep or wait for connectivity
- Schedule background work

The runtime performs those operations after receiving the decision. This keeps
retry evaluation:

- Deterministic
- Fast
- Testable
- Multiplatform
- Independent of runtime infrastructure

---

## Recoverability Rules

### `Recoverability.NON_RECOVERABLE`

The normal decision is:

```text
RetryDecision.Stop(NON_RECOVERABLE)
```

A future runtime may reject a policy decision that attempts to retry a
non-recoverable error. Enforcement is deferred.

### `Recoverability.RECOVERABLE`

The policy may return either `Retry` or `Stop` depending on attempt limits
and application policy.

### `Recoverability.UNKNOWN`

The policy decides whether retrying is safe.

**Important rules:**

- Severity alone must not determine retry behaviour.
- `CRITICAL` does not automatically mean retry.
- `WARNING` does not automatically mean continue.
- Retry behaviour must not be inferred from exception class names.
- Provider-specific exceptions must already be mapped to `DataLoomError`.

---

## Provider Failure Versus Event Acknowledgement

### Provider-operation failure

```text
ProviderOperationResult.Failure
        ↓
DataLoomError
        ↓
RetryPolicy.evaluate()
```

The provider call itself failed.

### Event-level retry acknowledgement

```text
Provider operation succeeds
        ↓
ChangeSetAcknowledgement
        ↓
One or more events have status RETRY
        ↓
Runtime evaluates retry policy per future orchestration rules
```

A successful provider call can still contain event-level retry outcomes. These
are distinct from provider-level retry evaluation and are not implemented by
the DL-013 contracts.

---

## Backoff Semantics

`RetryDecision.Retry.delay` is the canonical output of backoff evaluation.

Future built-in policy implementations may include:

| Strategy           | Conceptual Formula                                   |
|--------------------|------------------------------------------------------|
| Immediate          | `delay = 0`                                          |
| Fixed              | `delay = configured delay`                           |
| Linear             | `delay = initial delay + attempt-based increment`    |
| Exponential        | `delay` grows according to the attempt number        |
| Custom application | Application-defined formula                          |

**DL-013 does not implement any of these algorithms.** No arithmetic overflow
handling, multipliers, jitter, randomness, maximum-delay enforcement, or
attempt-limit enforcement is implemented. Built-in policy implementations
belong in a later `dataloom-core` issue.

---

## Dependency-Injection Neutrality

Policy implementations may receive configuration through constructors or
constructor-injected dependencies. DataLoom does not depend on Hilt, Koin,
Dagger, or any other dependency-injection framework.

---

## Example Placeholder Policy

The following example illustrates a minimal policy implementation for
testing and documentation purposes only. It does not represent a production
strategy.

```kotlin
class AlwaysRetryPolicy(
    override val id: RetryPolicyId = RetryPolicyId("always-retry-policy"),
    private val delay: SchedulingDelay = SchedulingDelay(5_000L),
) : RetryPolicy {
    override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
        RetryDecision.Retry(delay = delay)
}
```

---

## Deferred Built-In Policies

The following policy implementations are deferred to a later issue:

- Fixed-delay policy
- Linear-backoff policy
- Exponential-backoff policy
- Maximum-attempt policy
- Composite policy

These implementations will reside in `dataloom-core`, not `dataloom-api`.
