# DataLoom Runtime Lifecycle Events (DL-029)

[API reference index](./README.md)

> **Status:** Available in-process lifecycle-event foundation. Durable delivery,
> replay, back-pressure, tracing, metrics, and operational read models remain.

This document defines the synchronization lifecycle event emitter and its
integration with the DataLoom execution runtime, introduced in
`dataloom-runtime` by DL-029.

These components generate and dispatch `SynchronizationEvent.Started`,
`SynchronizationEvent.PhaseChanged`, and `SynchronizationEvent.Completed`
during actual synchronization execution.

---

## Overview

DL-029 provides:

- `SynchronizationLifecycleEventEmitter` — public interface for emitting
  lifecycle events at runtime operation boundaries.
- `DispatchingSynchronizationLifecycleEventEmitter` — concrete implementation
  that generates event identifiers, reads the event clock, constructs the
  exact event variant, and dispatches it through
  `SynchronizationEventDispatcher`.
- Backward-compatible `lifecycleEventEmitter` integration in
  `SynchronizationExecutionContext`.
- Started and Completed integration in `SynchronizationExecutionCoordinator`.
- PhaseChanged integration in `OutboundPushSynchronizationPipeline` and
  `InboundPullSynchronizationPipeline`.
- PhaseChanged integration in `BidirectionalSynchronizationPipeline` through
  its child pipelines.

The current repository extends this lifecycle slice with selected
`ProgressUpdated`, scheduler-backed `RetryScheduled`, and `ConflictDetected`
integration documented in
[Runtime Operational Events](./runtime-operational-events.md). Queue-specific
operational coverage, event persistence/replay, buffering/history, streaming
adapters, background delivery, and isolated parallel consumption remain
unimplemented.

---

## SynchronizationLifecycleEventEmitter

`SynchronizationLifecycleEventEmitter` is a platform-independent interface
that defines the contract for emitting lifecycle events at runtime operation
boundaries.

### Purpose

The emitter abstracts event construction and dispatch from the calling pipeline
or coordinator. Callers supply the current `SynchronizationExecutionContext`
and, where required, additional parameters such as `SynchronizationPhase` or
`SynchronizationResult`. The emitter constructs the exact event variant and
delegates delivery to `SynchronizationEventDispatcher`.

### Methods

```kotlin
public interface SynchronizationLifecycleEventEmitter {

    public suspend fun emitStarted(
        context: SynchronizationExecutionContext,
    ): SynchronizationEventDispatchResult

    public suspend fun emitPhaseChanged(
        context: SynchronizationExecutionContext,
        phase: SynchronizationPhase,
    ): SynchronizationEventDispatchResult

    public suspend fun emitCompleted(
        context: SynchronizationExecutionContext,
        result: SynchronizationResult,
    ): SynchronizationEventDispatchResult
}
```

All methods are `suspend` functions to accommodate future asynchronous
dispatcher implementations. The current `SynchronizationEventDispatcher` is
synchronous; the suspend wrapper adds no observable overhead.

### Restrictions

The emitter must not:

- Execute synchronization work.
- Call any provider.
- Invoke retry policy.
- Schedule work.
- Modify queue state.
- Resolve conflicts.
- Modify `SynchronizationResult`.
- Persist events.
- Own a `CoroutineScope`.
- Select a coroutine dispatcher or thread.

---

## DispatchingSynchronizationLifecycleEventEmitter

`DispatchingSynchronizationLifecycleEventEmitter` is the concrete production
implementation of `SynchronizationLifecycleEventEmitter`.

### Constructor

```kotlin
public class DispatchingSynchronizationLifecycleEventEmitter(
    private val eventDispatcher: SynchronizationEventDispatcher,
    private val clock: DataLoomClock,
    private val eventIdGenerator: IdentifierGenerator,
) : SynchronizationLifecycleEventEmitter
```

All dependencies are supplied explicitly. No global clock, random UUID, system
clock, service locator, reflection, DI framework, or platform API is used.

### Event-ID generation

Exactly one `SynchronizationEventId` is generated for every emitted event by
calling `eventIdGenerator.generate()`. A distinct identifier is generated for
Started, each PhaseChanged, and Completed. Event IDs are not derived from the
request ID or concatenated from strings.

Event IDs are not generated for rejected execution preparation.

### Event timestamp generation

The injected `DataLoomClock` is read exactly once per emitted event. The
timestamp is used as the `occurredAt` field of the constructed event.

The following are never used:

- `System.currentTimeMillis()`
- `java.time`
- `kotlin.random`
- `UUID.randomUUID()`
- Android clocks
- Platform-specific date APIs

### Event construction

For each emit call:

1. Generate one `SynchronizationEventId` from `eventIdGenerator`.
2. Read `DataLoomClock` once to obtain the current timestamp.
3. Construct the exact existing event variant:
   - `SynchronizationEvent.Started` — with `id`, `request`, `occurredAt`
   - `SynchronizationEvent.PhaseChanged` — with `id`, `request`, `phase`,
     `occurredAt`
   - `SynchronizationEvent.Completed` — with `id`, `request`, `result`,
     `occurredAt`
4. Dispatch through `SynchronizationEventDispatcher`.
5. Return the exact dispatch result.

---

## Execution-context event access

`SynchronizationExecutionContext` carries an optional
`lifecycleEventEmitter: SynchronizationLifecycleEventEmitter?` parameter.

```kotlin
public class SynchronizationExecutionContext(
    public val request: SynchronizationRequest,
    public val providers: SynchronizationProviderBindings,
    public val runtimeDependencies: RuntimeDependencies,
    public val lifecycleEventEmitter: SynchronizationLifecycleEventEmitter? = null,
)
```

When `lifecycleEventEmitter` is `null`, pipelines perform no event emission.
No event identifier is generated and no clock read occurs.

This parameter is optional and defaults to `null` for backward compatibility.
Existing callers that do not supply an emitter continue to work without
modification.

---

## SynchronizationExecutionCoordinator integration

`SynchronizationExecutionCoordinator` accepts an optional
`lifecycleEventEmitter: SynchronizationLifecycleEventEmitter?` constructor
parameter.

### Accepted execution order

For every accepted synchronization execution:

```text
1. Check provider lifecycle state.
2. Resolve provider bindings.
3. Locate the synchronization pipeline.
4. Construct SynchronizationExecutionContext (with emitter when configured).
5. Dispatch Started.
6. Execute the selected pipeline exactly once.
7. Receive the exact SynchronizationResult.
8. Dispatch Completed containing that exact result.
9. Return SynchronizationExecutionResult.Executed containing the unchanged result.
```

### Rejected execution

When execution is rejected for any of the following reasons:

- `PROVIDERS_NOT_INITIALIZED`
- `PROVIDER_RESOLUTION_FAILED`
- `PIPELINE_NOT_FOUND`

No Started event is dispatched. No Completed event is dispatched. No event
identifier is generated. No clock read occurs. The exact existing rejection
result is returned unchanged.

### Cancellation during Started delivery

If `emitStarted` throws `CancellationException`, the exception propagates
normally. The selected pipeline is not executed. `emitCompleted` is not called.

### Pipeline cancellation

If the pipeline throws `CancellationException`, the exception propagates
normally. `emitCompleted` is not called. Cancellation is never converted into
`SynchronizationResult.Cancelled`.

### Unexpected pipeline exception

If the pipeline throws an unexpected exception, the exception propagates
normally. `emitCompleted` is not called. The exception is never converted into
`SynchronizationResult.Failed`.

---

## Started event

`SynchronizationEvent.Started` is dispatched exactly once per accepted
execution, immediately before the selected pipeline executes.

### Emission boundary

```text
Accepted execution preparation complete
    → emitStarted (exactly once)
    → pipeline execution begins
```

### Requirements

- Emitted after lifecycle validation, provider resolution, pipeline lookup, and
  context construction.
- Emitted before pipeline execution begins.
- Emitted exactly once per accepted execution.
- Not emitted for rejected executions.
- Not emitted twice for bidirectional synchronization.
- Preserves the exact `SynchronizationRequest` from the execution context.
- Generates a fresh `SynchronizationEventId`.
- Reads the injected `DataLoomClock` once.

### Observer failure during Started

An ordinary structured observer failure (represented through
`SynchronizationEventDispatchResult`) does not stop synchronization:

- Pipeline execution continues.
- `SynchronizationExecutionResult` is unchanged.
- No retry of event delivery occurs.
- No replacement Started event is dispatched.

`CancellationException` from Started delivery propagates normally and prevents
pipeline execution.

---

## PhaseChanged events

`SynchronizationEvent.PhaseChanged` is emitted at deterministic operation
boundaries using exact `SynchronizationPhase` values from the existing
contract.

### Outbound pipeline phases

`OutboundPushSynchronizationPipeline` emits the following phases before
the corresponding operations:

| Phase | Operation boundary |
|---|---|
| `READING_OUTBOUND` | Before reading outbound change events from storage |
| `PUSHING` | Before pushing changes to the transport provider |
| `ACKNOWLEDGING_OUTBOUND` | Before persisting acknowledgements to storage |

`READING_OUTBOUND` is emitted on every loop iteration before each batch read,
not only before the first batch.

`PUSHING` is emitted once per push batch before the transport call.

`ACKNOWLEDGING_OUTBOUND` is emitted once per acknowledgement batch before the
storage call.

### Inbound pipeline phases

`InboundPullSynchronizationPipeline` emits the following phases before the
corresponding operations:

| Phase | Operation boundary |
|---|---|
| `PULLING` | Before pulling changes from the transport provider |
| `APPLYING_INBOUND` | Before applying inbound changes to storage |
| `WRITING_CHECKPOINT` | Before writing the checkpoint to storage (both no-change and post-apply cases) |

**Unsupported boundary:** There is no `READING_CHECKPOINT` phase in the
current `SynchronizationPhase` contract. The inbound checkpoint read therefore
does not emit a phase event. No synthetic phase is invented as a replacement.

### Bidirectional pipeline phases

`BidirectionalSynchronizationPipeline` delegates to child outbound and inbound
pipeline instances. Each child pipeline emits its own phase events in order.
The coordinator emits one Started event and one Completed event; child
pipelines do not generate duplicate Started or Completed events.

### Phase requirements

- A phase event is emitted immediately before the corresponding operation.
- A phase event is not emitted after the operation has completed.
- Each phase event receives a distinct `SynchronizationEventId`.
- Each phase event reads the clock once at emission time.
- Phase events follow the actual operation order.
- No phase event is emitted after Completed.
- No phase value absent from the current contract is introduced in DL-029.

### Observer failure during PhaseChanged

An ordinary structured observer failure during a phase event does not stop
the corresponding provider operation. The operation executes normally.
Cancellation propagates normally.

---

## Completed event

`SynchronizationEvent.Completed` is dispatched exactly once after a pipeline
returns any `SynchronizationResult` variant.

### Emission boundary

```text
pipeline returns SynchronizationResult (any variant)
    → emitCompleted (exactly once)
    → return SynchronizationExecutionResult.Executed
```

### Result variants that receive Completed

| Variant | Receives Completed |
|---|---|
| `Succeeded` | Yes |
| `PartiallySucceeded` | Yes |
| `Failed` | Yes |
| `Cancelled` (returned as a normal result) | Yes |
| `Skipped` | Yes |

### Result variants that do not receive Completed

| Condition | Receives Completed |
|---|---|
| `CancellationException` thrown by pipeline | No |
| Unexpected exception thrown by pipeline | No |

A returned `SynchronizationResult.Cancelled` is a normal result and receives
Completed. A thrown `CancellationException` is not a normal result and must
not receive Completed.

### Requirements

- Dispatched exactly once.
- Preserves the exact `SynchronizationResult` instance.
- Preserves summary, errors, skip, and cancellation information unchanged.
- Generates a fresh `SynchronizationEventId`.
- Reads the injected `DataLoomClock` once.
- Does not change the result.
- Does not replace terminal timestamps already present in the result.
- No lifecycle event is emitted after Completed in DL-029.

### Cancellation during Completed delivery

If `emitCompleted` throws `CancellationException`, the exception propagates
normally. The synchronization business work has already completed. The caller
may not receive the completed synchronization result because the coroutine is
cancelled before `SynchronizationExecutionResult.Executed` is returned. No
second Completed event is attempted.

Event delivery is not cancellation-proof. Callers must account for this
possibility when cancellation may arrive during Completed dispatch.

---

## Observer failure isolation

All ordinary structured observer failures are isolated by
`SynchronizationEventDispatcher` per the DL-028 contract.

`PartiallyDelivered` and `DeliveryFailed` dispatch results must not:

- Stop synchronization.
- Replace `SynchronizationResult`.
- Change summary counters.
- Trigger retry.
- Trigger queue rescheduling.
- Trigger event redispatch.
- Cause another observer registry lookup.

The lifecycle integration ignores structured dispatch results. It does not
expose sensitive observer information.

---

## Event ordering

For one accepted synchronization execution:

```text
Started
    → zero or more PhaseChanged events (in actual operation order)
    → Completed
```

| Rule | Description |
|---|---|
| Started is first | Always the first lifecycle event for an accepted execution |
| Completed is last | Always the last lifecycle event; no event is emitted after it |
| Phase order follows operations | Phase events occur in the same order as provider operations |
| IDs generated in emission order | Each new event ID is generated when the event is emitted |
| Clocks read in emission order | Each timestamp is read when the event is emitted |

Concurrent synchronization executions may interleave events. No global
cross-execution ordering is introduced.

---

## Event-generation failure boundary

Unexpected failures from:

- `DataLoomClock`
- `IdentifierGenerator`
- Event constructor
- `SynchronizationEventDispatcher`

propagate unless the dispatcher contract returns a structured ordinary
observer failure.

Silent continuation when the runtime cannot construct a required lifecycle
event is not permitted.

Infrastructure programming failures are never converted into
`SynchronizationResult`.

---

## Queued synchronization behavior

`QueuedSynchronizationExecutionHandler` invokes the normal
`SynchronizationExecutionCoordinator`. Therefore, accepted synchronization
executions that originate from the queue processor emit the standard lifecycle
events (Started, phase events, Completed) through the coordinator.

No queue-specific duplicate Started or Completed events are emitted. No
queue-specific lifecycle events are added in DL-029.

---

## Operational-event integration

The shared runtime emitter now also supports:

- batch-boundary `ProgressUpdated` from push and pull pipelines;
- `RetryScheduled` after scheduler-backed retry acceptance; and
- `ConflictDetected` after an actual conflict is found and before resolver
  lookup.

Queue-backed retry-event emission and complete lifecycle/operational event
coverage remain incomplete. See
[Runtime Operational Events](./runtime-operational-events.md) for the exact
current boundaries.

---

## No event persistence or replay

DL-029 does not implement:

- Event persistence.
- Event replay.
- Event buffering.
- Event history.

---

## Performance characteristics

- Events are constructed only when `lifecycleEventEmitter` is non-null.
- No payload copying, result copying, or provider copying.
- No metadata serialization.
- No background fan-out.
- No `CoroutineScope` ownership.
- No dispatcher selection.
- No unbounded collection allocation.
- No polling or busy loop.
- Event payloads are never logged.
- One event is emitted per operation boundary, not per entity or payload byte.

---

## Security

Lifecycle event construction, diagnostics, and event `toString()` paths must
never expose:

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

All production and test code uses Kotlin standard-library and DataLoom API
types only. No Android API, JVM-only API, reflection, `ServiceLoader`, or DI
framework is used.

Production source: `dataloom-runtime/src/commonMain`  
Tests: `dataloom-runtime/src/commonTest`

---

## Module location

| Artifact | Location |
|---|---|
| `SynchronizationLifecycleEventEmitter` | `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/execution/lifecycle/` |
| `DispatchingSynchronizationLifecycleEventEmitter` | `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/execution/lifecycle/` |
| Integration tests | `dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/execution/lifecycle/` |
