# Outbound Push Pipeline (DL-021)

[API reference index](./README.md)

> **Status:** Available execution foundation with acknowledgement and
> progress-event integration. It is not a complete V1 synchronization strategy.

`OutboundPushSynchronizationPipeline` is the first concrete
`SynchronizationPipeline` implementation. It reads pending outbound changes
from the configured `StorageProvider`, pushes each batch through the
configured `TransportProvider`, validates the returned
`ChangeSetAcknowledgement`, and persists the acknowledgement back through
`StorageProvider`.

This pipeline implements outbound execution only. Separate runtime components
provide inbound and bidirectional execution, queue/retry orchestration,
connectivity admission, and lifecycle/operational event delivery. Complete
strategy and conflict-policy integration remains V1 work.

---

## Required flow

```text
OutboundPushSynchronizationPipeline
    → StorageProvider.readOutboundChanges()
    → TransportProvider.pushChanges()
    → validate ChangeSetAcknowledgement
    → StorageProvider.acknowledgeOutboundChanges()
    → repeat when hasMore
    → SynchronizationResult
```

---

## `OutboundPushPipelineConfiguration`

Immutable configuration bounding one pipeline execution.

```kotlin
public class OutboundPushPipelineConfiguration(
    entityTypes: Set<EntityType> = emptySet(),
    public val maxEventsPerBatch: Int = 100,
    public val maxBatchesPerExecution: Int = 100,
)
```

- `entityTypes` defaults to an empty set, meaning all supported entity types
  are eligible. The supplied set is defensively copied; no mutable collection
  is exposed.
- `maxEventsPerBatch` must be greater than zero. It bounds the number of
  events requested from storage per `OutboundChangeReadRequest`.
- `maxBatchesPerExecution` must be greater than zero. It bounds the number of
  batches attempted during one `execute` call.
- Construction performs no storage or transport operation, no clock read, and
  generates no identifiers.

The conceptual maximum number of events attempted in one execution is bounded
by `maxEventsPerBatch × maxBatchesPerExecution`. The pipeline does not
allocate a collection sized by that product; batches are processed and their
payload references released one at a time.

---

## `OutboundPushSynchronizationPipeline`

```kotlin
public class OutboundPushSynchronizationPipeline(
    private val configuration: OutboundPushPipelineConfiguration,
) : SynchronizationPipeline
```

- `direction` is `SynchronizationDirection.PUSH`, the exact existing
  outbound-only direction. This pipeline is not registered for
  `SynchronizationDirection.BIDIRECTIONAL` synchronization.
  `BidirectionalSynchronizationPipeline` provides the current ordered
  composition of this push pipeline with an inbound pull pipeline.
- Providers are obtained exclusively from
  `SynchronizationExecutionContext.providers`. The constructor does not
  receive a provider registry, lifecycle coordinator, provider resolver,
  `CoroutineScope`, dispatcher, Android `Context`, DI framework, logger,
  queue processor, or scheduler.

### Execution flow

1. Read the `SynchronizationRequest` from the execution context.
2. Obtain `StorageProvider` and `TransportProvider` from resolved providers.
3. Build an `OutboundChangeReadRequest` using the exact synchronization
   request, configured `entityTypes`, and configured `maxEventsPerBatch`.
4. Call `StorageProvider.readOutboundChanges()`.
5. When the result is `OutboundChangeReadResult.NoChanges` and no batch has
   been processed yet, return `SynchronizationResult.Skipped` with
   `SynchronizationSkipReason.NO_CHANGES`.
6. When the result is `OutboundChangeReadResult.Changes`:
   - Verify the returned `ChangeSet.id` has not already been processed during
     this execution (duplicate-batch protection).
   - Call `TransportProvider.pushChanges(PushChangesRequest(request, changeSet))`.
   - Validate the returned `ChangeSetAcknowledgement` against the pushed
     `ChangeSet`.
   - Call `StorageProvider.acknowledgeOutboundChanges(OutboundChangeAcknowledgementRequest(request, acknowledgement))`.
   - Update summary counters from the acknowledgement's event statuses.
7. Continue reading additional batches only while `hasMore` is `true` and the
   configured `maxBatchesPerExecution` has not been reached.
8. Construct and return the appropriate `SynchronizationResult`.

Batches are processed strictly sequentially. This pipeline never performs
parallel pushes.

---

## No-changes behavior

- First read returns `NoChanges` → `SynchronizationResult.Skipped` with
  `SynchronizationSkipReason.NO_CHANGES`, the original `SynchronizationRequest`,
  the terminal timestamp from `RuntimeDependencies.clock`, and a zero-valued
  `SynchronizationSummary`. No transport call and no acknowledgement call
  occur.
- A later read (after one or more batches were already processed) returns
  `NoChanges` → the pipeline finishes using `Succeeded` or
  `PartiallySucceeded` according to accumulated acknowledgement outcomes. It
  never returns `Skipped` after actual outbound work occurred.

---

## Duplicate batch protection

`ChangeSetId` values processed during the current `execute` call are tracked
locally. If storage returns the same `ChangeSetId` again during that call:

- The pipeline does not push it a second time.
- Execution stops.
- `SynchronizationResult.Failed` is returned with a canonical, safe
  `DataLoomError` (`DL-OUTBOUND-DUPLICATE-BATCH`) representing a
  provider-contract violation. The error contains no payload content.
- The summary from previously completed batches is preserved.

This protection is local to a single `execute` call; no persistent
deduplication storage is introduced.

---

## Transport push and provider failures

Each batch is pushed exactly once using:

```kotlin
TransportProvider.pushChanges(
    PushChangesRequest(request = synchronizationRequest, changeSet = changeSet),
)
```

When push returns `ProviderOperationResult.Failure`:

- Processing stops; no acknowledgement call occurs for that batch.
- Later batches are not read.
- `SynchronizationResult.Failed` is returned with the exact canonical
  `DataLoomError` preserved, previously completed summary counters retained,
  and the terminal timestamp obtained from `RuntimeDependencies.clock`.

`RetryPolicy` is never invoked. No retry, queue, or scheduler work is
performed by this pipeline.

---

## Acknowledgement validation

Before persisting an acknowledgement, the pipeline validates:

1. `acknowledgement.changeSetId` matches the pushed `ChangeSet.id`.
2. Every pushed change-event ID appears exactly once in the acknowledgement.
3. No acknowledgement references an unknown change-event ID.
4. No pushed event acknowledgement is missing.
5. The acknowledgement contains no duplicate event IDs (also enforced by the
   `ChangeSetAcknowledgement` constructor).

When validation fails:

- `StorageProvider.acknowledgeOutboundChanges()` is never called.
- Later batches are not processed.
- `SynchronizationResult.Failed` is returned with a safe canonical
  `DataLoomError` (one of `DL-OUTBOUND-ACK-CHANGESET-MISMATCH`,
  `DL-OUTBOUND-ACK-DUPLICATE-EVENT`, `DL-OUTBOUND-ACK-UNKNOWN-EVENT`, or
  `DL-OUTBOUND-ACK-MISSING-EVENT`) that exposes no payload content,
  credentials, checkpoint tokens, or personal data.

---

## Acknowledgement persistence and at-least-once delivery

After successful validation:

```kotlin
StorageProvider.acknowledgeOutboundChanges(
    OutboundChangeAcknowledgementRequest(request = synchronizationRequest, acknowledgement = acknowledgement),
)
```

The complete acknowledgement is persisted, including `ACCEPTED`, `RETRY`, and
`REJECTED` entries. `StorageProvider` remains responsible for mapping
acknowledgement statuses to application-owned outbound state.

When acknowledgement persistence fails:

- Processing stops; later batches are not processed.
- `SynchronizationResult.Failed` is returned with the exact canonical
  `DataLoomError` preserved and previously completed summary counters
  retained.

**Transport may have accepted a batch even though local acknowledgement
persistence failed.** This creates **at-least-once delivery** behavior.
Transport implementations and backend APIs should support idempotency using
stable `ChangeSetId` and change-event ID values. This pipeline does not claim
exactly-once delivery.

---

## Event-level acknowledgement statuses

After acknowledgement persistence succeeds, the pipeline classifies event
statuses and updates the corresponding `SynchronizationSummary` outbound
counters (`outboundEventsRead`, `outboundEventsAccepted`,
`outboundEventsMarkedForRetry`, `outboundEventsRejected`):

- **`ACCEPTED`** — the event contributes to a fully successful batch.
- **`RETRY`** or **`REJECTED`** — the acknowledgement-provided `DataLoomError`
  is retained when present. When a non-accepted acknowledgement has no error,
  a safe canonical runtime error describing the status is created; it never
  exposes payload content.

No retry is executed or scheduled. `RetryPolicy`, `SchedulerProvider`, and
`QueueProvider` are never invoked as a result of event-level statuses.

---

## Result classification

- **`Succeeded`** — every processed event was acknowledged `ACCEPTED`.
- **`PartiallySucceeded`** — at least one processed event was `RETRY` or
  `REJECTED`, or the per-execution batch limit was reached while more
  changes remained available. The `errors` collection is guaranteed
  non-empty.
- **`Failed`** — a canonical provider failure or acknowledgement-validation
  failure occurred. The exact canonical `DataLoomError` is preserved.
- **`Skipped`** — the first storage read reported no changes.

The terminal timestamp for every result is obtained from
`RuntimeDependencies.clock`; the pipeline never reads a system clock
directly. The original `SynchronizationRequest` is preserved unchanged in
every result.

---

## Batch-limit behavior

When `maxBatchesPerExecution` is reached and the most recently processed read
result reported `hasMore = true`:

- No additional storage read occurs.
- `SynchronizationResult.PartiallySucceeded` is returned.
- A safe, recoverable canonical `DataLoomError`
  (`DL-OUTBOUND-BATCH-LIMIT-REACHED`) is included, indicating that the
  per-execution batch limit was reached.
- All completed summary counters are preserved.
- No follow-up work is scheduled automatically. The caller or a later runtime
  orchestration issue may initiate another execution.

---

## Cancellation and exception boundary

`CancellationException` raised by `StorageProvider.readOutboundChanges`,
`TransportProvider.pushChanges`, or
`StorageProvider.acknowledgeOutboundChanges` propagates normally. It is never
converted into `SynchronizationResult.Failed`, `Cancelled`,
`PartiallySucceeded`, a `DataLoomError`, or an acknowledgement status.
Unexpected programming exceptions are not caught or converted into canonical
failures.

---

## Provider and lifecycle boundaries

This pipeline calls only:

- `StorageProvider.readOutboundChanges`
- `TransportProvider.pushChanges`
- `StorageProvider.acknowledgeOutboundChanges`

It does not call provider `initialize`, `health`, or `close`; does not call
`StorageProvider.applyInboundChanges`, `readCheckpoint`, or `writeCheckpoint`;
does not call `TransportProvider.pullChanges`; and does not invoke
`SchedulerProvider`, `ConnectivityProvider`, `QueueProvider`, `RetryPolicy`,
`ConflictDetector`, `ConflictResolver`, or any `SynchronizationObserver`.
Provider lifecycle and binding resolution are enforced by the execution
coordinator, not duplicated here.

Outbound push does not read, write, or advance synchronization checkpoints.
Checkpoint handling belongs to inbound pull/apply processing.

---

## Event-dispatch boundary

When an event emitter is present in the execution context, this pipeline emits
phase changes and durable batch-boundary progress. The execution coordinator
owns `Started` and `Completed`; the separate dispatcher owns observer delivery.
No component in this path supplies event persistence, replay, bounded
back-pressure, or streaming adapters.

---

## Performance and security

- Processes one batch at a time; avoids retaining completed `ChangeSet`
  payloads and avoids copying `DataLoomPayload` bytes.
- Uses configured batch limits; avoids parallel push fan-out and unbounded
  loops.
- Performs no blocking thread operation and selects no dispatcher.
- Diagnostics never expose `DataLoomPayload` bytes, credentials,
  authorization headers, checkpoint tokens, encryption keys, personal data,
  stack traces, provider internal state, or complete provider `toString()`
  output. Errors may contain structural identifiers such as `ChangeSetId`,
  `ChangeEventId`, `ProviderId`, and `ErrorCode`.

---

## KMP compatibility

Uses Kotlin standard-library and DataLoom API, core, and runtime types only.
No Android API, JVM-only API, reflection, `ServiceLoader`, or DI framework is
required. Safe for use in Kotlin Multiplatform common code.

---

## Example

```kotlin
val configuration = OutboundPushPipelineConfiguration(
    entityTypes = setOf(EntityType("Order")),
    maxEventsPerBatch = 100,
    maxBatchesPerExecution = 25,
)

val pipeline = OutboundPushSynchronizationPipeline(configuration)

val registry = SynchronizationPipelineRegistry(listOf(pipeline))
```
