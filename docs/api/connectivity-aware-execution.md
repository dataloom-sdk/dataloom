# Connectivity-Aware Execution (DL-031)

[API reference index](./README.md)

> **Status:** Available execution foundation. Direct preflight and queued
> offline deferral exist, and deferral now preserves retry history. Complete
> platform parity and V1 qualification remain open.

DataLoom can prevent network-dependent synchronization when the configured
connectivity requirement is not satisfied. This document describes the
configuration, preflight evaluation, direct-execution rejection, and
queued offline-deferral behavior introduced in DL-031.

---

## Contents

- [Configuration](#configuration)
- [No-Requirement Behavior](#no-requirement-behavior)
- [Connectivity Preflight](#connectivity-preflight)
- [Requirement Matching](#requirement-matching)
- [Missing Provider Behavior](#missing-provider-behavior)
- [Provider Failure Behavior](#provider-failure-behavior)
- [Direct Execution Rejection](#direct-execution-rejection)
- [Queued Offline Deferral](#queued-offline-deferral)
- [Offline Delay and Clock Calculation](#offline-delay-and-clock-calculation)
- [SchedulerProvider Boundary](#schedulerprovider-boundary)
- [QueueProvider Boundary](#queueprovider-boundary)
- [Event Rejection Boundary](#event-rejection-boundary)
- [Cancellation Propagation](#cancellation-propagation)
- [Unknown Connectivity](#unknown-connectivity)
- [No Polling or Observation](#no-polling-or-observation)
- [No Automatic Queue Creation](#no-automatic-queue-creation)
- [Concurrency Limitations](#concurrency-limitations)
- [KMP Compatibility](#kmp-compatibility)
- [Performance Restrictions](#performance-restrictions)
- [Security Restrictions](#security-restrictions)

---

## Configuration

`SynchronizationConnectivityConfiguration` holds the connectivity requirement
for a synchronization and the offline reschedule delay for queued deferral.

```kotlin
val configuration = SynchronizationConnectivityConfiguration(
    requirement = ConnectivityRequirement.AVAILABLE,
    offlineRescheduleDelay = SchedulingDelay(30_000L), // 30 seconds
)
```

| Parameter | Type | Description |
|---|---|---|
| `requirement` | `ConnectivityRequirement` | The connectivity level required before synchronization executes. |
| `offlineRescheduleDelay` | `SchedulingDelay` | How far in the future to schedule queued entries when the requirement is not met. Used only by queued execution. |

Construction performs no provider call, no clock read, no identifier
generation, and no platform access.

`SynchronizationConnectivityConfiguration.NONE` is the backward-compatible
default that applies no connectivity requirement.

---

## No-Requirement Behavior

When `requirement` is `ConnectivityRequirement.NONE`:

- The configured `ConnectivityProvider` is never invoked.
- Synchronization proceeds regardless of network state.
- No queued offline deferral occurs.

This is the default when no configuration is supplied to
`SynchronizationExecutionCoordinator` or `QueuedSynchronizationExecutionHandler`.

---

## Connectivity Preflight

`SynchronizationConnectivityPreflight` evaluates the connectivity requirement
before pipeline execution. It is invoked by `SynchronizationExecutionCoordinator`
exactly once per execution, after the pipeline is located.

Evaluation order:

1. If `requirement` is `NONE` → return `ConnectivityPreflightResult.NotRequired`.
   Provider is never called.
2. If `requirement` is not `NONE` and no `ConnectivityProvider` is resolved →
   return `ConnectivityPreflightResult.ProviderNotConfigured`.
3. Otherwise, call `ConnectivityProvider.currentConnectivity()` exactly once.
4. If the provider returns `ProviderOperationResult.Failure` →
   return `ConnectivityPreflightResult.CheckFailed` with the exact
   `DataLoomError`.
5. If the returned `ConnectivitySnapshot` satisfies the requirement →
   return `ConnectivityPreflightResult.Satisfied`.
6. Otherwise → return `ConnectivityPreflightResult.RequirementNotMet`.

---

## Requirement Matching

| Requirement | Satisfied when |
|---|---|
| `NONE` | Always (provider not invoked). |
| `AVAILABLE` | `ConnectivityStatus.AVAILABLE`. |
| `UNMETERED` | `ConnectivityStatus.AVAILABLE` AND `isMetered == false`. |

Requirement matching is deterministic. No ordinal, string, or reflection
comparison is used.

`ConnectivityStatus.UNKNOWN` does **not** satisfy `AVAILABLE` or `UNMETERED`.
`ConnectivityStatus.LIMITED` does **not** satisfy `AVAILABLE` or `UNMETERED`.
`ConnectivityStatus.UNAVAILABLE` does not satisfy any network requirement.

Unknown or indeterminate connectivity never silently satisfies a
network-required configuration.

---

## Missing Provider Behavior

When `requirement` is not `NONE` but the resolved `ConnectivityProvider` is
null (no `connectivityProviderId` in `SynchronizationProviderBindings`):

- Direct execution returns
  `SynchronizationExecutionResult.Rejected(CONNECTIVITY_PROVIDER_NOT_CONFIGURED)`.
- Queued execution returns `QueueEntryExecutionOutcome.Failed`.
- No pipeline executes.
- No lifecycle or operational event is emitted.

The host application is responsible for supplying a `ConnectivityProvider`
whenever a connectivity requirement is configured.

---

## Provider Failure Behavior

When `ConnectivityProvider.currentConnectivity()` returns
`ProviderOperationResult.Failure`:

- The exact `DataLoomError` is captured and preserved.
- Direct execution returns
  `SynchronizationExecutionResult.Rejected(CONNECTIVITY_CHECK_FAILED)`
  with `connectivityCheckError` set to the exact error.
- Queued execution returns `QueueEntryExecutionOutcome.Failed` with the
  exact error.
- Provider failure is never treated as `RequirementNotMet` (not rescheduled
  as offline).
- No pipeline executes.
- No lifecycle or operational event is emitted.

---

## Direct Execution Rejection

`SynchronizationExecutionCoordinator` evaluates connectivity after pipeline
lookup and before `Started` event emission and pipeline invocation.

The following rejection reasons are added for DL-031:

| Reason | Meaning |
|---|---|
| `CONNECTIVITY_PROVIDER_NOT_CONFIGURED` | Connectivity required but no provider resolved. |
| `CONNECTIVITY_REQUIREMENT_NOT_MET` | Provider returned a non-satisfying snapshot. |
| `CONNECTIVITY_CHECK_FAILED` | Provider returned `ProviderOperationResult.Failure`. |

For connectivity rejections:

- No `Started`, `PhaseChanged`, `ProgressUpdated`, or `Completed` event is emitted.
- No event-ID generation or clock read occurs.
- No pipeline is invoked.
- No storage or transport provider is invoked.
- Provider lifecycle state is not changed.
- The request is not automatically enqueued or scheduled.

The host application may choose to enqueue the request separately after
receiving a connectivity rejection.

---

## Queued Offline Deferral

`QueuedSynchronizationExecutionHandler` maps connectivity rejections to
`QueueEntryExecutionOutcome` as follows:

| Coordinator rejection reason | Queue outcome |
|---|---|
| `CONNECTIVITY_REQUIREMENT_NOT_MET` | `QueueEntryExecutionOutcome.Deferred` |
| `CONNECTIVITY_PROVIDER_NOT_CONFIGURED` | `QueueEntryExecutionOutcome.Failed` |
| `CONNECTIVITY_CHECK_FAILED` | `QueueEntryExecutionOutcome.Failed` |

When the outcome is `Deferred`:

- `RetryPolicy` is **not** invoked.
- The handler does **not** invoke `SchedulerProvider` directly.
- `DataLoomClock.now()` is read exactly once from the injected clock.
- `availableAt` = `clock.now().epochMilliseconds + offlineRescheduleDelay.milliseconds`
  (overflow-safe; see below).
- `reason` is
  `QueueDeferralReason.CONNECTIVITY_REQUIREMENT_NOT_MET`.
- No retry attempt or failure is manufactured.

`DurableQueueExecutionProcessor` remains responsible for calling
`QueueProvider.defer()`. The provider preserves the stored attempt exactly:
`null` remains `null`, while retry `N` remains `N`. The handler never calls
`QueueProvider` directly.
After that transition is persisted, `QueueWorkerCoordinator` may create one
wake-up schedule from
`QueueProcessingResult.Processed.earliestDeferredAt`.

---

## Offline Delay and Clock Calculation

The timestamp calculation is overflow-safe:

```
val epochMillis = clock.now().epochMilliseconds
val delayMillis = offlineRescheduleDelay.milliseconds
val nextMillis = epochMillis + delayMillis
val safeMillis = if (nextMillis < 0L) Long.MAX_VALUE else nextMillis
val availableAt = DataLoomInstant(epochMilliseconds = safeMillis)
```

When the sum would overflow `Long.MAX_VALUE`, the result is clamped to
`Long.MAX_VALUE`. `DataLoomInstant` requires `epochMilliseconds >= 0`, so the
calculation is always valid.

The injected clock must implement `DataLoomClock`. No system clock is accessed
directly. No random jitter is added.

---

## SchedulerProvider Boundary

`QueuedSynchronizationExecutionHandler` never invokes `SchedulerProvider`
directly for offline queue deferral.

Queued offline deferral uses:

```
QueueEntryExecutionOutcome.Deferred
    → DurableQueueExecutionProcessor
    → QueueProvider.defer()
```

No `ScheduleRequest`, WorkManager call, or other background scheduling occurs
inside the handler. After the processor persists the deferral,
`QueueWorkerCoordinator` may use the recorded `earliestDeferredAt` to
schedule the next bounded worker wake-up.

---

## QueueProvider Boundary

`QueuedSynchronizationExecutionHandler` never calls `QueueProvider` directly.

`DurableQueueExecutionProcessor` is responsible for all queue persistence
transitions based on the `QueueEntryExecutionOutcome` returned by the handler.
Its deferral transition is lease-guarded and separate from retry rescheduling,
so repeated offline checks do not consume retry budget.

---

## Event Rejection Boundary

Connectivity-rejected executions are rejected before the `Started` event. No
events are emitted for any connectivity preflight rejection:

- `Started` — not emitted
- `PhaseChanged` — not emitted
- `ProgressUpdated` — not emitted
- `Completed` — not emitted
- `RetryScheduled` — not emitted
- `ConflictDetected` — not emitted

No new connectivity event variant is added in DL-031.

---

## Cancellation Propagation

`CancellationException` thrown by `ConnectivityProvider.currentConnectivity()`
propagates normally. It is never converted into:

- A `ConnectivityPreflightResult` variant
- A `SynchronizationExecutionResult.Rejected`
- A `QueueEntryExecutionOutcome`

The `ProviderOperationResult.Failure` path is the canonical connectivity
failure path.

---

## Unknown Connectivity

`ConnectivityStatus.UNKNOWN` does **not** satisfy `ConnectivityRequirement.AVAILABLE`
or `ConnectivityRequirement.UNMETERED`. Unknown or indeterminate connectivity
always produces `ConnectivityPreflightResult.RequirementNotMet`.

DataLoom does not wait for connectivity to become known. It does not poll,
observe, or block on connectivity state.

---

## No Polling or Observation

DataLoom does not:

- Poll `ConnectivityProvider` repeatedly.
- Observe network changes.
- Wait for connectivity to improve.
- Register network callbacks.
- Subscribe to any connectivity flow or channel.

Each execution performs at most one connectivity check.

---

## No Automatic Queue Creation

Direct synchronization that cannot satisfy its connectivity requirement returns
a structured rejection. DataLoom does **not**:

- Enqueue the request automatically.
- Create background work automatically.
- Schedule a retry automatically.
- Sleep or delay the calling thread.

The host application decides how to handle the rejection.

---

## Concurrency Limitations

`SynchronizationConnectivityPreflight` is a stateless class. It owns no
`CoroutineScope`, selects no `CoroutineDispatcher`, and allocates no unbounded
collection. It performs no thread blocking.

`SynchronizationConnectivityConfiguration` is immutable after construction.

---

## KMP Compatibility

All DL-031 components are in `commonMain` and use Kotlin standard-library
types only. No Android API, JVM-only API, reflection, `ServiceLoader`, or DI
framework is used.

---

## Performance Restrictions

- At most one connectivity check per execution.
- No provider call when connectivity is not required.
- No polling or busy loop.
- No blocking wait.
- No unbounded collection.
- No payload copy for connectivity checking only.

---

## Security Restrictions

Connectivity diagnostics expose only:

- Requested `ConnectivityRequirement`
- Structural `ConnectivityStatus`
- Synchronization request ID
- Execution-rejection reason
- `ErrorCode`
- Queue outcome variant

The following are **never** exposed:

- Network names, SSIDs, carrier details, or IP addresses
- Request metadata values or credentials
- Authorization headers or checkpoint tokens
- Payload bytes or encryption keys
- Personal data
- Provider implementation state, exception messages, or stack traces
