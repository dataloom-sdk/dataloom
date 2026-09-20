# Fragment: explicit KMP Android target roll-out slice 1

Gate touched: #101 (platform parity; also unblocks #93 foundations and the #94 AC-FUNC-004 KMP-Android gap).

## (a) Proposed "Recently shipped" row

| 2026-09-19 | #101 | Explicit KMP Android target rolled out to `dataloom-provider-api`, `dataloom-plugin-api`, `dataloom-config` and `dataloom-api` (with the `dataloom-model` pilot, 3 of `#101`'s 5 named shared modules are done: model, provider-api, api; core and runtime are pending). Env-gated on `DATALOOM_ANDROID_BUILD=true` (decision D15) so default builds still need no Android SDK; each converted module carries two committed, byte-identical ABI baselines (`api/<m>.api`, `api/jvm/<m>.api`). Verified on a Windows host: jvmTest and testAndroidHostTest pass with identical counts (provider-api 26, plugin-api 16, config 53, api 969); default-env and env-on whole-build `checkKotlinAbi` and `compileKotlinJvm`; iOS klib cross-compile; `assembleDebug` of seven Android modules including `runtime-android-reference-consumer`, whose `dependencyInsight` shows `dataloom-api` resolving the `androidRuntimeElements` variant. NOT verified: Linux/macOS CI, Robolectric/instrumented tests, lint, `verification-metadata.xml` regeneration. |

## (b) Gate percentage

- #101: unchanged pending lead review. The first acceptance criterion (explicit Android variant for `dataloom-model`, `dataloom-provider-api`, `dataloom-api`, `dataloom-core`, `dataloom-runtime`) is now 3 of 5 named modules (plus plugin-api and config); `dataloom-core` and `dataloom-runtime` remain, and the criterion should be re-scored after they land.
- #93, #94: unchanged.

## (c) "Still pending" text

- Replace "roll out the explicit Android KMP target to dataloom-provider-api, dataloom-api, dataloom-core, dataloom-runtime" with: "Roll out the explicit Android KMP target to dataloom-core and dataloom-runtime; dataloom-runtime needs convention-plugin changes (`checkPublicAbiBoundaries` must read `abi/jvm/dataloom-runtime.api` when the Android target is enabled; `checkResolvedDependencyBoundaries` must also cover the Android runtime classpath). Then fold the repeated build block into the convention plugin."
- Keep: regenerate `gradle/verification-metadata.xml` for the new Android artifacts; Android-variant publishing metadata.
