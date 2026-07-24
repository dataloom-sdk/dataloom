# Conflict Detection and Resolution Flow (DL-025)

**Module:** `dataloom-runtime`

---

## Overview

This document describes the deterministic conflict detection and resolution
flow introduced in DL-025. The orchestration is performed by
[`SynchronizationConflictOrchestrator`](../api/conflict-orchestration.md#synchronizationconflictorchestrator)
using application-supplied
[`ConflictDetector`](../api/conflict-contracts.md#conflictdetector) and
[`ConflictResolver`](../api/conflict-contracts.md#conflictresolver)
implementations registered in immutable registries.

---

## Primary flow

```
SynchronizationConflictOrchestrator
    │
    ├─ 1. ConflictDetectorRegistry.lookup(detectorId)
    │       │
    │       ├─ ABSENT  ──────────────────────────► DetectorNotFound
    │       │
    │       └─ FOUND
    │               │
    │               ▼
    │       2. ConflictDetector.detect(detectionRequest)
    │               │
    │               ├─ NoConflict ───────────────► NoConflict
    │               │
    │               └─ ConflictDetected
    │                       │
    │                       ├─ resolverId is null ─► ResolverNotConfigured
    │                       │
    │                       ▼
    │               3. ConflictResolverRegistry.lookup(resolverId)
    │                       │
    │                       ├─ ABSENT ───────────► ResolverNotFound
    │                       │
    │                       └─ FOUND
    │                               │
    │                               ▼
    │                       4. ConflictResolver.resolve(resolutionRequest)
    │                               │
    │                               └─ decision ──► Resolved
    │
```

---

## Step-by-step description

### Step 1 — Detector lookup

The orchestrator looks up the detector registered for the exact
`ConflictDetectorId` supplied in `ConflictOrchestrationBindings`.

- Lookup is exact: no fuzzy matching, class name matching, or ConflictType
  matching.
- If the detector is absent, the orchestrator returns
  `ConflictOrchestrationResult.DetectorNotFound` immediately. No further
  operation occurs.
- No resolver lookup occurs at this step.

### Step 2 — Conflict detection

The orchestrator invokes `ConflictDetector.detect(detectionRequest)` exactly
once with the `ConflictDetectionRequest` from the orchestration request.

- The detector is invoked at most once per `detectAndResolve` call.
- If the detector returns `ConflictDetectionResult.NoConflict`, the
  orchestrator returns `ConflictOrchestrationResult.NoConflict`. No resolver
  lookup or resolver invocation occurs.
- If the detector returns `ConflictDetectionResult.ConflictDetected`, the
  exact `SynchronizationConflict` is preserved and the flow continues.

### Step 3 — Resolver lookup (conditional)

Only reached when a conflict is detected.

- If `ConflictOrchestrationBindings.resolverId` is `null`, the orchestrator
  returns `ConflictOrchestrationResult.ResolverNotConfigured` immediately with
  the exact conflict. No resolver lookup occurs.
- Otherwise, the orchestrator looks up the resolver registered for the exact
  `ConflictResolverId`.
- If the resolver is absent, the orchestrator returns
  `ConflictOrchestrationResult.ResolverNotFound` with the exact conflict and
  the requested resolver ID. No fallback resolver is selected.

### Step 4 — Conflict resolution

Only reached when a conflict is detected and the resolver is found.

The orchestrator builds a `ConflictResolutionRequest` using the exact detected
`SynchronizationConflict` and the originating `SynchronizationRequest`. It
then invokes `ConflictResolver.resolve(resolutionRequest)` exactly once.

- The resolver is invoked at most once per `detectAndResolve` call.
- The exact `ConflictResolutionDecision` returned by the resolver is preserved.
- The orchestrator does **not** reinterpret, apply, persist, or relay the
  decision automatically.
- The orchestrator returns
  `ConflictOrchestrationResult.Resolved` with the exact conflict, decision,
  detector ID, and resolver ID.

---

## Missing-detector path

```
detectAndResolve(request)
    → ConflictDetectorRegistry.lookup("unknown-id")
    → null
    → return DetectorNotFound(detectorId = "unknown-id")
```

No detector is invoked. No resolver lookup occurs. No resolver is invoked.

---

## Missing-resolver path

```
detectAndResolve(request)
    → ConflictDetectorRegistry.lookup("my-detector")  → FakeDetector
    → FakeDetector.detect(...)  → ConflictDetected(conflict)
    → resolverId = "unknown-resolver"
    → ConflictResolverRegistry.lookup("unknown-resolver")  → null
    → return ResolverNotFound(conflict, resolverId = "unknown-resolver")
```

The detector is invoked. The conflict is preserved. No resolver is invoked.

---

## Resolver not configured path

```
detectAndResolve(request with resolverId = null)
    → ConflictDetectorRegistry.lookup("my-detector")  → FakeDetector
    → FakeDetector.detect(...)  → ConflictDetected(conflict)
    → resolverId is null
    → return ResolverNotConfigured(conflict, detectorId = "my-detector")
```

The detector is invoked. The conflict is preserved. No resolver lookup or
invocation occurs.

---

## Exception and cancellation behaviour

Unexpected exceptions from `ConflictDetector` or `ConflictResolver` propagate
normally out of `detectAndResolve`. They are **never** converted into a
`ConflictOrchestrationResult` variant.

Because the DL-014 contracts are synchronous (`fun`, not `suspend fun`),
`CancellationException` does not apply to detector or resolver invocations.
If contracts become suspend in a future contract revision, cancellation must
still propagate normally.

---

## Retry boundary

The orchestrator does **not** invoke `RetryPolicy` when:

- The detector is absent.
- The resolver is absent.
- The detector throws.
- The resolver throws.
- The resolver returns an unresolved or deferred decision.

Conflict retry and manual-resolution workflows belong to later orchestration
layers not implemented in DL-025.

---

## Queue and scheduler boundary

The orchestrator does not schedule work, enqueue durable entries, or process
queue entries. It does not advance checkpoints or acknowledge outbound changes.

---

## Event dispatch boundary

The orchestrator does not emit `ConflictDetected` events or any
`SynchronizationEvent`. It owns no `CoroutineScope`, `Flow`, `StateFlow`,
`SharedFlow`, or `Channel`. The `ConflictOrchestrationResult` contains
sufficient structural information for a future event layer to emit conflict
events safely.

---

## Pipeline and storage boundary

The orchestrator does not call `StorageProvider`, `TransportProvider`,
`SchedulerProvider`, `ConnectivityProvider`, `QueueProvider`,
`SynchronizationPipeline`, `SynchronizationExecutionCoordinator`, any
lifecycle coordinator, or any observer.

---

## Security

- No `toString()` path on any result, request, or registry exposes payload
  content, credentials, authorization headers, checkpoint tokens, encryption
  keys, or personal data.
- Safe diagnostic output is limited to structural IDs: `ConflictId`,
  `ConflictDetectorId`, `ConflictResolverId`, `ConflictType`,
  `EntityReference`, and result variant names.
- Local and remote payloads (`DataLoomPayload`) remain **opaque** to the
  orchestrator at every step of the flow. The orchestrator does not inspect,
  copy, deserialize, or log payload content.

---

## KMP compatibility

All types in `io.dataloom.runtime.conflict` are KMP-compatible (`commonMain`).
No Android APIs, JVM-only APIs, reflection, ServiceLoader, or DI framework
dependency is introduced.

---

## Performance

- Registry lookup is constant-time (hash-map backed).
- At most one detector and one resolver are invoked per `detectAndResolve` call.
- No payload-sized buffers are allocated.
- No serialization or deserialization is performed.
- No reflection or service discovery is used.

---

## Related documentation

- [Conflict Orchestration API (DL-025)](../api/conflict-orchestration.md)
- [Conflict Contracts (DL-014)](../api/conflict-contracts.md)
- [Conflict Boundaries (DL-014)](./conflict-boundaries.md)
