# Circuit-protected execution gate

> **Status:** Partial V1 runtime integration. The common execution and provider
> adapters are implemented. Production Android/iOS circuit stores, direct
> pipeline wiring, administration, and complete observability remain.

`CircuitBreakerExecutionGate` joins the durable circuit state machine to one
already-classified provider or retry operation.

## Ordered execution

The gate performs this flow:

1. acquire permission for one explicit `CircuitBreakerScope`;
2. reject without invoking the operation when the circuit is open, a probe is in
   flight, the clock regressed, persistence failed, or contention was exhausted;
3. invoke the operation at most once when allowed;
4. record the classified outcome against the same scope and half-open generation;
5. return both the operation outcome and the exact post-execution record result.

The gate deliberately does not catch exceptions. Caller cancellation and
unexpected programming failures propagate unchanged and are not converted into
circuit failures.

## Classified operation outcomes

`CircuitProtectedOperationResult` has three variants:

- `Success(value)` records circuit success.
- `Failure(error)` preserves a canonical failure and contributes to circuit state.
- `NonCircuitFailure(error)` preserves a semantic failure while recording circuit
  success because the protected dependency responded.

A non-circuit failure is useful for authentication, authorization, validation,
serialization, configuration, policy, conflict, security, and other failures
that do not represent dependency availability.

## Unambiguous execution evidence

`CircuitBreakerExecutionResult.Executed` always means the operation ran exactly
once. It contains:

- `operationResult`, the canonical result returned by the operation; and
- `recordResult`, the exact `CircuitBreakerRecordResult` produced afterward.

This distinction is essential for idempotency. A post-execution persistence
failure, stale probe, clock regression, or contention limit never hides the fact
that the operation already ran. Callers must not repeat an executed operation
merely because its circuit-state update failed.

`Rejected`, `PermissionPersistenceFailure`, and
`PermissionContentionLimitReached` are pre-execution results. In all three cases,
the protected operation was not invoked.

## Provider integration

`CircuitBreakerProviderOperationAdapter` converts `ProviderOperationResult` into
the classified gate contract. The default classifier counts only recoverable
availability and infrastructure failures:

- network;
- storage;
- queue;
- scheduler;
- state;
- provider;
- plugin; and
- internal failures.

Protected or non-recoverable failures are preserved as `NonCircuitFailure` and
recorded as circuit success. Hosts may inject a custom
`CircuitBreakerFailureClassifier` when an explicitly approved domain rule is
required.

`CircuitBreakerRetrySchedulingAdapter` applies the provider adapter to
`SchedulerProvider.schedule`. A provider-scoped circuit must identify the same
scheduler provider; global and workflow scopes remain valid explicit choices.

`CircuitBreakerQueueOperationAdapter` applies the same gate to explicit
`QueueProvider` lifecycle and queue operations while preserving the enriched
`CircuitBreakerExecutionResult`. It deliberately does not implement
`QueueProvider`, because collapsing an executed queue mutation and a later
circuit-recording failure into one plain provider result would lose idempotency-
critical evidence. Provider-bearing and operation-bearing scopes are validated
before state-store access or provider invocation.

`CircuitBreakerQueueSubmission` performs encoder and structural preflight before
calling the queue adapter. Invalid local input therefore cannot touch circuit
state or reserve a half-open probe, while valid enqueue attempts retain the full
execution and recording evidence.

`CircuitBreakerDurableQueueExecutionProcessor` applies explicit scopes to atomic
acquisition and every lease-guarded transition. Its terminal results distinguish
pre-execution stop, provider failure, and provider success followed by circuit-
recording failure, so confirmed transitions are counted without replaying them.

## Security boundary

Circuit execution contracts contain only bounded scope, permission, canonical
error, operation outcome, and state-update evidence. They must not carry payloads,
credentials, authorization headers, raw protocol headers, exception messages,
provider instances, or arbitrary metadata.
