# DL-040 Storage and Transport Circuit Adapter Checkpoint

## Decision

Storage and transport provider operations may be circuit-protected only through
an evidence-preserving adapter that keeps provider execution separate from the
later circuit-state recording result.

This checkpoint advances the provider-circuit integration portion of DL-040. It
does not complete the retry/circuit engine or DataLoom V1.

## Implemented boundary

The runtime adds:

- `StorageCircuitOperation`;
- `TransportCircuitOperation`;
- `StorageCircuitBreakerFailureClassifier`;
- `TransportCircuitBreakerFailureClassifier`;
- `CircuitBreakerStorageOperationAdapter`; and
- `CircuitBreakerTransportOperationAdapter`.

The existing storage and transport timeout decorators now use the same stable
operation identities.

## Safety invariants

- Provider and operation scopes are validated before state-store or provider access.
- An allowed provider method is invoked at most once.
- Pre-execution rejection never invokes the provider.
- Provider success/failure and post-execution circuit recording remain separate.
- A provider success followed by circuit persistence failure is not converted to
  provider failure.
- A timed-out mutation or remote request is never automatically replayed.
- Caller cancellation and unexpected exceptions propagate.
- Construction is side-effect free.

## Timeout classification

`STORAGE_PROVIDER_TIMEOUT` and `TRANSPORT_PROVIDER_TIMEOUT` record circuit
unavailability even when completion is unknown.

Storage timeout recoverability:

- health, outbound read, checkpoint read: `RECOVERABLE`;
- initialize, close, apply, acknowledgement, checkpoint write: `UNKNOWN`.

Transport timeout recoverability:

- health: `RECOVERABLE`;
- initialize, close, push, pull: `UNKNOWN`.

Transport pull remains fail-closed because the shared provider contract does not
guarantee idempotency or rollback.

## Qualification requirements

The review branch must prove:

- exact scope validation before state/provider access;
- timeout-before-circuit composition;
- zero timeout prevents delegate invocation;
- ambiguous timeout still contributes to circuit health;
- an open circuit prevents a second invocation;
- provider success remains visible after circuit-recording failure;
- cancellation propagation;
- side-effect-free construction;
- external consumer compilation for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- exact JVM and Kotlin/Native ABI baselines;
- public-boundary checks and Apple XCFramework assembly; and
- final Pull Request, Android managed-device, and Apple/Swift validation on one
  clean head.

## Remaining work

- direct pipeline and facade/builder assembly;
- workflow deadline propagation;
- protocol connection/request/idle adapters;
- production KMP iOS persistence;
- manual administration and reclassification;
- complete observability; and
- multi-process, restart, contention, failure-injection, and AC-FUNC-004 evidence.
