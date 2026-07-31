# DL-040 Queue Circuit Operation Adapter Checkpoint

## Decision

Queue-provider operations now have an additive circuit permission and outcome
recording adapter that preserves the complete provider and circuit evidence.

This checkpoint advances FR-RETRY-007, FR-RETRY-008, and the queue-integration
portion of FR-RETRY-009. It does not complete queue-worker or queue-submission
circuit assembly.

## Safety decision: no transparent QueueProvider mapping

`CircuitBreakerExecutionResult.Executed` contains both the exact queue-provider
outcome and the exact post-execution circuit recording result.

A transparent `QueueProvider` decorator could return only one
`ProviderOperationResult`. It would therefore have to hide either:

- the fact that a queue operation already ran; or
- the fact that circuit-state recording later failed.

Either choice is unsafe for durable mutations. Hiding execution can cause replay
of an already-committed completion, reschedule, deferral, failure, cancellation,
or enqueue. Hiding recording failure makes operational state appear healthier
than it is.

The approved implementation therefore exposes
`CircuitBreakerQueueOperationAdapter` and returns enriched circuit results
without pretending to implement `QueueProvider`.

## Implemented operations

The adapter protects:

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
- expired-lease recovery.

Each operation has a stable `QueueCircuitOperation.retryOperation` identity.

## Scope invariants

- The caller supplies the exact circuit scope.
- Provider-bearing scopes must match the protected provider.
- Operation-bearing scopes must match the invoked queue operation.
- Global and workflow scopes remain explicit.
- No fallback, inheritance, tenant inference, or workflow inference occurs.
- Scope mismatch fails before state-store access and provider invocation.

## Failure classification

`QueueCircuitBreakerFailureClassifier` preserves the default provider classifier
except for `QUEUE_PROVIDER_TIMEOUT`.

That timeout is durably ambiguous and therefore remains
`Recoverability.UNKNOWN` for replay safety. It nevertheless represents a queue
dependency availability failure and is recorded as a circuit failure.

Other unknown queue errors preserve the default non-circuit behavior. Hosts may
inject a provider-specific classifier for additional stable timeout codes.

## Evidence preservation

The adapter guarantees:

- permission denial prevents provider invocation;
- an allowed provider operation runs at most once;
- exact provider success is preserved;
- exact circuit-eligible failure is preserved;
- exact semantic non-circuit failure is preserved;
- post-execution persistence failure does not hide the provider outcome;
- circuit rejection includes the stable rejection reason and retry instant;
- caller cancellation and unexpected exceptions propagate; and
- construction performs no state access, provider operation, clock read, queue
  mutation, or coroutine launch.

## Required evidence

The review branch must prove:

- all eleven adapter methods dispatch to the correct provider operation exactly
  once after permission;
- provider mismatch fails before store/provider access;
- provider-operation mismatch fails before store/provider access;
- an eligible queue failure opens the circuit;
- the next operation is rejected while open;
- `QUEUE_PROVIDER_TIMEOUT` with `Recoverability.UNKNOWN` opens the circuit;
- semantic validation failure records responsive dependency behavior;
- permission persistence failure prevents provider invocation;
- post-execution recording failure preserves the exact provider failure;
- caller cancellation propagates;
- external consumers compile for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- exact JVM and Kotlin/Native ABI baselines include the adapter, operation enum,
  and classifier; and
- permanent pull-request, Android, and Apple lanes pass on one clean final head.

## Focused qualification completed

The focused macOS evidence lane completed the implementation-sensitive steps
before the final-head retrigger:

- runtime JVM tests;
- `iosSimulatorArm64Test`;
- external-consumer compilation for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- exact JVM and Kotlin/Native ABI generation;
- public ABI-boundary validation;
- Apple release XCFramework assembly; and
- verification that the adapter, operation identities, classifier, API index,
  and integration documentation are present.

The generated ABI and documentation evidence is committed in
`d4646937e95fe8dee4d4f78cfabd08908bd2714b`, and the temporary workflow and patch
helper are absent from the review diff. This audit-only update intentionally
retriggers the permanent pull-request, Android, and Apple lanes on a clean head.

## Remaining work

- circuit-aware queue submission preserving preflight and enriched execution
  evidence;
- circuit-aware queue worker recovery, acquisition, and transitions;
- automatic builder assembly for explicit queue circuit policy;
- KMP iOS circuit-state persistence;
- circuit events, bounded metrics, structured logs, traces, redaction, and
  correlation;
- authorized and audited manual circuit open, close, and reset;
- multi-process, high-contention, process-death, restart, and probe recovery
  evidence; and
- complete Book 2 `AC-FUNC-004` evidence.
