# Fragment: `#101` re-audit -- cache-first PULL `queueEntryId` surfacing (2026-10-01)

For the lead to fold into `docs/status/market-readiness.md`. Not edited by
this PR itself.

## Investigation summary

The row's own "Still pending" cell currently says: "no public API returns
the PULL branch's durably-admitted entry's `queueEntryId` to a caller on
either platform, a real, narrow gap this row's own investigations surfaced
but left unfixed as out of scope." This PR was scoped to close that gap.
Reading current source shows the gap was **already closed** before this PR
started, and the "Still pending" clause is stale, not an open item:

- `StrategySynchronizationExecutionResult.ServedFromCache` (`dataloom-runtime/
  src/commonMain/kotlin/io/dataloom/runtime/strategy/StrategySynchronizationExecutionResult.kt:137-152`)
  has carried a `durableQueueEntryId: QueueEntryId?` field since `#265`
  (commit `7b5c9b4f`, "Wire durable queue admission for cache-first,
  offline-first, hybrid").
- `CacheFirstStrategyExecutor.execute` (`dataloom-runtime/src/commonMain/
  kotlin/io/dataloom/runtime/strategy/CacheFirstStrategyExecutor.kt:74-110,
  179-222`) threads the real admitted `queueEntryId` into every
  `ServedFromCache` it constructs, not just one branch.
- `BuiltInSynchronizationStrategyEvaluator.evaluateCacheFirst`
  (`dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/
  BuiltInSynchronizationStrategyEvaluator.kt:360-415`) confirms, by
  inspection, that cache-first's only two PULL/BIDIRECTIONAL branches that can
  produce `ENQUEUE_DURABLE_WORK` (`FRESH` + `refreshOnFreshHit`, and `STALE` +
  `SERVE_STALE_AND_REFRESH`, the profile default) *always* also include
  `SERVE_LOCAL` in the same `operations` list -- cache-first's durable refresh
  augments serving local state, it never replaces it the way offline-first's
  does. There is structurally no PULL/BIDIRECTIONAL path where
  `ENQUEUE_DURABLE_WORK` is admitted but the result is a bare `Deferred`/
  `DurablyEnqueued` with nowhere to carry the id -- every reachable path
  returns `ServedFromCache`, which already has the field.
- `DataLoom.synchronize(StrategySynchronizationRequest): StrategySynchronizationExecutionResult`
  (`dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/
  DataLoom.kt:318-320`) is the real public API surface -- it returns the
  sealed result directly, not a reduced DTO, and
  `ProviderProtectedStrategySynchronizationResult.strategyResult`
  (`.../execution/protection/ProviderProtectedStrategySynchronization.kt:38-39`)
  wraps the same full result rather than projecting it down, so no caller
  path drops the field either.
- The ABI baseline already exposes it:
  `dataloom-runtime/api/dataloom-runtime.api:4105-4114` shows
  `ServedFromCache`'s public `copy`/accessor surface already includes the
  field. No ABI change is needed or was made.
- Three existing, passing test suites already prove a *caller* -- not just
  the executor internally -- receives the real id, on both platforms, through
  the real public API:
  1. `CacheFirstStrategyExecutorTest.durableRefreshIsAdmittedAndStillServesLocalStateSynchronously`
     (`dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/strategy/
     CacheFirstStrategyExecutorTest.kt:333-359`) -- unit-level, asserts
     `served.durableQueueEntryId == QueueEntryId("cache-first-queue-entry")`.
  2. `AndroidReferenceConsumerCacheFirstPullQueueRobolectricTest
     .cacheFirstPullServesLocallyThenReplaysDurableRefresh`
     (`runtime-android-reference-consumer/src/test/kotlin/io/dataloom/
     consumer/android/AndroidReferenceConsumerCacheFirstPullQueueRobolectricTest.kt:299-304`)
     -- end-to-end through a real `DataLoom.synchronize(...)` call against a
     real `RoomStorageProvider`/`RoomQueueProvider`, asserts
     `assertNotNull(served.durableQueueEntryId)`, then reads that same
     continuation back out of the real queue and replays it.
  3. `IosReferenceConsumerCacheFirstPullQueueTest`
     (`runtime-ios-reference-consumer/src/iosTest/kotlin/io/dataloom/
     consumer/ios/IosReferenceConsumerCacheFirstPullQueueTest.kt:304-308`) --
     byte-for-byte the same proof against `SqlDelightStorageProvider`/
     `AppleFileQueueProvider`.
  All three were added by `#396` (commit `9c03dd2e`, "Implement
  StrategyLocalFallbackProvider/StrategyReconciliationProvider on both
  reference storage providers") and `#383` (commit `327a567f`, "iOS proof for
  cache-first PULL/BIDIRECTIONAL durable branch"), both already on `main`
  well before this PR's branch point (`git merge-base --is-ancestor 9c03dd2e
  origin/main` confirms it).

No production code or public API changed in this PR -- there was nothing to
fix. Verified by re-running the existing Android proof, unmodified, on this
Windows host: `DATALOOM_ANDROID_BUILD=true ./gradlew
:runtime-android-reference-consumer:testDebugUnitTest --tests
"io.dataloom.consumer.android.AndroidReferenceConsumerCacheFirstPullQueueRobolectricTest"`
-> `BUILD SUCCESSFUL`. The iOS counterpart cannot run (no Simulator on
Windows) but was re-verified to cross-compile clean against unmodified
source: `./gradlew :runtime-ios-reference-consumer:compileTestKotlinIosSimulatorArm64
-Pdataloom.appleKlibCrossCompile=true`.

Per the no-fake-fix discipline: since the gap named in "Still pending" does
not exist in current source, this PR makes no code change and instead
corrects the stale dashboard text.

## (a) Proposed "Recently shipped" row

| 2026-10-01 | `#101` (audit): re-derived evidence for the row's own "Still pending" claim that "no public API returns the PULL branch's durably-admitted entry's `queueEntryId` to a caller on either platform." Found this is stale -- `ServedFromCache.durableQueueEntryId` has existed since `#265` (`7b5c9b4f`), `CacheFirstStrategyExecutor` threads the real admitted id into every `ServedFromCache` it builds (cache-first's only two PULL/BIDIRECTIONAL durable-admission branches always also carry `SERVE_LOCAL`, confirmed by inspection of `BuiltInSynchronizationStrategyEvaluator.evaluateCacheFirst`, so there is no reachable path that admits durably without a place to carry the id), and `DataLoom.synchronize(StrategySynchronizationRequest)` -- the real public API -- returns that result directly with nothing projecting the field away. Three existing tests already proved a caller receives the real id through the real public API before this PR started: `CacheFirstStrategyExecutorTest.durableRefreshIsAdmittedAndStillServesLocalStateSynchronously` (unit), `AndroidReferenceConsumerCacheFirstPullQueueRobolectricTest.cacheFirstPullServesLocallyThenReplaysDurableRefresh` (`#396`, re-run clean on this host: `BUILD SUCCESSFUL`), and `IosReferenceConsumerCacheFirstPullQueueTest` (`#383`, re-verified to cross-compile clean; Simulator run remains unavailable on this Windows host). No production code, public API, or ABI changed -- this is a documentation-accuracy correction, zero new capability | `#101` audit |

## (b) Row percentage

`#101`: 80% -> **unchanged**, recommended. Justification: this audit adds no
new production capability -- the capability it was asked to add already
existed and was already tested on both platforms before this PR started.
Correcting a stale "Still pending" clause does not itself move the needle on
remaining parity work (physical-device proof, `BGTaskScheduler` real
invocation, retry/circuit-breaker/conflict-detection behavior during replay,
staged external consumers, the complete parity matrix -- all still open, see
below).

## (c) "Still pending" text

Remove this clause from the row's "Still pending" cell (the only change this
PR proposes to that cell):

> -- no public API returns the PULL branch's durably-admitted entry's
> `queueEntryId` to a caller on either platform, a real, narrow gap this
> row's own investigations surfaced but left unfixed as out of scope

Leave every other clause in the cell untouched (physical-device proof,
`BGTaskScheduler` real-invocation proof, remote-first's own non-empty-
`fallbackOn` iOS counterpart, retry/circuit-breaker/conflict-detection
behavior during queue replay, staged external consumers, the complete parity
matrix -- all independently re-confirmed as still genuinely open by this
audit and not addressed here).
