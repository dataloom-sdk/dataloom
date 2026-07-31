# Circuit-protected queue-worker scheduling

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. Follow-up queue-worker scheduling can use a
> separately configured timeout and circuit boundary while preserving exact
> provider and post-execution circuit-recording evidence.

## Purpose

A queue transition may already be durable before the worker requests another
platform wake-up. Scheduler execution is therefore a separate dependency
boundary and must not reuse queue-provider circuit policy implicitly.

The circuit-aware path preserves all of these cases:

- the scheduler circuit rejects before provider invocation;
- circuit state cannot be loaded before provider invocation;
- permission contention is exhausted;
- the scheduler returns a canonical failure;
- the scheduler accepts the request and circuit recording is accepted; and
- the scheduler accepts the request but later circuit recording is unconfirmed.

The last case is especially important: an accepted schedule must not be
submitted again merely because a later circuit-state write failed.

## Public contracts

- `SchedulerCircuitOperation.SCHEDULE`
- `DataLoomCircuitQueueWorkerSchedulerSpec`
- `DataLoomBuilder.circuitQueueWorkerSchedulerConfiguration(...)`
- `CircuitBreakerQueueWorkerRuntime.createWithSchedulerCircuit(...)`
- `QueueWorkerSchedulingResult.CircuitProtected`

`CircuitProtected.executionResult` contains the complete
`CircuitBreakerExecutionResult<ScheduleReceipt>`.

## Composition order

When both controls are configured, scheduling is assembled as:

```text
SchedulerProvider
    ↓ provider timeout
TimeoutEnforcingSchedulerProvider
    ↓ circuit permission / classification / recording
CircuitBreakerRetrySchedulingAdapter
    ↓ enriched worker result
QueueWorkerSchedulingResult.CircuitProtected
```

A zero timeout prevents delegate invocation and returns the stable
`SCHEDULER_PROVIDER_TIMEOUT` failure through the circuit adapter. The configured
scheduler classifier decides whether the canonical provider failure contributes
to circuit health.

## Explicit policy

The application supplies a separate:

- `CircuitBreakerConfiguration`;
- durable `CircuitBreakerStateStore`;
- exact global, workflow, provider, or `scheduler.schedule` scope; and
- optional scheduler failure classifier.

The builder never reuses queue circuit state, thresholds, scopes, or
classification. Provider-bearing scopes must match the bound scheduler.
Operation-bearing scopes must be exactly `scheduler.schedule`.

## Evidence and replay safety

`QueueWorkerSchedulingResult.CircuitProtected` does not collapse the execution
into `Scheduled` or `SchedulerFailed`.

When the nested execution result is `Executed`, callers can inspect both:

1. the scheduler provider outcome; and
2. the later `CircuitBreakerRecordResult`.

A successful `ScheduleReceipt` therefore remains visible even when recording
returns persistence failure, contention exhaustion, stale-probe evidence, clock
regression, or an expired probe lease. The worker performs at most one scheduler
call and never automatically retries inside the same cycle.

## Side-effect boundary

Configuration and builder assembly perform no store access, provider operation,
timeout execution, clock read, scheduling, identifier generation, or coroutine
launch. No-wake-up processing returns `NotRequired` without consulting the
scheduler circuit.

## Remaining V1 work

This slice does not complete DL-040. Transport/storage circuit and timeout
assembly, protocol connection/request/idle adapters, production KMP iOS
persistence, authorized operations, complete observability, contention/restart
qualification, and `AC-FUNC-004` remain open.
