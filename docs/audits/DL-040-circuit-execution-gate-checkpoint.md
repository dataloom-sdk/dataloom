# DL-040 circuit execution-gate checkpoint

## Scope

This checkpoint covers the common circuit permission and outcome integration
layer added after the durable circuit state machine and atomic persistence SPI.

## Implemented behavior

- one explicit `CircuitBreakerScope` is acquired before invocation;
- rejected or unavailable permission prevents operation execution;
- an allowed operation executes at most once;
- success, circuit-eligible failure, and non-circuit failure are distinct;
- provider results can be adapted through a configurable failure classifier;
- retry scheduling can be protected without exposing platform scheduler types;
- post-execution circuit recording evidence remains attached to the completed
  operation result;
- exceptions and caller cancellation propagate unchanged.

## Correctness invariant

A post-execution persistence failure, contention limit, stale probe, or clock
regression must never be represented as though the protected operation did not
run. `CircuitBreakerExecutionResult.Executed` therefore carries both the
operation result and the exact `CircuitBreakerRecordResult`.

## Focused validation evidence

The evidence commit `fdb36f60ac78239ac483e610adf1d9bc135b78ee`
completed the following before removing the temporary workflow:

- generated exact JVM and Kotlin/Native runtime ABI baselines;
- ran build-logic and runtime JVM tests;
- compiled the external consumer for JVM, `iosArm64`,
  `iosSimulatorArm64`, and `iosX64`;
- assembled the Apple release XCFramework;
- verified the gate, provider adapter, and retry scheduling adapter in both JVM
  and Kotlin/Native public ABI evidence;
- updated repository capability and architecture documentation.

The permanent Pull Request, Android, and Apple workflows remain authoritative
for the final review head.

## Security boundary

The execution layer carries no payload bytes, credentials, tokens, raw headers,
provider instances, exception text, or arbitrary metadata. Canonical errors must
already be sanitized before entering the gate.

## Remaining V1 work

- production Android Room and KMP iOS circuit stores;
- direct integration in transport, storage, queue, scheduler, and retry runtime
  assembly;
- circuit lifecycle events, metrics, logs, and trace correlation;
- authorized and audited manual open, close, and reset operations;
- multi-process and high-contention qualification;
- final Book 2 AC-FUNC-004 end-to-end evidence.
