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
