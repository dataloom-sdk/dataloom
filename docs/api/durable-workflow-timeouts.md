# Durable workflow timeout evidence

## Status

Partial V1 subsystem. This slice makes an accepted complete-workflow deadline
immutable across queue persistence, retry, connectivity deferral, lease
recovery, process restart, and Room reopen. Protocol-specific connection,
request, and idle timeout adapters remain separate work.

## Accepted state

`WorkflowTimeoutState` contains only two absolute instants:

- `startedAt`: when the workflow timeout window was accepted.
- `deadline`: the exclusive absolute deadline.

`WorkflowTimeoutState.from(startedAt, timeout)` derives the deadline with
saturating arithmetic. A zero timeout produces `deadline == startedAt`, so the
workflow is expired at that exact instant.

The absolute deadline is authoritative. A later runtime configuration change
must not recalculate or extend it.

## Durable queue behavior

`QueueEntry.workflowTimeoutState` is optional and appended to the existing
public constructor. When present, the state is preserved through:

- enqueue and acquisition;
- retry rescheduling;
- connectivity deferral;
- completion, failure, and cancellation records;
- expired-lease recovery;
- Android Room close and reopen.

Queue submission validates that the application-owned encoder persisted the
exact workflow timeout state supplied by `QueuedSynchronizationSubmission`.
Changing or dropping the state is a local contract violation and prevents the
queue provider from being called.

## Execution behavior

`WorkflowTimeoutStateExecutor` reads the clock once before execution:

1. `observedAt < startedAt` fails closed as clock regression.
2. `observedAt >= deadline` rejects before invoking the operation.
3. Otherwise, the operation receives exactly `deadline - observedAt` as a
   `WORKFLOW` timeout boundary.

Queued synchronization uses this executor before the execution coordinator.
An entry with persisted timeout evidence but without assembled enforcement
fails closed with a configuration error. Entries without timeout evidence keep
the historical unbounded workflow behavior.

## Android Room schema

Schema version 4 adds nullable columns:

- `workflow_started_at_ms`
- `workflow_deadline_at_ms`

`MIGRATION_3_4` adds both columns without rewriting existing queue rows.
Existing entries migrate with both values null. Partially populated persisted
state is treated as corruption and fails closed during mapping.

## Safety boundaries

- Deadline expiry does not invoke synchronization.
- Clock regression does not invoke synchronization.
- Retry, deferral, recovery, and restart cannot reset the accepted window.
- A changed timeout configuration cannot alter a persisted absolute deadline.
- Timeout errors contain no payloads, credentials, headers, exception text, or
  arbitrary metadata.
- Caller cancellation propagates through the timeout executor.
