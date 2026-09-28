# DL-039B (`#102`) six-strategy decision matrix and backlog

> **Status:** Evidence-based audit, 2026-09-28, against `main` at `acc4a02`.
> Docs only, apart from one added evaluator test (see "What was verified").
> Every citation below was read in the current source; nothing is quoted from
> the dashboard, KDoc, or memory. "PROVEN" means a test that asserts the
> behavior exists and is wired into a CI task; **CI results themselves were not
> observed** (see "What was verified").

This is the "full connectivity/cache/retry/conflict/cancellation/restart
decision matrix audited per built-in profile" that `docs/status/market-readiness.md`
row 1 names as outstanding, plus the acceptance text of GitHub issue `#102`
(read with `gh issue view 102`; the repository only holds fragments of it).

## 1. Method, legend, and how tests are wired

| Class | Meaning |
|---|---|
| **PROVEN common** | Asserted in `commonTest` with fake providers. Logic proof only. The suite runs on `jvmTest`, on `testAndroidHostTest` when `DATALOOM_ANDROID_BUILD=true` (`.github/workflows/android-validation.yml`, job env; `check` includes `testAndroidHostTest`, confirmed with `check --dry-run`), and on `iosSimulatorArm64Test` on macOS CI (`docs/apple/apple-testing.md`, `apple-validation.yml` runs `build`). It does not prove any platform provider. |
| **PROVEN Android (Robolectric)** | Real Room providers under Robolectric in `runtime-android-reference-consumer`. JVM-hosted, not an emulator. |
| **PROVEN Android (emulator)** | `androidTest` on the `pixel2Api35` managed device (`android-validation.yml`, "Run managed-device tests"). |
| **PROVEN iOS** | Kotlin/Native `iosTest` on the iOS Simulator (`apple-validation.yml`). Not observable from Windows. |
| **UNPROVEN** | No test asserts it, or only a mock/adjacent path does. Says nothing about whether the code is right. |
| **N/A** | The strategy's contract excludes the case by design. |

Abbreviations (all paths under the repository root):

- `E` = `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/BuiltInSynchronizationStrategyEvaluator.kt`
- `COORD` = `.../strategy/StrategySynchronizationExecutionCoordinator.kt`; `ACCEPTED` = `.../strategy/AcceptedStrategyPlanExecutionCoordinator.kt`
- `NOX`, `RFX`, `CFX`, `OFX`, `HYX` = `.../strategy/{NetworkOnly,RemoteFirst,CacheFirst,OfflineFirst,Hybrid}StrategyExecutor.kt`
- `ET` = `dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/BuiltInSynchronizationStrategyEvaluatorTest.kt`; `NOT`, `RFT`, `CFT`, `OFT`, `HYT`, `ADT`, `APT` = sibling `{NetworkOnly,RemoteFirst,CacheFirst,OfflineFirst,Hybrid}StrategyExecutorTest`, `AdaptiveStrategyResolutionTest`, `AcceptedStrategyPlanExecutionCoordinatorTest`
- `DBD` / `DBP` = `dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/facade/DataLoomBuilder{DirectStrategyExecution,ProtectedStrategy}Test.kt`
- `AND:<X>` = `runtime-android-reference-consumer/src/test/kotlin/io/dataloom/consumer/android/AndroidReferenceConsumer<X>RobolectricTest.kt`
- `IOS:<X>` = `runtime-ios-reference-consumer/src/iosTest/kotlin/io/dataloom/consumer/ios/IosReferenceConsumer<X>Test.kt`
- `ROOM-PLAN` = `dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/StrategyPlanRoomPersistenceInstrumentedTest.kt`; `APPLE-PLAN` = `dataloom-runtime/src/iosTest/kotlin/io/dataloom/runtime/queue/AppleStrategyPlanQueuePersistenceTest.kt`

## 2. Cross-cutting findings that every cell depends on

These change how the matrix should be read. Each was verified in source.

1. **Runtime evidence is supplied by the caller; nothing derives it.**
   `StrategyRuntimeEvidence` (`dataloom-api/.../strategy/StrategyPolicy.kt:81-89`)
   is only ever constructed in tests. No production code turns a
   `ConnectivityProvider` snapshot, a storage freshness check, or provider
   health into `StrategyConnectivity`/`StrategyCacheState`/`StrategyProviderHealth`
   (repository-wide search for `StrategyConnectivity`, `StrategyRuntimeEvidence(`
   finds only the evaluator, one executor KDoc mention, the enum, and tests). The decision to treat
   Android and iOS connectivity "equivalently" is therefore made entirely by
   whoever builds the evidence, and platform parity of the *connectivity
   dimension* cannot be proven for the strategy engine as it stands.
2. **Two evidence fields are dead, and the connectivity enum is narrower than
   the acceptance matrix.** `queueHealth` and `isBackgroundExecutionAvailable`
   are declared and read nowhere. `StrategyConnectivity` has `AVAILABLE,
   LIMITED, UNAVAILABLE, UNKNOWN, NOT_EVALUATED`; `docs/strategies/README.md`
   (V1 acceptance matrix) requires "metered, unmetered, ... provider failure",
   which cannot be expressed. `ConnectivitySnapshot.isMetered` exists on the
   provider but never reaches strategy evidence.
3. **Cache freshness is application-owned, and the real providers only check
   existence.** `RoomStorageProvider.evaluateLocalFallback`
   (`dataloom-storage-room/.../RoomStorageProvider.kt:219-233`) and
   `SqlDelightStorageProvider.evaluateLocalFallback`
   (`dataloom-storage-sqldelight/.../SqlDelightStorageProvider.kt:271-284`)
   return `Available` when any checkpoint or inbound change set exists,
   echoing the caller's `FRESH`/`STALE` (defaulting to `STALE`), else
   `Unavailable(MISSING)`. `reconcileStrategy` (Room `:267-280`, SQLDelight
   `:316-337`) only confirms a checkpoint exists. `docs/strategies/cache-first.md`
   acceptance ("boundary tests ... using an injected clock") describes a
   DataLoom-owned freshness window that does not exist.
4. **Retry and circuit are not part of the strategy executors.** No executor
   retries. Direct `DataLoom.synchronize(StrategySynchronizationRequest)` has no
   retry or circuit (`docs/api/provider-protected-strategy-execution.md`
   "Public assembly"). Circuit protection is the opt-in
   `protectedStrategySynchronization` facade, a provider-wrapping boundary
   (`ProviderProtectedStrategySynchronization.kt`), and retry for queued
   plan-bearing work is `StrategyQueueExecutionOutcomeMapper`
   (`dataloom-runtime/.../queue/StrategyQueueExecutionOutcomeMapper.kt`).
5. **`SCHEDULE_REFRESH` is planning-only.** It adds `SCHEDULER`+`QUEUE`
   capabilities (`E:756-759`) but no executor calls a `SchedulerProvider`;
   "refresh scheduled" means "queue entry durably admitted". A platform wake
   (WorkManager / `BGTaskScheduler`) depends on a separately configured queue
   worker.
6. **Durable admission is not idempotent per decision.**
   `StrategyDurableQueueAdmitter.kt:62-73` generates a fresh `QueueEntryId` on
   every attempt and documents that a caller retry may enqueue a duplicate.
7. **Offline-first's "atomic local intent + outbox" is not owned by DataLoom.**
   `ACCEPT_LOCAL` has no executor action (`OFX` KDoc "ACCEPT_LOCAL requires no
   executor action of its own"). The executor admits to the queue only; the
   local write is the application's. `#102` requires "eligible local intent
   plus durable outbox/queue admission is atomic before success".
8. **Strategy docs lag the code.** `docs/strategies/README.md` (banner and
   "Current repository" table) and the banners of `offline-first.md`,
   `cache-first.md`, `hybrid.md`, `adaptive.md` still say those strategies'
   execution is pending; `network-only.md`'s acceptance table says "Pending
   publication of this slice". The executors, `docs/api/*-strategy-execution.md`
   and tests all exist.

## 3. Real defects found (not just missing proof)

### D1. Remote-first PUSH with unavailable connectivity and a fallback allowlist throws

`E:236-252` takes the "typed local fallback" branch whenever
`UNAVAILABLE in profile.fallbackOn` and cache state is `FRESH`/`STALE`, for
**every direction**, using `localFallbackOperations(direction)`. For `PUSH`
that is `[READ_LOCAL]` (`E:820-823`), and `remoteFallbackPlan` deliberately
returns no fallback plan for `PUSH` (`E:799`). The plan is `EXECUTE`,
`operations=[READ_LOCAL]`, `requiredCapabilities={STORAGE}`. The coordinator
resolves only `STORAGE` (`StrategyProviderResolver` resolves required roles
only), then `RFX` sees no `SERVE_LOCAL`, skips the fallback branch and runs
`executeProviderBackedPipeline`, which does
`requireNotNull(providers.transportProvider)` (`RFX:102-104`).

Reproduced with a temporary executor-level test (not committed): the plan was
`ops=[READ_LOCAL] disp=EXECUTE caps=[STORAGE] fb=null` and `execute(...)` threw
`IllegalArgumentException: Required value was null.` The coordinator-level path
was traced by reading, not run. If a transport were present the pipeline would
push despite `UNAVAILABLE` connectivity. Hybrid handles the same shape
correctly (`HYX:143-150` returns `AcceptedLocally`), remote-first has no such
branch. Untested: every remote-first `UNAVAILABLE`-fallback test uses `PULL`
(`ET.remoteFirstUsesOnlyConfiguredTypedFallback`,
`ET.remoteFirstUnavailableTransportHealthTriggersSameOutcomeAsUnavailableConnectivity`,
`RFT.serveLocalFastPath*`).

### D2. Cache-first hides a served cache behind `Failed` when the synchronous refresh fails

`CFX:179-185` maps a failed refresh pipeline to `failed(...)`, discarding the
already-served cache state. `CFT.staleCacheWithSynchronousRefreshFailurePropagatesTheFailure`
asserts exactly that, with a comment that cache-first "has no
fallback-on-refresh-failure semantics". `docs/strategies/cache-first.md`
("Failure and fallback semantics") requires "Remote transient failure after
stale state was served: Preserve the served result and report refresh
failure/retry state separately." Code, test, and spec disagree. Related: the
evaluator does not consult connectivity on hit branches (`E:312-367`), so a
non-durable refresh is attempted even when connectivity is `UNAVAILABLE`.

### D3. Concrete-strategy behavior under `LIMITED` connectivity is inconsistent and untested

`LIMITED` is treated as unavailable by offline-first (`E:208-211`) and hybrid
(`E:509`, remote requires `AVAILABLE`), but as "attempt remote" by remote-first
(`E:236-277`) and network-only (`E:466-490`). Cache-first's miss branch treats
`LIMITED` like unavailable (`E:369-394`). No test drives any concrete strategy
with `LIMITED` evidence; `ET.adaptiveLimitedConnectivityPrefersHybridOverCacheOverOffline`
asserts only which strategy adaptive picks. `docs/strategies/network-only.md`
says "Connectivity unavailable or constrained: Return typed unavailable/policy
result", which the code does not do. This needs a policy decision before it can
be pinned by tests.

## 4. The matrix

Dimensions: **Conn** connectivity (AVAILABLE / UNAVAILABLE / LIMITED / UNKNOWN);
**Cache** hit (fresh) / stale / miss; **Retry/Circuit**; **Conflict**;
**Cancel**; **Restart** (durable continuation, process death).
`ET.x` names a test function in `ET`.

### 4.1 Network-only

| Dim | Code today | Proof | Class |
|---|---|---|---|
| Conn AVAILABLE | Executes transport only (`E:491-500`, `NOX:33-105`); ignores `transportHealth` and cache | `NOT.pushWithAMatchingAcknowledgementSucceeds`, `pullWithChangesSucceeds`, `bidirectionalWithBothOperationsSucceedingReturnsCombinedOutput`, `ET.networkOnlyNeverRequiresOrInvokesLocalCapabilitiesAcrossDirections` | PROVEN common. iOS: a real `synchronize` network-only run in `IOS:StrategyDiagnosticsAppleFile.executedNetworkOnlyDecisionSurvivesARealAppleFileStoreRestart` (happy path only, PROVEN iOS). Android: no network-only run on real providers, UNPROVEN |
| Conn UNAVAILABLE | `REJECT CONNECTIVITY_UNAVAILABLE`, no operations (`E:466-478`) | `ET.networkOnlyUnavailableIsTypedRejectionWithNoOperations`; coordinator rejection `DBD.strategyRejectedByPolicyIsRejected` | PROVEN common |
| Conn UNKNOWN | `ATTEMPT_REMOTE` or `REJECT` (`DEFER` is unconstructible, `StrategyContractsTest.networkOnlyCannotPromiseQueueBackedDeferral`) | `ET.networkOnlyUnknownConnectivityPolicyIsExplicit` | PROVEN common |
| Conn LIMITED | Executes (no gate); spec says "unavailable or constrained" is a typed result | none | UNPROVEN (defect D3) |
| Cache | No local state read or written; `cacheState` ignored | `ET.networkOnlyNeverRequiresOrInvokesLocalCapabilitiesAcrossDirections` | N/A |
| Retry/Circuit | No in-call retry (spec table lists the bounded in-call retry contract as "Pending"); circuit only through the protected facade | `DBP` "network only pull/push/bidirectional protects transport without resolving storage", "network only bidirectional preserves push evidence when pull fails after successful push", "missing transport protection rejects network only before provider invocation" | PROVEN common (protected facade). Real Room/Apple circuit store under the strategy facade: UNPROVEN (the Android/iOS AC-FUNC-004 proofs drive the legacy `protectedSynchronization`, `AndroidReferenceConsumerRetryCircuitQualificationInstrumentedTest` / `IOS:RetryCircuitQualification`) |
| Conflict | No inbound apply, so no local conflict. A remote conflict error is returned as `Failed` | `NOT.pushWhenTransportFailsPropagatesTheTransportError` | N/A |
| Cancel | `CancellationException` propagates (`NOX` has no catch) | `NOT.pullCancellationPropagatesRatherThanBecomingAFailedResult`, `pushCancellationPropagates...` | PROVEN common. Cancel after a completed push in BIDIRECTIONAL: UNPROVEN (minor) |
| Restart | No durable work by design; `DURABLE_QUEUE` trigger rejected (`COORD:298-303`); decision event survives store reopen | `DBD.durableQueueTriggerIsIncompatibleWithDirectExecution`; iOS `IOS:StrategyDiagnosticsAppleFile.executedNetworkOnlyDecisionSurvivesARealAppleFileStoreRestart` | N/A (contract). Decision-log durability on real Android Room: UNPROVEN (`RoomDurableStateStoreStrategyDecisionIntegrationTest` uses a mocked DAO) |

### 4.2 Remote-first

| Dim | Code today | Proof | Class |
|---|---|---|---|
| Conn AVAILABLE | Remote first via provider-backed pipeline, or non-persisting transport paths (`E:279-294`, `RFX:34-93`) | `RFT.providerBackedPipelineSuccessIsExecuted`, `transportOnlyPullSucceeds`, `nonPersistingBidirectionalWithBothOperationsSucceedingReturnsCombinedOutput`; real Room run of the pull leg in `AND:Robolectric.realAndroidProvidersApplyAPulledChangeToRealStorage` (legacy request path) | PROVEN common. Strategy-path remote execution on real providers: UNPROVEN on both platforms (platform proofs replay queue entries, below) |
| Conn UNAVAILABLE (or `transportHealth` UNAVAILABLE) | Local fallback if `UNAVAILABLE in fallbackOn` and local data, else `REJECT` (`E:236-264`); PULL/BIDIRECTIONAL serve local via provider (`RFX:50-60`) | `ET.remoteFirstUsesOnlyConfiguredTypedFallback`, `remoteFirstUnavailableTransportHealthTriggersSameOutcomeAsUnavailableConnectivity`, `RFT.serveLocalFastPathWithAvailableCacheActivatesFallbackWithoutAttemptingRemote`, `serveLocalFastPathWithNoLocalDataReturnsFallbackUnavailable`, `serveLocalFastPathWithoutFallbackProviderIsRejected` | PROVEN common for PULL. **PUSH: defect D1.** Platform: UNPROVEN |
| Conn UNKNOWN | Per profile: `ATTEMPT_REMOTE` / `DEFER` / `REJECT` (`E:266-277`, `E:603-635`); `DEFER` durably admits when an encoder is configured (`COORD:451-501`) | `ET.remoteFirstUnknownConnectivityPolicyIsExplicit` (PUSH only), `DBD.deferWithAnEncoderConfiguredIsDurablyAdmittedForReal`; `DEFER` end to end: `AND:RemoteFirstQueue.remoteFirstDeferralReplayedByQueueWorker`, `IOS:RemoteFirstQueue.remoteFirstDeferralReplayedByQueueWorker` | PROVEN Android (Robolectric) + PROVEN iOS for `DEFER`; PROVEN common for the other two policies |
| Conn LIMITED | Attempts remote | none | UNPROVEN (D3) |
| Cache | Fallback serves local only when the provider reports `Available`; `Unavailable(MISSING)` yields `FallbackUnavailable` (`RFX:308-332`) | `AND:RemoteFirstFallbackQueue.remoteFirstFallbackQueueWorkerReplay` (real Room, `Available`); `RFT.serveLocalFastPathWithNoLocalDataReturnsFallbackUnavailable` (fake); provider unit tests in `SqlDelightStorageProviderTest` ("evaluateLocalFallback ...") and `RoomStorageProviderTest` | PROVEN Android (Robolectric) for `Available`; iOS provider-unit only, no iOS end-to-end (UNPROVEN); `Unavailable` and freshness variants PROVEN common only |
| Retry/Circuit | Queue replay: `FallbackActivated` with an unresolved primary error goes through `evaluateRetry`, so the queue entry ends `FAILED` under a never-retry policy even when fallback data was served (documented in `AND:RemoteFirstFallbackQueue` KDoc; `StrategyQueueExecutionOutcomeMapper`). Protected facade wraps transport, storage and fallback | `DBP` "remote first fallback preserves transport and local fallback evidence", "missing local fallback protection rejects before remote or local invocation", "provider success with unconfirmed circuit recording stays fail closed"; `AND:RemoteFirstFallbackQueue.remoteFirstFallbackQueueWorkerReplay` (never-retry stop only) | PROVEN common (protected). Retry-then-succeed, exhaustion, circuit-open during plan replay: UNPROVEN on both platforms (row 2 "Still pending" says the same) |
| Conflict | `CONFLICT`, auth, authz, validation, integrity, cancellation cannot be in `fallbackOn` (profile `require`s, `SynchronizationStrategyProfile.kt:37-58`); classification `StrategyRemoteOutcomeClassifier.kt:10-28`. Detection/resolution rides the shared inbound pipeline (`DataLoomBuilder.kt:1236,1679-1699`) | `StrategyContractsTest.remoteFirstCannotHideProtectedFailureClasses`, `RFT.providerBackedPipelineUnrelatedFailureDoesNotActivateFallback` | Fallback-hiding: PROVEN common. Detection/resolution through a strategy request: UNPROVEN (`DataLoomBuilderConflictDetectionTest` and the quarantine/selection tests all call `synchronize(SynchronizationRequest)`, the legacy path) |
| Cancel | Propagates; pipeline `Cancelled` mapped (`RFX:234-239`) | `RFT.transportOnlyPullCancellationPropagatesRatherThanBecomingAResult`, `providerBackedPipelineCancellationIsPreserved` | PROVEN common. Queue-level cancelled outcome only for bidirectional push (`StrategyQueueExecutionOutcomeMapperTest.defensiveCancelledBidirectionalPushRemainsCancelled`) |
| Restart | Replay uses the persisted plan and persisted cache state, no re-evaluation (`ACCEPTED` KDoc, `execute`) | `APT.typedFallbackUsesPersistedCacheStateNotCurrentEvidence`, `fallbackReconciliationRunsOnceWithoutClaimingFailedPullCompleted`; `AND:RemoteFirstQueue`, `AND:RemoteFirstFallbackQueue`, `IOS:RemoteFirstQueue`; plan survives reopen/retry/defer/lease recovery: `ROOM-PLAN.exactPlanSurvivesReopenRetryDeferralAndExpiredLeaseRecovery`, `APPLE-PLAN.productionFileProviderPreservesPlanAcrossAllDurableTransitions` | PROVEN Android (Robolectric+emulator, persistence) + PROVEN iOS for `DEFER` replay; fallback-bearing replay PROVEN Android only (iOS counterpart missing); real process kill: UNPROVEN |

### 4.3 Cache-first

| Dim | Code today | Proof | Class |
|---|---|---|---|
| Conn AVAILABLE / miss | `MISSING` + `AVAILABLE` fetches remote and persists (`E:369-380`); PUSH executes `READ_LOCAL, PUSH_REMOTE` (`E:412-429`) | `ET.cacheFirstFreshStaleAndMissingDecisionsAreDistinct`, `CFT.missingCacheWithConnectivityFetchesRemoteWithoutServingLocal`, `pushWithConnectivitySucceeds` | PROVEN common |
| Conn UNAVAILABLE / non-AVAILABLE | Miss without `AVAILABLE` rejects `CACHE_MISS` (`E:381-393`); PUSH defers if `requireDurableRefresh` else rejects (`E:430-457`); hit branches ignore connectivity (D2) | `ET.cacheFirstFreshStaleAndMissingDecisionsAreDistinct` (`UNAVAILABLE` miss only); PUSH defer end to end `AND:CacheFirstQueue.cacheFirstPushDeferralReplayedByQueueWorker`, `IOS:CacheFirstQueue.cacheFirstPushDeferralReplayedByQueueWorker` | PROVEN Android (Robolectric) + PROVEN iOS for PUSH defer; UNKNOWN/LIMITED miss and PUSH-reject: UNPROVEN |
| Cache fresh / stale / miss / unknown | All four states and all three `StaleCachePolicy` values (`E:312-409`) | `ET.cacheFirstFreshStaleAndMissingDecisionsAreDistinct`, `cacheFirstRejectPolicyNeverServesStaleState`, `cacheFirstServeStalePolicyServesWithoutSchedulingAnyRefresh`, `cacheFirstUnknownCacheStateRejectsRatherThanGuessing`; executors `CFT.freshCacheWithNoRefreshIsServedFromCache`, `staleCacheWithServeStalePolicyIsServedFromCache`, `localStateMismatchWithEvidenceIsAContractError`, `freshCacheWithSynchronousRefreshServesAndRefreshes`; STALE + durable refresh on real providers: `AND:CacheFirstPullQueue.cacheFirstPullServesLocallyThenReplaysDurableRefresh`, `IOS:CacheFirstPullQueue.cacheFirstPullServesLocallyThenReplaysDurableRefresh` | PROVEN common; PROVEN Android (Robolectric) + PROVEN iOS for STALE + durable refresh. `FRESH` with `refreshOnFreshHit`, synchronous refresh, and miss-fetch on real providers: UNPROVEN. DataLoom-owned freshness windows do not exist (finding 3) |
| Retry/Circuit | Durable refresh replay maps `ServedFromCache.refreshOutput` through retry (`StrategyQueueExecutionOutcomeMapper`). Sync refresh failure returns `Failed` (D2). Protected facade: untested for cache-first | `CFT.staleCacheWithSynchronousRefreshFailurePropagatesTheFailure` (asserts D2); `CFT.durableRefreshAdmissionFailurePropagatesAsFailed` | Behavior asserted but contradicts spec (D2). Protection and retry/circuit on platform: UNPROVEN |
| Conflict | "A remote refresh cannot overwrite newer local work" rides the shared pipeline | none via strategy request | UNPROVEN |
| Cancel | Sync refresh cancellation propagates | `CFT.synchronousRefreshCancellationPropagatesRatherThanBecomingAServedResult` | PROVEN common |
| Restart | Durable refresh admitted while still serving local; continuation frozen (`E:683-737`); replay by accepted-plan coordinator; not idempotent (finding 6) | `CFT.durableRefreshIsAdmittedAndStillServesLocalStateSynchronously`, `StrategyDurableContinuationEvaluationTest.durableCacheRefreshFreezesPullAndPersistence`; platform `AND:CacheFirstQueue`, `AND:CacheFirstPullQueue`, `IOS:CacheFirstQueue`, `IOS:CacheFirstPullQueue`; persistence `ROOM-PLAN`, `APPLE-PLAN` | PROVEN Android (Robolectric+emulator persistence) + PROVEN iOS for admit-then-replay. "At most one active refresh" and "process death after admission resumes without duplicate apply": UNPROVEN (no dedupe exists) |

### 4.4 Offline-first

| Dim | Code today | Proof | Class |
|---|---|---|---|
| Conn AVAILABLE | Accept-local, then admit durably (`requireDurableQueue`, default) which **replaces** the synchronous attempt and returns `DurablyEnqueued` (`OFX:111-128`); with `requireDurableQueue=false` runs the pipeline then reconciles (`OFX:130-158`) | `ET.offlineFirstOnlinePlanReconcilesAfterDurableAdmission`, `OFT.durableQueueIsAdmittedAndShortCircuitsTheSynchronousRemoteAttempt`, `pushWithConnectivitySucceeds`; `AND:DurableQueue.durableAdmissionIsReplayedByQueueWorker`, `IOS:DurableQueue.durableAdmissionIsReplayedByQueueWorker` | PROVEN Android (Robolectric) + PROVEN iOS for admit-then-replay, **with `reconcileWhenOnline = false`** (both tests set it) |
| Conn UNAVAILABLE | `DEFER` (`CONNECTIVITY_UNAVAILABLE`), local accepted, durable if encoder (`E:208-227`, `COORD:451-501`) | `ET.offlineFirstAcceptsAndQueuesBeforeConnectivityDeferral`, `DBD.deferredDispositionIsReturnedAsDeferred`, `deferWithAnEncoderConfiguredIsDurablyAdmittedForReal`; the same `handleDefer` path end to end via remote-first/hybrid `UNKNOWN`+`DEFER` platform tests | PROVEN common; `handleDefer` PROVEN Android + iOS via other strategies |
| Conn LIMITED / UNKNOWN / NOT_EVALUATED | `DEFER`; LIMITED reported `CONNECTIVITY_UNAVAILABLE`, UNKNOWN and NOT_EVALUATED reported `CONNECTIVITY_UNKNOWN` (`E:208-216`); `docs/strategies/offline-first.md` says the same | **added by this PR:** `ET.offlineFirstDefersLimitedAndUnknownConnectivityWithDistinctReasons` | PROVEN common (new) |
| Cache | `SERVE_LOCAL` only when cache is `FRESH`/`STALE` and direction is PULL/BIDIRECTIONAL (`E:173-186`) | `ET.offlineFirstServesLocalOnlyWhenCacheStateMakesDataAvailable`, `OFT.pullWithLocalDataServesLocalThenSynchronizes`, `pullLocalStateMismatchWithEvidenceIsAContractError`, `bidirectionalWithLocalDataServesAndSynchronizesBothWays` | PROVEN common; platform UNPROVEN |
| Retry/Circuit | Connectivity deferral is not a retry (`DEFER`, queue `defer`); transient failure goes to queue retry. Retry-count preservation through deferral/lease recovery is a queue property | `ROOM-PLAN` / `APPLE-PLAN` reschedule with `RetryAttempt(1)` then defer then lease recovery, but only assert the plan is unchanged, not the attempt count; protected accepted-plan facade has routing tests only (`ProtectedAcceptedStrategyQueuedExecutionRoutingTest`) | Plan persistence PROVEN Android (emulator) + PROVEN iOS. Retry N stays N for plan-bearing entries, exhaustion, circuit-open: UNPROVEN |
| Conflict | Shared pipeline; `RECONCILE` is a bounded existence check via `StrategyReconciliationProvider` (finding 3) | `OFT.reconciliationRunsAfterASuccessfulSynchronizationAndSucceeds`, `reconciliationWithoutAConfiguredProviderIsRejected`, `reconciliationFailurePropagatesAsAFailure`, `APT.offlineContinuationUsesStoredOperationsAndReconcilesExactlyOnce` | PROVEN common. **Default profile (`reconcileWhenOnline = true`) replay with the real Room/SQLDelight reconcile hooks: UNPROVEN on both platforms** (only hybrid's reconcile branch is proven, `AND:HybridReconcileQueue`, `IOS:HybridReconcileQueue`). Detection through a strategy request: UNPROVEN |
| Cancel | Propagates | `OFT.remoteSynchronizationCancellationPropagatesRatherThanBecomingAResult` | PROVEN common |
| Restart | Persisted plan replay (see above). `#102` requires "accepted intent survives process death between admission and transport" | persistence-across-reopen `ROOM-PLAN`, `APPLE-PLAN`; no kill test | Reopen PROVEN Android (emulator) + PROVEN iOS. Process death: **UNPROVEN**. Atomic local-intent + outbox: not implemented (finding 7) |

### 4.5 Hybrid

| Dim | Code today | Proof | Class |
|---|---|---|---|
| Conn AVAILABLE | Primary source used; remote leg honors `persistRemoteResult` (`E:532-578`, `HYX:143-171`) | `HYT.remotePrimaryPullSucceeds`, `remotePrimaryPushSucceeds`, `remotePrimaryBidirectionalSucceeds`, `persistRemoteResultFalse*` | PROVEN common; platform remote leg UNPROVEN |
| Conn UNAVAILABLE | Remote primary falls back to local when local is available and storage healthy, plus `ENQUEUE_DURABLE_WORK`+`RECONCILE` (`SERVE_AND_REFRESH`) (`E:566-600`) | `ET.hybridRemotePrimaryUsesDeclaredLocalFallbackAndReconciliation`, `hybridStorageHealthGatesLocalFallbackIndependentlyOfCacheState`, `HYT.durableRefreshFallbackIsAdmittedAndStillServesLocalStateSynchronously`; `AND:HybridReconcileQueue.hybridReconcileQueueWorkerReplay`, `IOS:HybridReconcileQueue.hybridReconcileQueueWorkerReplay` | PROVEN Android (Robolectric) + PROVEN iOS |
| Conn UNKNOWN | Remote primary + `DEFER` defers (`E:514-530`) | `ET.hybridUnknownConnectivityPolicyDoesNotImproviseFallback`; `AND:HybridQueue.hybridDeferralReplayedByQueueWorker`, `IOS:HybridQueue.hybridDeferralReplayedByQueueWorker` | PROVEN Android (Robolectric) + PROVEN iOS |
| Conn LIMITED | Remote requires `AVAILABLE`, so LIMITED behaves as unavailable | none | UNPROVEN (D3) |
| Cache | Local availability needs cache `FRESH`/`STALE` and storage not `UNAVAILABLE` (`E:511-512`); local-primary serves local (`HYX:129-141`) | `ET.hybridMissingCacheStatePreventsLocalFallbackIndependentlyOfStorageHealth`, `HYT.localPrimaryPullServesFromCache`, `localPrimaryBidirectionalServesFromCache`, `localPrimaryPushIsAcceptedLocallyAsTransportFree`, `localStateMismatchWithEvidenceIsAContractError` | PROVEN common |
| Retry/Circuit | No reactive fallback: a remote failure is a plain `Failed` (`HYX` KDoc, unlike remote-first) | `HYT.remoteFailurePropagatesWithoutReactiveFallback` | PROVEN common. Protected facade for hybrid, retry/circuit on platform: UNPROVEN |
| Conflict | Shared pipeline plus bounded `RECONCILE` | `APT.localOnlyReconciliationExecutesExactlyOnce` | Detection through a strategy request: UNPROVEN. "Coherence rule" enforcement beyond `RECONCILE`: not evidenced |
| Cancel | Propagates for non-persisting pull; pipeline `Cancelled` mapping (`HYX:317-321`) | `HYT.persistRemoteResultFalsePullCancellationPropagatesRatherThanBecomingAResult` | PROVEN common for the transport path; pipeline-`Cancelled` mapping UNPROVEN |
| Restart | Durable fallback continuation replay; PUSH fallback yields `DurablyEnqueued` | `HYT.durableRefreshFallbackForPushIsDurablyEnqueuedWithoutServingLocal` (common only); platform `AND:HybridReconcileQueue`, `IOS:HybridReconcileQueue`, `AND:HybridQueue`, `IOS:HybridQueue` | PROVEN Android (Robolectric) + PROVEN iOS for PULL; PUSH-fallback durable path platform UNPROVEN; process kill UNPROVEN |

### 4.6 Adaptive

| Dim | Code today | Proof | Class |
|---|---|---|---|
| Conn (selection) | Deterministic preference order per connectivity; `UNKNOWN`/`NOT_EVALUATED` fall to `safeDefaultProfileId` or `REJECT NO_ELIGIBLE_ADAPTIVE_PROFILE` (`E:85-140`, `E:55-83`). Reads only `hasPendingLocalChanges`, `cacheState`, `connectivity`, `transportHealth` (`docs/audits/DL-039B-adaptive-selection-factor-gap.md`) | `ET.adaptiveSelectionIsDeterministicAndRecordsRequestedAndEffectiveStrategy`, `adaptiveAvailableConnectivitySkipsRemoteCandidatesWhenTransportIsUnavailable`, `adaptiveLimitedConnectivityPrefersHybridOverCacheOverOffline`, `adaptiveWithoutEligibleOrSafeDefaultRejectsExplicitly`; end-to-end through each real executor `ADT.adaptiveResolvedTo{RemoteFirst,Hybrid,CacheFirst,OfflineFirst,NetworkOnly}ExecutesThroughTheRealCandidateProfile` | PROVEN common. No platform run of an adaptive request: UNPROVEN |
| Cache | Same factors as concrete strategies; `FRESH` shortcut to cache-first | `ET.adaptiveSelectionIsDeterministic...` | PROVEN common |
| Retry/Circuit | Circuit state is only proxied by caller-supplied `transportHealth`; `operation`, tenant/workflow config, `configurationVersion`, `queueHealth` are not read | `ET.adaptiveAvailableConnectivitySkipsRemoteCandidatesWhenTransportIsUnavailable` | Partly implemented; the missing `#102` factors are UNPROVEN and unimplemented (needs product decision) |
| Conflict | Delegated to the selected strategy | n/a | N/A (see the concrete strategy) |
| Cancel | Delegated | n/a | N/A |
| Restart | Concrete selection is what is persisted (`requestedStrategy=ADAPTIVE`, concrete effective profile) | `StrategyQueueAdmissionEvaluatorTest.adaptiveRequestPersistsTheConcreteEffectiveStrategy`, `StrategyDurableContinuationEvaluationTest.adaptiveSelectionFreezesConcreteCandidateContinuation`; persisted `ADAPTIVE` plan round-trips in `ROOM-PLAN` and `APPLE-PLAN` fixtures | PROVEN common; storage round-trip PROVEN Android (emulator) + PROVEN iOS; replay of an adaptive-admitted entry on a platform: UNPROVEN |

### 4.7 Acceptance items of `#102` that are not strategy-specific

| `#102` acceptance item | Status |
|---|---|
| "Effective strategy cannot silently change after queueing, restart, retry, or lease recovery" | PROVEN: `QueuedStrategyDecisionCorrespondenceTest`, `QueuedStrategyPlanCorrespondenceTest`, `APT.mismatchedDecisionStopsBeforeProviderExecution`, `ROOM-PLAN`, `APPLE-PLAN`. Process kill UNPROVEN |
| "network-only proves zero storage/queue calls" | PROVEN common (`NOX` has no storage/queue reference; `ET.networkOnlyNeverRequiresOrInvokesLocalCapabilitiesAcrossDirections`) |
| Durable strategy diagnostics | Builder-level tests cover only a network-only execution and a rejection (`DataLoomBuilderStrategyDiagnosticsTest`); the mapping of the other result kinds to `StrategyDecisionOutcomeKind` (`COORD:237-259`) is untested. Android real-Room store: UNPROVEN (mocked DAO). iOS Apple-file store: PROVEN iOS |
| "Native Android, KMP Android, and KMP iOS expose equivalent observable decisions" | Native/KMP Android: the runtime's `commonTest` (2,012 tests per the 2026-09-28 fragment `2026-09-28-101-kmp-android-rollout-2.md`, not re-counted here) runs under `testAndroidHostTest`; the strategy suite passes there (26 evaluator tests re-run for this audit). No cell above distinguishes "native Android" from "KMP Android" beyond that. KMP iOS: rows marked PROVEN iOS |

## 5. Ranked backlog of bounded next slices

"Windows" = authorable and verifiable on this host (JVM, Android host test,
Robolectric). "macOS CI" = can be authored and cross-compiled here but only run
in `apple-validation.yml`.

| # | Slice | Files it would touch | Proof | Environment |
|---|---|---|---|---|
| 1 | **Fix D1**: remote-first PUSH must not take the local-fallback shortcut (reject `CONNECTIVITY_UNAVAILABLE`, consistent with `remoteFallbackPlan` returning null for PUSH). Tiny decision: reject vs defer | `E` (`evaluateRemoteFirst`), `ET`, `DBD` | `ET` PUSH+`UNAVAILABLE`+`fallbackOn` asserts `REJECT`; `DBD` coordinator test asserts `Rejected`, no exception | Windows; needs a one-line human OK on reject vs defer |
| 2 | Conflict through the strategy facade: drive a conflicting inbound change through `synchronize(StrategySynchronizationRequest)` for remote-first, cache-first, offline-first and hybrid pull legs, asserting `conflictsDetected` and unresolved-log content | new `DataLoomBuilderStrategyConflictDetectionTest.kt` in `dataloom-runtime` commonTest (reuse `DataLoomBuilderConflictDetectionTest` fixtures) | asserts the strategy registry (`DataLoomBuilder.kt:1679-1699`) threads conflict configuration | Windows |
| 3 | Refresh the stale strategy docs (README banner and "Current repository" table, four page banners, `network-only.md` acceptance table, `cache-first.md` D2 wording once decided) | `docs/strategies/*.md` | doc review against this matrix | Windows |
| 4 | Offline-first default profile (`reconcileWhenOnline = true`) admit-then-replay with the real reconcile hooks | `AND:DurableQueue` (variant), `IOS:DurableQueue` (variant) | replay ends `COMPLETED` with `reconcileStrategy` invoked once on the real provider | Android on Windows (Robolectric); iOS macOS CI |
| 5 | iOS counterpart of `AND:RemoteFirstFallbackQueue` (already a named follow-up in row 2) | new `IosReferenceConsumerRemoteFirstFallbackQueueTest.kt` | reactive fallback served through `SqlDelightStorageProvider.evaluateLocalFallback` once, entry `FAILED` under never-retry | macOS CI (cross-compile on Windows) |
| 6 | Protected strategy facade beyond network-only/remote-first: cache-first (stale + sync refresh), hybrid (fallback), offline-first, and protected accepted-plan replay per strategy | `DBP` (extend) | evidence lists for each strategy; missing protection rejects before invocation | Windows |
| 7 | Retry-then-succeed, retry exhaustion, and circuit-open during replay of plan-bearing entries with real providers (the item row 2 says is open) | new Robolectric tests beside `AND:*Queue`; iOS twins | attempt count, terminal state, and circuit rejection asserted | Android on Windows; iOS macOS CI |
| 8 | Diagnostics mapping: one test per `StrategySynchronizationExecutionResult` kind through `strategyDiagnosticsConfiguration` | `DataLoomBuilderStrategyDiagnosticsTest.kt` | each kind maps to its `StrategyDecisionOutcomeKind`/detail | Windows |
| 9 | Adaptive on platforms: adaptive request resolving to a durable branch, replayed by the queue worker | new `AND:`/`IOS:` adaptive queue tests | requested `ADAPTIVE`, effective concrete strategy preserved across replay | Android on Windows; iOS macOS CI |
| 10 | Real-Room strategy diagnostics: instrumented test replacing the mocked-DAO coverage | `dataloom-queue-room/src/androidTest` (beside `RoomDurableStateStoreInstrumentedTest`) | event and outcome history survive DB reopen | Android emulator CI (author on Windows, verify in CI) |
| 11 | Emulator (managed-device) variants of the strategy replay proofs | `runtime-android-reference-consumer/src/androidTest` | same assertions as the Robolectric tests | Android emulator CI |
| 12 | Direct (non-queue) strategy execution on real providers for network-only (Android) and remote-first/cache-first/hybrid pull legs (both platforms) | new reference-consumer tests | real Room/SQLDelight storage receives the pull | Android on Windows; iOS macOS CI |

## 6. Blocked or needing a human/external decision

| Item | Blocker |
|---|---|
| Connectivity/cache/provider-health **evidence derivation** (finding 1) and platform parity of the connectivity dimension | Design decision: does the runtime observe `ConnectivityProvider` itself, or stay caller-supplied? Public API shape (ABI). Everything platform-level in the connectivity column depends on it |
| Metered/unmetered/provider-failure connectivity states; `LIMITED` semantics per strategy (D3) | Policy decision; changes an ABI enum and evaluator behavior |
| Cache freshness ownership (finding 3) | Product decision: DataLoom-owned freshness windows and metadata, or application-owned evidence as built. `cache-first.md` and the code disagree |
| Cache-first refresh-failure semantics (D2) | Decision: keep `Failed` or return served-plus-refresh-error. The latter changes the public `StrategySynchronizationExecutionResult` and the queue outcome mapper |
| Adaptive factors: operation, tenant/workflow config, configuration version, circuit state | Product decision recorded in `docs/audits/DL-039B-adaptive-selection-factor-gap.md`; no channel for tenant/workflow data exists |
| Offline-first atomic local-intent + outbox admission (finding 7) | Design decision: DataLoom-owned local write or an application-provided atomic callback |
| Process death for strategy plans on iOS (offline-first "survives process death between admission and transport") | Needs a launchable Simulator proof app, a `simctl` kill/relaunch job and a state read-back, in a new workflow file (`docs/apple/process-termination-investigation.md`). Android could reuse the `AndroidProcessTermination*` instrumented pattern and is bounded, but was not scoped here |
| Real `BGTaskScheduler` / WorkManager wake driving a strategy refresh; physical-device proof | Hardware or a macOS-hosted run; `SCHEDULE_REFRESH` is planning-only (finding 5) |
| Explicit-unsupported/degraded outcome for a platform limitation ("platform differences produce explicit unsupported/degraded outcomes") | No such type exists for strategies; needs design |
| Durable admission idempotency (finding 6) | Design decision on a decision-derived idempotency key |
| CI evidence for every PROVEN Android/iOS cell | Requires reading Actions results; this audit could not |

## 7. What was verified

Run on Windows in this worktree:

- `:dataloom-runtime:jvmTest --tests ...BuiltInSynchronizationStrategyEvaluatorTest`: 26 tests, 0 failures (25 existing plus the added one).
- `DATALOOM_ANDROID_BUILD=true :dataloom-runtime:testAndroidHostTest --tests ...BuiltInSynchronizationStrategyEvaluatorTest`: 26 tests, 0 failures.
- `DATALOOM_ANDROID_BUILD=true :dataloom-runtime:check --dry-run` lists `testAndroidHostTest`.
- `:dataloom-runtime:compileTestKotlinIosSimulatorArm64 -Pdataloom.appleKlibCrossCompile=true`: succeeds (compile only; the iOS test was not run).
- `DATALOOM_ANDROID_BUILD=true :runtime-android-reference-consumer:testDebugUnitTest`: all 8 Robolectric classes (9 tests, 7 of them the strategy queue proofs plus the 2 basic provider proofs) pass, 0 failures. So every "PROVEN Android (Robolectric)" cell was observed passing on this host.
- A temporary executor-level test reproducing D1 (reverted, not committed).

Not verified, so every PROVEN Android (emulator) and PROVEN iOS cell means "the
test exists and is wired into CI", not "I saw it pass":

- Any Actions run, including the Android managed-device and macOS jobs.
- The `iosTest` suites and the Room `androidTest` suites (they cannot run here).
- The coordinator-level manifestation of D1 (traced by reading; only the executor was run).
- The `RoomStorageProviderTest` and `SqlDelightStorageProviderTest` fallback/reconcile unit tests (names read, not run).

Files read to build this matrix (not exhaustive): the evaluator, all five
executors, both coordinators, `StrategyDurableQueueAdmitter`,
`StrategyRemoteOutcomeClassifier`, both real storage providers' fallback and
reconcile hooks, `DataLoomBuilder`'s registry assembly, every strategy test
named above, the Android/iOS reference-consumer strategy tests, `docs/strategies/*`,
ADR-0002's strategy section, and `docs/audits/DL-039B-adaptive-selection-factor-gap.md`.
