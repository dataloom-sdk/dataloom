# Fragment: application lifecycle provider (decision D16), slice 1

Gate touched: #101 (DL-039A Android/KMP/iOS parity), row 2. Closes the row's
named open item "iOS `LifecycleProvider` design (no contract exists for either
platform yet)". Decision record: `docs/adr/ADR-0007-app-lifecycle-provider.md`;
design doc: `docs/api/app-lifecycle-provider.md`.

## (a) Proposed "Recently shipped" row

| 2026-09-20 | #101 | Application lifecycle provider (decision D16, ADR-0007). New platform-neutral contract `AppLifecycleProvider` in `dataloom-api` (`current` plus a cold, conflated, cancellation-safe `states(): Flow<AppLifecycleState>` of `FOREGROUND`/`BACKGROUND`/`TERMINATING_SOON`, capability-gated, no callbacks, no PII; named `AppLifecycleProvider` rather than `LifecycleProvider` to avoid clashing with the existing provider-lifecycle types) and `ProviderType.APP_LIFECYCLE`; `dataloom-api` now declares `api(kotlinx-coroutines-core)` because `Flow` is part of the contract, the first coroutines type in any public API. Implementations: `AndroidLifecycleProvider` (new module `dataloom-lifecycle-android`, AndroidX `ProcessLifecycleOwner`, new dependency `androidx.lifecycle:lifecycle-process:2.9.4`) and `AppleLifecycleProvider` (`dataloom-platform-ios`, the five `UIApplication` notifications through `NSNotificationCenter`, main-thread registration and removal, observer removed on cancellation). `dataloom-testing` gains `MutableAppLifecycleProvider` and `AppLifecycleProviderContract`, one suite run by the fake and both real implementations. Verified: JVM tests for the contract types, the fake (18) and `ProviderType`; `testAndroidHostTest` for `dataloom-api`/`dataloom-provider-api`; 23 Robolectric tests for the Android provider (13 shared-suite, 10 provider-specific incl. a real background-thread collect/cancel through the main looper), with a deliberate observer-leak mutation confirmed to fail 5 of them; `assembleDebug`/`assembleRelease`/`lintDebug` for the new module; the iOS provider's platform-independent core (16 provider tests over an in-memory notification source, 10 source tests, 5 mapping tests) executed on the JVM from a throwaway copy of the sources (not committed); iOS main and test klib cross-compile for all three Apple targets; ABI baselines regenerated (both layouts identical for `dataloom-api`/`dataloom-provider-api`) and whole-build `checkKotlinAbi` green in the default, cross-compile and `DATALOOM_ANDROID_BUILD=true` configurations. NOT verified: any iOS test execution (the `iosTest` suites over a real `NSNotificationCenter` and the `dispatch` main-queue executor test need the macOS CI job), the production `UIApplication.applicationState` reader (a test process has no `UIApplication`), any genuine OS-driven lifecycle transition on either platform, physical devices, the Apple XCFramework header audit with `Flow` now reachable from the exported `dataloom-api`, and Android CI (Linux). Nothing in the runtime consumes lifecycle signals and neither provider is added to `AndroidDataLoomProviders`/`AppleDataLoomProviders`. |

## (b) Gate percentage

- #101: propose 74% to 75%. New production capability on both platforms with
  the contract and a shared suite, which is more than proof-only work, but it
  is unconsumed by the runtime and has no iOS execution or device evidence,
  so a larger bump is not justified. Lead to decide.

## (c) "Still pending" text

- Remove: "iOS `LifecycleProvider` design (no contract exists for either
  platform yet, an open design question)".
- Add (ordered next slices):
  1. Run and confirm the `apple-validate` job's `iosSimulatorArm64Test` results
     for the new iOS suites, and the XCFramework header audit.
  2. Consume the lifecycle signal: use `AppLifecycleProvider` in the runtime to
     trigger a real queue-drain tick (needs a policy decision on what
     `BACKGROUND` and `TERMINATING_SOON` should do; a real `BGTaskScheduler`
     invocation still needs a device or macOS run).
  3. Wire both providers into `AndroidDataLoomProviders`/`AppleDataLoomProviders`
     once step 2 exists, with the builder binding for `ProviderType.APP_LIFECYCLE`.
  4. Physical-device proof on both platforms, including observing a real
     `ProcessLifecycleOwner` transition and a real `UIApplication` transition.
  5. Retry, circuit-breaker, and conflict-detection behavior during queue
     replay.
  6. Expose the PULL branch's `queueEntryId` through a public API.
- Index rows for the lead to add: `docs/adr/README.md` (ADR-0007) and
  `docs/api/README.md` (`app-lifecycle-provider.md`); module table in
  `docs/architecture/modules.md` (`dataloom-lifecycle-android`).
