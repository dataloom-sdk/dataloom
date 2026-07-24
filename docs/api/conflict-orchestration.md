# Conflict Orchestration (DL-025)

**Package:** `io.dataloom.runtime.conflict`  
**Module:** `dataloom-runtime`

---

## Overview

DL-025 provides deterministic conflict detection and resolution orchestration
for DataLoom. It coordinates application-supplied detectors and resolvers
through immutable registries and an explicit ID-based binding model.

The orchestrator does **not**:

- Apply resolution decisions to application storage.
- Mutate local or remote payloads.
- Execute inbound or outbound synchronization pipelines.
- Read or write checkpoints.
- Acknowledge outbound changes.
- Evaluate or schedule retry policy.
- Process queue entries.
- Dispatch synchronization events.
- Initialize or shut down providers.
- Discover or create detector or resolver implementations.

---

## Components

### ConflictDetectorRegistry

Immutable registry of
[`ConflictDetector`](./conflict-contracts.md#conflictdetector) instances,
keyed by [`ConflictDetectorId`](./conflict-contracts.md#conflict-identifiers).

**Package:** `io.dataloom.runtime.conflict`

```kotlin
class ConflictDetectorRegistry(
    detectors: Collection<ConflictDetector>,
)
```

#### Behaviour

- Accepts application-supplied `ConflictDetector` instances.
- Defensively copies the provided collection at construction time. Mutations
  to the original collection after construction have no effect.
- Preserves registration order.
- Rejects duplicate `ConflictDetectorId` values with
  `IllegalArgumentException`.
- Supports exact lookup by `ConflictDetectorId`.
- Returns `null` when a requested ID is absent.
- Performs no detection or resolution during construction.
- Uses no global registry, reflection, or ServiceLoader.
- KMP-compatible (commonMain).

#### Selection key

The explicit `ConflictDetectorId` returned by `ConflictDetector.id` is the
selection key. Detectors are never selected by class name, collection hash
order, `toString()`, `ConflictType`, entity type, or platform service
discovery.

#### API

| Member | Description |
|---|---|
| `lookup(id: ConflictDetectorId): ConflictDetector?` | Returns the registered detector, or `null`. |
| `detectors: List<ConflictDetector>` | Read-only snapshot of all detectors in registration order. |

---

### ConflictResolverRegistry

Immutable registry of
[`ConflictResolver`](./conflict-contracts.md#conflictresolver) instances,
keyed by [`ConflictResolverId`](./conflict-contracts.md#conflict-identifiers).

**Package:** `io.dataloom.runtime.conflict`

```kotlin
class ConflictResolverRegistry(
    resolvers: Collection<ConflictResolver>,
)
```

#### Behaviour

- Accepts application-supplied `ConflictResolver` instances.
- Defensively copies the provided collection at construction time.
- Preserves registration order.
- Rejects duplicate `ConflictResolverId` values with `IllegalArgumentException`.
- Supports exact lookup by `ConflictResolverId`.
- Returns `null` when a requested ID is absent.
- Performs no detection or resolution during construction.
- Uses no global registry, reflection, or ServiceLoader.
- KMP-compatible (commonMain).

#### Selection key

The explicit `ConflictResolverId` returned by `ConflictResolver.id` is the
selection key. Resolvers are **never** automatically selected by conflict type,
class name, registration order, or ID sorting. Resolution policy is
application-controlled through explicit `ConflictOrchestrationBindings`.

#### API

| Member | Description |
|---|---|
| `lookup(id: ConflictResolverId): ConflictResolver?` | Returns the registered resolver, or `null`. |
| `resolvers: List<ConflictResolver>` | Read-only snapshot of all resolvers in registration order. |

---

### ConflictOrchestrationBindings

Immutable binding model that pairs a required
[`ConflictDetectorId`](./conflict-contracts.md#conflict-identifiers) with an
optional [`ConflictResolverId`](./conflict-contracts.md#conflict-identifiers).

**Package:** `io.dataloom.runtime.conflict`

```kotlin
data class ConflictOrchestrationBindings(
    val detectorId: ConflictDetectorId,
    val resolverId: ConflictResolverId?,
)
```

#### Behaviour

- `detectorId` is required. The orchestrator performs exactly one detector
  lookup using this value.
- `resolverId` is optional. When `null`:
  - Detection is still performed.
  - A detected conflict is represented as
    `ConflictOrchestrationResult.ResolverNotConfigured`.
  - DataLoom does **not** silently choose a resolver as a fallback.
- Construction performs no registry lookup, no detection, and no resolution.
- Value-based equality.
- KMP-compatible (commonMain).

---

### ConflictOrchestrationRequest

Immutable request supplied to
[`SynchronizationConflictOrchestrator`](#synchronizationconflictorchestrator)
for a single detection and optional resolution cycle.

**Package:** `io.dataloom.runtime.conflict`

```kotlin
data class ConflictOrchestrationRequest(
    val detectionRequest: ConflictDetectionRequest,
    val bindings: ConflictOrchestrationBindings,
)
```

#### Behaviour

- Preserves the exact `ConflictDetectionRequest` and
  `ConflictOrchestrationBindings` supplied at construction.
- Performs no registry lookup, no detector or resolver invocation, and does
  not inspect or mutate payload content.
- Callers are responsible for supplying a complete `ConflictDetectionRequest`
  per the DL-014 contract. The orchestrator does not read local application
  storage or generate payload content.
- Value-based equality.
- KMP-compatible (commonMain).

#### Diagnostic safety

`toString()` exposes only the detector ID, resolver ID, and entity reference.
It does **not** expose local or remote payload content, credentials,
authorization values, encryption keys, personal data, checkpoint tokens, or
stack traces.

---

### ConflictOrchestrationStatus

Canonical status values for orchestration outcomes.

**Package:** `io.dataloom.runtime.conflict`

```kotlin
enum class ConflictOrchestrationStatus {
    DETECTOR_NOT_FOUND,
    NO_CONFLICT,
    RESOLVER_NOT_CONFIGURED,
    RESOLVER_NOT_FOUND,
    RESOLVED,
}
```

| Status | Meaning |
|---|---|
| `DETECTOR_NOT_FOUND` | The configured detector ID was absent from the registry. No detector ran. |
| `NO_CONFLICT` | The detector completed and reported no conflict. No resolver ran. |
| `RESOLVER_NOT_CONFIGURED` | A conflict was detected but `resolverId` is `null`. No resolver ran. |
| `RESOLVER_NOT_FOUND` | A conflict was detected and a resolver ID was supplied, but no matching resolver exists. No resolver ran. |
| `RESOLVED` | A conflict was detected, the resolver was found, and the resolver returned a decision. |

Do not persist or serialize enum ordinals. Use variant names for any durable
representation.

---

### ConflictOrchestrationResult

Sealed result produced by
[`SynchronizationConflictOrchestrator.detectAndResolve`](#synchronizationconflictorchestrator).

**Package:** `io.dataloom.runtime.conflict`

```kotlin
sealed interface ConflictOrchestrationResult {
    data class DetectorNotFound(val detectorId: ConflictDetectorId) : ConflictOrchestrationResult
    data class NoConflict(val detectorId: ConflictDetectorId) : ConflictOrchestrationResult
    class ResolverNotConfigured(val conflict: SynchronizationConflict, val detectorId: ConflictDetectorId) : ConflictOrchestrationResult
    class ResolverNotFound(val conflict: SynchronizationConflict, val resolverId: ConflictResolverId) : ConflictOrchestrationResult
    class Resolved(val conflict: SynchronizationConflict, val decision: ConflictResolutionDecision, val detectorId: ConflictDetectorId, val resolverId: ConflictResolverId) : ConflictOrchestrationResult
}
```

#### Variants

**`DetectorNotFound`**
- The configured `ConflictDetectorId` was not found in the registry.
- Preserves the requested `detectorId`.
- No detector, resolver lookup, or resolver invocation occurred.

**`NoConflict`**
- The detector completed and reported no conflict.
- Preserves the `detectorId` of the detector that ran.
- No resolver lookup or invocation occurred.

**`ResolverNotConfigured`**
- A conflict was detected but `ConflictOrchestrationBindings.resolverId` is `null`.
- Preserves the exact `SynchronizationConflict` and the `detectorId`.
- No resolver lookup or invocation occurred.

**`ResolverNotFound`**
- A conflict was detected and a `resolverId` was supplied but no matching
  resolver exists.
- Preserves the exact `SynchronizationConflict` and the requested `resolverId`.
- No resolver was invoked.

**`Resolved`**
- A conflict was detected, the resolver was found, and the resolver returned
  a decision.
- Preserves the exact `SynchronizationConflict`, `ConflictResolutionDecision`,
  `detectorId`, and `resolverId`.
- The decision is **not** applied to storage, queues, or any pipeline.

#### Security

`toString()` on variants that carry `SynchronizationConflict` or
`ConflictResolutionDecision` exposes only structural IDs (conflict ID, conflict
type, detector ID, resolver ID) and the variant name. It does not expose
payload content, credentials, stack traces, or implementation `toString()`
output.

---

### SynchronizationConflictOrchestrator

Platform-independent orchestrator that coordinates conflict detection and
optional resolution for a single cycle.

**Package:** `io.dataloom.runtime.conflict`

```kotlin
class SynchronizationConflictOrchestrator(
    private val detectorRegistry: ConflictDetectorRegistry,
    private val resolverRegistry: ConflictResolverRegistry,
) {
    fun detectAndResolve(request: ConflictOrchestrationRequest): ConflictOrchestrationResult
}
```

#### Required flow

```
detectAndResolve(request)
    → look up detector by detectorId
    → if absent: return DetectorNotFound
    → invoke detector.detect(detectionRequest) exactly once
    → if NoConflict: return NoConflict
    → if ConflictDetected:
        → if resolverId is null: return ResolverNotConfigured
        → look up resolver by resolverId
        → if absent: return ResolverNotFound
        → build ConflictResolutionRequest
        → invoke resolver.resolve(request) exactly once
        → return Resolved with exact decision
```

#### Deterministic guarantees

- Detector lookup occurs before detector execution.
- Resolver lookup occurs only after conflict detection.
- The resolver is never called when no conflict exists.
- The resolver is never called when `resolverId` is `null`.
- The resolver is never called when its ID is absent.
- Each selected component is invoked at most once per call.
- No fallback detector or resolver is selected.
- No sorting of detectors or resolvers occurs.

#### Exception and cancellation boundary

Unexpected exceptions from `ConflictDetector` or `ConflictResolver` propagate
normally. They are never converted into a `ConflictOrchestrationResult` variant.

Because the DL-014 contracts are synchronous (`fun`, not `suspend fun`),
`CancellationException` does not apply to detector or resolver invocations
directly. If contracts become suspend in a future revision, cancellation must
still propagate normally.

#### Boundaries

The orchestrator must not call:

- `StorageProvider`
- `TransportProvider`
- `SchedulerProvider`
- `ConnectivityProvider`
- `QueueProvider`
- `RetryPolicy`
- `SynchronizationPipeline`
- `SynchronizationExecutionCoordinator`
- Any lifecycle coordinator
- Any event dispatcher or observer

---

## No automatic resolution

DataLoom coordinates conflict orchestration, but does not apply resolution
decisions automatically. The application or a future integration layer is
responsible for consuming the `ConflictResolutionDecision` and acting on it.

---

## Performance notes

- Registry lookup is constant-time (hash-map backed).
- At most one detector and one resolver are invoked per `detectAndResolve` call.
- No payload-sized buffers are allocated.
- No serialization or deserialization is performed.
- No reflection or service discovery is used.

---

## Security notes

- No variant exposes raw `Throwable` or stack traces.
- No diagnostic output exposes payload bytes, credentials, authorization
  headers, checkpoint tokens, encryption keys, or personal data.
- Safe diagnostics include only structural IDs (conflict ID, conflict type,
  detector ID, resolver ID) and result variant names.

---

## KMP compatibility

All types in `io.dataloom.runtime.conflict` use Kotlin standard-library and
DataLoom API types only. They are safe for use in Kotlin Multiplatform common
code (`commonMain`).

No Android APIs, JVM-only APIs, reflection, ServiceLoader, or DI framework
dependency is introduced.

---

## Usage example

```kotlin
// Register detectors and resolvers
val detectorRegistry = ConflictDetectorRegistry(listOf(myVersionDetector))
val resolverRegistry = ConflictResolverRegistry(listOf(myServerWinsResolver))

val orchestrator = SynchronizationConflictOrchestrator(
    detectorRegistry = detectorRegistry,
    resolverRegistry = resolverRegistry,
)

// Build a request
val request = ConflictOrchestrationRequest(
    detectionRequest = ConflictDetectionRequest(
        synchronizationRequest = syncRequest,
        localChange = localEvent,
        remoteChange = remoteEvent,
    ),
    bindings = ConflictOrchestrationBindings(
        detectorId = ConflictDetectorId("entity-version-detector"),
        resolverId = ConflictResolverId("server-preferred-resolver"),
    ),
)

// Run orchestration
when (val result = orchestrator.detectAndResolve(request)) {
    is ConflictOrchestrationResult.DetectorNotFound ->
        // Handle missing detector configuration
    is ConflictOrchestrationResult.NoConflict ->
        // Continue normal synchronization
    is ConflictOrchestrationResult.ResolverNotConfigured ->
        // Conflict detected but no resolver configured; surface to application
    is ConflictOrchestrationResult.ResolverNotFound ->
        // Handle missing resolver configuration
    is ConflictOrchestrationResult.Resolved ->
        // Apply result.decision — DataLoom does not apply it automatically
}
```

---

## Related documentation

- [Conflict Contracts (DL-014)](./conflict-contracts.md)
- [Conflict Detection and Resolution Flow](../architecture/conflict-detection-resolution-flow.md)
- [Conflict Boundaries (DL-014)](../architecture/conflict-boundaries.md)
