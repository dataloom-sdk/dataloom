# Fragment: lifecycle-triggered queue drain (decision D23)

Gate touched: #101 (DL-039A Android/KMP/iOS parity), row 2. Closes the pending
item "consume the lifecycle signal: use `AppLifecycleProvider` in the runtime to
trigger a real queue-drain tick" from the 2026-09-20 fragment, in its
runtime-consumer half. Decision record:
`docs/adr/ADR-0013-lifecycle-triggered-queue-drain.md`; design doc:
`docs/api/app-lifecycle-provider.md`.

## (a) Proposed "Recently shipped" row

| 2026-09-28 | #101 | Lifecycle-triggered queue drain (decision D23, ADR-0013). New opt-in `DataLoomBuilder.lifecycleDrainConfiguration(DataLoomLifecycleDrainSpec)` taking an `AppLifecycleProvider`, a `QueueConsumerId` and a `LifecycleDrainPolicy` (triggers, default `BACKGROUND` and `TERMINATING_SOON`, `FOREGROUND` optional; 30 s minimum interval; 25 max entries per drain; 60 s lease), exposed as `DataLoom.lifecycleDrain.run()` which the host launches in its own scope. On a qualifying transition the runtime performs at most one bounded drain through the existing queue worker (same instance as `DataLoom.queueWorker`, so health tracking and event bridging apply; no second worker path). Drains never overlap (a transition mid-drain is dropped, also across concurrent collectors), the minimum interval is enforced on the runtime clock (a backwards clock never blocks), the collection-start state is not a transition, cancelling the collector cancels the in-flight drain, worker failures never end the collection, and a lifecycle-stream failure returns `LifecycleDrainEnd.ObservationFailed` instead of throwing. Absent spec is inert and `build()` collects nothing. Verified: 24 unit tests (fake lifecycle provider, fake worker, fake clock) and 5 builder tests (real queue provider, worker and `QueueWorkerHealthTracker`) on the JVM `jvmTest` and the Android `testAndroidHostTest`; `dataloom-runtime` main and test klibs cross-compiled for iosArm64, iosSimulatorArm64 and iosX64; `dataloom-runtime` ABI baselines regenerated in both layouts (byte-identical) and `checkKotlinAbi` green in the default+cross-compile and `DATALOOM_ANDROID_BUILD=true` configurations. NOT verified: any iOS test execution (macOS CI only), a real `BGTaskScheduler` or WorkManager invocation, a physical device, a drain racing a real OS suspension. A background drain on a real device is best effort and bounded by what the OS grants. Neither lifecycle provider is added to `AndroidDataLoomProviders`/`AppleDataLoomProviders`. |

## (b) Gate percentage

- #101: propose 80% to 81%. The runtime now consumes the lifecycle signal, which
  closes a named gap, but it has no iOS execution, no device evidence, and no
  real scheduler invocation, so a larger bump is not justified. Lead to decide.

## (c) "Still pending" text

- Remove: "Consume the lifecycle signal (runtime queue-drain tick)" from the
  2026-09-20 ordered list, keeping its `BGTaskScheduler` device caveat.
- Add (ordered next slices):
  1. Run and confirm the `apple-validate` job's `iosSimulatorArm64Test` results
     for the lifecycle provider suites and the new drain tests.
  2. Wire `AndroidLifecycleProvider` and `AppleLifecycleProvider` into
     `AndroidDataLoomProviders`/`AppleDataLoomProviders` with the builder
     binding for `ProviderType.APP_LIFECYCLE` (the drain spec takes the
     provider directly today).
  3. Real invocation proof: a drain under a real `BGTaskScheduler` launch and a
     real WorkManager run, then physical-device proof on both platforms
     including a genuine `ProcessLifecycleOwner` and `UIApplication` transition.
  4. Retry, circuit-breaker and conflict-detection behavior during queue replay.
  5. Expose the PULL branch's `queueEntryId` through a public API.
- Index rows for the lead to add: `docs/adr/README.md` (ADR-0013, title
  "Lifecycle-triggered queue drain", accepted, D23, `#101`).
- Incidental: `dataloom-runtime/api/jvm/dataloom-runtime.api` on main was stale
  (missing the `DataLoom.governance` and `governanceConfiguration` /
  `DataLoomGovernance*` entries added by the governance slice); regenerating for
  this change corrected it, so this PR's jvm baseline diff also contains those
  governance lines.
