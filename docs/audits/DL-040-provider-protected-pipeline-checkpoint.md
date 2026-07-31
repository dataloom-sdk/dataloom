# DL-040 provider-protected existing pipeline checkpoint

## Decision

This checkpoint qualifies one additive execution boundary. It does not close
DL-040 or approve V1.

## Implemented scope

- execution of an existing `SynchronizationPipeline` through protected storage
  and transport operation surfaces;
- exact provider identity and pipeline-direction validation before execution;
- execution-local storage and transport bridges;
- preservation of original scheduler, connectivity, queue, request, runtime
  dependency, and lifecycle emitter references;
- ordered bounded provider/circuit evidence;
- exact pre-execution circuit decision evidence;
- exact provider failure classification;
- exact post-execution circuit recording evidence;
- fail-closed `Recoverability.UNKNOWN` mapping when a provider operation ran but
  circuit recording was not accepted;
- defensive result evidence snapshots;
- cancellation and unexpected-exception propagation;
- redaction-safe diagnostic representations.

## Safety conclusions

1. A provider operation is invoked at most once after circuit permission.
2. A pipeline never receives a successful provider result when the later
   circuit-state recording is unconfirmed.
3. The returned evidence still proves whether the provider ran and whether it
   succeeded, failed for availability, or returned a semantic failure.
4. No provider return value or payload is stored in the evidence list.
5. An open or otherwise rejecting circuit prevents provider invocation.
6. Provider identity mismatch fails before state-store, provider, clock, timeout,
   I/O, identifier, or coroutine activity.
7. Existing direct pipeline execution remains unchanged.

## Focused tests

The focused common tests cover:

- successful storage and transport health checks with ordered evidence;
- open storage circuit stopping before provider invocation;
- provider success followed by circuit-store write failure;
- exact canonical provider failure preservation;
- provider identity mismatch before all side effects;
- caller cancellation propagation; and
- diagnostics excluding representative authorization values.

## Required generated evidence

Before review, one macOS evidence lane must:

- run runtime JVM tests;
- run `iosSimulatorArm64Test`;
- compile external consumers for JVM, `iosArm64`, `iosSimulatorArm64`, and
  `iosX64`;
- generate exact JVM and Kotlin/Native ABI baselines;
- pass public-boundary validation;
- assemble the Apple release XCFramework;
- index the new API documentation; and
- remove all temporary workflow helpers.

The clean final head must then pass permanent Pull Request, Android managed-device,
and Apple/Swift validation on the same commit.

## Remaining DL-040 work

- DataLoomBuilder and facade adoption;
- strategy and queued-execution adoption;
- durable workflow deadline propagation;
- protocol connection, request, and idle timeout adapters;
- production KMP iOS retry/circuit persistence;
- manual retry/reclassification and circuit administration;
- complete observability and health integration; and
- multi-process, restart, contention, failure-injection, and `AC-FUNC-004`
  qualification.
