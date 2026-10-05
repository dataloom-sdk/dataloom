# Fragment: `#101` iOS counterpart of the Android remote-first non-empty-`fallbackOn` queue proof

Proposed dashboard changes for the lead to fold into `docs/status/market-readiness.md`.
`docs/status/market-readiness.md` itself was not edited.

## (a) Proposed "Recently shipped" row

| 2026-10-05 | `#101`: iOS counterpart of the Android remote-first non-empty-`fallbackOn` durable-queue proof. New `IosReferenceConsumerRemoteFirstFallbackQueueTest` (`runtime-ios-reference-consumer/src/iosTest`, one test `remoteFirstFallbackQueueWorkerReplay`) mirrors `AndroidReferenceConsumerRemoteFirstFallbackQueueRobolectricTest` step for step: `RemoteFirstStrategyProfile(fallbackOn = {UNAVAILABLE}, unknownConnectivityPolicy = DEFER)` PULL with connectivity `UNKNOWN` is durably admitted (`Deferred`, non-null `queueEntryId`, zero transport and zero fallback calls) through the real on-disk `AppleFileQueueProvider`; one `queueWorker.run(...)` replays it; the test transport's `pullChanges` always fails with a `ClassifiedStrategyRemoteError` classified `UNAVAILABLE` (called exactly once); the real `SqlDelightStorageProvider.evaluateLocalFallback` (observed through a fully delegating counting wrapper, seeded with one real checkpoint) is invoked exactly once and reports `Available(STALE)`; the observer sees a `Failed` result carrying the simulated transport error; and the queue summary is `failed == 1`, `completed == 0`. Investigation findings: (1) the previous iOS remote-first test's KDoc (and the `#101` row's older text) claimed `SqlDelightStorageProvider` does not implement `StrategyLocalFallbackProvider` -- that is stale; it has implemented it (and `StrategyReconciliationProvider`) since `#101` 2026-09-13, so no production change was needed and the branch is provable on iOS exactly as on Android; (2) the documented property that the queue-level disposition is a confirmed `FAILED`, not `COMPLETED`, even when local fallback data is served was re-verified against `StrategyQueueExecutionOutcomeMapper.map`'s `FallbackActivated` arm directly (`unresolvedErrors = errorsFrom(partialOutput)` is non-empty because `handleRemoteFailure`/`executeFallback` carry the failed `PULL_REMOTE` forward as `partialOutput`, so the entry goes through `evaluateRetry`, and the never-retry policy resolves to `StopRetry` -> `failed(...)`), not copied from the Android test's prose. The stale KDoc claims in `IosReferenceConsumerRemoteFirstQueueTest` were corrected (comment-only). Test-only; zero production, commonMain or API/ABI files changed. Verified: the new file cross-compiles cleanly for `iosSimulatorArm64`, `iosArm64` and `iosX64` (`:runtime-ios-reference-consumer:compileTestKotlinIos*`, run individually, `-Pdataloom.appleKlibCrossCompile=true`), and the Android Robolectric counterpart (same commonMain planner/coordinator/mapper logic, real Room storage instead of SQLDelight) was re-run fresh with `DATALOOM_ANDROID_BUILD=true ... --rerun-tasks :runtime-android-reference-consumer:testDebugUnitTest --tests ...AndroidReferenceConsumerRemoteFirstFallbackQueueRobolectricTest`: JUnit XML `tests="1" skipped="0" failures="0" errors="0"`. NOT verified: execution of the new iOS test -- it is cross-compiled only and will first execute on macOS CI (`apple-validation`); the Robolectric run is the JVM-side evidence for the shared scenario logic, not evidence the Kotlin/Native `SqlDelightStorageProvider`/`AppleFileQueueProvider` stack behaves identically. NOT proven: the `Unavailable` fallback outcome on either platform, retry/circuit-breaker interaction during replay, a real Apple background-scheduler tick, a physical device. | `#101` iOS remote-first fallback |

## (b) Percentage recommendation: unchanged

Justification: this closes a named follow-up ("its iOS counterpart remains a named follow-up") with a
test-only proof, but the new test has not executed on macOS CI yet and `#101`'s larger open items
are untouched. Recommend holding the percentage until `apple-validation` is green on the new file;
if the lead prefers to credit it now, at most +1.

## (c) "Still pending" text

Remove the clause "its iOS counterpart remains a named follow-up" from the remote-first
non-empty-`fallbackOn` sentence, and replace it with:

> remote-first's own non-empty-`fallbackOn` durable branch now has an iOS counterpart
> (`IosReferenceConsumerRemoteFirstFallbackQueueTest`, 2026-10-05) mirroring the Android proof;
> cross-compiled only, first execution is the macOS `apple-validation` job. Its queue-level
> disposition is a confirmed `FAILED` (not `COMPLETED`) even when local fallback data is served
> (`StrategyQueueExecutionOutcomeMapper`'s `FallbackActivated` arm retries on the carried-forward
> `PULL_REMOTE` failure).

Also correct any remaining "`SqlDelightStorageProvider` implements only `StorageProvider`" wording
in the `#101` row/log: it has implemented `StrategyLocalFallbackProvider`/`StrategyReconciliationProvider`
since 2026-09-13.
