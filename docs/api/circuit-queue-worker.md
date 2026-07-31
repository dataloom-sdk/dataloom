# Circuit-aware queue worker

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. Expired-lease recovery, bounded queue
> processing, and follow-up scheduling now have an additive circuit-aware
> coordination path with explicit DataLoomBuilder/facade adoption.
> Scheduler-circuit policy, production KMP iOS persistence, observability,
> administration, and end-to-end
> qualification remain open.

## Overview

`CircuitBreakerQueueWorkerCoordinator` coordinates one bounded queue-worker
cycle while preserving recovery, processing, and scheduling as separate
operational evidence boundaries.

One `run()` call performs at most:

1. one optional circuit-protected expired-lease recovery operation;
2. one circuit-aware bounded acquisition and transition cycle; and
3. one follow-up scheduler operation after normal processing.

The coordinator never loops, re-acquires the queue, or automatically replays an
operation whose provider outcome is already known to have executed.

## Public contracts

Package: `io.dataloom.runtime.worker`

- `CircuitBreakerQueueWorkerRecoveryResult`
- `CircuitBreakerQueueWorkerRunResult`
- `CircuitBreakerQueueWorkerCoordinator`
- `CircuitBreakerQueueWorkerRuntime`

An internal queue-processing seam keeps coordinator tests deterministic without
adding a host-replaceable public execution engine. The public production path
uses `CircuitBreakerDurableQueueExecutionProcessor` through the coordinator's
production constructor and `CircuitBreakerQueueWorkerRuntime`.

## Recovery evidence

`CircuitBreakerQueueWorkerRecoveryResult` distinguishes:

- `NotRequested` — recovery was disabled;
- `Completed` — provider recovery succeeded and circuit recording was accepted;
- `PreExecutionStopped` — circuit permission failed before provider invocation;
- `ProviderFailure` — recovery ran and returned a canonical failure; and
- `CircuitRecordingUnconfirmed` — recovery succeeded, but the subsequent
  circuit-state update was not accepted.

Only `NotRequested` and `Completed` allow acquisition to begin.

A successful recovery followed by an unconfirmed circuit write is not converted
into a provider failure. The provider result and record result remain visible,
and the worker stops before acquisition to avoid compounding uncertain circuit
state.

## Processing evidence

The coordinator accepts the enriched `CircuitBreakerQueueProcessingResult`.
Scheduling is allowed only after:

- `NoWork`; or
- `Processed`.

The following processing variants stop the worker without scheduling:

- `PreExecutionStopped`;
- `ProviderFailure`;
- `CircuitRecordingUnconfirmed`; and
- `QueueContractViolation`.

This preserves the exact point at which the cycle stopped and prevents a
scheduler wake-up from being presented as continuation evidence after an
unconfirmed queue transition.

## Scheduling boundary

Scheduling remains a separate provider boundary.

- `QueueWorkerConfiguration.schedulerProviderTimeout` controls only the
  follow-up scheduler call.
- A null timeout preserves direct scheduler invocation.
- A zero timeout prevents scheduler invocation.
- A positive timeout cooperatively cancels an in-flight scheduler call.
- Caller cancellation propagates unchanged.
- Queue-provider circuit scopes are not silently reused as scheduler circuit
  policy.

A scheduler failure after normal queue processing does not roll back confirmed
queue transitions.

## Explicit scope model

The caller supplies:

- one exact recovery scope for `queue.recover-expired-leases`; and
- one `QueueProcessingCircuitScopes` value for acquisition, completion,
  reschedule, deferral, failure, and cancellation.

Provider-bearing scopes must identify the protected queue provider.
Operation-bearing scopes must identify the exact queue operation.

No global, workflow, tenant, provider, or operation fallback is inferred.
Scope mismatch is rejected during construction before state-store access,
provider invocation, clock reads, processing, or scheduling.

## Production assembly

`CircuitBreakerQueueWorkerRuntime.create(...)` constructs:

1. one `CircuitBreakerQueueOperationAdapter` shared by recovery and processing;
2. one `CircuitBreakerDurableQueueExecutionProcessor`; and
3. one `CircuitBreakerQueueWorkerCoordinator`.

Sharing the adapter prevents recovery and acquisition/transitions from
accidentally using different queue providers, classifiers, or circuit gates.

A `TimeoutEnforcingQueueProvider` may be supplied as the queue provider. The
resulting timeout and circuit layers preserve both durable-ambiguity and
post-execution circuit-recording evidence.

Construction performs no provider operation, state-store access, queue mutation,
clock read, scheduling, processing, identifier generation, or coroutine launch.

## Cancellation and exceptions

Caller cancellation from recovery, processing, the timeout boundary, the
scheduler, or the clock propagates normally. It is never converted into a
provider failure or circuit outcome.

Unexpected provider, handler, store, or scheduler exceptions also propagate.
The circuit-aware worker operates on canonical provider results; it does not
silently translate arbitrary exceptions.

## Safety rules

- An open recovery circuit prevents recovery provider invocation.
- Recovery must complete with accepted circuit recording before processing.
- Processing must complete normally before scheduling.
- A provider operation is invoked at most once after permission.
- Confirmed queue transitions are never rolled back after scheduler failure.
- An executed operation is never automatically replayed because later circuit
  recording failed.
- Results carry bounded operational evidence only; payloads, credentials,
  headers, provider instances, and arbitrary metadata are excluded.

## DataLoomBuilder adoption

Applications can expose this capability through
`DataLoomBuilder.circuitQueueWorkerConfiguration(...)` and
`DataLoom.circuitQueueWorker`. The builder requires an explicit durable circuit
state store and exact recovery/acquisition/transition scopes. See
[DataLoomBuilder circuit-aware queue worker](./builder-circuit-queue-worker.md).

## Current limitations

This slice does not complete DL-040. V1 still requires:

- circuit protection for queue-worker scheduling where configured;
- transport and storage circuit/timeout assembly;
- protocol-specific connection, request, and idle timeout adapters;
- production KMP iOS retry/circuit persistence;
- retry and circuit events, metrics, logs, traces, and support diagnostics;
- authorized manual retry, reclassification, circuit open/close/reset;
- multi-process, process-death, contention, and restart qualification; and
- complete Book 2 `AC-FUNC-004` evidence.
