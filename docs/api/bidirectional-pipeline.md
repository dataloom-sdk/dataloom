# Bidirectional Pipeline (DL-023)

[API reference index](./README.md)

> **Status:** Available execution foundation. Ordered push/pull composition is
> not proof of the mandatory six-strategy V1 architecture.

`BidirectionalSynchronizationPipeline` is the third concrete
`SynchronizationPipeline` implementation. It composes an existing outbound
push pipeline and an existing inbound pull pipeline into a single, sequential
bidirectional synchronization execution.

This component implements bidirectional pipeline composition only. It delegates all
provider operations to its two child pipelines and performs no provider calls
directly. Separate runtime components own retry, queue, scheduling,
connectivity admission, conflict orchestration, and event delivery.

---

## Required flow

```text
BidirectionalSynchronizationPipeline
    → outbound child pipeline (e.g. OutboundPushSynchronizationPipeline)
    → inbound child pipeline (e.g. InboundPullSynchronizationPipeline)
    → combine results
    → SynchronizationResult
```

or, with `INBOUND_THEN_OUTBOUND`:

```text
BidirectionalSynchronizationPipeline
    → inbound child pipeline
    → outbound child pipeline (when first permits continuation)
    → combine results
    → SynchronizationResult
```

---

## `BidirectionalExecutionOrder`

```kotlin
public enum class BidirectionalExecutionOrder {
    OUTBOUND_THEN_INBOUND,
    INBOUND_THEN_OUTBOUND,
}
```

Configures the sequential execution order of the outbound and inbound child
pipelines. Do not persist or compare enum ordinals.

### `OUTBOUND_THEN_INBOUND` (default)

Executes the outbound push pipeline first, then the inbound pull pipeline.
This is the default order. Pushing local changes before pulling remote changes
reduces the chance that an immediately following inbound pull overwrites or
conflicts with unsent local work.

### `INBOUND_THEN_OUTBOUND`

Executes the inbound pull pipeline first, then the outbound push pipeline.
Use this order when the application requires server-authoritative
synchronization or when receiving the latest remote state before sending local
changes is necessary for correct conflict handling.

This setting changes child-pipeline order only. It does not by itself implement
a remote-first strategy: it does not define remote authority, freshness,
fallback, persistence, queueing, or cache-coherence semantics. Those are owned
by the versioned V1 strategy contract in ADR-0002/#102.

---

## `BidirectionalPipelineConfiguration`

```kotlin
public data class BidirectionalPipelineConfiguration(
    public val executionOrder: BidirectionalExecutionOrder =
        BidirectionalExecutionOrder.OUTBOUND_THEN_INBOUND,
)
```

Immutable configuration for `BidirectionalSynchronizationPipeline`.

- **`executionOrder`**: the sequential order in which the outbound and inbound
  child pipelines are executed. Defaults to `OUTBOUND_THEN_INBOUND`.

### Construction restrictions

Construction performs no pipeline execution, no clock read, and generates no
identifiers.

---

## `BidirectionalSynchronizationPipeline`

```kotlin
public class BidirectionalSynchronizationPipeline(
    private val outboundPipeline: SynchronizationPipeline,
    private val inboundPipeline: SynchronizationPipeline,
    private val configuration: BidirectionalPipelineConfiguration,
) : SynchronizationPipeline
```

- `direction` is `SynchronizationDirection.BIDIRECTIONAL`, the exact existing
  bidirectional direction.
- `outboundPipeline` must declare `SynchronizationDirection.PUSH`.
- `inboundPipeline` must declare `SynchronizationDirection.PULL`.
- Incorrect delegate directions are rejected at construction time with
  `IllegalArgumentException`.
- Construction does not invoke either child pipeline.

### Execution flow

The pipeline receives the `SynchronizationExecutionContext` and passes the
exact same context to both child pipelines without modification.

### Continuation rules

The second pipeline is executed when the first returns:
- `Succeeded`
- `PartiallySucceeded`
- `Skipped(NO_CHANGES)`

The second pipeline is **not** executed when the first returns:
- `Failed`
- `Cancelled`
- `Skipped` with any reason other than `NO_CHANGES`

### Result combination matrix

| First result          | Second result         | Combined result       |
|-----------------------|-----------------------|-----------------------|
| Skipped(NO_CHANGES)   | Skipped(NO_CHANGES)   | Skipped(NO_CHANGES)   |
| Skipped(NO_CHANGES)   | Succeeded             | Succeeded             |
| Succeeded             | Skipped(NO_CHANGES)   | Succeeded             |
| Succeeded             | Succeeded             | Succeeded             |
| Succeeded             | PartiallySucceeded    | PartiallySucceeded    |
| PartiallySucceeded    | Succeeded             | PartiallySucceeded    |
| Skipped(NO_CHANGES)   | PartiallySucceeded    | PartiallySucceeded    |
| PartiallySucceeded    | PartiallySucceeded    | PartiallySucceeded    |
| Failed                | (not executed)        | Failed (first error)  |
| Cancelled             | (not executed)        | Cancelled             |
| Skipped(non-NO_CHANGES) | (not executed)      | Skipped (same reason) |
| (any continuing)      | Failed                | Failed (second error) |
| (any continuing)      | Cancelled             | Cancelled             |

The composed terminal result uses `RuntimeDependencies.clock` for its final
completion timestamp.

### Summary combination

Every counter in both child summaries is summed using overflow-safe addition.
If any counter addition would overflow `Long.MAX_VALUE` or `Int.MAX_VALUE`, a
canonical `SynchronizationResult.Failed` is returned instead of a wrapped
counter value.

### Partial-error ordering

When both pipelines produce `PartiallySucceeded`:
- Errors from the first pipeline precede errors from the second pipeline.
- Errors follow the configured execution order.
- Error order is preserved; duplicate errors are not removed.

### Failure preservation

When a child pipeline returns `Failed`:
- The exact canonical `DataLoomError` instance is preserved.
- No retry, queue scheduling, or restart is performed.
- The composed result contains a newly combined summary and a terminal
  timestamp from the injected clock.

### Explicit `Cancelled` result versus `CancellationException`

A child pipeline that returns the explicit `SynchronizationResult.Cancelled`
value is distinct from a thrown `CancellationException`. A thrown
`CancellationException` propagates normally and is never converted into a
`SynchronizationResult`. The explicit `Cancelled` result is combined and
returned normally.

### Clock usage

`RuntimeDependencies.clock` is read exactly once per `execute` call to
produce the composed terminal result's completion timestamp. Child completion
timestamps are not reused.

### Direct provider-call restriction

`BidirectionalSynchronizationPipeline` does not directly call any
`StorageProvider`, `TransportProvider`, `SchedulerProvider`,
`ConnectivityProvider`, `QueueProvider`, `RetryPolicy`, `ConflictDetector`,
`ConflictResolver`, `ProviderLifecycleCoordinator`, `ProviderRegistry`,
`SynchronizationProviderResolver`, or `SynchronizationObserver`.

All provider calls are delegated exclusively to the two child pipelines.

### Retry, queue, and scheduler boundary

This pipeline does not invoke any retry policy, enqueue work, reschedule
queue entries, or call any scheduler. Child results are combined only.

### Event boundary

This pipeline does not dispatch events, register observers, or use any
`Flow`, `StateFlow`, `SharedFlow`, or `Channel`.

### Concurrency limitations

The two child pipelines execute strictly sequentially. Parallel execution is
not performed.

### Performance constraints

- At most two child pipelines are executed per `execute` call.
- No `ChangeSet` payloads are retained by this pipeline.
- No dispatcher or platform thread pool is used.
- No unbounded collection is allocated.

### Security restrictions

Diagnostic representations include only execution order, direction names,
result variant names, and summary counts. Payload bytes, checkpoint token
values, credentials, authorization headers, encryption keys, personal data,
and stack traces are never exposed.

### KMP compatibility

Uses Kotlin standard-library and DataLoom API, core, and runtime types only.
Safe for use in Kotlin Multiplatform common code.

---

## Module placement

| Type                                  | Module            | Package                                           |
|---------------------------------------|-------------------|---------------------------------------------------|
| `BidirectionalExecutionOrder`         | dataloom-runtime  | `io.dataloom.runtime.execution.bidirectional`     |
| `BidirectionalPipelineConfiguration`  | dataloom-runtime  | `io.dataloom.runtime.execution.bidirectional`     |
| `BidirectionalSynchronizationPipeline` | dataloom-runtime | `io.dataloom.runtime.execution.bidirectional`     |
