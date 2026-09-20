# Explicit KMP Android target: root cause found, pilot landed

## Status

**Unblocked (2026-09-19). Roll-out: `dataloom-model` (pilot) plus
`dataloom-provider-api`, `dataloom-plugin-api`, `dataloom-config` and
`dataloom-api` (slice 1) are converted; `dataloom-core` and `dataloom-runtime`
remain.** The "confirmed blocked" conclusion recorded in the
historical sections below was wrong about the cause. The conflict was never
inherent to AGP, Gradle classloading, or the Kotlin/AGP version pairing: it
is triggered only by requesting the plugin **with a version** in a
subproject. Applying `com.android.kotlin.multiplatform.library` by bare id
works on the repository's current toolchain (Kotlin `2.4.10`, AGP `9.1.0`,
Gradle `9.5.0`) with no version bump.

`dataloom-model` is the pilot: it now exposes an explicit `android` KMP
target (`androidRuntimeElements`, `org.jetbrains.kotlin.platform.type =
androidJvm`) beside `jvm`/`iosArm64`/`iosSimulatorArm64`/`iosX64`, when
`DATALOOM_ANDROID_BUILD=true`. Rolling the rest of `#101`'s list out
(`dataloom-core`, `dataloom-runtime`) is a separate, mechanical follow-up: see
"Roll-out recipe" below.

## Root cause (2026-09-19)

The root `build.gradle.kts` declares `alias(libs.plugins.android.library)
apply false`, which puts the whole `com.android.tools.build:gradle` artifact
(AGP) on the shared build classpath. That single artifact also contains the
`com.android.kotlin.multiplatform.library` plugin. When a subproject then
requests `com.android.kotlin.multiplatform.library` **with a version** (a
`version "..."` clause, or a `libs.plugins.*` catalog alias, which always
carries one), Gradle refuses, because the plugin is already on the classpath
and it cannot tell which version got there (it was mapped through
`pluginManagement.resolutionStrategy.useModule`, not a marker artifact):

```
Error resolving plugin [id: 'com.android.kotlin.multiplatform.library', version: '9.1.0']
> The request for this plugin could not be satisfied because the plugin is
  already on the classpath with an unknown version, so compatibility cannot
  be checked.
```

Reproduced on 2026-09-19 (Kotlin `2.4.10`, AGP `9.1.0`, Gradle `9.5.0`) by
putting `id("com.android.kotlin.multiplatform.library") version "9.1.0"` in
`dataloom-model/build.gradle.kts`; removing only the `version "9.1.0"` clause
made the identical build succeed. This is exactly the error the earlier
rounds saw, and it also explains why neither adding a `useModule` mapping,
removing every other Android module from the build, nor changing the
Kotlin/AGP versions changed anything: none of those touched the version
clause on the request.

The fix is therefore to request the plugin by bare id from a subproject and
let the root build's already-loaded AGP supply it:

```kotlin
apply(plugin = "com.android.kotlin.multiplatform.library") // no version
```

## What the pilot changed

Only `dataloom-model` (plus two doc/comment path fix-ups noted below):

- `dataloom-model/build.gradle.kts` applies the plugin by bare id and
  configures `androidLibrary { namespace; compileSdk; minSdk; withHostTest {} }`
  (via `extensions.configure<KotlinMultiplatformAndroidLibraryTarget>
  ("androidLibrary")`, because the type-safe accessor does not exist when the
  plugin is applied conditionally).
- **Gated on `DATALOOM_ANDROID_BUILD=true`**, the same switch that already
  includes the other Android modules in `settings.gradle.kts`. A default
  JVM/iOS build (`pr-validation.yml`, `apple-validation.yml`, a contributor
  with no Android SDK) applies no Android plugin and produces the same
  JVM/iOS outputs and ABI as before (only the source-directory rename
  below is visible to it). Verified: with no `ANDROID_HOME`, `jvmTest`/`checkKotlinAbi` still
  pass; only Android tasks then fail with `SDK location not found`.
- **`jvmAndroid` intermediate source set.** `dataloom-model`'s JVM-only
  `System*` implementations (`SystemDataLoomClock`,
  `SystemDataLoomMonotonicClock`, `SystemDataLoomSecureRandom`,
  `SystemDataLoomDigestCalculator`, `SystemDataLoomHmacCalculator`) only use
  `java.*`/`javax.crypto.*` APIs that Android also provides. `src/jvmMain` and
  `src/jvmTest` were `git mv`'d (history preserved, no content edits) to
  `src/jvmAndroidMain` and `src/jvmAndroidTest`, and the build declares a
  `jvmAndroid` group so both the `jvm` and `android` targets compile them.
  Without this, the Android target compiles only `commonMain`; the first
  downstream check caught it immediately
  (`runtime-android-reference-consumer` failed with `Unresolved reference
  'SystemDataLoomClock'` once Android modules started resolving the new
  variant).
- The group must match the new plugin's target with
  `withCompilations { it.platformType == KotlinPlatformType.androidJvm }`,
  **not** `withAndroidTarget()`, which silently does not match it (observed:
  `androidMain` stayed a direct child of `commonMain`, and the Android host
  test ran 138 tests instead of 171).
- `dataloom-model/api/jvm/dataloom-model.api` was added (see the ABI note
  below); the existing `api/dataloom-model.api` and `api/dataloom-model.klib.api`
  are unchanged.
- Stale path references to `dataloom-model/src/jvmMain` were updated in
  `docs/api/asset-synchronization-caller-investigation.md` and in the comments
  of three `FakeDataLoomDigestCalculator`/`ConfigurationSnapshotTest` test
  files (comment-only edits).

## Evidence (Windows host, 2026-09-19)

All with `-Dorg.gradle.workers.max=2`; `DATALOOM_ANDROID_BUILD=true` and
`ANDROID_HOME` set unless stated.

- `:dataloom-model:build :dataloom-model:testAndroidHostTest
  :dataloom-model:jvmTest -Pdataloom.appleKlibCrossCompile=true
  --rerun-tasks` -- `BUILD SUCCESSFUL`. `jvmTest`: 171 tests, 0 failures;
  `testAndroidHostTest`: 171 tests, 0 failures (the identical suite,
  `commonTest` + `jvmAndroidTest`, now also runs against the Android target).
- iOS cross-compile and the module ABI check ran inside that same `build`
  (`compileKotlinIosArm64`/`IosSimulatorArm64`/`IosX64`, `checkKotlinAbi`).
- Default configuration (no `DATALOOM_ANDROID_BUILD`, no `ANDROID_HOME`):
  `:dataloom-model:checkKotlinAbi :dataloom-model:jvmTest
  -Pdataloom.appleKlibCrossCompile=true` -- `BUILD SUCCESSFUL`.
- Downstream Android modules still build against the new variant:
  `assembleDebug` for `dataloom-connectivity-android`,
  `dataloom-scheduler-workmanager`, `dataloom-queue-room`,
  `dataloom-storage-room`, `dataloom-storage-sqldelight-android`,
  `dataloom-android`, `runtime-android-reference-consumer`, plus
  `runtime-android-reference-consumer:compileDebugUnitTestKotlin` --
  `BUILD SUCCESSFUL`.
- `:runtime-android-reference-consumer:dependencyInsight --configuration
  debugRuntimeClasspath --dependency :dataloom-model` shows `dataloom-model`
  now resolves the `androidRuntimeElements` variant
  (`org.jetbrains.kotlin.platform.type = androidJvm`,
  `org.gradle.jvm.environment = android`) instead of the plain JVM variant.
  This is the first real KMP-aware Android variant of a shared module.

Not run: Android instrumented (device/emulator) tests, Robolectric unit tests
of the Android modules, `lint`, and any macOS/Linux CI. CI's
`android-validation.yml` (which sets `DATALOOM_ANDROID_BUILD=true` and runs
`build`) is the first place the pilot runs on Linux; that result was not
observed by this change.

## Findings that shape the roll-out

1. **ABI baselines change layout when an Android target is present.** Kotlin's
   ABI validation writes the JVM dump to `api/dataloom-model.api` when `jvm` is
   the only non-native target, but to `api/jvm/dataloom-model.api` once a second
   JVM-like target (`android`) exists. The two files are byte-identical for
   `dataloom-model`. The new Android target itself produces **no** ABI dump in
   Kotlin `2.4.10`, so Android API surface is not baseline-protected; it is the
   same source as the JVM surface for now because `jvmAndroid` shares the
   implementations. Because the Android target is env-gated, a converted
   module needs **both** baselines committed (`api/<m>.api` for default builds,
   `api/jvm/<m>.api` for `DATALOOM_ANDROID_BUILD=true`), each generated by the
   matching configuration. Each configuration only reads its own file.
2. **A KMP Android module can depend on KMP modules that have no Android
   target yet.** Tested by temporarily giving `dataloom-api` an Android
   target while `dataloom-provider-api` and `dataloom-config` had none:
   `:dataloom-api:compileAndroidMain` succeeded (Gradle falls back to the
   dependencies' JVM variants). Conversion order is therefore not forced by
   the compiler; it is only needed for `#101`'s claim that the whole shared
   graph exposes Android variants. (This experiment was reverted.)
3. **No version bump was needed and none was made.** Newer Kotlin/AGP/Gradle
   pairings were not re-tested this time, because the current toolchain
   already works; the round-31 note that AGP `9.4.0` needs Gradle `9.6.0`+
   still stands.
4. **`gradle/verification-metadata.xml` was not regenerated.** The build
   remains in lenient dependency-verification mode; artifacts newly resolved
   for the Android target of `dataloom-model` may lack checksum entries until
   the metadata is regenerated per `docs/development/supply-chain-verification.md`.
5. **KDoc in the `System*` classes still says "Android consumes this module's
   JVM target directly".** True for default builds, no longer true when
   `DATALOOM_ANDROID_BUILD=true`. Left unchanged; reword once the roll-out
   settles.

## Roll-out recipe

Do these as separate small PRs, bottom-up. For each module: add the two
plugin/config blocks below, run the module's `build` under both
configurations, regenerate **both** ABI baselines (default:
`./gradlew :<m>:updateKotlinAbi -Pdataloom.appleKlibCrossCompile=true`; Android:
the same with `DATALOOM_ANDROID_BUILD=true ANDROID_HOME=...`), and confirm
each diff is only the new `api/jvm/<m>.api` file.

```kotlin
val androidTargetEnabled = System.getenv("DATALOOM_ANDROID_BUILD") == "true"
if (androidTargetEnabled) apply(plugin = "com.android.kotlin.multiplatform.library") // bare id!
kotlin {
    if (androidTargetEnabled) {
        (this as ExtensionAware).extensions
            .configure<KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
                namespace = "io.dataloom.<module>"
                compileSdk = libs.versions.android.compileSdk.get().toInt()
                minSdk = libs.versions.android.minSdk.get().toInt()
                withHostTest {}
            }
    }
}
```

Once two or three modules have it, fold the block into
`build-logic`'s `DataLoomKotlinMultiplatformLibraryPlugin` (it is Java and
deliberately has no AGP dependency; it would need `compileOnly` AGP or
apply-by-id plus reflection) so each module only supplies a namespace.

| Order | Module | Notes and risks |
|---|---|---|
| 0 (done, pilot) | `dataloom-model` | Needed the `jvmAndroid` source set because it has real JVM-only sources. |
| 1 (done, slice 1) | `dataloom-provider-api`, `dataloom-plugin-api`, `dataloom-config` | Pure `commonMain`, only `api(project(":dataloom-model"))`. Converted with just the plugin/config block plus `api/jvm/<m>.api`; no source move, no `jvmAndroid` group. |
| 2 (done, slice 1) | `dataloom-api` | `src/jvmMain`/`jvmTest` contain only `.gitkeep`; converted the same way. 969 tests pass on both `jvmTest` and `testAndroidHostTest`. |
| 3 | `dataloom-core` | Same: only `.gitkeep` under `jvmMain`/`jvmTest`. |
| 4 | `dataloom-runtime` | **Highest build-logic risk.** The convention plugin's `checkPublicAbiBoundaries` reads `build/kotlin/abi/dataloom-runtime.api`, which moves to `build/kotlin/abi/jvm/dataloom-runtime.api` once Android is on (verified for `dataloom-model`'s dump location); `checkResolvedDependencyBoundaries` inspects only `jvmRuntimeClasspath`, so the Android runtime classpath would be unguarded. Update `DataLoomKotlinMultiplatformLibraryPlugin.java` (conditionally on the env switch) and re-run `:build-logic:test`. Also has `iosMain` code; `jvmMain` is only `.gitkeep`. |
| 5 | `dataloom-testing`, `runtime-external-consumer` | Optional. `dataloom-testing` is `.gitkeep`-only for JVM. `runtime-external-consumer` disables ABI validation and its check task depends on `compileKotlinJvm` only. |
| never/decide | `dataloom-storage-file` | `FileSystemFacade` (in `jvmMain`) uses `java.nio.file.Files`/`StandardCopyOption`, unavailable below Android API 26 while `android-minSdk` is 21. Needs a decision (raise minSdk for this module, rewrite with `java.io`, or keep JVM-only) before it can share a `jvmAndroid` source set. Not in `#101`'s named list. |
| never/decide | `dataloom-storage-sqldelight` | Its `jvmMain` uses the SQLite JDBC driver, wrong for Android; Android is already served by the paired `dataloom-storage-sqldelight-android` module. Keep as is. |
| n/a | `dataloom-transport-*` | `retrofit` and `grpc` are `kotlin("jvm")` (not KMP); `ktor` and `graphql` use the convention plugin. None are in `#101`'s list; out of scope. |

### Roll-out slice 1 (2026-09-19): what it showed

Converted `dataloom-provider-api`, `dataloom-plugin-api`, `dataloom-config`
and (as a separate commit) `dataloom-api`. Edits are limited to each module's
`build.gradle.kts` plus a new `api/jvm/<module>.api`; no source, no
`settings.gradle.kts`, no version-catalog change.

- **The recipe transferred verbatim.** For a module with no JVM-specific
  sources the whole conversion is the `import`, the gated `apply(plugin = ...)`
  by bare id, and the `androidLibrary` block. The `jvmAndroid` hierarchy group
  from the pilot is only needed when a module has real `jvmMain` code.
- **Namespaces used** (must stay unique across the Android modules that already
  exist, `io.dataloom.android`, `io.dataloom.model`, ...): `io.dataloom.provider.api`,
  `io.dataloom.plugin.api`, `io.dataloom.config`, `io.dataloom.api`.
- **`updateKotlinAbi` with the Android target on only adds the new
  `api/jvm/<m>.api`.** The existing `api/<m>.api` and `api/<m>.klib.api` stay
  untouched, and the two `.api` files are byte-identical for all four modules
  (checked with `git diff --no-index`). Nothing needed hand-editing.
- **Android host tests run the same suites as `jvmTest`:** provider-api 26,
  plugin-api 16, config 53, api 969 tests, 0 failures each, in both tasks.
- **Downstream resolution:** with the env on, `dependencyInsight` on
  `runtime-android-reference-consumer` shows `dataloom-api` resolving
  `androidRuntimeElements` (`androidJvm`). JVM-only consumers of these modules
  (`dataloom-core`, `dataloom-runtime`, `dataloom-storage-*`,
  `dataloom-transport-*`, `dataloom-plugin`) still compile against the JVM
  variant (whole-build `compileKotlinJvm` passed in both configurations).
- `dataloom-plugin` (the plugin engine, added by `#98`) was not touched; it is
  not in `#101`'s list and can be converted the same way in a later slice once
  its owner is done.

### Still to do

1. `dataloom-core` (same shape as `dataloom-api`).
2. `dataloom-runtime`, together with the build-logic changes described in its
   row: make `checkPublicAbiBoundaries` read `build/kotlin/abi/jvm/dataloom-runtime.api`
   when the Android target is enabled, and extend `checkResolvedDependencyBoundaries`
   to the Android runtime classpath. Run `:build-logic:test` under both configurations.
3. Optionally `dataloom-testing` and `runtime-external-consumer`.
4. Fold the repeated build block into the convention plugin (now four modules
   plus the pilot share it).

Decided (D15): the Android target stays env-gated on `DATALOOM_ANDROID_BUILD=true`,
so a default build needs no Android SDK, and each converted module keeps two
committed ABI baseline layouts. Still open: publishing metadata for the Android
variants and regenerating `gradle/verification-metadata.xml`.

## Historical record (superseded by the sections above)

Everything below describes the 2026-08-14 and 2026-09-12 attempts as they
were recorded then. Its conclusions ("not a version-specific bug",
"architectural") are **superseded** by "Root cause" above; the reproduced
error text and the list of things that did not help remain accurate.

### What was attempted

`#101`'s first acceptance criterion requires shared KMP artifacts (
`dataloom-model`, `dataloom-provider-api`, `dataloom-api`, `dataloom-core`,
`dataloom-runtime`) to expose an explicit Android variant alongside their
existing `jvm()`/`iosArm64`/`iosSimulatorArm64`/`iosX64` targets — "a JVM
fallback is not the sole KMP Android evidence." Today, native Android
consumption works only by resolving the plain `jvm()` variant (proven by
`runtime-android-reference-consumer`, `#267`), not a real KMP-aware Android
variant.

An earlier session (documented in this project's own history) tried adding
a classic `androidTarget()` to a KMP module directly and confirmed AGP
`9.0+` does not allow `com.android.library` to coexist with
`org.jetbrains.kotlin.multiplatform` in the same module — the documented
alternative, `com.android.kotlin.multiplatform.library`, was tried next and
hit `"already on the classpath with an unknown version, so compatibility
cannot be checked"` when resolved via this repo's `pluginManagement.
resolutionStrategy.eachPlugin { useModule(...) }` mechanism, since
`com.android.library` (used by six existing Android provider modules)
already claims the same `com.android.tools.build:gradle` module coordinate
in the same build.

This session re-attempted `com.android.kotlin.multiplatform.library`
directly on the smallest, leaf-most shared module (`dataloom-model`, zero
DataLoom dependencies) as an isolated experiment, with two variations:

1. **Added a matching `useModule` mapping** for
   `com.android.kotlin.multiplatform.library` pointing at the same
   `com.android.tools.build:gradle:${agp}` coordinate `com.android.library`
   already uses — hypothesizing the conflict was a resolution-strategy gap.
   **Result: identical failure** — `"already on the classpath with an
   unknown version, so compatibility cannot be checked."`
2. **Removed the mapping and ran in complete isolation** — no
   `DATALOOM_ANDROID_BUILD=true`, so no other module in the build applies
   `com.android.library` at all, ruling out a same-build coexistence
   theory. **Result: identical failure**, unchanged.

Both experiments were reverted immediately after reproducing the failure;
no functional or build-configuration change shipped from this
investigation — this doc is the only artifact.

### What this rules out

- It is not a `pluginManagement.resolutionStrategy` mapping gap — adding
  the matching `useModule` entry does not help.
- It is not caused by coexisting with other `com.android.library` modules
  in the same Gradle invocation — the failure reproduces in complete
  isolation, on the smallest possible module, with no other Android module
  present in the build at all.
- `build-logic` itself does not declare AGP as a dependency (checked
  directly), so the conflict is not coming from the convention-plugin
  build's own classpath.

### What remains unknown (answered: see "Root cause")

The stacktrace for the failure only shows Gradle's own plugin-application
machinery (`DefaultPluginRequestApplicator`), not the original source of
the "already on the classpath" entry. The most likely remaining
explanation, not yet confirmed, is that the Kotlin Gradle plugin `2.4.10`
itself has some form of eager or transitive reference to AGP's Android
library plugin surface as part of its own KMP+Android integration
detection, which loads ahead of and conflicts with an explicit
`com.android.kotlin.multiplatform.library` plugin request. Confirming this
would need deeper Gradle internals investigation (dependency insight
reports on the root build's buildscript classpath, or testing a different
Kotlin/AGP version pair) than was budgeted for this pass.

### Round 31 re-attempt (2026-09-12): version bump tested, ruled out

This round's assignment was specifically this doc's own first candidate
direction: try a newer/older Kotlin/AGP pairing in a disposable, isolated
experiment. Attempted on the same isolated `dataloom-model` probe this doc
already used, with two version pairings:

1. **`kotlin = "2.4.20"` / `agp = "9.4.0"`** — did not even reach the
   original conflict: AGP `9.4.0` requires Gradle `9.6.0` or newer, and this
   repository's wrapper is pinned to `9.5.0`
   (`gradle/wrapper/gradle-wrapper.properties`). Bumping the Gradle wrapper
   itself is a repo-wide change affecting every CI workflow and module, far
   outside a disposable single-module probe's scope, so this pairing was
   abandoned rather than pursued further this round.
2. **`kotlin = "2.4.20"` / `agp = "9.2.0"`** (compatible with Gradle `9.5.0`)
   — reproduced the **identical** `"already on the classpath with an unknown
   version, so compatibility cannot be checked"` failure, byte-for-byte the
   same error this doc already documented at `2.4.10`/`9.1.0`. Tested both
   with and without a matching `useModule` mapping for
   `com.android.kotlin.multiplatform.library` (mirroring this doc's own
   already-ruled-out variation 1) — identical result either way.

This doc's second candidate direction (`build-logic`'s `implementation(libs.
kotlin.gradlePlugin)` transitively pulling in AGP integration classes) was
also re-checked directly this round: `build-logic/build.gradle.kts` depends
on nothing but `libs.kotlin.gradlePlugin` and `gradleTestKit()`, and a
repository-wide search of `DataLoomKotlinMultiplatformLibraryPlugin.java`
(the one convention plugin `build-logic` publishes) for any `android`/
`Android` reference returns zero matches — the convention plugin itself
does not touch Android at all, ruling this candidate out too, not just
narrowing it.

**Conclusion: this is not a version-specific bug.** Both of this doc's own
named candidate directions have now been tried and ruled out. The
conflict reproduces identically across at least three AGP versions
(`9.1.0`, `9.2.0`, confirmed; `9.4.0` untested due to the separate Gradle-
version floor) and is not sourced from this repository's own build-logic
convention plugin. The remaining, most likely explanation is architectural:
AGP's `com.android.tools.build:gradle` artifact appears to expose
`com.android.library` and `com.android.kotlin.multiplatform.library` as
two plugin IDs that cannot both be resolved-and-applied — even to
completely different, unrelated Gradle subprojects in the same build represented
by two build invocations run independently — implying the conflict may be
inherent to how Gradle's plugin classloading interns the underlying AGP
module once *any* project in the build graph has ever requested it under
either ID, not something this repository's configuration can route around
with a different version pairing.

### Candidate directions for a future attempt (obsolete)

None of these is needed any more; the sibling-module split is no longer the
only path. Kept only as a record.


- ~~Try a newer/older Kotlin Gradle plugin or AGP version pairing~~ —
  **tried and ruled out, round 31 (2026-09-12), see above.**
- ~~Investigate whether `build-logic`'s `implementation(libs.kotlin.
  gradlePlugin)` dependency itself transitively pulls in AGP integration
  classes~~ — **checked directly and ruled out, round 31 (2026-09-12), see
  above:** `build-logic` has no Android dependency or reference anywhere.
- Consider whether the shared modules need the Android variant on the
  *same* Gradle module at all, or whether a sibling-module split (the
  proven, already-shipped SQLDelight pattern — see
  `dataloom-storage-sqldelight` / `dataloom-storage-sqldelight-android`)
  is the more realistic path for the whole shared-module graph, accepting
  that `#101`'s acceptance criterion may need to be satisfied by paired
  modules rather than one module exposing every target. **This is now the
  only remaining named candidate direction that has not been tried or
  ruled out** — it sidesteps the conflict entirely rather than resolving
  it, by never applying `com.android.kotlin.multiplatform.library` and
  `com.android.library` from the same artifact resolution in a way that
  triggers it; a future attempt should investigate whether a sibling
  Android-only module (mirroring `dataloom-storage-sqldelight-android`'s
  shape) can satisfy `#101`'s "expose an explicit Android variant" language
  without one Gradle module claiming both plugin identities.
- A deeper Gradle-internals investigation (a `--stacktrace` capture through
  `DefaultPluginRequestApplicator`, or filing/searching a public AGP/Gradle
  issue tracker for this exact error string) was not attempted this round
  and remains open as a longer-shot path to a real fix rather than a
  workaround.

### What is not blocked

Native Android consumption via the `jvm()` variant worked throughout
(`runtime-android-reference-consumer`, `#267`). It still does for every shared
module except `dataloom-model`, which now resolves as a real Android variant
when `DATALOOM_ANDROID_BUILD=true`; that is a stronger bar than "an Android
app can consume the JVM artifact."
