# DL-AUDIT-002: Re-Audit of DL-009 through DL-017 after Recovery

**Audit date:** 2026-07-22
**Auditor:** Copilot Coding Agent (DL-AUDIT-002)
**Scope:** Issues DL-009 through DL-017 — full independent re-audit after recovery
**Repository:** dataloom-sdk/dataloom
**Branch audited:** `main` (commit `1e11af556e313cee209a7cb774f49d07d4cc378e`)
**Closes:** #44

---

## Executive Summary

The previous audit (DL-AUDIT-001) found that DL-009, DL-011, DL-012, DL-013,
DL-015, DL-016, and DL-017 had been merged with only initial plan commits. A
recovery effort subsequently reimplemented all seven.

This audit independently re-examines all nine issues (DL-009 through DL-017)
against the current `main` branch. **Eight of the nine issues PASS. One issue
(DL-013) is INCOMPLETE and blocks DL-018.**

| Issue | Title | Verdict |
|---|---|---|
| DL-009 | Storage Provider SPI | ✅ PASS |
| DL-010 | Transport Provider SPI | ✅ PASS |
| DL-011 | Acknowledgements and checkpoints | ✅ PASS |
| DL-012 | Scheduler and connectivity SPI | ✅ PASS |
| DL-013 | Retry contracts | ❌ INCOMPLETE |
| DL-014 | Conflict contracts | ✅ PASS |
| DL-015 | Queue models and queue provider | ✅ PASS |
| DL-016 | Results, progress, events, observers | ✅ PASS |
| DL-017 | Clock and identifier generation | ✅ PASS |

**The repository is NOT ready for DL-018 until DL-013 is fully implemented.**

---

## Phase 1 — Repository and Governance

### Toolchain

| Item | Value |
|---|---|
| Gradle Wrapper | 9.5.0 |
| Kotlin | 2.4.10 |
| Java toolchain | Temurin 17 |
| JVM bytecode target | 17 |
| KMP targets | JVM (commonMain + jvmMain) |
| Configuration cache | Enabled |

### Module structure

| Module | Type | Description |
|---|---|---|
| `dataloom-api` | KMP library | Stable public contracts and models |
| `dataloom-core` | KMP library | Platform-independent runtime foundations |
| `dataloom-runtime` | KMP library | Future synchronization runtime (stub only) |
| `dataloom-testing` | KMP library | Test utilities |
| `build-logic` | Included build | Convention plugin (`io.dataloom.kotlin.multiplatform-library`) |

### Module dependency graph

```
dataloom-runtime  → dataloom-api, dataloom-core
dataloom-core     → dataloom-api
dataloom-testing  → dataloom-api, dataloom-core
```

No production module (dataloom-api, dataloom-core, dataloom-runtime) depends
on dataloom-testing. ✅

### KMP platform independence

Every `commonMain` source file audited. No Android, JVM-only, Apple-specific,
or third-party platform types are present in any `commonMain` source set. The
convention plugin applies `kotlin("multiplatform")` only. ✅

### GitHub Actions CI

Single workflow: `.github/workflows/pr-validation.yml`

```
trigger : pull_request (main), push (main), workflow_dispatch
runner  : ubuntu-latest
timeout : 20 minutes
steps   :
  1. Checkout (persist-credentials: false)
  2. Set up Java (temurin 17)
  3. Set up Gradle (cache-provider: basic)
  4. Make wrapper executable
  5. ./gradlew --version
  6. ./gradlew projects
  7. ./gradlew build --configuration-cache
  8. ./gradlew check --configuration-cache
  9. ./gradlew :dataloom-api:allTests :dataloom-core:allTests
         :dataloom-runtime:allTests :dataloom-testing:allTests
 10. ./gradlew :dataloom-runtime:dependencies --configuration jvmRuntimeClasspath
```

**Latest completed CI run for this PR branch:** success (run ID 29923035095,
workflow `pr-validation.yml`, attempt 2). ✅

**Local build verification performed during this audit:**
`./gradlew build --configuration-cache` completed with exit code 0. ✅

---

## Phase 2 — Cross-Cutting Checks

### Public API hygiene

All `commonMain` packages examined. No third-party library types, no
`java.time`, no Android SDK types, and no platform-specific cursor or DAO
types appear in any public API. ✅

### `explicitApi()` enforcement

`dataloom-api/build.gradle.kts` applies `kotlin { explicitApi() }`. Every
public declaration carries an explicit visibility modifier. ✅

### KDoc coverage

All public types and members carry KDoc documentation. ✅

### toString() sensitive-data exposure

All `toString()` overrides checked:

- `OutboundChangeReadRequest.toString()` does not expose payload bytes. ✅
- `ChangeSetAcknowledgement.toString()` reports only `eventCount`, not event
  content. ✅
- `SynchronizationResult.PartiallySucceeded.toString()` reports only
  `errorCount`, not error messages. ✅
- `SynchronizationEvent.Completed` uses `data class` default toString on
  `result`; the `SynchronizationResult` sealed types do not contain payload
  bytes in their `toString()` paths. ✅
- `SynchronizationCheckpoint.toString()` is the data class default and
  exposes the `CheckpointToken` value. **See finding F-001.**

---

## DL-009 — Storage Provider SPI

**Verdict: PASS**

### Required contracts

| Contract | Present | Notes |
|---|---|---|
| `OutboundChangeReadRequest` | ✅ | `io.dataloom.api.storage` |
| `OutboundChangeReadResult` | ✅ | Sealed: `NoChanges`, `Changes` |
| `InboundChangeApplyRequest` | ✅ | `io.dataloom.api.storage` |
| `StorageProvider` | ✅ | Extends `DataLoomProvider` |
| Defensive entityTypes copy | ✅ | `entityTypes.toSet()` at construction |
| maxEvents > 0 validation | ✅ | `require(maxEvents == null || maxEvents > 0)` |
| `ProviderType.STORAGE` | ✅ | Present in `ProviderType` enum |
| Provider operations return `ProviderOperationResult` | ✅ | All five operations |
| No Room/SQLite/DAO/Android types in public API | ✅ | |
| StorageProvider is a synchronization adapter | ✅ | Documented; no domain query methods |

### DL-011 additions present on StorageProvider

| Operation | Present |
|---|---|
| `acknowledgeOutboundChanges(OutboundChangeAcknowledgementRequest)` | ✅ |
| `readCheckpoint(CheckpointReadRequest)` | ✅ |
| `writeCheckpoint(CheckpointWriteRequest)` | ✅ |

### Tests

`StorageContractsTest.kt` — common tests with real assertions covering:
defensive copy invariants, maxEvents validation, NoChanges result, Changes
result, provider descriptor type, apply-inbound, and equality. ✅

### Documentation

`docs/api/storage-provider.md` — covers all contracts and usage examples.
`docs/architecture/storage-boundaries.md` — covers module separation. ✅

---

## DL-010 — Transport Provider SPI

**Verdict: PASS**

### Required contracts

| Contract | Present | Notes |
|---|---|---|
| `PushChangesRequest` | ✅ | Data class; no network in construction |
| `PullChangesRequest` | ✅ | Defensive entityTypes copy; maxEvents validation |
| `PullChangesResult` | ✅ | Sealed: `NoChanges(data class)`, `Changes` |
| `TransportProvider` | ✅ | Extends `DataLoomProvider` |
| `ProviderType.TRANSPORT` | ✅ | Present in `ProviderType` enum |
| Protocol independence | ✅ | No HTTP, WebSocket, or gRPC types in public API |
| Defensive collection behavior (PullChangesRequest) | ✅ | entityTypes defensively copied |
| maxEvents validation | ✅ | `require(maxEvents == null || maxEvents > 0)` |
| Provider failure → `ProviderOperationResult.Failure` | ✅ | Documented |
| DL-011: pushChanges returns `ProviderOperationResult<ChangeSetAcknowledgement>` | ✅ | |
| DL-011: PullChangesRequest carries optional checkpoint | ✅ | `checkpoint: SynchronizationCheckpoint?` |
| DL-011: PullChangesResult carries optional nextCheckpoint | ✅ | Both `NoChanges` and `Changes` variants |

`PullChangesResult.NoChanges` is correctly implemented as a `data class`
(not `data object`) to carry the optional `nextCheckpoint`. ✅

### Tests

`TransportContractsTest.kt` — real assertions covering push, pull, provider
type, defensive copy, maxEvents, checkpoint propagation. ✅

### Documentation

`docs/api/transport-provider.md` — covers all contracts and DL-011 updates. ✅

---

## DL-011 — Acknowledgements and Checkpoints

**Verdict: PASS**

### Required contracts

| Contract | Present | Notes |
|---|---|---|
| `CheckpointKey` | ✅ | `Identifiers.kt` — blank-rejected value class |
| `CheckpointToken` | ✅ | `Identifiers.kt` — blank-rejected value class |
| `SynchronizationCheckpoint` | ✅ | `io.dataloom.api.synchronization` |
| `ChangeAcknowledgementStatus` | ✅ | Enum: ACCEPTED, RETRY, REJECTED |
| `ChangeEventAcknowledgement` | ✅ | Data class |
| `ChangeSetAcknowledgement` | ✅ | Custom class |
| Empty acknowledgement rejection | ✅ | `require(events.isNotEmpty())` |
| Duplicate acknowledgement rejection | ✅ | Duplicate eventId check in init |
| Defensive copy of events list | ✅ | `events.toList()` |
| `OutboundChangeAcknowledgementRequest` | ✅ | Data class |
| `CheckpointReadRequest` | ✅ | Data class |
| `CheckpointWriteRequest` | ✅ | Data class |
| StorageProvider: acknowledgeOutboundChanges | ✅ | See DL-009 section |
| StorageProvider: readCheckpoint / writeCheckpoint | ✅ | See DL-009 section |
| TransportProvider.pushChanges returns `ChangeSetAcknowledgement` | ✅ | |
| PullChangesRequest optional checkpoint | ✅ | |
| PullChangesResult optional nextCheckpoint | ✅ | Both variants |
| "Apply inbound before checkpoint advance" documented | ✅ | `StorageProvider.writeCheckpoint` KDoc |
| No automatic checkpoint persistence | ✅ | Documented; construction performs no persistence |

### Tests

`SynchronizationContractsTest.kt` — real assertions covering all
acknowledgement and checkpoint contract types, including empty/duplicate
event rejection. ✅

### Documentation

`docs/api/acknowledgement-contracts.md` and `docs/api/checkpoint-contracts.md`
— comprehensive coverage. ✅

---

## DL-012 — Scheduler and Connectivity SPI

**Verdict: PASS**

### Required contracts

| Contract | Present | Notes |
|---|---|---|
| `ScheduleId` | ✅ | `Identifiers.kt` |
| `SchedulingDelay` | ✅ | `io.dataloom.api.scheduling`; rejects negative ms |
| `ExistingSchedulePolicy` | ✅ | Enum: KEEP, REPLACE |
| `ConnectivityRequirement` | ✅ | Enum: NONE, AVAILABLE, UNMETERED |
| `ScheduleConstraints` | ✅ | Data class |
| `ScheduleRequest` | ✅ | Data class |
| `ScheduleReceipt` | ✅ | Data class |
| `ScheduleCancellationRequest` | ✅ | Data class |
| `SchedulerProvider` | ✅ | Extends `DataLoomProvider` |
| `ConnectivityStatus` | ✅ | Enum: UNKNOWN, UNAVAILABLE, AVAILABLE, LIMITED |
| `ConnectivitySnapshot` | ✅ | Data class; no IP/SSID/platform types |
| `ConnectivityCheckRequest` | ✅ | Data class |
| `ConnectivityProvider` | ✅ | Extends `DataLoomProvider` |
| `ProviderType.SCHEDULER` | ✅ | Present in `ProviderType` enum |
| `ProviderType.CONNECTIVITY` | ✅ | Present in `ProviderType` enum |
| No WorkManager/ConnectivityManager/Android/Apple/JVM types in public API | ✅ | |

`SchedulingDelay` is reused across DL-012 and DL-013 (via
`SynchronizationEvent.RetryScheduled`), with no duplication. ✅

### Tests

`SchedulingContractsTest.kt` and `ConnectivityContractsTest.kt` — real
assertions covering all types, provider contracts, and validation rules. ✅

### Documentation

`docs/api/scheduler-provider.md` and `docs/api/connectivity-provider.md` —
comprehensive coverage. ✅

---

## DL-013 — Retry Contracts

**Verdict: INCOMPLETE — BLOCKS DL-018**

### Defect F-002: Required retry contracts are absent

| Contract | Present | Severity |
|---|---|---|
| `RetryAttempt` | ✅ | |
| `RetryPolicyId` | ❌ | Critical |
| `RetryOperation` | ❌ | Critical |
| `RetryStopReason` | ❌ | Critical |
| `RetryEvaluationRequest` | ❌ | Critical |
| `RetryDecision` | ❌ | Critical |
| `RetryPolicy` | ❌ | Critical |

**Issue number:** DL-013  
**Severity:** Critical  
**Files:** None — contracts do not exist  
**Violated requirement:** DL-013 acceptance criteria require all seven retry
contract types.  
**Evidence:** Only `RetryAttempt.kt` exists in
`dataloom-api/src/commonMain/kotlin/io/dataloom/api/retry/`. No
`RetryPolicyId` is present in `Identifiers.kt`. No `RetryOperation`,
`RetryStopReason`, `RetryEvaluationRequest`, `RetryDecision`, or `RetryPolicy`
files exist anywhere in the repository.  
**Recommended correction:** Implement the missing contracts in a new PR that
satisfies all DL-013 acceptance criteria.  
**Blocks DL-018:** Yes.

### Defect F-003: No retry-specific test file

**Issue number:** DL-013  
**Severity:** Critical  
**Files:** None  
**Evidence:** No `RetryContractsTest.kt` or equivalent exists. `RetryAttempt`
is exercised incidentally in `QueueContractsTest.kt` but no standalone retry
policy test exists.  
**Recommended correction:** Add `RetryContractsTest.kt` in `dataloom-api`
covering all retry contract types including evaluation semantics, I/O
prohibition, and stop-reason classification.  
**Blocks DL-018:** Yes.

### Defect F-004: No retry-contracts documentation

**Issue number:** DL-013  
**Severity:** Critical  
**Files:** None  
**Evidence:** No `docs/api/retry-contracts.md` exists. Retry is mentioned
in passing in `docs/api/queue-model.md` and `docs/api/synchronization-events.md`
but is not independently documented.  
**Recommended correction:** Add `docs/api/retry-contracts.md` describing all
retry contract types, evaluation semantics, synchronous evaluation requirement,
I/O prohibition, and recoverability classification.  
**Blocks DL-018:** Yes.

### Correct items within DL-013 scope

The following items that DL-013 required are present and correct:

- `RetryAttempt` — correctly defined in `io.dataloom.api.retry`. Count > 0
  enforced. No I/O, no sleeping, no scheduling. ✅
- `SchedulingDelay` reused — `SynchronizationEvent.RetryScheduled` carries a
  `SchedulingDelay`, reusing the DL-012 type rather than duplicating it. ✅
- No retry engine, sleeping, scheduling, queue mutation, randomness, jitter, or
  attempt persistence was added. ✅
- Provider failure and event-level retry acknowledgement remain separate
  (`ChangeAcknowledgementStatus.RETRY` vs. `RetryAttempt`). ✅

---

## DL-014 — Conflict Contracts

**Verdict: PASS**

DL-014 was implemented prior to the recovery work. This re-audit confirms it
remains correct and was not regressed by DL-015, DL-016, or DL-017.

### Required contracts

| Contract | Present | Notes |
|---|---|---|
| `ConflictId` | ✅ | `Identifiers.kt` |
| `ConflictDetectorId` | ✅ | `Identifiers.kt` |
| `ConflictResolverId` | ✅ | `Identifiers.kt` |
| `ConflictType` | ✅ | Enum: CONCURRENT_CHANGE, VERSION_MISMATCH, UPDATE_DELETE, DELETE_UPDATE, CREATE_COLLISION, CUSTOM |
| `SynchronizationConflict` | ✅ | Same-entity invariant enforced |
| `ConflictDetectionRequest` | ✅ | Same-entity invariant enforced |
| `ConflictDetectionResult` | ✅ | Sealed: `NoConflict`, `ConflictDetected` |
| `ConflictDetector` | ✅ | Synchronous; no I/O |
| `ConflictResolutionRequest` | ✅ | Data class |
| `ConflictResolutionDecision` | ✅ | Sealed: UseLocal, UseRemote, Merge, Defer, Fail |
| `ConflictResolver` | ✅ | Synchronous; no I/O |
| Same-entity invariants enforced | ✅ | localChange/remoteChange entity-type/ID must match |
| Merge.resolvedChange entity invariant | ✅ | `require(resolvedChange.entity.type == expectedEntity.type && ...)` |
| Opaque payload and version handling | ✅ | Documented; detectors and resolvers must not inspect `DataLoomPayload` |
| No built-in strategy | ✅ | No concrete strategy provided |
| No runtime orchestration | ✅ | Events and decisions perform no side effects |

### Regression check for DL-014 after recovery

- DL-015 added `RetryAttempt` import to `QueueRescheduleRequest` and
  `QueueFailureRequest`. No conflict types were modified. ✅
- DL-016 introduced `SynchronizationEvent.ConflictDetected` which carries a
  `SynchronizationConflict` reference. This creates a forward reference but
  does not modify `SynchronizationConflict`. ✅
- DL-017 added clock and identifier abstractions with no dependency on conflict
  package. ✅

### Tests

`ConflictContractsTest.kt` — real assertions covering all conflict types,
same-entity invariant rejection, detection result variants, and resolution
decision variants. ✅

### Documentation

`docs/api/conflict-contracts.md` and `docs/architecture/conflict-boundaries.md`
— comprehensive coverage. ✅

---

## DL-015 — Queue Models and Queue Provider

**Verdict: PASS**

### Required contracts

| Contract | Present | Notes |
|---|---|---|
| `DataLoomInstant` | ✅ | `io.dataloom.api.time` — non-negative epochMilliseconds |
| `QueueEntryId` | ✅ | `Identifiers.kt` |
| `QueueLeaseId` | ✅ | `Identifiers.kt` |
| `QueueConsumerId` | ✅ | `Identifiers.kt` |
| `ProviderType.QUEUE` | ✅ | Present in `ProviderType` enum |
| `QueueEntryState` | ✅ | Enum: PENDING, LEASED, RETRY_WAITING, COMPLETED, FAILED, CANCELLED, DEAD_LETTER |
| `QueueLease` | ✅ | `expiresAt > acquiredAt` enforced |
| `QueueEntry` | ✅ | State invariants enforced; `availableAt >= enqueuedAt` |
| `QueueEnqueueRequest` | ✅ | PENDING state enforced; lease and retryAttempt must be null |
| `QueueAcquireRequest` | ✅ | leaseExpiresAt > acquiredAt; maxEntries > 0 |
| `QueueAcquireResult` | ✅ | Sealed: `NoEntries`, `Entries` |
| `QueueCompletionRequest` | ✅ | |
| `QueueRescheduleRequest` | ✅ | |
| `QueueFailureDisposition` | ✅ | Enum: FAILED, DEAD_LETTER |
| `QueueFailureRequest` | ✅ | |
| `QueueCancellationRequest` | ✅ | |
| `ExpiredLeaseRecoveryRequest` | ✅ | |
| `ExpiredLeaseRecoveryResult` | ✅ | |
| `QueueProvider` | ✅ | `io.dataloom.api.provider` |
| Timestamp invariants | ✅ | `availableAt >= enqueuedAt`; `expiresAt > acquiredAt`; `leaseExpiresAt > acquiredAt` |
| Lease expiration invariants | ✅ | `QueueLease` enforces `expiresAt > acquiredAt` |
| Queue state invariants | ✅ | LEASED requires non-null lease; all others require null lease; RETRY_WAITING requires non-null retryAttempt; PENDING requires null retryAttempt |
| Retry-waiting invariants | ✅ | RETRY_WAITING requires non-null `retryAttempt` |
| Atomic acquisition contract | ✅ | Documented in `QueueProvider.acquire` KDoc |
| Defensive copying | ✅ | `QueueAcquireResult.Entries` copies with `entries.toList()` |
| Stale and mismatched lease protection | ✅ | Documented in `QueueProvider` interface contract |
| QueueProvider and StorageProvider are separate | ✅ | Different interfaces; different package |
| No concrete Room/SQLDelight/WorkManager/Android implementation | ✅ | Interface only |

### QueueAcquireResult lease invariant assessment

The audit requirement asks whether `QueueAcquireResult.Entries` can
**truthfully enforce** that all returned entries belong to the same acquisition
lease.

**Finding:** The invariant IS enforced by the public model.
`QueueAcquireResult.Entries` validates every entry in its `init` block:

```kotlin
this.entries.forEachIndexed { index, entry ->
    require(entry.state == QueueEntryState.LEASED) { ... }
    require(entry.lease == lease) { ... }
}
```

Any caller who constructs an `Entries` result where one entry carries a
different lease object will receive an `IllegalArgumentException` at
construction time. This invariant is verifiable at the public model level and
is correctly enforced. ✅

### Tests

`QueueContractsTest.kt` — real assertions covering all queue types, state
invariants, lease invariants, timestamp invariants, and atomic acquisition
semantics. ✅

### Documentation

`docs/api/queue-model.md` and `docs/api/queue-provider.md` — comprehensive
coverage. `docs/architecture/queue-boundaries.md` covers module separation. ✅

---

## DL-016 — Results, Progress, Events, Observers

**Verdict: PASS**

### Required contracts

| Contract | Present | Notes |
|---|---|---|
| `SynchronizationEventId` | ✅ | `Identifiers.kt` |
| `SynchronizationObserverId` | ✅ | `Identifiers.kt` |
| `SynchronizationPhase` | ✅ | Enum: 11 phases |
| `SynchronizationProgressUnit` | ✅ | Enum: EVENTS, BYTES, OPERATIONS, STEPS |
| `SynchronizationProgress` | ✅ | Non-negative invariants; completed ≤ total |
| `SynchronizationSummary` | ✅ | Non-negative counters; outbound sub-counts ≤ outboundEventsRead; inboundEventsApplied ≤ inboundEventsReceived |
| `SynchronizationSkipReason` | ✅ | Enum: NO_CHANGES, CONSTRAINTS_NOT_SATISFIED, POLICY_REJECTED, DUPLICATE_REQUEST |
| `SynchronizationResult` | ✅ | Sealed: Succeeded, PartiallySucceeded, Failed, Cancelled, Skipped |
| PartiallySucceeded errors non-empty | ✅ | `require(errors.isNotEmpty())` |
| PartiallySucceeded defensive copy | ✅ | `errors.toList()` |
| `SynchronizationEvent` | ✅ | Sealed: Started, PhaseChanged, ProgressUpdated, RetryScheduled, ConflictDetected, Completed |
| Completed cross-invariants | ✅ | `request == result.request`; `occurredAt >= result.completedAt` |
| `SynchronizationObserver` | ✅ | `io.dataloom.api.observation` |
| Progress non-negative invariants | ✅ | `completed >= 0`; `total >= 0`; `completed <= total` |
| DataLoomInstant timestamps | ✅ | `occurredAt: DataLoomInstant`; `completedAt: DataLoomInstant` |
| Retry and conflict events perform no runtime work | ✅ | Construction only; documented |
| Observer exposes no Flow/StateFlow/SharedFlow/Channel/CoroutineScope/Android Lifecycle/dispatcher | ✅ | Plain Kotlin interface |
| No event bus, registry, replay, persistence, or runtime orchestration | ✅ | |

### toString() payload exposure check

- `SynchronizationResult.PartiallySucceeded.toString()` — custom implementation
  reports `errorCount` only; actual error messages are not included. ✅
- `SynchronizationEvent` variants use `data class` defaults which may include
  the `DataLoomError` message field on `RetryScheduled.error`. **See
  finding F-001** (minor; DataLoomError must not carry stack traces or
  credentials per its own contract).
- `SynchronizationEvent.Completed.toString()` includes the `result` reference.
  `SynchronizationResult` subtypes do not include payload bytes in their
  `toString()` paths. ✅

### Tests

`SynchronizationEventContractsTest.kt`, `SynchronizationResultContractsTest.kt`,
and `SynchronizationProgressContractsTest.kt` — real assertions covering all
contract types, invariants, and equality. ✅

### Documentation

`docs/api/synchronization-events.md`, `docs/api/synchronization-progress.md`,
`docs/api/synchronization-result.md`, and
`docs/architecture/observation-boundaries.md` — comprehensive coverage. ✅

---

## DL-017 — Clock and Identifier Generation

**Verdict: PASS**

### Required contracts

#### dataloom-api

| Contract | Present | Notes |
|---|---|---|
| `DataLoomClock` | ✅ | `io.dataloom.api.time`; returns `DataLoomInstant` |
| `IdentifierGenerator<T>` | ✅ | `io.dataloom.api.identifier`; strongly typed |

#### dataloom-core

| Contract | Present | Notes |
|---|---|---|
| `RuntimeIdentifierGenerators` | ✅ | `io.dataloom.core.runtime` |
| `RuntimeDependencies` | ✅ | `io.dataloom.core.runtime` |

#### dataloom-testing

| Contract | Present | Notes |
|---|---|---|
| `FixedDataLoomClock` | ✅ | `io.dataloom.testing.time` |
| `MutableDataLoomClock` | ✅ | `io.dataloom.testing.time` |
| `SequenceIdentifierGenerator<T>` | ✅ | `io.dataloom.testing.identifier` |
| `ConstantIdentifierGenerator<T>` | ✅ | `io.dataloom.testing.identifier` |

### Invariant verification

| Invariant | Enforced | Evidence |
|---|---|---|
| No production system clock | ✅ | `DataLoomClock` is an interface; no `System.currentTimeMillis()` call in any production source |
| No java.time/UUID/ULID/Random/Android in production contracts | ✅ | All imports verified |
| Runtime time and identifier dependencies are explicitly injected | ✅ | `RuntimeDependencies` requires `clock` and `identifiers`; no defaults |
| No global singleton or service locator | ✅ | No companion-object mutable state; no static accessor |
| Mutable clock rejects negative advancement | ✅ | `require(milliseconds >= 0L)` in `advanceBy()` |
| Mutable clock detects overflow | ✅ | `check(advanced >= current)` after addition; wrapping arithmetic produces negative values which fail the check |
| Failed advancement preserves previous value | ✅ | Exception thrown before `currentInstant` is reassigned |
| Sequence generator rejects empty input | ✅ | `require(copied.isNotEmpty())` |
| Source collection defensively copied | ✅ | `values.toList()` at construction |
| Sequence exhaustion fails deterministically | ✅ | `throw NoSuchElementException(...)` |
| Constant generator documents non-uniqueness | ✅ | KDoc explicitly states "does not guarantee uniqueness" |
| No production module depends on dataloom-testing | ✅ | Module dependency graph verified |

### Tests

`DataLoomClockTest.kt`, `DataLoomInstantTest.kt` (dataloom-api);
`RuntimeDependenciesTest.kt`, `RuntimeIdentifierGeneratorsTest.kt`
(dataloom-core); `FixedDataLoomClockTest.kt`, `MutableDataLoomClockTest.kt`,
`SequenceIdentifierGeneratorTest.kt`, `ConstantIdentifierGeneratorTest.kt`
(dataloom-testing) — real assertions covering all contracts and invariants. ✅

### Documentation

`docs/api/clock.md`, `docs/api/identifier-generation.md`,
`docs/testing/clock-and-identifiers.md`, and
`docs/architecture/runtime-dependencies.md` — comprehensive coverage. ✅

---

## Cross-Cutting API Audit

### Provider type registry

| Provider | Interface | ProviderType | Present |
|---|---|---|---|
| StorageProvider | ✅ | `STORAGE` | ✅ |
| TransportProvider | ✅ | `TRANSPORT` | ✅ |
| SchedulerProvider | ✅ | `SCHEDULER` | ✅ |
| ConnectivityProvider | ✅ | `CONNECTIVITY` | ✅ |
| QueueProvider | ✅ | `QUEUE` | ✅ |

### Identifier coverage

All identifiers defined in `Identifiers.kt` are value classes with:
- Non-blank validation in `init`
- `toString()` returning the underlying `value`

New identifiers added by DL-011 through DL-017:

| Identifier | Issue | Present |
|---|---|---|
| `CheckpointKey` | DL-011 | ✅ |
| `CheckpointToken` | DL-011 | ✅ |
| `ScheduleId` | DL-012 | ✅ |
| `QueueEntryId` | DL-015 | ✅ |
| `QueueLeaseId` | DL-015 | ✅ |
| `QueueConsumerId` | DL-015 | ✅ |
| `SynchronizationEventId` | DL-016 | ✅ |
| `SynchronizationObserverId` | DL-016 | ✅ |
| `RetryPolicyId` | DL-013 | ❌ MISSING |

### Coroutines and concurrency

No `GlobalScope` usage found. No `Thread.sleep` found. No swallowed
`CancellationException` found. Coroutine cancellation preservation is
documented in all provider interface KDocs. ✅

### Immutability

All model types use `val` properties exclusively. No `var` properties are
exposed in public APIs. ✅

---

## Defect Summary

| ID | Issue | Severity | Description | Blocks DL-018 |
|---|---|---|---|---|
| F-001 | DL-016 | Low | `SynchronizationCheckpoint.toString()` (data class default) exposes the opaque `CheckpointToken.value`. Checkpoint tokens must not be treated as credentials; this is a minor diagnostic concern. | No |
| F-002 | DL-013 | Critical | `RetryPolicyId`, `RetryOperation`, `RetryStopReason`, `RetryEvaluationRequest`, `RetryDecision`, and `RetryPolicy` do not exist. | Yes |
| F-003 | DL-013 | Critical | No `RetryContractsTest.kt` or equivalent retry-specific test file exists. | Yes |
| F-004 | DL-013 | Critical | No `docs/api/retry-contracts.md` exists. | Yes |

### F-001 Detail

**Severity:** Low  
**File:** `dataloom-api/src/commonMain/kotlin/io/dataloom/api/synchronization/SynchronizationCheckpoint.kt`  
**Symbol:** `SynchronizationCheckpoint` (data class default `toString()`)  
**Violated requirement:** KDoc states "A transport provider may redact
checkpoint tokens from diagnostics." The data class default `toString()`
renders `token=CheckpointToken(value=<actual-token>)`, which exposes the token
value in logs.  
**Evidence:** `SynchronizationCheckpoint` is declared `data class`; Kotlin
data class generates `toString()` from all properties.  
**Recommended correction:** Override `toString()` to redact the token value,
for example: `"SynchronizationCheckpoint(key=$key, token=[REDACTED])"`. This
is consistent with the existing `ChangeSetAcknowledgement.toString()` pattern
which similarly avoids exposing sensitive content.  
**Blocks DL-018:** No.

### F-002, F-003, F-004 Detail

See DL-013 section above.

---

## Readiness for DL-018

The repository is **NOT ready for DL-018**.

The blocking gap is DL-013. Six of seven required retry contract types are
absent. Without them, the synchronization runtime cannot evaluate retry
decisions, and the queue reschedule path has no policy contract to exercise.

**Required before DL-018 may start:**

1. Implement `RetryPolicyId` in `Identifiers.kt`.
2. Implement `RetryOperation` sealed interface or enum in
   `io.dataloom.api.retry`.
3. Implement `RetryStopReason` enum in `io.dataloom.api.retry`.
4. Implement `RetryEvaluationRequest` in `io.dataloom.api.retry`.
5. Implement `RetryDecision` sealed interface in `io.dataloom.api.retry`.
6. Implement `RetryPolicy` interface in `io.dataloom.api.retry`.
7. Add `RetryContractsTest.kt` with coverage of all retry types and invariants.
8. Add `docs/api/retry-contracts.md`.
9. Achieve a green CI build and passing test suite.

**Optional (non-blocking) improvement:**

- Override `SynchronizationCheckpoint.toString()` to redact the token value
  (F-001, Low severity).

**All other issues (DL-009, DL-010, DL-011, DL-012, DL-014, DL-015, DL-016,
DL-017) are fully implemented, tested, documented, and ready for DL-018 to
build upon.**
