# DataLoom Health Snapshot

[API reference index](./README.md)

> **Status:** Bounded first slice. This checkpoint aggregates the
> already-synchronously-queryable subsystem state that existed before it
> (retry/circuit telemetry, provider lifecycle coordinator state) plus
> caller-supplied, already-redacted provider health results, into one
> point-in-time value type. It does not complete SDK-wide health
> aggregation, and it is not the deployable operations dashboard/adaptor
> DL-042 still requires.

`dataLoomHealthSnapshot(...)`, in `io.dataloom.runtime.observation.health`,
is a pure function that builds a `DataLoomHealthSnapshot` from whichever
already-available, caller-supplied collaborator state it is given. It
performs no I/O, never suspends, and calls no provider or collaborator
itself.

## What it aggregates

| Section | Source | Kind |
|---|---|---|
| `providerLifecycleState` | `ProviderLifecycleCoordinator.state` | Synchronous property read |
| `retryCircuitTelemetry` | `BoundedRetryCircuitTelemetry.snapshot()` | Synchronous, already-redacted read model (`docs/api/retry-circuit-telemetry.md`) |
| `providerHealth` | Caller-awaited `DataLoomProvider.health()` results, keyed by `ProviderId` | Caller-supplied, redacted on the way in |

Each parameter defaults to an empty/absent value. Calling
`dataLoomHealthSnapshot()` with no arguments is well-defined -- it returns a
snapshot with `providerLifecycleState = null`, `retryCircuitTelemetry =
null`, and an empty `providerHealth` map, never a failure.

## Redaction

`retryCircuitTelemetry` and `providerLifecycleState` need no additional
redaction: `RetryCircuitTelemetrySnapshot` is already a redacted read model
by construction (no payload, exception, credential, or free-form message --
see `docs/api/retry-circuit-telemetry.md`), and
`ProviderLifecycleCoordinatorState` is a closed, non-sensitive enum.

`ProviderHealth` is different -- its `error` is an arbitrary
`DataLoomError` implementation, and `DataLoomError.message` is documented as
"should" be sanitized but is not enforced at the type level, so
`dataLoomHealthSnapshot` runs it through `DataLoomRedactor`
(`StrictDataLoomRedactor` by default) using the exact same convention
`SynchronizationOperationalEventBridge` already applies to every other
`DataLoomError` this SDK exports:

- `code`, `category`, `severity`, `recoverability` are closed, stable
  vocabularies -- classified `PUBLIC` and always kept;
- `message` is unstructured free text -- classified `CONFIDENTIAL`, which
  the default policy removes outright (not merely masked -- the field is
  absent from the result);
- `error.cause` (a raw `Throwable`) is never read at all.

`ProviderHealth.details` (a `DataLoomMetadata`) is intentionally **not**
redacted field-by-field. Nothing in this codebase today converts free-form
`DataLoomMetadata` keys (only required to be non-blank) into the bounded
ASCII tokens `ClassifiedData` requires, and inventing that general-purpose
conversion is a separate, larger concern than this slice. Instead,
`RedactedProviderHealth.detailFieldCount` discloses only how many entries
were present -- the same bound `DataLoomMetadata.toString()` itself already
exposes -- never the field names or values.

## Example

```kotlin
val telemetrySnapshot = telemetry.snapshot()
val awaitedHealth: Map<ProviderId, ProviderHealth> = registry.providerIds
    .associateWith { id -> awaitProviderHealth(id) } // caller's own suspend call

val snapshot = dataLoomHealthSnapshot(
    providerLifecycleState = lifecycleCoordinator.state,
    retryCircuitTelemetry = telemetrySnapshot,
    providerHealth = awaitedHealth,
)

check(snapshot.providerHealth.values.none { it.status == ProviderHealthStatus.UNHEALTHY })
```

## Scope -- what this deliberately is not

- **Not a live dashboard.** There is no continuous feed, subscription, or
  polling loop here -- a caller decides when to call
  `dataLoomHealthSnapshot` and receives exactly one instant.
- **Not a deployable service or reference adaptor.** No HTTP endpoint,
  process, or exporter ships with this slice.
- **Not historical.** No trend, time series, or retained-snapshot history
  is produced or stored.
- **Not cross-process or cross-node aggregation.** One snapshot describes
  exactly one process's in-memory state at the instant it was built.
- **Not new durable storage.** Nothing this function touches is persisted;
  it reads only already-in-memory or caller-supplied state.
- **Not full subsystem coverage.** Durable-outbox state
  (`DurableOperationalEventOutbox.entries`) and queue-worker state
  (`QueueWorkerCoordinator`) are not included: both require suspending,
  potentially-failing I/O today, with no already-synchronous read path to
  aggregate purely. Including them would mean inventing new synchronous
  query capability on subsystems that do not have one yet, which is
  out of scope for this first slice.

## Remaining DL-042 boundary

This checkpoint narrows, but does not close, DL-042's health/read-model
gap: SDK-wide health aggregation across every subsystem (including queue
and durable-outbox state) and the deployable operations dashboard/adaptor
both remain open, mandatory V1 work.
