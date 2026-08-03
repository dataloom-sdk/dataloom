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
arbitrary metadata field. Metric keys use only signal and closed circuit enums.
Workflow, tenant, correlation, trace, and error-code values are never metric
labels, so adversarial dynamic identities cannot grow the metric-key space.

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
