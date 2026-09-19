# Fragment: explicit KMP Android target unblocked (pilot on dataloom-model)

Gates touched: #93 (foundations), #101 (platform parity), #94 (AC-FUNC-004 KMP-Android provider-flow gap).

## (a) Proposed "Recently shipped" row

| 2026-09-19 | #101/#93/#94 | KMP Android target root cause found and pilot landed. The "already on the classpath with an unknown version" failure recorded in `docs/android/kmp-android-target-blocker.md` was caused by requesting `com.android.kotlin.multiplatform.library` WITH a version in a subproject; applying it by bare id works on the current Kotlin 2.4.10 / AGP 9.1.0 / Gradle 9.5.0 with no version bump. `dataloom-model` now exposes an explicit `android` KMP target (env-gated on `DATALOOM_ANDROID_BUILD=true`) beside jvm/ios. Verified on a Windows host: `:dataloom-model:build` + `testAndroidHostTest` + `jvmTest` (171 tests each, 0 failures), iOS klib cross-compile, `checkKotlinAbi`, and `assembleDebug` of seven of the eight existing Android modules (all but `dataloom-storage-datastore`) incl. `runtime-android-reference-consumer`, whose `dependencyInsight` now shows `dataloom-model` resolving `androidRuntimeElements` (`androidJvm`). NOT verified: Linux/macOS CI, Robolectric/instrumented tests, lint, and the other shared modules (provider-api, api, core, runtime are still JVM+iOS only; roll-out recipe documented). |

## (b) Gate percentage

- #101: unchanged pending lead review. The first acceptance criterion (explicit Android variant for `dataloom-model`, `dataloom-provider-api`, `dataloom-api`, `dataloom-core`, `dataloom-runtime`) is now 1 of 5 modules done and the blocker is removed, so the remaining work is mechanical; suggest a small bump only after the roll-out PRs land.
- #93: unchanged.
- #94: unchanged (the AC-FUNC-004 KMP-Android provider-flow gap is no longer blocked by build tooling, but nothing on that gap was implemented).

## (c) "Still pending" text

- Remove: "explicit KMP Android target blocked by Gradle plugin-resolution conflict".
- Add: "Roll out the explicit Android KMP target to dataloom-provider-api, dataloom-api, dataloom-core, dataloom-runtime (recipe and per-module risks in `docs/android/kmp-android-target-blocker.md`); dataloom-runtime needs convention-plugin changes (`checkPublicAbiBoundaries` ABI path, Android runtime classpath boundary check). Decide env-gated vs unconditional Android target (env-gated needs two committed ABI baseline layouts per module). Regenerate `gradle/verification-metadata.xml` for the new Android artifacts."
