# Inbound Pull Pipeline (DL-022)

[API reference index](./README.md)

> **Status:** Available execution foundation with checkpoint and progress-event
> integration, plus optional best-effort conflict detection (see
> [Conflict detection](#conflict-detection)). It is not a complete V1
> synchronization strategy.

`InboundPullSynchronizationPipeline` is the second concrete
`SynchronizationPipeline` implementation. It reads the stored synchronization
checkpoint once, pulls inbound change batches through the configured
`TransportProvider`, applies each batch through the configured
`StorageProvider`, and advances the checkpoint only after the corresponding
inbound changes have been applied successfully.

This pipeline implements inbound pull, apply, and checkpoint coordination
only. Separate runtime components provide outbound and bidirectional execution,
queue/retry orchestration, connectivity admission, and lifecycle/operational
event delivery. Complete strategy and conflict-policy integration remains V1
work.

---

## Required flow

```text
InboundPullSynchronizationPipeline
    → StorageProvider.readCheckpoint()
    → TransportProvider.pullChanges(checkpoint)
    → StorageProvider.applyInboundChanges()
    → StorageProvider.writeCheckpoint(nextCheckpoint)
    → repeat when hasMore
    → SynchronizationResult
```

---

## `InboundPullPipelineConfiguration`

Immutable configuration bounding one pipeline execution.

```kotlin
public class InboundPullPipelineConfiguration(
    entityTypes: Set<EntityType> = emptySet(),
    public val maxEventsPerBatch: Int = 100,
    public val maxBatchesPerExecution: Int = 100,
)
```

- `entityTypes` defaults to an empty set, meaning all supported entity types
  are eligible. The supplied set is defensively copied; no mutable collection
  is exposed.
- `maxEventsPerBatch` must be greater than zero. It bounds the number of
  events requested from the remote endpoint per `PullChangesRequest`.
- `maxBatchesPerExecution` must be greater than zero. It bounds the number of
  batches attempted during one `execute` call.
- Construction performs no storage or transport operation, no clock read, and
  generates no identifiers.

The conceptual maximum number of events attempted in one execution is bounded
by `maxEventsPerBatch × maxBatchesPerExecution`. The pipeline does not
allocate a collection sized by that product; batches are processed and their
payload references released one at a time.

---

## `InboundPullSynchronizationPipeline`

```kotlin
public class InboundPullSynchronizationPipeline(
    private val configuration: InboundPullPipelineConfiguration,
    private val conflictDetection: InboundPullConflictDetectionConfiguration? = null,
) : SynchronizationPipeline
```

- `direction` is `SynchronizationDirection.PULL`, the exact existing
  inbound-only direction. This pipeline is not registered for
  `SynchronizationDirection.BIDIRECTIONAL` synchronization.
  `BidirectionalSynchronizationPipeline` provides the current ordered
  composition of this pull pipeline with an outbound push pipeline.
- Providers are obtained exclusively from
  `SynchronizationExecutionContext.providers`. The constructor does not
  receive a provider registry, lifecycle coordinator, provider resolver,
  `CoroutineScope`, dispatcher, Android `Context`, DI framework, logger,
  queue processor, or scheduler.
- `conflictDetection` is optional and `null` by default — see
  [Conflict detection](#conflict-detection).

---

## Checkpoint read

The stored checkpoint is read exactly once at the beginning of each `execute`
call using `StorageProvider.readCheckpoint()` with a key derived from
`SynchronizationRequest.workflowId`. This ensures a consistent starting point
for the entire execution.

When `readCheckpoint` returns `null`:

- The first `PullChangesRequest` is built with `checkpoint = null`.
- No checkpoint is manufactured.

When `readCheckpoint` returns a stored checkpoint:

- The checkpoint is passed unchanged to the first `PullChangesRequest`.
- The token is never transformed or inspected beyond passing it to pull.

When `readCheckpoint` returns a failure:

- `TransportProvider.pullChanges()` is never called.
- `SynchronizationResult.Failed` is returned with the exact provider
  `DataLoomError` and an empty summary.

---

## Pull request and checkpoint propagation

Every `PullChangesRequest` preserves:

- the exact `SynchronizationRequest`
- configured entity types
- configured `maxEventsPerBatch`
- the current checkpoint (null until a checkpoint is first persisted)

After a next checkpoint has been successfully persisted via
`StorageProvider.writeCheckpoint()`, that checkpoint is used as the current
checkpoint for the next `PullChangesRequest`. A checkpoint that has not been
persisted successfully is never used as the next pull request's checkpoint.

---

## Inbound change application

For every `PullChangesResult.Changes`:

- the exact returned `ChangeSet` is applied through
  `StorageProvider.applyInboundChanges()`.
- the `ChangeSet` is not mutated.
- opaque payload contents are not inspected.
- `applyInboundChanges()` is called exactly once for each batch.
- `writeCheckpoint()` is never called before `applyInboundChanges()` succeeds.

When `applyInboundChanges` returns failure:

- `writeCheckpoint()` is not called.
- No additional pull is attempted.
- `SynchronizationResult.Failed` is returned with the exact provider
  `DataLoomError`.
- Summary evidence from previously completed batches is preserved.

---

## Apply-before-checkpoint invariant

The mandatory order is:

```text
TransportProvider.pullChanges()
    → StorageProvider.applyInboundChanges()
    → StorageProvider.writeCheckpoint()
```

This invariant is made explicit in the execution sequence and verified by
tests. It is never possible for a checkpoint associated with a `Changes`
result to be written before that `ChangeSet` has been applied successfully.

A concrete provider may implement both `applyInboundChanges` and
`writeCheckpoint` using the same physical database, but the DataLoom contract
sequence remains explicit.

---

## Checkpoint advancement

After successful application:

- when `nextCheckpoint` is non-null, `writeCheckpoint()` is called.
- when `nextCheckpoint` is null, no checkpoint write occurs.
- checkpoint key and token are preserved exactly.
- checkpoint token values are never logged or included in error messages.

When checkpoint persistence fails after apply succeeds:

- execution stops.
- `SynchronizationResult.Failed` is returned with the exact provider
  `DataLoomError`.
- Summary counters show that the batch was received and applied.
- No additional pull is attempted.
- No automatic rollback of application data occurs.

**At-least-once inbound application:** The same inbound changes may be
delivered again because the application succeeded but checkpoint advancement
failed. Application storage adapters and backend change models must therefore
support idempotent inbound application. This pipeline does not claim
exactly-once delivery.

---

## No-changes checkpoint behavior

When the first pull returns `PullChangesResult.NoChanges`:

- `applyInboundChanges()` is never called.
- When `nextCheckpoint` is null, no checkpoint write occurs and
  `Skipped(NO_CHANGES)` is returned.
- When `nextCheckpoint` is non-null, `writeCheckpoint()` is called. Only
  after the write succeeds is `Skipped(NO_CHANGES)` returned.
- When the checkpoint write fails, `Failed` is returned with the exact
  provider error.

When a later pull (after at least one batch was applied) returns
`NoChanges`, the same checkpoint rules apply, but the result is `Succeeded`
rather than `Skipped`.

---

## Paging and `hasMore` behavior

When `hasMore` is false:

- execution finishes after successful apply and any required checkpoint write.
- no additional pull is attempted.

When `hasMore` is true:

- execution continues only after apply succeeds and any required checkpoint
  write succeeds.
- the successfully persisted checkpoint is used for the next pull.

When `hasMore` is true but `nextCheckpoint` is null:

- execution stops.
- `SynchronizationResult.Failed` is returned with a safe provider-contract
  error.
- no repeated pull occurs using the unchanged checkpoint.

This prevents a non-progressing or infinite pull loop.

---

## Duplicate-batch protection

`ChangeSetId` values encountered during one `execute` call are tracked
locally. When `TransportProvider` returns the same `ChangeSetId` again:

- the duplicate batch is not applied.
- no checkpoint is written for it.
- execution stops.
- `SynchronizationResult.Failed` is returned with a safe provider-contract
  violation error.
- previously completed summary counters are preserved.

This is execution-local protection only. No persistent deduplication storage
is introduced.

---

## Batch-limit behavior

When `maxBatchesPerExecution` is reached and the last successfully processed
result had `hasMore = true`:

- no additional pull occurs.
- `SynchronizationResult.PartiallySucceeded` is returned.
- a safe, recoverable `DataLoomError` (`DL-INBOUND-BATCH-LIMIT-REACHED`) is
  included.
- the last successfully written checkpoint is preserved.
- completed summary counters are preserved.
- no follow-up work is scheduled automatically; no retry, queue, or scheduler
  operation occurs.

---

## Result classification

| Condition | Result |
|---|---|
| First pull has no changes; any no-change checkpoint write succeeds | `Skipped(NO_CHANGES)` |
| At least one batch applied; all checkpoints persisted; no partial condition | `Succeeded` |
| One or more batches applied; batch limit reached while `hasMore = true` | `PartiallySucceeded` |
| Checkpoint read fails | `Failed` |
| Pull fails | `Failed` |
| Apply fails | `Failed` |
| Checkpoint write fails | `Failed` |
| Duplicate `ChangeSetId` returned | `Failed` |
| `hasMore = true` with no `nextCheckpoint` | `Failed` |

The terminal timestamp for every result is obtained from
`RuntimeDependencies.clock`; no system clock is accessed directly. The
original `SynchronizationRequest` is preserved unchanged in every result.

---

## Summary construction

`SynchronizationSummary` fields populated by this pipeline:

- `inboundEventsReceived` — incremented when a valid `Changes` result is
  accepted for processing (before apply).
- `inboundEventsApplied` — incremented after `applyInboundChanges` succeeds.
- `conflictsDetected` — incremented per event when conflict detection is
  enabled and the orchestration outcome indicates a genuine detected
  conflict (see [Conflict detection](#conflict-detection)); `0` when
  `conflictDetection` is not supplied.

Unrelated outbound counters remain zero. All invariants required by
`SynchronizationSummary` are satisfied.

---

## Provider failure handling

Provider failures are returned as `ProviderOperationResult.Failure` with a
canonical `DataLoomError`. This pipeline:

- never invokes `RetryPolicy`.
- never schedules or enqueues follow-up work.
- preserves the exact provider `DataLoomError` instance in the result.

---

## Cancellation propagation

`CancellationException` raised by `readCheckpoint`, `pullChanges`,
`applyInboundChanges`, or `writeCheckpoint` propagates normally. It is never
converted into `Failed`, `Cancelled`, `PartiallySucceeded`, a
`DataLoomError`, or a retry decision. Unexpected programming exceptions are
not caught or converted into canonical failures.

---

## Retry boundary

This pipeline does not invoke `RetryPolicy`. No retry logic is performed. An
application or a later orchestration issue is responsible for scheduling
subsequent executions when a partial or recoverable failure occurs.

---

## Queue and scheduler boundaries

This pipeline does not call `QueueProvider`, `SchedulerProvider`, or any
scheduling, enqueuing, or dequeueing operation.

---

## Conflict detection

Supplying `conflictDetection` (an `InboundPullConflictDetectionConfiguration`
— a `DurableConflictDetectionCoordinator` plus a `ConflictOrchestrationBindings`)
turns on best-effort conflict detection for inbound changes. Without it, this
pipeline behaves exactly as it did before this capability existed —
`ConflictDetector`/`ConflictResolver` are never reached, matching this
section's original text.

When supplied, for every event in an accepted `ChangeSet`, **before** that
batch is applied:

1. The pipeline reads the local conflict candidate for that event's entity via
   `StorageProvider.readLocalConflictCandidate`.
2. When a local counterpart is found, it calls
   `DurableConflictDetectionCoordinator.detectAndResolve` with the local and
   remote `ChangeEvent`s. Genuinely unresolved outcomes (no resolver
   configured or found) are durably recorded via
   [`DurableUnresolvedConflictLog`](./durable-state-contracts.md#adoption-unresolved-conflicts).

Detection is **strictly observational**: its outcome never blocks, alters, or
delays `applyInboundChanges` for the same batch — `StorageProvider.applyInboundChanges`
remains solely responsible for applying the supplied batch, unchanged from
before. A `readLocalConflictCandidate` failure is treated the same as "no
local counterpart" — detection is skipped for that one event, and the batch
application proceeds unaffected; no `SynchronizationResult` is ever failed
because of a conflict-detection-only failure.

`SynchronizationSummary.conflictsDetected` counts every event whose
orchestration outcome was not `ConflictOrchestrationResult.NoConflict` (and
was not `DetectorNotFound`, since that means detection never actually ran).
This field previously always read `0`; it is now populated for real when
conflict detection is enabled.

Detection reads one entity at a time — there is no batch-local-read
capability on `StorageProvider` — so a batch of `n` events issues up to `n`
additional `readLocalConflictCandidate` calls when `conflictDetection` is
supplied. This is a known, documented characteristic of this first slice,
not an oversight.

### Available through provider-protection and timeout wrapping consistently

`StorageProvider.readLocalConflictCandidate` has a safe default
(`NotFound`) so every pre-existing `StorageProvider` implementation compiles
and behaves unchanged without adopting it. Both decorator types in
`dataloom-runtime` that wrap an arbitrary `StorageProvider` now forward this
call correctly: `TimeoutEnforcingStorageProvider` (a pure pass-through,
timeout-wrapped like every other operation) and `ProviderProtectionStorageBridge`
(circuit-protected through a dedicated `StorageCircuitScopes.readLocalConflictCandidate`
scope, added as a breaking constructor addition — see
[durable state contracts](./durable-state-contracts.md#adoption-unresolved-conflicts)).
A wrapped provider's real `Found`/`NotFound` result passes through unchanged
when the circuit is closed; a tripped circuit rejects the call the same way
it rejects every other protected storage operation, so conflict detection
is unavailable (not silently wrong) while the circuit is open, exactly like
any other protected operation during an open circuit.

---

## Event boundary

When an event emitter is present in the execution context, this pipeline emits
phase changes and durable batch-boundary progress. The execution coordinator
owns `Started` and `Completed`; the separate dispatcher owns observer delivery.
No component in this path supplies event persistence, replay, bounded
back-pressure, or streaming adapters.

---

## KMP compatibility

Uses Kotlin standard-library and DataLoom API, core, and runtime types only.
No Android API, JVM-only API, reflection, `ServiceLoader`, or DI framework is
required. Safe for use in Kotlin Multiplatform common code.

---

## Performance restrictions

- Processes one batch at a time; avoids retaining completed `ChangeSet`
  payloads and avoids copying `DataLoomPayload` bytes.
- Uses configured batch limits; avoids parallel pull fan-out and unbounded
  loops.
- Performs no blocking thread operation and selects no dispatcher.

---

## Security and checkpoint-token redaction

Diagnostics and errors never expose:

- `DataLoomPayload` bytes
- checkpoint token values
- credentials
- authorization headers
- encryption keys
- personal data
- stack traces
- provider internal state
- complete provider `toString()` output

Structural identifiers (`ChangeSetId`, `CheckpointKey`, `ProviderId`,
`ErrorCode`) may be used safely in error messages.

---

## Example

```kotlin
val configuration = InboundPullPipelineConfiguration(
    entityTypes = setOf(EntityType("Order")),
    maxEventsPerBatch = 100,
    maxBatchesPerExecution = 25,
)

val pipeline = InboundPullSynchronizationPipeline(configuration)

val registry = SynchronizationPipelineRegistry(listOf(pipeline))
```

With conflict detection enabled (see [Conflict detection](#conflict-detection)):

```kotlin
val coordinator = DurableConflictDetectionCoordinator(
    orchestrator = SynchronizationConflictOrchestrator(detectorRegistry, resolverRegistry),
    unresolvedConflictLog = DurableUnresolvedConflictLog(store),
    clock = clock,
)

val pipeline = InboundPullSynchronizationPipeline(
    configuration = configuration,
    conflictDetection = InboundPullConflictDetectionConfiguration(
        coordinator = coordinator,
        bindings = ConflictOrchestrationBindings(detectorId, resolverId),
    ),
)
```
