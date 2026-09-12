# Explicit KMP Android target: confirmed blocked, not just risky

## Status

**Attempted and confirmed blocked (2026-08-14; re-attempted and confirmed
still blocked with two newer version pairings, 2026-09-12).** This documents
a real, reproduced Gradle plugin-resolution conflict, so a future attempt
does not re-discover the same dead end from scratch. This is not a decision
to stop pursuing `#101`'s "explicit Android KMP variant" acceptance
criterion permanently — it is a record of what has been tried and ruled
out, so the next attempt starts from a different angle. As of round 31
(2026-09-12), both of this doc's own previously-open candidate directions
have now been tried and ruled out — see "Round 31 re-attempt" below; only
the sibling-module-split architectural alternative remains untried.

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

## Round 31 re-attempt (2026-09-12): version bump tested, ruled out

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

## Candidate directions for a future attempt

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

## What is not blocked

Native Android consumption via the `jvm()` variant continues to work today
(`runtime-android-reference-consumer`, `#267`) and is unaffected by this
finding — this blocker is specifically about exposing a *real* KMP-aware
Android variant from the same Gradle module, which is a stronger, separate
bar than "an Android app can consume the JVM artifact."
