# Retry and Circuit Telemetry

[API reference index](./README.md)

> **Status:** Partial V1 subsystem. This checkpoint completes a bounded,
> exporter-neutral observability path for retry/circuit execution and
> administration. It does not complete the durable event-delivery and full
> operations-dashboard scope of DL-042.

`BoundedRetryCircuitTelemetry` is a non-blocking fan-out boundary for retry and
circuit facts. Each configured exporter owns a dedicated bounded channel and
worker. Runtime callers submit with `record`; exporters are never invoked on
that caller's coroutine.

## Delivery and overflow

- buffers are bounded per exporter from 1 through 10,000 records;
- the explicit V1 checkpoint policy is `DROP_LATEST`;
- a full exporter buffer preserves already-accepted order and drops only the
  newest submission for that exporter;
- drops are counted in the redacted support snapshot;
- one slow, failed, or timed-out exporter cannot block runtime work or another
  exporter;
- exporter calls have a positive configured timeout;
- cooperative cancellation is required from exporter implementations; an
  exporter that suppresses cancellation can strand only its own bounded worker,
  never synchronization or another exporter;
- `close` stops new acceptance and drains accepted records; `join` waits for
  those workers after close.

## Stable schema and cardinality

`RetryCircuitTelemetryEvent` schema version 1 contains only:

- a closed signal taxonomy;
- wall-clock occurrence time;
- optional workflow, tenant, correlation, and trace identities already present
  in `ExecutionContext`;
- closed circuit scope, phase, operation-outcome, and detail taxonomies;
- typed retry attempt and selected delay; and
- an optional canonical `ErrorCode`.

It has no payload, exception, credential, free-form message, tag map, or
arbitrary metadata field. Metric keys use only signal and closed circuit enums
(plus, for conflict signals only, the bounded dimensions described in
"Conflict telemetry" below). Workflow, tenant, correlation, trace, and
error-code values are never metric labels, so adversarial dynamic identities
cannot grow the metric-key space.

## Exporters, logs, and traces

Applications implement `RetryCircuitTelemetryExporter` for a vendor or local
destination. `RetryCircuitStructuredLogExporter` converts events into the
fixed `RetryCircuitStructuredLogRecord` schema; integrations never parse a
diagnostic string. `RetryCircuitTraceExporter` forwards correlated signals only
when the original execution context already has a `TraceId`; it does not invent
trace identity.

The SDK catches ordinary exporter exceptions, records only the stable failure
category, and does not retain exception text. Fatal errors remain fatal to that
exporter worker and are not converted into business results.

## Metrics, health, and support snapshot

`snapshot()` is local and never contacts an exporter. It returns:

- saturated counters by fixed `RetryCircuitMetricKey`;
- accepted, dropped, exported, failure, and timeout counters per exporter;
- `HEALTHY`, `DEGRADED`, or `STOPPED` exporter state; and
- aggregate `degraded` status.

The snapshot contains no payload, free-form reason, exception text, provider
instance, credential, or exporter object.

## Runtime instrumentation

The additive wrappers preserve exact delegate results:

- `ObservedSynchronizationRetryOrchestrator` records every terminal retry
  orchestration status and propagates workflow/correlation/trace context;
- `ObservedCircuitBreakerExecutionGate` records permission rejection,
  persistence/contention outcomes, protected-operation classification, and
  circuit record evidence;
- `ObservedRetryAdministrationCoordinator` and
  `ObservedCircuitAdministrationCoordinator` record every terminal command
  outcome, including replay, denial, conflict, persistence ambiguity, clock
  regression, and contention.

The delegate completes before telemetry is assembled. A clock or telemetry
exception therefore cannot replace an already-produced retry, circuit, or
administrative result. Caller cancellation from the delegate still propagates
and is never translated into telemetry.

## Conflict telemetry

The conflict engine (DL-041, issue #95) reports through this same pipeline
rather than a second one: the same `BoundedRetryCircuitTelemetry`, exporters,
`RetryCircuitTelemetryEvent`, and `RetryCircuitMetricKey`, with additional
closed signals and three optional dimensions. Like every other `Observed*`
wrapper here, the two conflict wrappers are opt-in classes a host composes
around the runtime component; `DataLoomBuilder` does not assemble them (it
does not assemble the retry/circuit wrappers either), and a component that is
not wrapped emits nothing.

`ObservedSynchronizationConflictOrchestrator` wraps
`SynchronizationConflictOrchestrator.detectAndResolve`, returns the exact
delegate result, and records after it completes:

| Orchestration result | Signals |
|---|---|
| `DetectorNotFound`, `NoConflict` | none (no conflict occurred) |
| `ResolverNotConfigured` | `CONFLICT_DETECTED`, `CONFLICT_UNRESOLVED` (reason `RESOLVER_NOT_CONFIGURED`) |
| `ResolverNotFound` | `CONFLICT_DETECTED`, `CONFLICT_UNRESOLVED` (reason `RESOLVER_NOT_FOUND`), tier hit |
| `Resolved` with `UseLocal`, `UseRemote` or `Merge` | `CONFLICT_DETECTED`, `CONFLICT_RESOLVED` (resolver ID), tier hit |
| `Resolved` with `Defer` | `CONFLICT_DETECTED`, `CONFLICT_DEFERRED` (resolver ID), tier hit |
| `Resolved` with `Fail` | `CONFLICT_DETECTED`, `CONFLICT_FAILED` (resolver ID), tier hit |
| `Quarantined` | `CONFLICT_DETECTED`, `CONFLICT_QUARANTINED`, tier hit |
| `QuarantineUnavailable` | `CONFLICT_DETECTED`, `CONFLICT_FAILED` (no resolver: none was invoked), tier hit |

`CONFLICT_RESOLVER_SELECTION_TIER_HIT` carries a `ConflictResolverSelectionTier`
(`ENTITY_TYPE`, `WORKFLOW`, `TENANT`, `GLOBAL`) and is recorded whenever a tier
selected a resolver ID, even if that ID was then not found; it is not recorded
when nothing was selected. The tier is computed by
`ConflictOrchestrationBindings.selectedTier`, which shares its implementation
with `selectResolverId`, so the reported tier cannot disagree with the tier
actually used. A detector or resolver exception propagates as before and
records no outcome signal. `ObservedConflictAdministrationCoordinator` records
`CONFLICT_QUARANTINE_RELEASED` only for a `Released` result (not
`AlreadyReleased`, denial, or failure); full command audit stays in the
operational-event outbox.

Cardinality: the only new dimensions are the closed `ConflictResolverSelectionTier`
and `UnresolvedConflictReason` enums and the `ConflictResolverId`. A resolver ID
is recorded only for a `Resolved` result, which exists only after a lookup in
the orchestrator's immutable `ConflictResolverRegistry` (application
registrations plus the fixed built-in catalog), so its label set is closed for
the lifetime of one instance. Entity IDs, change IDs, conflict IDs, and tenant
IDs never appear in a metric key, and no signal carries a payload. The mechanism
has counters only; there are no gauges and no latency metric (a monotonic
duration model remains DL-042 work).

## Example

```kotlin
val telemetry = BoundedRetryCircuitTelemetry(
    coroutineContext = applicationScope.coroutineContext,
    configuration = RetryCircuitTelemetryConfiguration(
        bufferCapacityPerExporter = 256,
        exporterTimeout = SchedulingDelay(1_000L),
    ),
    exporters = listOf(
        RetryCircuitStructuredLogExporter(
            id = RetryCircuitTelemetryExporterId("operations-log"),
            sink = applicationLogSink,
        ),
    ),
)

val observedRetry = ObservedSynchronizationRetryOrchestrator(
    delegate = retryOrchestrator,
    clock = clock,
    telemetry = telemetry,
)
```

## Remaining DL-042 boundary

This checkpoint does not claim the complete canonical operational envelope,
durable outbox/acknowledgement/replay/retention, filtering, authoritative
restart ordering, upcasting, monotonic duration model, subsystem-wide health,
or deployable reference dashboard. Those remain mandatory V1 work under
DL-039/DL-042.
