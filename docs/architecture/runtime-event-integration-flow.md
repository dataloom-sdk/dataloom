# Runtime Event Integration Flow (DL-029)

This document describes the synchronization lifecycle event integration flow
introduced in `dataloom-runtime` by DL-029.

---

## Overview

DL-029 wires lifecycle event emission into the DataLoom execution runtime.
`SynchronizationEvent.Started`, `SynchronizationEvent.PhaseChanged`, and
`SynchronizationEvent.Completed` are dispatched at deterministic operation
boundaries during synchronization execution.

This integration builds on the event dispatcher and observer registry
introduced in DL-028.

---

## Accepted execution flow

```text
SynchronizationExecutionCoordinator.execute(request, bindings)
    → Check provider lifecycle state
    → Resolve provider bindings
    → Locate SynchronizationPipeline
    → Construct SynchronizationExecutionContext (with optional emitter)
    → emitStarted(context)
    → SynchronizationPipeline.execute(context)
        → pipeline emits PhaseChanged events before supported operations
        → pipeline returns SynchronizationResult
    → emitCompleted(context, result)
    → return SynchronizationExecutionResult.Executed(result)
```

---

## Rejected execution flow

```text
SynchronizationExecutionCoordinator.execute(request, bindings)
    → lifecycle/provider/pipeline validation fails
    → return SynchronizationExecutionResult.Rejected(reason)
    → (no Started event)
    → (no PhaseChanged event)
    → (no Completed event)
    → (no event identifier generated)
    → (no clock read)
```

Rejection reasons that suppress all lifecycle events:

| Reason | Description |
|---|---|
| `PROVIDERS_NOT_INITIALIZED` | Provider lifecycle coordinator is not initialized |
| `PROVIDER_RESOLUTION_FAILED` | Bindings could not be resolved to registered providers |
| `PIPELINE_NOT_FOUND` | No pipeline is registered for the requested direction |

---

## Thrown CancellationException flow

```text
emitStarted(context)                 ← CancellationException
    → propagates normally
    → pipeline is NOT executed
    → emitCompleted is NOT called
    → no Completed event
```

```text
pipeline.execute(context)            ← CancellationException
    → propagates normally
    → emitCompleted is NOT called
    → no Completed event
    → cancellation is NOT converted into SynchronizationResult.Cancelled
```

```text
emitCompleted(context, result)       ← CancellationException
    → propagates normally
    → synchronization business work has already completed
    → caller may not receive SynchronizationExecutionResult.Executed
    → no second Completed event is attempted
    → event delivery is not cancellation-proof
```

---

## Outbound push pipeline phase flow

```text
OutboundPushSynchronizationPipeline.execute(context)
    (loop begins)
    → emitPhaseChanged(context, READING_OUTBOUND)   ← before each batch read
    → storageProvider.readOutboundChanges(...)
    → (no more changes: exit loop)
    → emitPhaseChanged(context, PUSHING)             ← before transport push
    → transportProvider.pushChanges(...)
    → emitPhaseChanged(context, ACKNOWLEDGING_OUTBOUND) ← before ack persistence
    → storageProvider.acknowledgeOutboundChanges(...)
    (loop to next batch or finish)
    → return SynchronizationResult
```

`READING_OUTBOUND` is emitted at the start of every batch iteration. When
no changes are found on the first read, the loop exits without emitting
`PUSHING` or `ACKNOWLEDGING_OUTBOUND`.

There is no `READING_CHECKPOINT` phase in the current `SynchronizationPhase`
contract. No synthetic phase is invented for the inbound checkpoint read.

---

## Inbound pull pipeline phase flow

```text
InboundPullSynchronizationPipeline.execute(context)
    → (checkpoint read: no phase — READING_CHECKPOINT not in contract)
    → emitPhaseChanged(context, PULLING)             ← before transport pull
    → transportProvider.pullChanges(...)
    → (no changes: skip apply)
        → emitPhaseChanged(context, WRITING_CHECKPOINT) ← before no-change checkpoint write
        → storageProvider.writeCheckpoint(...)
        → return SynchronizationResult
    → emitPhaseChanged(context, APPLYING_INBOUND)    ← before storage apply
    → storageProvider.applyInboundChanges(...)
    → emitPhaseChanged(context, WRITING_CHECKPOINT)  ← before post-apply checkpoint write
    → storageProvider.writeCheckpoint(...)
    → return SynchronizationResult
```

**Unsupported boundary:** The inbound checkpoint read that occurs before the
pull has no corresponding `SynchronizationPhase` value. DL-029 does not emit
a phase for this operation and does not introduce a synthetic phase.

---

## Bidirectional pipeline phase flow

`BidirectionalSynchronizationPipeline` delegates to child outbound and inbound
pipeline instances. Each child pipeline is responsible for its own phase
events. The coordinator emits exactly one Started event and exactly one
Completed event around the bidirectional execution.

```text
SynchronizationExecutionCoordinator
    → emitStarted(context)                            ← once only
    → BidirectionalSynchronizationPipeline.execute(context)
        → OutboundPushSynchronizationPipeline (child)
            → READING_OUTBOUND phase
            → PUSHING phase
            → ACKNOWLEDGING_OUTBOUND phase
        → InboundPullSynchronizationPipeline (child)
            → PULLING phase
            → APPLYING_INBOUND phase
            → WRITING_CHECKPOINT phase
        → return combined SynchronizationResult
    → emitCompleted(context, result)                  ← once only
    → return SynchronizationExecutionResult.Executed
```

No duplicate Started or Completed events are emitted for bidirectional
synchronization. Direction-transition phases are not invented; only existing
`SynchronizationPhase` values are used.

---

## Observer failure isolation flow

```text
emitStarted(context)
    → dispatcher dispatches to registered observers
    → Observer A: success
    → Observer B: throws ordinary Exception
        → SynchronizationObserverDispatchFailure recorded
        → delivery continues to C
    → Observer C: success
    → SynchronizationEventDispatchResult.PartiallyDelivered returned
    → lifecycle integration ignores structured result
    → pipeline execution continues normally
    → SynchronizationResult is unchanged
    → SynchronizationExecutionResult is unchanged
```

Ordinary observer failures (represented as structured
`SynchronizationEventDispatchResult` values) never:

- Stop synchronization.
- Replace `SynchronizationResult`.
- Trigger retry.
- Trigger queue rescheduling.
- Trigger event redispatch.

`CancellationException` propagates normally and is never isolated.

---

## Event ordering invariants

For one accepted synchronization execution:

```text
Started
    → PhaseChanged (READING_OUTBOUND)       — before each outbound read
    → PhaseChanged (PUSHING)                — before transport push
    → PhaseChanged (ACKNOWLEDGING_OUTBOUND) — before ack persistence
    → PhaseChanged (PULLING)                — before transport pull
    → PhaseChanged (APPLYING_INBOUND)       — before inbound apply
    → PhaseChanged (WRITING_CHECKPOINT)     — before checkpoint write
    → Completed
```

| Rule | Description |
|---|---|
| Started is first | No lifecycle event precedes Started |
| Completed is last | No lifecycle event follows Completed |
| Phase order | Phase events occur in the same order as provider operations |
| Distinct IDs | Each event receives a unique generated identifier |
| Clock reads | Each event's timestamp is read at emission time, in emission order |
| No cross-execution ordering | Concurrent executions may interleave events |

---

## Queued synchronization behavior

`QueuedSynchronizationExecutionHandler` invokes
`SynchronizationExecutionCoordinator`. Accepted executions originating from
the durable queue processor emit lifecycle events through the standard
coordinator flow described above.

No queue-specific Started or Completed events are emitted. Queue acquisition,
transition persistence, lease recovery, and queue-processing cycles do not
emit lifecycle events directly.

---

## DL-029 scope boundary

DL-029 does not emit:

| Event | Status |
|---|---|
| `ProgressUpdated` | Not implemented (deferred) |
| `RetryScheduled` | Not implemented (deferred) |
| `ConflictDetected` | Not implemented (deferred) |
| Queue-specific events | Not implemented (deferred) |

DL-029 does not implement:

- Event persistence or replay
- Event buffering or history
- `Flow`, `StateFlow`, `SharedFlow`, or `Channel` adapters
- Background event delivery
- Parallel observer delivery
- Analytics integration
- Logging provider integration

---

## Dependency chain

```text
DL-016 — SynchronizationEvent contracts
DL-017 — DataLoomClock, IdentifierGenerator
DL-020 — SynchronizationExecutionCoordinator
DL-021 — OutboundPushSynchronizationPipeline
DL-022 — InboundPullSynchronizationPipeline
DL-023 — BidirectionalSynchronizationPipeline
DL-028 — SynchronizationEventDispatcher, SynchronizationObserverRegistry
DL-029 — SynchronizationLifecycleEventEmitter (this document)
```

---

## Concurrency limitations

- No observer executes in parallel.
- No background fan-out occurs.
- The emitter owns no `CoroutineScope`.
- Thread selection is the caller's responsibility.
- Concurrent synchronization executions may interleave lifecycle events.
- No global cross-execution event serialization is introduced.

---

## Security

All event-building, diagnostic, and `toString()` paths must never expose:

- `DataLoomPayload` bytes
- Local or remote conflict payloads
- `SynchronizationRequest` metadata values
- Credentials or authorization headers
- Checkpoint tokens or encryption keys
- Personal data
- Stack traces
- Provider implementation state
- Observer implementation state
- Provider or observer `toString()` output

Safe diagnostics:

- `SynchronizationEventId`
- Synchronization request ID
- `SynchronizationPhase`
- Result variant name
- Event variant name
- Dispatch-result counts

---

## Kotlin Multiplatform compatibility

All production and test code is `commonMain`-compatible. No Android API,
JVM-only API, reflection, `ServiceLoader`, or DI framework is used.
