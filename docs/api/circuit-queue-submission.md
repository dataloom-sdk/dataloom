# Circuit-aware queue submission

> **Status:** Partial V1 runtime integration. Local submission preflight and
> enriched circuit execution are implemented. Builder assembly, circuit-aware
> queue-worker processing, durable iOS state, observability, and complete
> end-to-end qualification remain open.

## Purpose

`CircuitBreakerQueueSubmission` combines the existing application-owned queue
encoder with `CircuitBreakerQueueOperationAdapter.enqueue` without losing either
local-preflight or circuit-recording evidence.

It deliberately does not implement `DataLoomQueueSubmission`. The historical
interface returns `QueueSubmissionResult`, which cannot represent both an
executed enqueue outcome and a later circuit-state recording failure.

## Ordering

Each submission follows this order:

1. invoke `QueuedSynchronizationWorkEncoder` exactly once;
2. return `EncodingRejected` when the encoder rejects;
3. validate queue-entry identity and availability correspondence;
4. return `ContractViolation` when validation fails;
5. acquire circuit permission for the explicit scope;
6. invoke `QueueProvider.enqueue` at most once when allowed; and
7. preserve the exact `CircuitBreakerExecutionResult`.

Preflight occurs before circuit permission. Invalid local input therefore cannot
load circuit state, reserve a half-open probe, or invoke a queue provider.

## Construction

```kotlin
val submission = CircuitBreakerQueueSubmission(
    encoder = workEncoder,
    queueOperationAdapter = queueCircuitAdapter,
    scope = CircuitBreakerScope.providerOperation(
        providerId = queueProvider.descriptor.id,
        operation = QueueCircuitOperation.ENQUEUE.retryOperation,
    ),
)
```

Provider-bearing scopes must identify the protected queue provider.
Operation-bearing scopes must identify `queue.enqueue`. Global and workflow
scopes remain valid explicit choices. No fallback or inference is performed.

## Result model

`CircuitBreakerQueueSubmissionResult` has three variants:

- `EncodingRejected` — encoder rejected before circuit access;
- `ContractViolation` — encoded request failed local correspondence validation;
- `EnqueueEvaluated` — preflight succeeded and the circuit was evaluated.

`EnqueueEvaluated.executionResult` retains the complete circuit contract:

- `Rejected` — enqueue did not run because the circuit denied permission;
- `PermissionPersistenceFailure` — enqueue did not run because state loading failed;
- `PermissionContentionLimitReached` — enqueue did not run because permission
  contention exceeded its configured bound; or
- `Executed` — enqueue ran exactly once and contains both the provider outcome
  and the subsequent `CircuitBreakerRecordResult`.

A post-execution state-store failure never hides that enqueue already ran.
Callers must not replay an `Executed` result merely because circuit recording
failed.

## Timeout composition

Timeout and circuit policy remain independent and composable:

```kotlin
val timeoutProtectedQueue = TimeoutEnforcingQueueProvider(
    delegate = queueProvider,
    timeoutCoordinator = timeoutCoordinator,
)
val queueCircuitAdapter = CircuitBreakerQueueOperationAdapter(
    queueProvider = timeoutProtectedQueue,
    executionGate = circuitGate,
)
```

The queue-specific classifier counts canonical `QUEUE_PROVIDER_TIMEOUT` as a
circuit failure while retaining `Recoverability.UNKNOWN` for durable replay
safety. A timed-out enqueue may already have committed and is never replayed
automatically.

## Cancellation and half-open probes

Caller cancellation and unexpected encoder, provider, or state-store exceptions
propagate unchanged.

If cancellation or an unexpected exception occurs after a half-open probe was
granted, the existing durable probe lease bounds abandonment and allows later
recovery. Local preflight cannot strand a probe because it runs before permission.

## Security

The result surface contains only canonical errors, stable queue-entry identity,
explicit circuit decisions, and bounded record evidence. It does not expose
payload bytes, credentials, headers, exception stack traces, provider instances,
or arbitrary metadata.

## KMP boundary

The public contracts use Kotlin common and DataLoom API/runtime types only. They
expose no Android, Room, SQLite, SQLDelight, JVM-only, or Apple storage types.

## Remaining V1 work

- circuit-aware queue-worker recovery, acquisition, and transitions;
- explicit builder assembly for queue circuit policy;
- production KMP iOS circuit-state storage and relaunch recovery;
- circuit lifecycle events, metrics, structured logs, traces, redaction, and
  correlation;
- authorized and audited circuit administration; and
- multi-process, process-death, restart, contention, and Book 2 `AC-FUNC-004`
  evidence.
