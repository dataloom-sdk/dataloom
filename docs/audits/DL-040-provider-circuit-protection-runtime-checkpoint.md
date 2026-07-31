# DL-040 Provider Circuit Protection Runtime Checkpoint

## Decision

Storage and transport timeout/circuit policy must be assembled once into an
immutable operation surface rather than selected independently on each pipeline
call.

This checkpoint advances production provider assembly for DL-040. It does not
complete direct synchronization integration or DataLoom V1.

## Implemented boundary

The runtime adds:

- `StorageCircuitScopes`;
- `TransportCircuitScopes`;
- `ProtectedStorageOperations`;
- `ProtectedTransportOperations`;
- `StorageCircuitProtectionRuntime`; and
- `TransportCircuitProtectionRuntime`.

## Invariants

- Every current provider operation has one explicit bound scope.
- Provider- and operation-bearing scopes are validated during construction.
- No scope is inherited, inferred, or replaced per call.
- One provider, circuit configuration, state store, classifier, and clock are
  shared by the complete operation surface.
- Optional provider timeout protection is applied before circuit classification.
- Every operation preserves the complete `CircuitBreakerExecutionResult`.
- A provider method is invoked at most once after permission.
- Caller cancellation and unexpected exceptions propagate.
- Construction performs no provider/store/clock/timeout/I/O/coroutine activity.

## Required qualification

The review branch must prove:

- invalid provider and operation scopes fail before side effects;
- valid construction is side-effect free;
- bound methods use the exact configured scopes;
- zero timeout is classified inside the circuit and prevents delegate execution;
- the opened circuit rejects a later call;
- a custom classifier survives runtime assembly;
- provider descriptors and immutable scope sets are preserved;
- external consumer compilation for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- exact JVM and Kotlin/Native ABI baselines;
- public-boundary validation and Apple XCFramework assembly; and
- final Pull Request, Android managed-device, and Apple/Swift validation on one
  clean head.

## Remaining work

- push, pull, bidirectional, strategy, and facade adoption;
- workflow deadline persistence and restart propagation;
- connection/request/idle protocol adapters;
- production KMP iOS persistence;
- authorized administration and reclassification;
- complete observability; and
- contention, process-death, restart, failure-injection, and AC-FUNC-004 evidence.
