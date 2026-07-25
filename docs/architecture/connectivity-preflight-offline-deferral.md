# Connectivity Preflight and Offline Deferral (DL-031)

This document describes the architectural flows introduced in DL-031 for
connectivity-aware synchronization execution and queued offline deferral.

---

## Contents

- [Overview](#overview)
- [Sequence 1: Connectivity Requirement Satisfied](#sequence-1-connectivity-requirement-satisfied)
- [Sequence 2: Connectivity Requirement Not Met](#sequence-2-connectivity-requirement-not-met)
- [Sequence 3: Connectivity Provider Failure](#sequence-3-connectivity-provider-failure)
- [Sequence 4: Queued Offline Deferral](#sequence-4-queued-offline-deferral)
- [Sequence 5: Cancellation During Connectivity Check](#sequence-5-cancellation-during-connectivity-check)
- [Component Responsibilities](#component-responsibilities)
- [SchedulerProvider Boundary](#schedulerprovider-boundary)
- [QueueProvider Boundary](#queueprovider-boundary)
- [Event Boundary](#event-boundary)
- [Direct Execution Boundary](#direct-execution-boundary)

---

## Overview

DL-031 adds a deterministic connectivity preflight step to
`SynchronizationExecutionCoordinator`. The step is inserted after pipeline
lookup and before `Started` event emission.

The preflight is driven by `SynchronizationConnectivityConfiguration` and
executed by `SynchronizationConnectivityPreflight`. When connectivity is not
required (`ConnectivityRequirement.NONE`), no provider is invoked.

`QueuedSynchronizationExecutionHandler` maps `CONNECTIVITY_REQUIREMENT_NOT_MET`
coordinator rejections to `QueueEntryExecutionOutcome.Reschedule` using an
injected `DataLoomClock` and the configured `offlineRescheduleDelay`.

---

## Sequence 1: Connectivity Requirement Satisfied

```
Host
  → SynchronizationExecutionCoordinator.execute(request, bindings)
      → ProviderLifecycleCoordinator: validate state (INITIALIZED or RUNNING)
      → SynchronizationProviderResolver.resolve(bindings)
            → ResolvedSynchronizationProviders (includes connectivityProvider)
      → SynchronizationPipelineRegistry.lookup(direction)
            → SynchronizationPipeline
      → SynchronizationConnectivityPreflight.evaluate(requirement, connectivityProvider, request)
            → ConnectivityProvider.currentConnectivity(ConnectivityCheckRequest)
                  → ProviderOperationResult.Success(ConnectivitySnapshot)
            → isSatisfied(snapshot, requirement) → true
            → ConnectivityPreflightResult.Satisfied(snapshot)
      → SynchronizationExecutionContext: construct context
      → SynchronizationEventDispatcher: emit Started
      → SynchronizationPipeline.execute(context)
            → SynchronizationResult
      → SynchronizationEventDispatcher: emit Completed
      → SynchronizationExecutionResult.Executed(result)
```

---

## Sequence 2: Connectivity Requirement Not Met

```
Host
  → SynchronizationExecutionCoordinator.execute(request, bindings)
      → ProviderLifecycleCoordinator: validate state
      → SynchronizationProviderResolver.resolve(bindings)
      → SynchronizationPipelineRegistry.lookup(direction)
      → SynchronizationConnectivityPreflight.evaluate(requirement, connectivityProvider, request)
            → ConnectivityProvider.currentConnectivity(ConnectivityCheckRequest)
                  → ProviderOperationResult.Success(ConnectivitySnapshot{UNAVAILABLE})
            → isSatisfied(snapshot, requirement) → false
            → ConnectivityPreflightResult.RequirementNotMet(requirement, status)
      → SynchronizationExecutionResult.Rejected(CONNECTIVITY_REQUIREMENT_NOT_MET)
            [no Started event]
            [no pipeline execution]
            [no Completed event]
```

---

## Sequence 3: Connectivity Provider Failure

```
Host
  → SynchronizationExecutionCoordinator.execute(request, bindings)
      → ProviderLifecycleCoordinator: validate state
      → SynchronizationProviderResolver.resolve(bindings)
      → SynchronizationPipelineRegistry.lookup(direction)
      → SynchronizationConnectivityPreflight.evaluate(requirement, connectivityProvider, request)
            → ConnectivityProvider.currentConnectivity(ConnectivityCheckRequest)
                  → ProviderOperationResult.Failure(DataLoomError)
            → ConnectivityPreflightResult.CheckFailed(error)
      → SynchronizationExecutionResult.Rejected(
            reason = CONNECTIVITY_CHECK_FAILED,
            connectivityCheckError = error,
        )
            [no Started event]
            [no pipeline execution]
            [no Completed event]
```

---

## Sequence 4: Queued Offline Deferral

```
DurableQueueExecutionProcessor
  → QueuedSynchronizationExecutionHandler.execute(entry)
      → QueuedSynchronizationWorkResolver.resolve(entry)
            → QueuedSynchronizationWork(request, bindings)
      → SynchronizationExecutionCoordinator.execute(request, bindings)
            → [connectivity preflight → RequirementNotMet]
            → SynchronizationExecutionResult.Rejected(CONNECTIVITY_REQUIREMENT_NOT_MET)
      → mapCoordinatorRejection:
            reason == CONNECTIVITY_REQUIREMENT_NOT_MET
                → offlineDeferralReschedule(entry, config, clock)
                    → now = clock.now()                         ← injected clock, read once
                    → nextMillis = now.epochMilliseconds + delay.milliseconds (overflow-safe)
                    → availableAt = DataLoomInstant(nextMillis)
                    → retryAttempt = entry.retryAttempt ?: RetryAttempt(1)
                → QueueEntryExecutionOutcome.Reschedule(availableAt, retryAttempt)
            [no RetryPolicy invocation]
            [no SchedulerProvider call]
            [no QueueProvider call from handler]
  → DurableQueueExecutionProcessor receives Reschedule
      → QueueProvider.reschedule(...)
```

---

## Sequence 5: Cancellation During Connectivity Check

```
SynchronizationConnectivityPreflight.evaluate(...)
  → ConnectivityProvider.currentConnectivity(request)
        → throw CancellationException
  ← CancellationException propagates through evaluate()
  ← CancellationException propagates through SynchronizationExecutionCoordinator.execute()
  ← CancellationException propagates to caller
      [not converted to ConnectivityPreflightResult]
      [not converted to SynchronizationExecutionResult.Rejected]
      [not converted to QueueEntryExecutionOutcome]
```

---

## Component Responsibilities

### SynchronizationConnectivityConfiguration

Immutable value holder. Contains the `ConnectivityRequirement` and
`offlineRescheduleDelay`. Performs no I/O during construction. Companion
constant `NONE` is the backward-compatible default.

### ConnectivityPreflightResult

Sealed interface with five variants:

| Variant | Meaning |
|---|---|
| `NotRequired` | No connectivity check needed. |
| `Satisfied(snapshot)` | Requirement met; snapshot preserved. |
| `ProviderNotConfigured` | Requirement set but no provider resolved. |
| `RequirementNotMet(requirement, status)` | Provider returned unsatisfying status. |
| `CheckFailed(error)` | Provider returned `ProviderOperationResult.Failure`. |

### SynchronizationConnectivityPreflight

Stateless evaluator. Owns no `CoroutineScope`. Invokes provider exactly once.
Propagates `CancellationException`.

### SynchronizationExecutionCoordinator

Extended with optional `connectivityConfiguration` and `connectivityPreflight`
constructor parameters. Default values maintain backward compatibility.
Connectivity preflight is evaluated after pipeline lookup and before context
construction.

### QueuedSynchronizationExecutionHandler

Extended with optional `connectivityConfiguration` and `clock` constructor
parameters. When both are present and the coordinator returns
`CONNECTIVITY_REQUIREMENT_NOT_MET`, the handler returns
`QueueEntryExecutionOutcome.Reschedule`. For all other connectivity rejections,
it returns `QueueEntryExecutionOutcome.Failed`.

---

## SchedulerProvider Boundary

`SchedulerProvider` is never invoked for offline queue deferral.

Offline deferral produces `QueueEntryExecutionOutcome.Reschedule` only. The
`DurableQueueExecutionProcessor` translates this outcome to
`QueueProvider.reschedule()`. No `ScheduleRequest` is created. No WorkManager
or background job scheduling occurs.

---

## QueueProvider Boundary

`QueuedSynchronizationExecutionHandler` never calls `QueueProvider` directly.
All queue persistence transitions are owned by `DurableQueueExecutionProcessor`.

---

## Event Boundary

Connectivity-rejected executions are rejected before `Started`. No lifecycle
or operational events are emitted for connectivity preflight failures.

The following events are not emitted for any connectivity rejection:

- `Started`
- `PhaseChanged`
- `ProgressUpdated`
- `Completed`
- `RetryScheduled`
- `ConflictDetected`

No new connectivity event variant is introduced in DL-031.

---

## Direct Execution Boundary

Direct synchronization that fails connectivity preflight returns
`SynchronizationExecutionResult.Rejected`. DataLoom does **not**:

- Enqueue the request automatically.
- Schedule a retry automatically.
- Wait for connectivity to change.
- Poll `ConnectivityProvider`.
- Observe network events.
- Create background work.
- Sleep or block.

The host application is responsible for deciding what to do after a
connectivity rejection.
