# DL-040 provider-protected queued execution checkpoint

## Decision

Durable queued work must use its exact persisted provider bindings and must not
collapse provider execution or later circuit recording into a plain queue
outcome. The queue transition request and protected synchronization evidence
are separate facts.

## Implemented scope

This slice adds:

- explicit-bindings protected synchronization facade execution;
- `ProviderProtectedQueuedSynchronizationExecutionHandler`;
- `ProviderProtectedQueueEntryExecutionResult`;
- exact request and binding forwarding;
- immutable workflow deadline enforcement before protected execution;
- ordered defensive provider-operation evidence;
- deterministic queue outcome mapping;
- fail-closed unknown-recoverability handling;
- retry-attempt overflow protection;
- external JVM and Kotlin/Native consumer coverage.

## Reviewed invariants

- local resolver rejection invokes no protected facade;
- deadline expiry invokes no protected facade;
- exact explicit bindings are forwarded unchanged;
- a protected provider operation is never hidden by the queue outcome;
- provider success plus unconfirmed circuit recording remains visible;
- unknown completion does not become an automatic retry;
- admission rejection does not fabricate provider execution evidence;
- the handler does not call a queue provider or scheduler;
- caller cancellation and unexpected exceptions propagate;
- result diagnostics do not render provider values or payloads.

## Qualification requirements

The focused evidence lane must:

- run runtime JVM and iOS Simulator tests;
- compile external consumers for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- generate exact JVM and Kotlin/Native ABI baselines;
- pass public ABI-boundary checks;
- assemble the Apple release XCFramework;
- index the API documentation;
- remove temporary evidence helpers.

The clean final review head must pass Pull Request, Android managed-device, and
Apple/Swift validation before merge.

## Remaining DL-040 work

- circuit-aware queue processor and worker adoption of per-entry provider
  evidence;
- protected strategy execution;
- protocol-specific connection, request, and idle timeout adapters;
- production KMP iOS retry/circuit/deadline persistence;
- authorized manual retry/reclassification and circuit administration;
- complete observability, health, multi-process, restart, contention,
  failure-injection, and Book 2 `AC-FUNC-004` evidence.
