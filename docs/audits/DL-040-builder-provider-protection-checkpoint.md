# DL-040 DataLoomBuilder provider-protection checkpoint

## Decision

This checkpoint qualifies additive facade assembly for protected direct
synchronization. It does not close DL-040 or approve V1.

## Implemented scope

- explicit `DataLoomStorageProtectionSpec`;
- explicit `DataLoomTransportProtectionSpec`;
- combined `DataLoomProviderProtectionSpec`;
- `DataLoomBuilder.providerProtectionConfiguration(...)`;
- default-null `DataLoom.protectedSynchronization` compatibility property;
- `DataLoomProtectedSynchronization` facade;
- protected execution result preserving the existing rejection model;
- build-time default-provider and exact-operation scope validation;
- timeout-before-circuit composition;
- explicit durable circuit-store injection;
- reuse of the existing lifecycle coordinator, provider resolver, direction
  pipeline registry, connectivity preflight, runtime dependencies, and lifecycle
  emitter;
- immutable capability assembly with no build-time side effects;
- direct `DataLoom.synchronize(...)` behavior left unchanged.

## Safety conclusions

1. The builder never creates a process-local circuit store implicitly.
2. Storage and transport timeout/circuit policy remain independent.
3. Scope mismatch fails during build before provider, store, clock, timeout, I/O,
   identifier, event, or coroutine activity.
4. Existing applications are not silently redirected to protected execution.
5. Protected execution returns ordered bounded provider and circuit evidence.
6. Provider execution followed by failed circuit recording remains fail-closed
   and cannot be treated as permission for automatic replay.
7. Pre-initialization, provider-resolution, pipeline, and connectivity rejection
   semantics use the existing rejection contract.
8. Diagnostic strings exclude state-store and classifier instances.

## Focused tests

The common tests cover:

- no capability when protection is absent;
- valid side-effect-free build;
- invalid operation scope before all side effects;
- pre-initialization rejection;
- successful protected execution with ordered storage/transport evidence; and
- unchanged historical direct synchronization without circuit-store access.

## Required generated evidence

Before review, one macOS lane must:

- apply the reviewed builder/facade patch;
- run runtime JVM and iOS Simulator tests;
- compile external consumers for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- generate exact JVM and Kotlin/Native ABI baselines;
- pass public-boundary validation;
- assemble the release XCFramework;
- index the public documentation; and
- remove all temporary patch/workflow helpers.

One clean final head must then pass permanent Pull Request, Android managed-device,
and Apple/Swift validation.

## Remaining DL-040 work

- strategy and queued-execution adoption;
- durable workflow deadline state across queueing and restart;
- connection, request, and idle protocol timeout adapters;
- production KMP iOS retry/circuit persistence;
- authorized manual retry/reclassification and circuit administration;
- complete observability and health integration; and
- multi-process, restart, contention, failure-injection, and `AC-FUNC-004`
  qualification.
