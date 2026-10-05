# DataLoom Health Snapshot

[API reference index](./README.md)

> **Status:** Bounded slice 3. `dataLoomHealthSnapshot` aggregates provider
> lifecycle state, retry/circuit telemetry and caller-supplied provider health
> (slice 1, any `DataLoomProvider` including a `SchedulerProvider`), the
> durable operational-event outbox and the queue worker through two
> purpose-built synchronous read paths (slice 2), and now asset-transfer
> session-store failures and a plugin's `DEGRADED` lifecycle state (slice 3),
> rolling everything up into a `HEALTHY`/`DEGRADED`/`UNHEALTHY` severity with
> closed-vocabulary findings. Configuration/policy history still contributes
> nothing -- see "Remaining DL-042 boundary" below for why. A bounded
> pull-export core of the deployable operations dashboard/adaptor DL-042
> also requires now exists -- `dataLoomPrometheusMetrics`, below -- but this
> SDK still ships no HTTP server, OTLP/push exporter, or dashboard service.

`dataLoomHealthSnapshot(...)`, in `io.dataloom.runtime.observation.health`,
is a pure function that builds a `DataLoomHealthSnapshot` from whichever
already-available, caller-supplied collaborator state it is given. It
performs no I/O, never suspends, reads no clock, and calls no provider or
collaborator itself.

## What it aggregates

| Section | Source | Kind |
|---|---|---|
| `providerLifecycleState` | `ProviderLifecycleCoordinator.state` | Synchronous property read |
| `retryCircuitTelemetry` | `BoundedRetryCircuitTelemetry.snapshot()` | Synchronous, already-redacted read model (`docs/api/retry-circuit-telemetry.md`) |
| `providerHealth` | Caller-awaited `DataLoomProvider.health()` results, keyed by `ProviderId` | Caller-supplied, redacted on the way in -- covers *any* `DataLoomProvider`, including a `SchedulerProvider` (`SchedulerProvider` already extends `DataLoomProvider`), not only synchronization providers |
| `outboxHealth` | `OperationalEventOutboxHealthTracker.snapshot()` | **Cached observation** pushed by the outbox -- see "Outbox: the guarantee" |
| `queueWorkerHealth` | `QueueWorkerHealthTracker.snapshot()` | Exact bookkeeping of runs made through a wrapper -- see "Queue worker: the guarantee" |
| `assetTransferHealth` | `AssetTransferHealthTracker.snapshot()` | Exact bookkeeping of outcomes reported through the engine's observer seam -- see "Asset transfer: the guarantee" |
| `pluginHealth` | Caller-read `DataLoomPluginEngine.stateOf(id)` for every id in `.resolutionOrder`, keyed by `PluginId` | Caller-supplied; already a closed enum, needs no redaction |

Each parameter defaults to an empty/absent value. Calling
`dataLoomHealthSnapshot()` with no arguments is well-defined -- `severity` is
`HEALTHY`, `findings` and `outboxHealth` are empty, `queueWorkerHealth` is
`null`, never a failure. `now` is required exactly when outbox or worker
observations are supplied: the function never reads a clock, so the caller
states what "now" is.

## Outbox: the guarantee

The outbox's own reads suspend over a `DurableStateStore`, so a synchronous
snapshot cannot read them. The chosen design is a **pushed cache**:

1. `DurableOperationalEventOutbox` takes an optional `stateObserver`. After
   every state it loads-and-evaluates or successfully writes -- reads, an
   idempotent `append`, a no-op `acknowledge`, and every successful
   compare-and-set (reporting the state just written and its new version) --
   it hands the observer a bounded `OperationalEventOutboxStateObservation`:
   pending count, oldest pending `occurredAt`, retained-acknowledged count,
   store version, and `observedAt`. Failed loads, lost races and persistence
   failures report nothing. Observer exceptions are swallowed; with no
   observer nothing is computed and no extra clock read happens.
2. `OperationalEventOutboxHealthTracker` is the observer. `snapshot()` returns
   the latest observation per scope (ordered by scope name), synchronously,
   without touching the outbox or its store. Observations of one scope merge by
   store version, so a late older observation cannot replace a newer one.
3. `DurableOperationalEventOutboxProcessor(outbox, healthTracker)` additionally
   reports each processing cycle (`NO_WORK` / `PROCESSED` / `READ_FAILURE` and
   the skipped/failed/acknowledge-failed counts), because the outbox cannot know
   what a handler decided.

**What this is not.** A value is *the state this process's outbox instance last
saw or wrote*, stamped with when. It is **not** the store's current state:
another instance or process sharing the store may have changed the scope since,
and a scope this process has never touched is simply absent. Nothing refreshes
a value on its own. The snapshot therefore reports, per scope, `observedAt`,
`observationAge` and a `stale` flag (age >= `outboxObservationStaleAfter`,
default 10 minutes), and:

- `oldestPendingAge` is measured from the entry's caller-supplied `occurredAt`
  (not its append time) as of the snapshot's `now`, *assuming it is still
  pending*;
- a stale observation that had pending entries is `DEGRADED` (entries nobody
  has looked at for a while); a stale observation of an empty outbox only sets
  the flag;
- `hasSkippedOrFailedEntries` is a fact about the last processing cycle
  (entries presented but left pending), not a live count; a clean later cycle
  clears it.

## Queue worker: the guarantee

A worker's `run` is a suspending call returning a sealed result; there is no
queryable worker state. `QueueWorkerHealthTracker` is fed by wrapping the worker
-- `dataLoom.queueWorker.withHealthTracking(tracker)` /
`circuitQueueWorker.withHealthTracking(tracker)`, or
`DataLoomBuilder.queueWorkerHealthTracker(tracker)` -- which reports each run's
start and end without altering its result or exception. The tracker's counters
are therefore *exact for that wrapper in this process*: `runsInFlight`,
`lastRunStartedAt`, `lastCompletedRunAt`, `lastEmptyQueueAt` (the only evidence
of a drained queue: a run that found it empty), `lastRunOutcome`
(`COMPLETED_NO_WORK`, `COMPLETED_PROCESSED`, `RECOVERY_FAILED`,
`PROCESSING_FAILED`, `UNEXPECTED_EXCEPTION`), `consecutiveFailedRuns`, and the
last failure's closed error vocabulary (code/category/severity/recoverability --
`DataLoomError.message` and `cause` are never read). Cancellation is neither a
failure nor an outcome; it only ends the run.

What it cannot see: runs made by another process sharing the queue, and runs
made on an unwrapped coordinator. A worker that has never run reports
`NEVER_RUN`, which is **not** evidence the queue is empty. The worker section's
`stale` flag (no event for `queueWorkerObservationStaleAfter`, default 6 hours)
is informational only -- a worker scheduled on demand is legitimately quiet, so
staleness never changes its severity.

## Asset transfer: the guarantee

`io.dataloom.assets.AssetTransferEngine` has one optional constructor seam, its
`AssetTransferObserver`, notified once per `upload`/`download`/`cancel` call
with the already-decided `AssetTransferOutcome`. `AssetTransferHealthTracker`
is fed through that seam -- `DataLoomBuilder.assetTransferHealthTracker(tracker)`,
which composes the tracker with any already-configured
`AssetTransferOperationalEventRecorder` via
`io.dataloom.runtime.facade.withAssetTransferHealthTracking` rather than
replacing it, since the engine has only one observer slot.

Only consecutive `AssetTransferOutcome.SessionStoreFailure` outcomes drive
severity. Every other outcome -- `Completed`, `Cancelled`, `NotStarted`, and
critically `Failed` and `Interrupted` -- is recorded in
`AssetTransferObservedState` for diagnostics but never counted, because those
routinely reflect caller input the engine correctly rejected (a quota
exceeded, a content-policy denial, a chunk that does not match its manifest)
rather than engine health; counting them would flag the SDK as degraded
because an application tried to upload something too large. A
`SessionStoreFailure` has no such ambiguity: it means the engine's own durable
session store threw before any business decision could even be evaluated --
this gate's outbox already has the same-shaped finding
(`OUTBOX_LAST_CYCLE_READ_FAILED`, "the outbox could not even read").

## Plugin: the guarantee

Each registered plugin's lifecycle state is already synchronously queryable
(`DataLoomPluginEngine.stateOf(id)`, backed by a volatile cell per plugin), so
`pluginHealth` needs no new tracker -- the caller reads it directly, exactly
like `providerHealth`. Only `PluginLifecycleState.DEGRADED` ("partially usable
with reduced capability or reliability") is a health verdict; every other
state (`LOADED`, `VALIDATED`, `INITIALIZING`, `ACTIVE`, `DISABLED`, `UNLOADED`)
is a lifecycle phase, the same posture `providerLifecycleState` already has.

## The roll-up

`DataLoomHealthSnapshot.findings` lists every reason the snapshot is not clean
as `(component, code, severity, subject)` -- all closed enums plus a
non-sensitive subject (provider id, exporter id or outbox scope name), never
free text -- and `severity` is their maximum (`HEALTHY` when there are none).

| Source | Finding | Severity |
|---|---|---|
| Provider health | `DEGRADED` / `UNHEALTHY` status | `DEGRADED` / `UNHEALTHY` (`HEALTHY`, `UNKNOWN`: none) |
| Telemetry exporter | `DEGRADED` / `STOPPED` | both `DEGRADED` (telemetry is lost; work is unaffected) |
| Outbox depth | pending >= `outboxPendingDegradedAt` (1,000) / `outboxPendingUnhealthyAt` (5,000) | `DEGRADED` / `UNHEALTHY` |
| Outbox age | oldest pending >= `outboxOldestPendingDegradedAfter` (1 h) / `...UnhealthyAfter` (24 h) | `DEGRADED` / `UNHEALTHY` |
| Outbox cycle | last cycle left entries pending; last cycle could not read | `DEGRADED` |
| Outbox staleness | stale observation that had pending entries | `DEGRADED` |
| Worker failures | consecutive failed runs >= `queueWorkerFailedRunsDegradedAt` (1) / `...UnhealthyAt` (3) | `DEGRADED` / `UNHEALTHY` |
| Worker run | a run in flight >= `queueWorkerRunStuckAfter` (30 min) | `DEGRADED` |
| Asset transfer | consecutive `SessionStoreFailure` >= `assetTransferSessionStoreFailuresDegradedAt` (1) / `...UnhealthyAt` (3) | `DEGRADED` / `UNHEALTHY` |
| Plugin | tracked lifecycle state is `DEGRADED` | `DEGRADED` |

`providerLifecycleState` is reported but does **not** influence `severity`: it
is a lifecycle phase (for example `NOT_INITIALIZED` before startup), not a
health verdict.

**`severity` speaks only for the evidence supplied.** A snapshot built with
nothing is `HEALTHY` with no findings, meaning "nothing reported a problem",
not "everything was checked". Thresholds live in `DataLoomHealthThresholds`;
every field has a safe default (conservative for a diagnostics outbox and an
on-demand worker), is compared with `>=`, and is validated (an unhealthy
threshold below its degraded one is rejected).

## Redaction

`retryCircuitTelemetry` and `providerLifecycleState` need no additional
redaction: `RetryCircuitTelemetrySnapshot` is already a redacted read model by
construction, and `ProviderLifecycleCoordinatorState` is a closed enum.
Outbox health carries counts and timestamps only -- never an envelope, id or
attribute. Findings are closed enums.

`ProviderHealth` is different -- its `error` is an arbitrary `DataLoomError`
implementation, and `DataLoomError.message` is documented as "should" be
sanitized but is not enforced, so `dataLoomHealthSnapshot` runs it through
`DataLoomRedactor` (`StrictDataLoomRedactor` by default) using the exact
convention `SynchronizationOperationalEventBridge` applies to every other
`DataLoomError` this SDK exports: `code`, `category`, `severity`,
`recoverability` are closed vocabularies classified `PUBLIC` and kept;
`message` is `CONFIDENTIAL` and removed outright; `cause` is never read. The
worker's last failure follows the same convention minus `message`, which the
tracker never stores. `ProviderHealth.details` is reported only as
`detailFieldCount`.

## Example

```kotlin
val outboxTracker = OperationalEventOutboxHealthTracker(clock)
val workerTracker = QueueWorkerHealthTracker(clock)

val dataLoom = DataLoomBuilder()
    // ... providers, bindings, queueWorkerConfiguration, outbox specs ...
    .operationalEventOutboxHealthTracker(outboxTracker) // observes every configured outbox
    .queueWorkerHealthTracker(workerTracker)            // wraps the built worker
    .build()

// Standalone outboxes and processors opt in the same way:
//   DurableOperationalEventOutbox(store, clock, stateObserver = outboxTracker)
//   DurableOperationalEventOutboxProcessor(outbox, outboxTracker)

val snapshot = dataLoomHealthSnapshot(
    providerLifecycleState = lifecycleCoordinator.state,
    retryCircuitTelemetry = telemetry.snapshot(),
    providerHealth = awaitedHealth, // caller's own suspend calls
    outboxObservations = outboxTracker.snapshot(),
    queueWorkerObservation = workerTracker.snapshot(),
    now = clock.now(),
    thresholds = DataLoomHealthThresholds(outboxPendingDegradedAt = 200),
)

if (snapshot.severity != DataLoomHealthSeverity.HEALTHY) {
    snapshot.findings.forEach { log("${it.component} ${it.code} ${it.subject}") }
}
```

Not configuring the trackers leaves every outbox and worker exactly as before:
no observer, no summary computation, no wrapper.

## Prometheus text export

`dataLoomPrometheusMetrics(snapshot)`, in the same package, renders an
already-built `DataLoomHealthSnapshot` as
[Prometheus text exposition format](https://github.com/prometheus/docs/blob/main/content/docs/instrumenting/exposition_formats.md):
overall severity, a per-`DataLoomHealthComponent` roll-up, per-scope outbox
depth/severity (a never-observed scope is omitted, not reported as zero),
queue-worker gauges, and per-provider status. It is a pure formatting
function -- no I/O, no suspension, no clock read, no new instrumentation --
over exactly the fields already documented above; it adds no subsystem
coverage `DataLoomHealthSnapshot` itself does not already have.

```kotlin
val text = dataLoomPrometheusMetrics(snapshot)
// host application serves `text` from its own /metrics HTTP handler
```

This SDK still does not run an HTTP server or any scrape/push transport --
exporting the text is this function's entire job; serving it is the host
application's.

## Scope -- what this deliberately is not

- **Not a live dashboard.** No continuous feed, subscription or polling loop; a
  caller decides when to call the function and receives exactly one instant.
- **Not a deployable service or reference adaptor.** No HTTP endpoint or
  process ships with this slice; `dataLoomPrometheusMetrics` only renders
  text, it does not serve it.
- **Not historical.** No trend, time series or retained-snapshot history.
- **Not cross-process or cross-node aggregation.** One snapshot describes one
  process's in-memory state (and, for the outbox, what that process last saw of
  a possibly shared store).
- **Not new durable storage.** The trackers hold in-memory values only; nothing
  is persisted, and they start empty after a restart.
- **Not full subsystem coverage.** Configuration/policy history contributes
  nothing to `severity`: `DataLoomConfigurationResolver`/`DurableConfigurationHistory`
  have no runtime producer in `dataloom-runtime` (see
  `configuration-resolver-caller-investigation.md`), so there is nothing to
  observe. Asset-transfer health counts only session-store failures (see above).
  A caller who wants an asset provider's own health feeds `AssetProvider.health()`
  into `providerHealth` like any other `DataLoomProvider`.
- **Not tuned by production data.** The default thresholds are reasoned
  defaults, not measured ones.

## Remaining DL-042 boundary

Health aggregation now covers every subsystem with a real runtime signal; configuration/policy history remains unaggregated until a runtime producer exists. The
deployable operations dashboard/adaptor's bounded pull-export core now
exists (`dataLoomPrometheusMetrics`, above); an HTTP server, OTLP/push
export, and a dashboard service remain open, mandatory V1 work, as does
extending the exported surface to subsystems `DataLoomHealthSnapshot` itself
does not yet cover.
