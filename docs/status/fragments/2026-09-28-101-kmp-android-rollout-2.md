# Fragment: explicit KMP Android target, roll-out slice 2 (dataloom-core, dataloom-runtime)

Gates touched: #101 (platform parity), #93 (foundations), #94 (AC-FUNC-004 KMP-Android provider-flow gap).

## (a) Proposed "Recently shipped" row

| 2026-09-28 | `#101`: KMP Android roll-out slice 2 puts the env-gated (`DATALOOM_ANDROID_BUILD=true`) `android` target on `dataloom-core` and `dataloom-runtime`, completing `#101`'s named module list (`dataloom-model`, `dataloom-provider-api`, `dataloom-plugin-api`, `dataloom-config`, `dataloom-api`, `dataloom-core`, `dataloom-runtime`). `dataloom-runtime` needed build-logic changes: `DataLoomKotlinMultiplatformLibraryPlugin` and `ResolvedDependencyBoundaryCheckTask` now handle the Android target's `api/jvm` ABI layout and the Android runtime classpath. Verified from Windows: `dataloom-core` 133 tests and `dataloom-runtime` 2,012 tests give identical counts and no failures under both `jvmTest` and `testAndroidHostTest`; `:build-logic:test` and whole-build `checkKotlinAbi` pass in the default configuration and with the Android target on; all seven converted modules' paired `api/` and `api/jvm/` baselines are byte-identical. Not verified: Linux/macOS CI for this change, Robolectric/instrumented tests on the runtime, and `gradle/verification-metadata.xml` regeneration (lenient mode). |

## (b) Gate percentage

- `#101`: unchanged at 80%. The named module list is now complete, but the acceptance criteria still need physical-device proof, a real Apple background-scheduler tick, and the remaining parity matrix; no criterion is newly satisfied by build configuration alone.
- `#93`, `#94`: unchanged.

## (c) "Still pending" text

- Remove: "Explicit KMP Android target roll-out to the remaining shared modules".
- Add: "Decide whether the Android target stays env-gated or becomes unconditional; regenerate `gradle/verification-metadata.xml` for the Android artifacts; move the repeated per-module Android build block into the convention plugin."
