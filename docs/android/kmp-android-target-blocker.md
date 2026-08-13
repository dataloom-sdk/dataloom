# Explicit KMP Android target: confirmed blocked, not just risky

## Status

**Attempted and confirmed blocked (2026-08-14).** This documents a real,
reproduced Gradle plugin-resolution conflict in this repository's current
Kotlin `2.4.10` / AGP `9.1.0` combination, so a future attempt does not
re-discover the same dead end from scratch. This is not a decision to stop
pursuing `#101`'s "explicit Android KMP variant" acceptance criterion
permanently — it is a record of what has been tried and ruled out, so the
next attempt starts from a different angle.

## What was attempted

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

## What this rules out

- It is not a `pluginManagement.resolutionStrategy` mapping gap — adding
  the matching `useModule` entry does not help.
- It is not caused by coexisting with other `com.android.library` modules
  in the same Gradle invocation — the failure reproduces in complete
  isolation, on the smallest possible module, with no other Android module
  present in the build at all.
- `build-logic` itself does not declare AGP as a dependency (checked
  directly), so the conflict is not coming from the convention-plugin
  build's own classpath.

## What remains unknown

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

## Candidate directions for a future attempt

- Try a newer/older Kotlin Gradle plugin or AGP version pairing in a
  disposable, isolated experiment (not a repo-wide upgrade) to see if the
  conflict is version-specific.
- Investigate whether `build-logic`'s `implementation(libs.kotlin.
  gradlePlugin)` dependency itself transitively pulls in AGP integration
  classes — inspect via `./gradlew :build-logic:dependencies` or
  `buildEnvironment`.
- Consider whether the shared modules need the Android variant on the
  *same* Gradle module at all, or whether a sibling-module split (the
  proven, already-shipped SQLDelight pattern — see
  `dataloom-storage-sqldelight` / `dataloom-storage-sqldelight-android`)
  is the more realistic path for the whole shared-module graph, accepting
  that `#101`'s acceptance criterion may need to be satisfied by paired
  modules rather than one module exposing every target.

## What is not blocked

Native Android consumption via the `jvm()` variant continues to work today
(`runtime-android-reference-consumer`, `#267`) and is unaffected by this
finding — this blocker is specifically about exposing a *real* KMP-aware
Android variant from the same Gradle module, which is a stronger, separate
bar than "an Android app can consume the JVM artifact."
