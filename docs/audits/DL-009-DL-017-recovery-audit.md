# DL-AUDIT-002: Re-Audit of DL-009 through DL-017 after Recovery

**Audit date:** 2026-07-22 (updated after DL-013 recovery merge)
**Auditor:** Copilot Coding Agent (DL-AUDIT-002)
**Scope:** Issues DL-009 through DL-017 — complete re-audit against latest `main`
**Repository:** dataloom-sdk/dataloom
**Audited commit (main):** `970bc6d7aaf9eb62a7bbd09b0e7e600be0679a46`
  ([DL-013] Implement retry policy, backoff, and retry-decision contracts, #46)
**Audit branch HEAD:** `ce2660f3411acfe2931c7d0d6b93304717d541d9`
  (merge of `origin/main` into `copilot/dl-audit-002-re-audit-dl-009-to-dl-017`)
**Closes:** #44

---

## Executive Summary

The previous audit (DL-AUDIT-001) found that DL-009, DL-011, DL-012, DL-013,
DL-015, DL-016, and DL-017 had been merged with only initial plan commits. A
recovery effort subsequently reimplemented all seven. A first pass of
DL-AUDIT-002 (pre-DL-013) found eight issues passing and DL-013 incomplete.

This re-audit is performed after the completed DL-013 recovery implementation
was merged to `main` (PR #46, commit `970bc6d`). The audit branch was updated
with a merge commit to include the DL-013 changes before evaluation.

**All nine issues (DL-009 through DL-017) now PASS.** One non-blocking
diagnostic finding (F-001) remains open.

| Issue | Title | Verdict |
|---|---|---|
| DL-009 | Storage Provider SPI | ✅ PASS |
| DL-010 | Transport Provider SPI | ✅ PASS |
| DL-011 | Acknowledgements and checkpoints | ✅ PASS |
| DL-012 | Scheduler and connectivity SPI | ✅ PASS |
| DL-013 | Retry contracts | ✅ PASS |
| DL-014 | Conflict contracts | ✅ PASS |
| DL-015 | Queue models and queue provider | ✅ PASS |
| DL-016 | Results, progress, events, observers | ✅ PASS |
| DL-017 | Clock and identifier generation | ✅ PASS |

**The repository is READY FOR DL-018 WITH NON-BLOCKING FIXES.**

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
on dataloom-testing. Verified with `./gradlew :dataloom-runtime:dependencies
--configuration jvmRuntimeClasspath` — dataloom-testing does not appear. ✅

### KMP platform independence

Every `commonMain` source file audited. No Android, JVM-only, Apple-specific,
or third-party platform types are present in any `commonMain` source set.
The convention plugin applies `kotlin("multiplatform")` only. ✅

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

**Latest completed CI run on `main` (commit `970bc6d`):** success
(run ID 29928640387, workflow `pr-validation.yml`, run number 69,
event `push`). ✅

**Previous PR branch CI run (commit `4cc38f0`):** success
(run ID 29923877108, workflow `pr-validation.yml`, run number 65). ✅

---

## Phase 2 — Build and Test Validation

All commands below were executed locally during this audit against the merged
audit branch (which includes `970bc6d` via the merge commit `ce2660f`).

### `./gradlew --version`

```
Gradle 9.5.0
Build time: 2026-04-28 12:05:30 UTC
```

### `./gradlew projects`

```
Root project 'dataloom'
+--- Project ':dataloom-api'
+--- Project ':dataloom-core'
+--- Project ':dataloom-runtime'
\--- Project ':dataloom-testing'
BUILD SUCCESSFUL
```

### `./gradlew :dataloom-api:compileKotlinJvm`

```
BUILD SUCCESSFUL
```

### `./gradlew :dataloom-api:allTests`

```
BUILD SUCCESSFUL
605 tests, 0 failures, 0 skipped
```

Per-suite breakdown (sorted by count):

| Test suite | Tests |
|---|---|
| `ConflictContractsTest` | 91 |
| `QueueContractsTest` | 73 |
| `RetryPolicyContractsTest` | 58 |
| `SchedulingContractsTest` | 45 |
| `SynchronizationProgressContractsTest` | 30 |
| `TransportContractsTest` | 29 |
| `StorageContractsTest` | 29 |
| `PayloadContractsTest` | 24 |
| `IdentifierContractsTest` | 24 |
| `ConnectivityContractsTest` | 23 |
| `ChangeContractsTest` | 23 |
| `SynchronizationContractsTest` | 22 |
| `SynchronizationResultContractsTest` | 21 |
| `SynchronizationEventContractsTest` | 20 |
| `ProviderContractsTest` | 14 |
| `DataLoomInstantTest` | 12 |
| `SynchronizationObserverTest` | 11 |
| `DataLoomMetadataTest` | 8 |
| `ProviderOperationAndInterfaceTest` | 8 |
| `IdentifierGeneratorTest` | 7 |
| `ExecutionContextTest` | 7 |
| `DataLoomClockTest` | 6 |
| `SynchronizationRequestTest` | 5 |
| `ModelEnumContractsTest` | 5 |
| `ErrorContractsTest` | 5 |
| `ProviderIdentifiersTest` | 4 |
| `DataLoomApiModuleTest` | 1 |
| **Total** | **605** |

### `./gradlew :dataloom-api:check`

```
BUILD SUCCESSFUL
```

### `./gradlew :dataloom-core:compileKotlinJvm`

```
BUILD SUCCESSFUL
```

### `./gradlew :dataloom-core:allTests`

```
BUILD SUCCESSFUL
20 tests (RuntimeIdentifierGeneratorsTest, RuntimeDependenciesTest,
DataLoomCoreModuleTest), 0 failures, 0 skipped
```

### `./gradlew :dataloom-core:check`

```
BUILD SUCCESSFUL
```

### `./gradlew :dataloom-runtime:compileKotlinJvm`

```
BUILD SUCCESSFUL
```

### `./gradlew :dataloom-runtime:allTests`

```
BUILD SUCCESSFUL
1 test (DataLoomRuntimeModuleTest), 0 failures, 0 skipped
```

### `./gradlew :dataloom-runtime:check`

```
BUILD SUCCESSFUL
```

### `./gradlew :dataloom-testing:compileKotlinJvm`

```
BUILD SUCCESSFUL
```

### `./gradlew :dataloom-testing:allTests`

```
BUILD SUCCESSFUL
41 tests (SequenceIdentifierGeneratorTest, MutableDataLoomClockTest,
ConstantIdentifierGeneratorTest, FixedDataLoomClockTest,
DataLoomTestingModuleTest), 0 failures, 0 skipped
```

### `./gradlew :dataloom-testing:check`

```
BUILD SUCCESSFUL
```

### `./gradlew check --configuration-cache`

```
BUILD SUCCESSFUL — Configuration cache entry stored.
```

### `./gradlew build --configuration-cache`

```
BUILD SUCCESSFUL — Configuration cache entry stored.
```

### `./gradlew :dataloom-runtime:dependencies --configuration jvmRuntimeClasspath`

```
+--- project :dataloom-api
\--- project :dataloom-core
     \--- project :dataloom-api (*)
```

`dataloom-testing` does not appear in the runtime classpath. ✅

**Total tests across all modules:** 667 (605 + 20 + 1 + 41), 0 failures.

---

## Phase 3 — Cross-Cutting Checks

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
- `RetryDecision.Retry` and `RetryDecision.Stop` are `data class` types;
  neither exposes credentials, keys, or payload bytes (only
  `SchedulingDelay`/`RetryStopReason`/`DataLoomMetadata`). ✅
- `RetryEvaluationRequest` is a `data class`; its `error` field must not
  carry credentials or sensitive state per its own contract. ✅
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

### Regression check

No regressions found. DL-013 contracts do not modify the storage package. ✅

### Tests

`StorageContractsTest.kt` — 29 common tests with real assertions covering:
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

### Regression check

No regressions found. DL-013 contracts do not modify the transport package. ✅

### Tests

`TransportContractsTest.kt` — 29 common tests with real assertions covering:
push, pull, provider type, defensive copy, maxEvents, checkpoint propagation. ✅

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

### Regression check

No regressions found. DL-013 contracts do not modify the acknowledgement or
checkpoint package. ✅

### Tests

`SynchronizationContractsTest.kt` — 22 common tests with real assertions
covering all acknowledgement and checkpoint contract types, including
empty/duplicate event rejection. ✅

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

`SchedulingDelay` is reused by DL-013 (`RetryDecision.Retry.delay`) with
no duplication. ✅

### Regression check

No regressions found. DL-013 imports `SchedulingDelay` but does not modify it. ✅

### Tests

`SchedulingContractsTest.kt` (45 tests) and `ConnectivityContractsTest.kt`
(23 tests) — real assertions covering all types, provider contracts, and
validation rules. ✅

### Documentation

`docs/api/scheduler-provider.md` and `docs/api/connectivity-provider.md` —
comprehensive coverage. ✅

---

## DL-013 — Retry Contracts

**Verdict: PASS**

DL-013 was originally found INCOMPLETE in the first DL-AUDIT-002 pass (missing
six of seven required contracts, no dedicated test file, no API documentation).
The recovery implementation was merged to `main` as PR #46,
commit `970bc6d7aaf9eb62a7bbd09b0e7e600be0679a46`.

This section presents the complete re-audit of DL-013 against the current
`main` branch.

### Contract inventory

| Contract | Package | File | Present |
|---|---|---|---|
| `RetryPolicyId` | `io.dataloom.api.identifier` | `Identifiers.kt` | ✅ |
| `RetryOperation` | `io.dataloom.api.retry` | `RetryOperation.kt` | ✅ |
| `RetryAttempt` | `io.dataloom.api.retry` | `RetryAttempt.kt` | ✅ |
| `RetryStopReason` | `io.dataloom.api.retry` | `RetryStopReason.kt` | ✅ |
| `RetryEvaluationRequest` | `io.dataloom.api.retry` | `RetryEvaluationRequest.kt` | ✅ |
| `RetryDecision` | `io.dataloom.api.retry` | `RetryDecision.kt` | ✅ |
| `RetryPolicy` | `io.dataloom.api.retry` | `RetryPolicy.kt` | ✅ |

### Detailed contract verification

#### `RetryPolicyId`

- Declared as `@JvmInline public value class RetryPolicyId(public val value: String)`. ✅
- `init { require(value.isNotBlank()) ... }` — blank and whitespace-only values
  rejected. ✅
- `toString()` returns `value` (the underlying string). ✅
- Defined in `Identifiers.kt` alongside all other DataLoom identifier types. ✅

#### `RetryOperation`

- Declared as `@JvmInline public value class RetryOperation(public val value: String)`. ✅
- Blank value rejected: `require(value.isNotBlank()) { "RetryOperation value must not be blank." }`. ✅
- Extensible: not an enum; new operations can be introduced without API changes. ✅
- `toString()` returns `value`. ✅
- Example placeholder values (`transport.push`, `transport.pull`, etc.) are
  illustrative only and documented as non-exhaustive. ✅

#### `RetryAttempt`

- `count` property, validated `require(count > 0)`. ✅
- Count=1 represents the first retry evaluation after the original operation
  failure, as documented in both `RetryAttempt.kt` and
  `RetryEvaluationRequest.kt`. ✅
- Zero and negative values rejected at construction. ✅
- Provides value-based `equals()`, `hashCode()`, and `toString()`. ✅
- No I/O, no sleeping, no scheduling in construction. ✅

#### `RetryStopReason`

- Enum with four values: `NON_RECOVERABLE`, `ATTEMPT_LIMIT_REACHED`,
  `POLICY_REJECTED`, `UNSUPPORTED_OPERATION`. ✅
- All four values required by issue #26 are present. ✅
- `NON_RECOVERABLE` — maps to `Recoverability.NON_RECOVERABLE`; normal policy
  decision documented. ✅
- `ATTEMPT_LIMIT_REACHED` — attempt budget exhausted. ✅
- `POLICY_REJECTED` — general-purpose rejection. ✅
- `UNSUPPORTED_OPERATION` — unrecognised `RetryOperation` value. ✅
- Ordinal stability warning documented. ✅
- Coroutine cancellation prohibition documented: must not be converted into a
  `RetryStopReason`. ✅

#### `RetryEvaluationRequest`

- Declared as `data class`. ✅
- Required properties: `synchronizationRequest`, `operation`, `error`,
  `attempt`. All present. ✅
- Optional properties: `previousDelay: SchedulingDelay?`, `provider:
  ProviderDescriptor?`. Both present. ✅
- `metadata: DataLoomMetadata` defaults to `DataLoomMetadata.Empty`. ✅
- `attempt` KDoc: "Attempt number 1 represents the first retry evaluation after
  the original operation failure." ✅
- `previousDelay` documentation: null when no prior retry exists. ✅
- Sensitive-data restriction on `metadata` documented. ✅
- Construction performs no policy evaluation, scheduling, storage access, or
  attempt-number increment. ✅

#### `RetryDecision`

- Declared as `sealed interface RetryDecision`. ✅
- `Retry` variant: `data class Retry(val delay: SchedulingDelay, val metadata:
  DataLoomMetadata = DataLoomMetadata.Empty)`. ✅
- `Stop` variant: `data class Stop(val reason: RetryStopReason, val metadata:
  DataLoomMetadata = DataLoomMetadata.Empty)`. ✅
- `Retry.delay` reuses `SchedulingDelay` from DL-012; no duplication. ✅
- Zero-delay retry supported: `SchedulingDelay(0L)` is accepted. ✅
- Both variants are immutable data classes with value-based equality. ✅
- Construction restrictions documented: no sleeping, scheduling, queue mutation,
  or operation execution. ✅

#### `RetryPolicy`

- Declared as `interface RetryPolicy`. ✅
- Exposes `val id: RetryPolicyId`. ✅
- Exposes `fun evaluate(request: RetryEvaluationRequest): RetryDecision`. ✅
- Evaluation is synchronous (non-suspend function). ✅
- Determinism requirement documented: identical input and policy configuration
  must produce identical output. ✅
- I/O prohibition documented: must not block, sleep, access network, access
  storage, schedule work, mutate queues, or execute provider operations. ✅
- Coroutine cancellation prohibition documented: `evaluate` must not catch or
  translate `CancellationException`. ✅
- Thread-safety documentation requirement: implementations must document their
  thread-safety guarantees. ✅

### Recoverability semantics

| `Recoverability` | Normal decision | Documented |
|---|---|---|
| `NON_RECOVERABLE` | `RetryDecision.Stop(NON_RECOVERABLE)` | ✅ |
| `RECOVERABLE` | Either `Retry` or `Stop` | ✅ |
| `UNKNOWN` | Policy determines | ✅ |

Severity alone must not determine retry behaviour; `CRITICAL` does not
automatically mean stop, `WARNING` does not automatically mean retry. ✅

### Provider failure vs. event-level RETRY acknowledgement

These remain separate and distinct concepts:

- **Provider-operation failure** → `DataLoomError` → `RetryPolicy.evaluate()`
  → `RetryDecision`
- **Event-level RETRY acknowledgement** → `ChangeAcknowledgementStatus.RETRY`
  in `ChangeEventAcknowledgement`

No conflation. ✅

### What was not added (correct absence)

The following were explicitly not implemented in DL-013, as required:

- No retry engine or runtime orchestration. ✅
- No built-in policy (fixed, linear, exponential, composite). ✅
- No backoff calculation algorithm. ✅
- No jitter or randomness. ✅
- No attempt persistence. ✅
- No WorkManager integration. ✅
- No Android or platform-specific types. ✅
- No `CoroutineScope` or dispatcher in `RetryPolicy`. ✅

### Test coverage

**File:** `dataloom-api/src/commonTest/kotlin/io/dataloom/api/retry/RetryPolicyContractsTest.kt`
**Tests:** 58, failures: 0, skipped: 0

| Test group | Examples |
|---|---|
| `RetryPolicyId` validation | accepts valid value; rejects blank; rejects whitespace; preserves exact value; equality; toString |
| `RetryOperation` validation | accepts valid value; rejects blank; rejects whitespace-only; extensible (arbitrary values) |
| `RetryAttempt` validation | accepts positive number; accepts 1; rejects 0; rejects negative; equality |
| `RetryStopReason` values | NON_RECOVERABLE; ATTEMPT_LIMIT_REACHED; POLICY_REJECTED; UNSUPPORTED_OPERATION; distinct values; match without ordinal dependency |
| `RetryEvaluationRequest` | preserves all required properties; previousDelay absent; previousDelay present; provider absent; provider present; metadata defaults to empty; supplied metadata preserved; equality |
| `RetryDecision` | preserves delay; supports zero delay; metadata defaults to empty; stop preserves reason; retry/stop are distinct; sealed type exhaustively matchable |
| `RetryPolicy` fake implementation | policy id is exposed; immediate retry; delayed retry; stop; deterministic for identical requests; stop for non-recoverable error; retry for recoverable error; no platform-specific type required |

All 58 tests pass on the JVM target. ✅

### Documentation

| Document | Path | Present |
|---|---|---|
| Retry policy API contracts | `docs/api/retry-policy.md` (452 lines) | ✅ |
| Retry architectural boundaries | `docs/architecture/retry-boundaries.md` (243 lines) | ✅ |
| Docs index link (API) | `docs/README.md` line 22 | ✅ |
| Docs index link (architecture) | `docs/README.md` line 36 | ✅ |
| Architecture README link | `docs/architecture/README.md` line 24 | ✅ |

`docs/api/retry-policy.md` covers all required content: all seven contract
types, synchronous evaluation rationale, recoverability rules, provider failure
vs. event-level acknowledgement separation, backoff semantics, deferred built-in
policies, and dependency-injection neutrality. This document is the
issue-defined equivalent of `docs/api/retry-contracts.md`. ✅

`docs/architecture/retry-boundaries.md` covers: policy, runtime, scheduler,
queue, and provider responsibilities; WorkManager boundary; KMP boundary;
backoff semantics; attempt-limit boundary; cancellation rules; security
restrictions; and what is not implemented in DL-013. ✅

---

## DL-014 — Conflict Contracts

**Verdict: PASS**

DL-014 was implemented prior to the recovery work. This re-audit confirms it
remains correct and was not regressed by DL-015, DL-016, DL-017, or DL-013.

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

### Regression check

- DL-015 added `RetryAttempt` import to `QueueRescheduleRequest` and
  `QueueFailureRequest`. No conflict types were modified. ✅
- DL-016 introduced `SynchronizationEvent.ConflictDetected` which carries a
  `SynchronizationConflict` reference. This creates a forward reference but
  does not modify `SynchronizationConflict`. ✅
- DL-017 added clock and identifier abstractions with no dependency on conflict
  package. ✅
- DL-013 added retry contracts with no dependency on conflict package. ✅

### Tests

`ConflictContractsTest.kt` — 91 common tests with real assertions covering all
conflict types, same-entity invariant rejection, detection result variants, and
resolution decision variants. ✅

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

The invariant IS enforced by the public model.
`QueueAcquireResult.Entries` validates every entry in its `init` block:

```kotlin
this.entries.forEachIndexed { index, entry ->
    require(entry.state == QueueEntryState.LEASED) { ... }
    require(entry.lease == lease) { ... }
}
```

Any caller who constructs an `Entries` result where one entry carries a
different lease object will receive an `IllegalArgumentException` at
construction time. ✅

### Regression check

No regressions found. DL-013 retry contracts are consumed by queue request
types (`QueueRescheduleRequest`, `QueueFailureRequest`) but do not modify
queue invariants. ✅

### Tests

`QueueContractsTest.kt` — 73 common tests with real assertions covering all
queue types, state invariants, lease invariants, timestamp invariants, and
atomic acquisition semantics. ✅

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
  the `DataLoomError` message field on `RetryScheduled.error`. DataLoomError
  must not carry credentials or stack traces per its own contract. ✅
- `SynchronizationEvent.Completed.toString()` includes the `result` reference.
  `SynchronizationResult` subtypes do not include payload bytes in their
  `toString()` paths. ✅

### Regression check

No regressions found. DL-013 contracts do not modify the synchronization result,
progress, event, or observer packages. ✅

### Tests

`SynchronizationEventContractsTest.kt` (20 tests),
`SynchronizationResultContractsTest.kt` (21 tests), and
`SynchronizationProgressContractsTest.kt` (30 tests) — real assertions covering
all contract types, invariants, and equality. ✅

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

### Regression check

No regressions found. DL-013 does not modify the clock or identifier generation
packages. ✅

### Tests

`DataLoomClockTest.kt` (6 tests), `DataLoomInstantTest.kt` (12 tests) —
dataloom-api;
`RuntimeDependenciesTest.kt`, `RuntimeIdentifierGeneratorsTest.kt` —
dataloom-core (20 tests total);
`FixedDataLoomClockTest.kt`, `MutableDataLoomClockTest.kt`,
`SequenceIdentifierGeneratorTest.kt`, `ConstantIdentifierGeneratorTest.kt` —
dataloom-testing (41 tests total).
All pass. ✅

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

Identifiers added by DL-011 through DL-017:

| Identifier | Issue | Present |
|---|---|---|
| `CheckpointKey` | DL-011 | ✅ |
| `CheckpointToken` | DL-011 | ✅ |
| `ScheduleId` | DL-012 | ✅ |
| `RetryPolicyId` | DL-013 | ✅ |
| `QueueEntryId` | DL-015 | ✅ |
| `QueueLeaseId` | DL-015 | ✅ |
| `QueueConsumerId` | DL-015 | ✅ |
| `SynchronizationEventId` | DL-016 | ✅ |
| `SynchronizationObserverId` | DL-016 | ✅ |

### Coroutines and concurrency

No `GlobalScope` usage found. No `Thread.sleep` found. No swallowed
`CancellationException` found. Coroutine cancellation preservation is
documented in all provider interface KDocs. ✅

### Immutability

All model types use `val` properties exclusively. No `var` properties are
exposed in public APIs. ✅

---

## Defect Summary

| ID | Issue | Severity | Status | Description | Blocks DL-018 |
|---|---|---|---|---|---|
| F-001 | DL-011 | Low | **OPEN** | `SynchronizationCheckpoint.toString()` (data class default) exposes the opaque `CheckpointToken.value`. Minor diagnostic concern. | No |
| F-002 | DL-013 | Critical | **RESOLVED** | `RetryPolicyId`, `RetryOperation`, `RetryStopReason`, `RetryEvaluationRequest`, `RetryDecision`, and `RetryPolicy` were absent. All are now present. | Was blocking |
| F-003 | DL-013 | Critical | **RESOLVED** | No retry-specific test file existed. `RetryPolicyContractsTest.kt` (58 tests, 0 failures) now exists. | Was blocking |
| F-004 | DL-013 | Critical | **RESOLVED** | No retry API documentation existed. `docs/api/retry-policy.md` (452 lines) and `docs/architecture/retry-boundaries.md` (243 lines) now exist. | Was blocking |

### F-001 Detail (non-blocking, retained)

**Severity:** Low
**File:** `dataloom-api/src/commonMain/kotlin/io/dataloom/api/synchronization/SynchronizationCheckpoint.kt`
**Symbol:** `SynchronizationCheckpoint` (data class default `toString()`)
**Evidence:** `SynchronizationCheckpoint` is declared `data class`. Kotlin data
class generates `toString()` from all properties. `CheckpointToken.toString()`
returns its underlying `value` string (not wrapped). Therefore the data class
`toString()` will render `token=<actual-token-value>`, making the raw token
string visible in log output.
**Violated guidance:** KDoc states "A transport provider may redact checkpoint
tokens from diagnostics." The auto-generated `toString()` does not redact.
**Evidence of non-resolution:** No custom `toString()` override was added by
any PR between the initial audit and the current `main` state.
**Recommended correction:** Override `toString()` to redact the token, for
example: `"SynchronizationCheckpoint(key=$key, token=[REDACTED])"`. This is
consistent with the existing `ChangeSetAcknowledgement.toString()` pattern.
**Blocks DL-018:** No.

---

## Recovery History

This section preserves the historical context of DL-013's implementation path.

**Pre-audit state (before DL-AUDIT-001):** DL-013 was merged with only an
initial plan commit. Only `RetryAttempt.kt` existed in the retry package.

**DL-AUDIT-001 finding:** DL-013 flagged as unimplemented.

**First DL-AUDIT-002 pass (commit `1e11af5`):** DL-013 confirmed INCOMPLETE.
Six of seven required contracts absent. No test file. No documentation. Three
critical defects raised (F-002, F-003, F-004).

**DL-013 recovery (PR #46, commit `970bc6d`):** Full implementation merged to
`main`. All six missing contracts implemented. 58-test file added. Full API and
architecture documentation added.

**This audit pass:** Audit branch updated with merge commit `ce2660f` to
include the DL-013 recovery. All seven retry contracts verified. All 58 retry
tests pass. F-002, F-003, F-004 closed as resolved. F-001 retained.

---

## Readiness for DL-018

**The repository is READY FOR DL-018 WITH NON-BLOCKING FIXES.**

All nine issues (DL-009 through DL-017) are fully implemented, tested,
documented, and passing CI. No blocking gaps remain.

**One non-blocking finding remains open:**

- **F-001 (Low):** Override `SynchronizationCheckpoint.toString()` to redact
  the opaque `CheckpointToken` value. This is a diagnostic hygiene concern and
  does not affect API correctness, required invariants, KMP compatibility,
  security requirements, or runtime prerequisites for DL-018.

**All other issues are complete:**

DL-009, DL-010, DL-011, DL-012, DL-013, DL-014, DL-015, DL-016, and DL-017
are fully implemented, tested, documented, and ready for DL-018 to build upon.

---

## Final Verdict

**READY FOR DL-018 WITH NON-BLOCKING FIXES**
