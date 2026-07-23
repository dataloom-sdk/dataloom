# Inbound Pull Flow (DL-022)

This document describes the architectural flow and boundaries of
`InboundPullSynchronizationPipeline`, the second concrete synchronization
pipeline implementation.

---

## Sequence

```text
InboundPullSynchronizationPipeline
    → StorageProvider.readCheckpoint()
    → TransportProvider.pullChanges(checkpoint)
    → StorageProvider.applyInboundChanges()
    → StorageProvider.writeCheckpoint(nextCheckpoint)
    → repeat when hasMore
    → SynchronizationResult
```

Each batch is processed strictly sequentially: read checkpoint (once only),
pull, apply, write checkpoint. The pipeline never performs parallel pulls and
does not begin pulling the next batch until the current batch's checkpoint
has been persisted (or execution has stopped).

---

## Happy path

```text
SynchronizationExecutionCoordinator
    → SynchronizationExecutionContext(request, providers, runtimeDependencies)
    → InboundPullSynchronizationPipeline.execute(context)
        → StorageProvider.readCheckpoint(...) = Success(storedCheckpoint)
        → TransportProvider.pullChanges(checkpoint=storedCheckpoint) = Changes(changeSet1, hasMore=true, nextCheckpoint=cp1)
        → StorageProvider.applyInboundChanges(changeSet1) = Success
        → StorageProvider.writeCheckpoint(cp1) = Success
        → TransportProvider.pullChanges(checkpoint=cp1) = Changes(changeSet2, hasMore=false, nextCheckpoint=cp2)
        → StorageProvider.applyInboundChanges(changeSet2) = Success
        → StorageProvider.writeCheckpoint(cp2) = Success
        → hasMore=false → stop
    → SynchronizationResult.Succeeded(summary, completedAt=clock.now())
```

---

## Failure paths

### No checkpoint stored (first execution)

```text
StorageProvider.readCheckpoint(...) = Success(null)
    → TransportProvider.pullChanges(checkpoint=null)
    → ...
```

The pipeline uses `null` as the checkpoint. No checkpoint is manufactured.

### Checkpoint read failure

```text
StorageProvider.readCheckpoint(...) = Failure(canonicalError)
    → TransportProvider.pullChanges is NOT called
    → SynchronizationResult.Failed(error=canonicalError, summary=empty)
```

### No changes available

```text
TransportProvider.pullChanges(...) = NoChanges(nextCheckpoint=null)
    → applyInboundChanges is NOT called
    → writeCheckpoint is NOT called
    → SynchronizationResult.Skipped(reason=NO_CHANGES, completedAt=clock.now())
```

When `NoChanges` carries a `nextCheckpoint`:

```text
TransportProvider.pullChanges(...) = NoChanges(nextCheckpoint=cp)
    → StorageProvider.writeCheckpoint(cp) = Success
    → SynchronizationResult.Skipped(reason=NO_CHANGES)
```

When the no-change checkpoint write fails:

```text
TransportProvider.pullChanges(...) = NoChanges(nextCheckpoint=cp)
    → StorageProvider.writeCheckpoint(cp) = Failure(canonicalError)
    → SynchronizationResult.Failed(error=canonicalError)
```

### Pull failure

```text
TransportProvider.pullChanges(...) = Failure(canonicalError)
    → applyInboundChanges is NOT called
    → writeCheckpoint is NOT called
    → SynchronizationResult.Failed(error=canonicalError)
```

### Apply failure

```text
TransportProvider.pullChanges(...) = Changes(changeSet, ...)
StorageProvider.applyInboundChanges(changeSet) = Failure(canonicalError)
    → writeCheckpoint is NOT called (apply-before-checkpoint invariant enforced)
    → SynchronizationResult.Failed(error=canonicalError, summary shows received but not applied)
```

### Checkpoint write failure after apply

```text
StorageProvider.applyInboundChanges(changeSet) = Success
StorageProvider.writeCheckpoint(nextCheckpoint) = Failure(canonicalError)
    → SynchronizationResult.Failed(error=canonicalError, summary shows received AND applied)
```

**Apply succeeded but checkpoint advancement failed.** The same inbound
changes may be delivered again on a later execution. Application storage
adapters must support idempotent inbound application. This pipeline does not
claim exactly-once delivery.

### Paging contract violation

```text
TransportProvider.pullChanges(...) = Changes(changeSet, hasMore=true, nextCheckpoint=null)
StorageProvider.applyInboundChanges(changeSet) = Success
    → writeCheckpoint is NOT called (no checkpoint to write)
    → SynchronizationResult.Failed(error=DL-INBOUND-PAGING-CONTRACT-VIOLATION)
```

Another pull would use the unchanged checkpoint and risk an infinite loop.
This path prevents non-progressing or infinite pull loops.

### Duplicate batch detected

```text
TransportProvider.pullChanges(...) = Changes(changeSetA, hasMore=true, nextCheckpoint=cp1) // processed
TransportProvider.pullChanges(...) = Changes(changeSetA, ...)                              // same ChangeSetId again
    → SynchronizationResult.Failed(error=DL-INBOUND-DUPLICATE-BATCH)
```

The second occurrence of `changeSetA` is never applied. This protection is
local to a single `execute` call; no persistent deduplication store exists.

### Batch limit reached

```text
batchesProcessed == maxBatchesPerExecution
last pull result hasMore=true
    → no further pull
    → SynchronizationResult.PartiallySucceeded(error=DL-INBOUND-BATCH-LIMIT-REACHED)
```

No follow-up work is scheduled. No retry, queue, or scheduler operation
occurs.

### Later pull returns NoChanges (after successful batches)

```text
TransportProvider.pullChanges(...) = Changes(changeSet1, hasMore=true, ...)  // applied
TransportProvider.pullChanges(...) = NoChanges(nextCheckpoint=null)
    → SynchronizationResult.Succeeded  // not Skipped: actual work occurred
```

---

## Apply-before-checkpoint invariant

The invariant is enforced unconditionally:

```text
pullChanges()
    → applyInboundChanges()          ← must succeed first
    → writeCheckpoint()              ← called only after successful apply
```

A failed `applyInboundChanges` call terminates the execution without calling
`writeCheckpoint`. Tests verify the invocation order explicitly.

---

## Boundaries this pipeline does not cross

| Boundary | Status |
|---|---|
| Outbound push | Not implemented (DL-021) |
| Bidirectional composition | Not implemented (DL-023) |
| `RetryPolicy` execution | Not invoked |
| Queue acquire/complete/reschedule | Not invoked |
| `SchedulerProvider` | Not invoked |
| `ConnectivityProvider` | Not invoked |
| Conflict detection/resolution | Not invoked (DL-014 contracts exist but are not executed) |
| Event dispatch / observer registry | Not implemented (deferred) |
| Provider `initialize` / `health` / `close` | Not invoked (owned by execution coordinator and lifecycle coordinator) |

---

## Delivery semantics

`InboundPullSynchronizationPipeline` provides **at-least-once** inbound
application. It does not claim exactly-once delivery: `applyInboundChanges`
and `writeCheckpoint` are two separate provider calls, and a failure between
them can result in the same batch being applied again on a later execution.
Application storage adapters should treat `ChangeSetId` and change-event IDs
as stable idempotency keys.

---

## KMP compatibility

All types participating in this flow (`InboundPullPipelineConfiguration`,
`InboundPullSynchronizationPipeline`, and the DL-020/DL-019/DL-017 contracts
it depends on) are implemented using Kotlin standard-library and DataLoom
API, core, and runtime types only. No Android API, JVM-only API, or
third-party library type is required.

---

## Performance and security restrictions

- One batch is pulled, applied, and check-pointed at a time; no parallel
  batch pull occurs.
- Completed batch payload references are released before the next pull; no
  pipeline-owned collection accumulates processed `ChangeSet` instances.
- No blocking thread operation, `Thread.sleep`, `GlobalScope`, or dispatcher
  selection is used.
- Diagnostics and errors never expose checkpoint token values, payload bytes,
  credentials, authorization headers, encryption keys, personal data, stack
  traces, provider internal state, or complete provider `toString()` output.

See [Inbound Pull Pipeline (DL-022)](../api/inbound-pull-pipeline.md) for
the complete API-level contract documentation.
