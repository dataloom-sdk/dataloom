# DL-040 Circuit-Aware Queue Submission Checkpoint

## Decision

Queue submission now has an additive circuit-aware path that completes local
encoding and structural validation before circuit permission and preserves the
full provider/circuit execution evidence.

This checkpoint advances the queue-submission portion of FR-RETRY-007 through
FR-RETRY-009. It does not complete circuit-aware queue-worker or builder assembly.

## Preflight-before-permission invariant

`CircuitBreakerQueueSubmission.submit` must perform:

1. encoder invocation;
2. encoding rejection handling;
3. encoded-request correspondence validation; and only then
4. circuit permission acquisition and enqueue execution.

This ordering is mandatory. Acquiring a half-open probe before local validation
could strand the probe when an encoder rejects or throws. The shared
`QueueSubmissionPreflight` therefore owns provider-free preparation for both the
historical direct path and the new circuit-aware path.

## Result integrity

`CircuitBreakerQueueSubmissionResult` distinguishes:

- `EncodingRejected` — no circuit or provider access;
- `ContractViolation` — no circuit or provider access; and
- `EnqueueEvaluated` — local preflight succeeded and the complete
  `CircuitBreakerExecutionResult<Unit>` is available.

An `Executed` circuit result proves enqueue ran exactly once. Its
`CircuitBreakerRecordResult` remains visible even when post-execution circuit
persistence fails. Callers must not replay an executed enqueue solely because
recording failed.

## Scope invariants

- provider-bearing scopes identify the protected queue provider;
- operation-bearing scopes identify `queue.enqueue`;
- global and workflow scopes remain explicit choices;
- no provider, operation, tenant, workflow, or global fallback is inferred; and
- invalid constructor scope fails before encoder, state-store, or provider access.

## Timeout composition

A `TimeoutEnforcingQueueProvider` may be supplied to the queue circuit adapter.
Canonical `QUEUE_PROVIDER_TIMEOUT` remains `Recoverability.UNKNOWN` because the
enqueue may already have committed, but the queue classifier records it as a
circuit failure. No automatic replay or replacement identifier is introduced.

## Compatibility

`DefaultDataLoomQueueSubmission` now shares the extracted preflight but retains
its existing public interface, result variants, exact provider result mapping,
and builder behavior.

The circuit-aware path is additive and deliberately does not implement
`DataLoomQueueSubmission`, because that interface cannot preserve enriched
circuit recording evidence.

## Required evidence

The review branch must prove:

- encoding rejection touches neither circuit state nor provider;
- structural violation touches neither circuit state nor provider;
- valid success returns an enriched executed result;
- eligible failure opens the circuit and the next enqueue is rejected;
- the second rejected enqueue still completes local preflight first;
- permission persistence failure prevents provider invocation;
- post-execution record failure preserves the provider outcome;
- zero-timeout queue-provider composition opens the circuit while preserving
  unknown durable outcome and without invoking the raw provider;
- invalid operation scope fails during construction before preflight;
- unexpected encoder exceptions leave circuit and provider untouched;
- caller cancellation propagates;
- existing direct queue-submission tests remain green;
- external consumers compile for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- exact JVM and Kotlin/Native ABI baselines contain the new public contracts; and
- permanent pull-request, Android, and Apple lanes pass on one clean final head.

## Remaining work

- circuit-aware queue-worker recovery, acquisition, and transitions;
- explicit `DataLoomBuilder` circuit-policy assembly;
- KMP iOS circuit-state persistence and relaunch recovery;
- circuit events, bounded metrics, structured logs, traces, redaction, and
  correlation;
- authorized and audited circuit open, close, and reset;
- multi-process, high-contention, process-death, restart, and probe-recovery
  qualification; and
- complete Book 2 `AC-FUNC-004` evidence.
