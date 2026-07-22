# DataLoom Conflict Boundaries (DL-014)

This document describes the architectural responsibility boundaries for
conflict detection and resolution in DataLoom.

> **Important:** Conflict orchestration is not implemented in this release.
> This document describes the intended architecture and the boundaries that
> govern future implementation.

---

## Overview

DataLoom coordinates conflict detection and resolution, while the host
application owns domain-specific conflict rules and merge behavior. Each
component has a clearly defined responsibility boundary.

```text
Application
  ├── ConflictDetector implementation (domain rules)
  └── ConflictResolver implementation (domain policies)

DataLoom Runtime (deferred)
  ├── Invokes ConflictDetector with ConflictDetectionRequest
  ├── Evaluates ConflictDetectionResult
  ├── Invokes ConflictResolver with ConflictResolutionRequest
  └── Applies ConflictResolutionDecision

Storage Provider (deferred)
  └── May apply the resolved change when instructed by the runtime

Transport Provider (deferred)
  └── May report remote conflicts through canonical errors
```

---

## Detector Responsibility

A `ConflictDetector`:

- Evaluates already-available local and remote change information.
- Returns `ConflictDetectionResult.NoConflict` or
  `ConflictDetectionResult.ConflictDetected`.
- Operates synchronously and deterministically.
- Does **not** query storage or network.
- Does **not** apply changes or persist data.
- Does **not** automatically log payload content.
- Does **not** inspect opaque payload content (generic detectors).
- Does **not** assume entity-version ordering.
- Does **not** generate conflict identifiers unless explicitly configured.

The application supplies the detector implementation. DataLoom does not
include a built-in detector in this release.

---

## Resolver Responsibility

A `ConflictResolver`:

- Evaluates a detected `SynchronizationConflict` within a
  `ConflictResolutionRequest`.
- Returns a `ConflictResolutionDecision` (`UseLocal`, `UseRemote`, `Merge`,
  `Defer`, or `Fail`).
- Operates synchronously and deterministically.
- Does **not** access databases or network services.
- Does **not** apply changes, persist decisions, or modify queues.
- Does **not** automatically log payload content.
- Does **not** call retry policy.
- Does **not** depend on platform-specific types.

The application supplies the resolver implementation. DataLoom does not
include a built-in resolver strategy in this release.

---

## Runtime Responsibility (Deferred)

The future synchronization runtime will:

1. Identify that local and remote changes may conflict.
2. Construct a `ConflictDetectionRequest` from available change information.
3. Invoke `ConflictDetector.detect(request)`.
4. Evaluate the `ConflictDetectionResult`:
   - `NoConflict` → continue normal synchronization.
   - `ConflictDetected` → construct a `ConflictResolutionRequest` and invoke
     the resolver.
5. Invoke `ConflictResolver.resolve(request)`.
6. Apply the `ConflictResolutionDecision`:
   - `UseLocal` → apply the conflict's local change.
   - `UseRemote` → apply the conflict's remote change.
   - `Merge` → apply the resolver-supplied merged `ChangeEvent`.
   - `Defer` → leave the conflict unresolved for later handling.
   - `Fail` → handle the canonical error without automatic retry.

This orchestration flow is **not implemented** in DL-014.

---

## Storage Provider Responsibility

The storage provider may later apply the resolved change when instructed by
the runtime.

It must **not** choose the conflict policy unless explicitly configured as
part of the application adapter. Storage mutation is deferred to runtime
orchestration.

---

## Transport Provider Responsibility

The transport provider may report remote conflicts through canonical
`DataLoomError` values or future protocol adaptation.

It must **not** directly mutate local storage. Local storage mutation is
coordinated by the runtime.

---

## Retry Boundary

- A deferred conflict (`ConflictResolutionDecision.Defer`) is **not**
  automatically a retry decision.
- A failed conflict resolution (`ConflictResolutionDecision.Fail`) is **not**
  automatically retryable.
- Retry policy uses the canonical `DataLoomError`.
- The future runtime may evaluate retry policy after a conflict decision.
- `ConflictResolver` must **not** call `RetryPolicy`.
- `RetryPolicy` must **not** resolve conflicts.
- Queue and scheduling behavior remains deferred.

---

## Payload Opacity

- `DataLoomPayload` is opaque within DataLoom core.
- Generic detectors must **not** inspect payload content.
- Application resolvers may interpret payloads only through
  application-controlled serialization outside DataLoom core.
- DataLoom does not provide JSON, protobuf, or field-level merge logic.
- Merged payloads are created by the host application.
- Payload content must **not** be logged automatically.
- `toString()` output must **not** reveal payload bytes.

---

## Version Opacity

- `EntityVersion` is opaque within DataLoom core.
- DataLoom must **not** assume numeric ordering.
- DataLoom must **not** assume timestamps.
- DataLoom must **not** assume ETag semantics.
- Application detectors may interpret versions according to their own contract.
- Version-comparison utilities are deferred to a future issue.

---

## Android-First and KMP Considerations

All conflict contracts are defined in `dataloom-api/src/commonMain/kotlin`.
They are platform-independent and safe for Kotlin Multiplatform common code.

Rules:

- Public conflict types must **not** expose Android, JVM-specific, Apple, or
  other platform-specific types.
- Shared contracts must **not** depend on Android APIs.
- Android-specific conflict integration (for example, WorkManager-backed
  deferral) belongs in a dedicated Android module (planned, not implemented).
- KMP compatibility must **not** delay the Android-first vertical slice.
- New platform targets require an approved issue.

---

## Security Restrictions

- Conflict metadata must **not** include credentials.
- Conflict metadata must **not** include encryption keys.
- Conflict metadata must **not** contain full payloads.
- Error messages must **not** expose secrets or sensitive payload content.
- Tests and examples must use placeholder values only.
- Application-defined resolver logic is responsible for safely processing
  sensitive payloads.
- `toString()` output for conflict models must **not** reveal payload bytes.

---

## Deferred Conflict Features

The following are not implemented in DL-014:

- Built-in client-wins resolver
- Built-in server-wins resolver
- Timestamp-based resolver
- Version-vector comparison
- Field-level merging
- JSON merging
- CRDT support
- Interactive user resolution
- Conflict persistence
- Conflict queue
- Conflict history
- Conflict metrics
- Conflict retry orchestration
- Provider-reported conflict conversion
- Partial change-set conflict handling
- Automatic conflict ID generation
- Runtime application of decisions
- Runtime conflict orchestration
- Storage mutation for conflict resolution
- Transport mutation for conflict resolution

---

## Module Placement

| Source location | Purpose |
|---|---|
| `dataloom-api/src/commonMain/kotlin/io/dataloom/api/conflict/` | Public conflict contracts |
| `dataloom-api/src/commonMain/kotlin/io/dataloom/api/identifier/` | Conflict, detector, and resolver identifiers |
| `dataloom-api/src/commonTest/kotlin/io/dataloom/api/conflict/` | Common contract tests |

All conflict contracts reside in `dataloom-api`, which has no dependency on
any other DataLoom implementation module.

---

## Related Documentation

- [Conflict Contracts (DL-014)](../api/conflict-contracts.md)
- [Change Model (DL-008)](../api/change-model.md)
- [Error Model](../api/error-model.md)
- [Module Architecture](./modules.md)
- [Platform Strategy (DL-006)](./platform-strategy.md)
- [Transport Boundaries](./transport-boundaries.md)
