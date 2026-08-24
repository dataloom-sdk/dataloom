# Artifact graph/BOM gap analysis: investigated, no bounded publication slice available yet

[Architecture index](./README.md)

## Status

**Investigated (2026-08-24). No genuinely bounded, decision-free implementation
slice found.** This document replaces `#93`'s vague "~20 separate enforced
modules is the remaining work" phrasing with the precise gap it names, and
records why even the narrowest possible first slice — wiring one already-shipped
module's publication metadata, without touching source code or picking a
release date — is blocked on unresolved business/governance decisions, not
engineering work. No file outside this investigation and the `#93` dashboard
row's wording was changed to force a slice into existence; inventing one here
would repeat the "moving files... must not be confused with implementing the
missing V1 behavior" mistake ADR-0002 itself warns against.

`docs/status/market-readiness.md`'s `#93` percentage is unchanged by this
document (87%) — a gap analysis alone is scoping work, not shipped progress,
per this session's own established precedent
(`docs/apple/process-termination-investigation.md`,
`docs/api/configuration-resolver-caller-investigation.md`).

## What ADR-0002 actually specifies

[ADR-0002](../adr/ADR-0002-v1-artifact-and-foundation-architecture.md) names
two related graphs, both already fully specified — nothing here is missing
design work:

- a **published artifact graph**: 12 consumer coordinates under the
  `io.dataloom` group (`dataloom-bom`, `dataloom-kmp-core`, `dataloom-api`,
  `dataloom-config`, `dataloom-provider-api`, `dataloom-plugin-api`,
  `dataloom-runtime`, `dataloom-assets`, `dataloom-testing`,
  `dataloom-android`, `dataloom-ios`, `dataloom-jvm`);
- a **source/engine graph**: 26 source modules/included builds that assemble
  into those 12 published coordinates (`dataloom-model`, `dataloom-api`,
  `dataloom-config`, `dataloom-provider-api`, `dataloom-plugin-api`,
  `dataloom-events`, `dataloom-state`, `dataloom-queue`, `dataloom-retry`,
  `dataloom-policy`, `dataloom-conflict`, `dataloom-replication`,
  `dataloom-assets`, `dataloom-storage-spi`, `dataloom-storage-default`,
  `dataloom-transport-spi`, `dataloom-transport-http`,
  `dataloom-observability`, `dataloom-runtime`, `dataloom-platform-android`,
  `dataloom-platform-ios`, `dataloom-platform-jvm`, `dataloom-apple`,
  `dataloom-testing`, `dataloom-benchmarks`, `build-logic`).

`#93`'s "~20 separate enforced modules" was an approximation of this second
table (24 modules excluding the two non-published entries, `build-logic` and
`dataloom-benchmarks`). The tables below are the precise count instead.

## Gap table 1: published artifact graph (12 coordinates)

Classification per the task's own three buckets: **(a)** fully built and
publication-ready, **(b)** built as a module but missing publication wiring,
**(c)** not built at all yet.

| Coordinate | State | Evidence |
|---|---|---|
| `dataloom-bom` | (c) not built | No BOM project exists in `settings.gradle.kts` or anywhere in the repo. |
| `dataloom-kmp-core` | (c) not built at full scope | Backing source module `dataloom-model` exists but, per `modules.md`, is still only "the first extraction slice" (`DataLoomInstant`/`DataLoomClock`); it does not yet own the canonical models, identifiers, errors, and compatibility primitives ADR-0002 assigns to `dataloom-kmp-core`. |
| `dataloom-api` | (b) built, no publish wiring | `dataloom-api/` exists and is extensively built out (public contracts, storage/transport SPIs, state, policy, operational/observation types — see below). Zero `maven-publish`/`publishing {}` block anywhere in the repo (confirmed by repo-wide search). |
| `dataloom-config` | (b) built, no publish wiring | `dataloom-config/` exists, split out of `dataloom-api` per `#93`/`#236`/`#255`. No publish wiring. |
| `dataloom-provider-api` | (b) built, no publish wiring | `dataloom-provider-api/` exists, minimal SPI as designed. No publish wiring. |
| `dataloom-plugin-api` | (b) built (contracts-only by design), no publish wiring | `dataloom-plugin-api/` exists; its own `build.gradle.kts` states it "intentionally contains no plugin loading, registration, enforcement, isolation, or certification behavior" (that's `#98`'s job). No publish wiring. |
| `dataloom-runtime` | (b) built, no publish wiring | `dataloom-runtime/` exists, heavily built, ABI-clean of `dataloom-core`/`dataloom-testing` types. No publish wiring. |
| `dataloom-assets` | (c) not built | No `dataloom-assets` module exists; `#97` (DL-043 asset synchronization) is tracked separately at 0%. |
| `dataloom-testing` | (b) built, no publish wiring | `dataloom-testing/` exists. No publish wiring. |
| `dataloom-android` | (b) built, no publish wiring, plus a separate confirmed blocker | `dataloom-android/` exists as a real aggregation artifact (connectivity + Room storage + Room queue + WorkManager). No publish wiring. ADR-0002 additionally requires an explicit KMP `androidTarget()` variant for shared artifacts, which `docs/android/kmp-android-target-blocker.md` documents as confirmed blocked, not merely unattempted. |
| `dataloom-ios` | (c) not built at full scope | Only `dataloom-platform-ios/` exists, explicitly documented (`modules.md`) as "the first `#101` slice toward the published `dataloom-ios` artifact" — one provider (`AppleConnectivityProvider`), no macOS device/simulator qualification yet. |
| `dataloom-jvm` | (c) not built | `modules.md` states explicitly: "`dataloom-jvm` (JVM/server integrations) do not exist yet." |

**Zero of the 12 published coordinates are in bucket (a).** Bucket (b) is not
"ready modulo a config file" either — see the blocker section below for why
even the most-built candidates in (b) cannot cross into (a) yet.

## Gap table 2: source/engine graph (26 modules)

| Source module | State | Evidence |
|---|---|---|
| `dataloom-model` | Exists, partial (first slice only) | `dataloom-model/` present; owns only clock primitives per `modules.md`. |
| `dataloom-api` | Exists, built | Present and extensive. |
| `dataloom-config` | Exists, built | Present. |
| `dataloom-provider-api` | Exists, built | Present. |
| `dataloom-plugin-api` | Exists, contracts-only by design | Present. |
| `dataloom-events` | **Not a module** — co-mingled | Operational envelope/outbox/observer types live in `dataloom-api`'s `io.dataloom.api.operational`/`io.dataloom.api.observation` packages and `dataloom-runtime`'s `io.dataloom.runtime.operational`/`io.dataloom.runtime.observation` packages, not an explicit `dataloom-events` module. |
| `dataloom-state` | **Not a module** — co-mingled | `DurableStateStore`/`DurableStateCodec` live in `dataloom-api`'s `io.dataloom.api.state` package. Apple's implementation lives in `dataloom-runtime`'s `io.dataloom.runtime.state` package. |
| `dataloom-queue` | **Not a module** — co-mingled | Lives as `io.dataloom.runtime.queue` inside `dataloom-runtime`. |
| `dataloom-retry` | **Not a module** — co-mingled | Lives as `io.dataloom.runtime.retry` inside `dataloom-runtime`. |
| `dataloom-policy` | **Not a module** — co-mingled | `PolicyEvaluator` and friends live in `dataloom-api`'s `io.dataloom.api.policy` package. |
| `dataloom-conflict` | **Not a module** — co-mingled | Lives as `io.dataloom.runtime.conflict` inside `dataloom-runtime`. |
| `dataloom-replication` | **Not built / not identifiable as a distinct concept** | No `replication` package found anywhere in the repository. |
| `dataloom-assets` | Not built | No asset synchronization capability exists yet (`#97`, 0%). |
| `dataloom-storage-spi` | **Not a module** — co-mingled | `StorageProvider` and related contracts live in `dataloom-api`'s `io.dataloom.api.storage` package — exactly the "combines... queue/storage/transport SPIs" problem ADR-0002's own Context section names for the pre-migration `dataloom-api`. |
| `dataloom-storage-default` | Shape mismatch, not built as named | The repo has four independent reference storage providers (`dataloom-storage-sqldelight`, `dataloom-storage-room` + Android driver, `dataloom-storage-file`, `dataloom-storage-datastore`), not one `dataloom-storage-default`. Whether ADR-0002 intends consolidation or a naming update is an open question for a future ADR refinement, not something this investigation should decide unilaterally. |
| `dataloom-transport-spi` | **Not a module** — co-mingled | `TransportProvider` and related contracts live in `dataloom-api`'s `io.dataloom.api.transport` package. |
| `dataloom-transport-http` | Shape mismatch, not built as named | The repo has `dataloom-transport-ktor`, `dataloom-transport-graphql`, `dataloom-transport-grpc`, and `dataloom-transport-retrofit` — four named reference transports, not one `dataloom-transport-http`. Same open naming question as `dataloom-storage-default`. |
| `dataloom-observability` | **Not a module** — co-mingled | Observability/diagnostics behavior lives split across `dataloom-runtime`'s `observation` package and `dataloom-api`'s redaction/error types; no dedicated module. |
| `dataloom-runtime` | Exists, built | Present. |
| `dataloom-platform-android` | Exists via differently-named modules | Covered by the existing Android integration modules (`dataloom-connectivity-android`, `dataloom-queue-room`, `dataloom-storage-room`, `dataloom-scheduler-workmanager`) plus the `dataloom-android` aggregation artifact — functionally present, not literally named `dataloom-platform-android`. |
| `dataloom-platform-ios` | Exists, partial (first slice), name matches exactly | `dataloom-platform-ios/` is present with the ADR's exact source-module name; one provider only. |
| `dataloom-platform-jvm` | Not built | No JVM platform module exists. |
| `dataloom-apple` | Exists (macOS-gated) | Present, thin XCFramework/Swift distribution boundary as designed. |
| `dataloom-testing` | Exists, built | Present. |
| `dataloom-benchmarks` | Not built | No benchmarks module in `settings.gradle.kts`. |
| `build-logic` | Exists, correctly unpublished | Present as an included build; `modules.md` already documents it as "not published as a library," matching ADR-0002's own treatment. |

The recurring pattern: **the behavior ADR-0002 assigns to `dataloom-events`,
`dataloom-state`, `dataloom-queue`, `dataloom-retry`, `dataloom-policy`,
`dataloom-conflict`, `dataloom-storage-spi`, `dataloom-transport-spi`, and
`dataloom-observability` already exists and is tested — it is simply still
inside `dataloom-api`/`dataloom-runtime` rather than split into its own
source module.** This matches ADR-0002's own diagnosis verbatim: "configuration,
plugin, asset, events/observability, policy, retry, conflict, state, storage,
and transport ownership is not represented by explicit source boundaries."
Splitting each of these out is real, substantial, individually-schedulable
migration work (ADR-0002's own "Migration from the current graph" section
describes exactly this kind of extraction, one bounded slice at a time — the
same pattern `dataloom-model`, `dataloom-config`, `dataloom-provider-api`, and
`dataloom-plugin-api` already followed).

## Publication mechanism: confirmed absent everywhere

A repository-wide search for Gradle's publication plugin and DSL found **zero
matches**:

```
grep -rn "maven-publish\|publishing {\|MavenPublication" -- '**/*.gradle.kts'
```

The one hit for the word "publish" anywhere in a `build.gradle.kts` file
(`dataloom-plugin-api/build.gradle.kts`) is a comment referencing the "V1
published artifact graph," not actual wiring. `build-logic`'s only convention
plugin (`io.dataloom.kotlin.multiplatform-library`) configures KMP targets,
the Java toolchain, ABI baselines, and dependency-boundary checks — it
contains no `group`/`version`/publication logic at all. No module, and no
convention plugin any module could opt into, has ever been wired for
publication. There is no partially-built publication path to extend
narrowly; a first slice would be the very first one, for every module.

## Why even the narrowest possible slice is blocked, not just unattempted

The task named five candidate unresolved decisions that could block "just
wiring one already-ready module's publication metadata." All five are
confirmed present:

| Decision | Status | Evidence |
|---|---|---|
| Group ID / namespace | **Blocked** | ADR-0002 states plainly: "The `io.dataloom` group is the design coordinate. Publication is blocked until namespace ownership and release authority are verified in DL-046." DL-046 (`#100`, "immutable V1 release") is itself tracked as `BLOCKED / NO-GO` at 10% in `docs/status/market-readiness.md`, listing "SBOM/provenance/signatures/licenses... legal approval, publication" among its own still-open items. |
| License | **Unresolved** | `README.md`'s License section: "License status: **to be finalized before V1 publication**." No `LICENSE` file exists; no module sets a license in its POM metadata (there is no POM metadata at all yet). |
| Versioning scheme | **Undecided** | No `version = "..."` is set anywhere in the repo — not in any module's `build.gradle.kts`, not in `gradle.properties`, not in a version catalog. There is no semver/pre-release convention documented for the eventual `1.0.0` freeze beyond ADR-0002's passing references to it. |
| Artifact repository target | **Undecided** | No Maven repository (Central, GitHub Packages, or otherwise) is configured or referenced anywhere in the build. `dependencyResolutionManagement` only configures *consumption* from `mavenCentral()`/`google()`, not a *publication* target. |
| Signing keys | **Nonexistent** | No signing plugin (`signing`, `gpg`), key reference, or CI secret for artifact signing appears anywhere in the repo. |

Every one of the five is a real business/governance/legal decision, not an
engineering task — and the group-ID/namespace blocker is not merely inferred
from absence, it is **stated directly by ADR-0002 itself** as blocked pending
`DL-046`. Wiring even one module's `maven-publish` block would require
picking values for at minimum group ID, version, and a target repository —
none of which this investigation is positioned to decide unilaterally, and
all of which ADR-0002 already routes through `DL-046`'s separate, currently
`NO-GO` gate. Forcing placeholder values in to make a Gradle block "work"
would produce exactly the kind of hollow, unqualified artifact ADR-0002's own
"Rejected alternatives" section already rejects: "Create only empty artifact
wrappers... an artifact name without owned behavior and compatibility
evidence does not satisfy V1."

**Conclusion: no genuinely bounded, decision-free implementation slice exists
for `#93`'s artifact-graph/BOM gap today.** The blocker is real and named,
not a placeholder for "more engineering effort" — it is the same class of
finding this session already recorded for the KMP Android target
(`docs/android/kmp-android-target-blocker.md`) and the configuration-resolver
caller (`docs/api/configuration-resolver-caller-investigation.md`).

## Ordered checklist for future rounds

Each item below is independently schedulable and does not require deciding
the others first, except where noted. None may be started as a silent
side-effect of another `#93` slice — each needs its own approved issue per
`modules.md`'s "Future Module Expansion" rule.

1. **Resolve `DL-046` namespace ownership and release authority** (`#100`) —
   the actual `io.dataloom` group ID must be confirmed available/owned before
   any `group = "io.dataloom..."` line can be written anywhere. This blocks
   every other item below.
2. **Finalize V1 license** (`README.md`'s open item) — blocks POM metadata
   for every artifact and blocks `DL-046`'s own SBOM/provenance/license
   evidence requirement.
3. **Decide the versioning scheme** (semver pre-release convention leading to
   `1.0.0`, single-version-for-all-artifacts vs. independent versions) and
   the target artifact repository (Maven Central, GitHub Packages, or both).
4. **Add a `maven-publish` convention plugin to `build-logic`** once 1–3 are
   resolved, proven first against one already-stable, already-published-name
   module (`dataloom-model` or `dataloom-plugin-api` are the smallest
   candidates) rather than all twelve at once.
5. **Extract `dataloom-events`, `dataloom-state`, `dataloom-queue`,
   `dataloom-retry`, `dataloom-policy`, `dataloom-conflict`,
   `dataloom-storage-spi`, and `dataloom-transport-spi`** out of
   `dataloom-api`/`dataloom-runtime` into their own source modules, one at a
   time, following the same extraction pattern ADR-0002's "Migration from the
   current graph" section already used for `dataloom-model`/`dataloom-config`/
   `dataloom-provider-api`/`dataloom-plugin-api`. Each extraction needs its
   own ABI-baseline and ownership-boundary review, independent of publication
   readiness.
6. **Build `dataloom-assets`, `dataloom-observability`, `dataloom-jvm`, and
   `dataloom-platform-jvm`** — these do not exist at all yet, tracked
   separately by `#97` (assets) and not yet tracked by a dedicated issue for
   observability/JVM.
7. **Resolve the `dataloom-storage-default`/`dataloom-transport-http` naming
   question** — either consolidate the four reference storage/transport
   providers into one default module each, or propose an ADR-0002 refinement
   documenting the four-provider shape as intentional (per-provider optional
   artifacts already documented as a valid pattern in ADR-0002's "Published
   artifact graph" section).
8. **Assemble `dataloom-bom`** once the coordinates it constrains have real
   versions to reference (depends on 1–3).
9. **Complete `dataloom-kmp-core`, `dataloom-ios`, and `dataloom-android`** to
   their full ADR-0002 scope — `dataloom-kmp-core` needs the remaining
   canonical-model/error/compatibility content beyond clock primitives;
   `dataloom-ios` needs the platform providers beyond connectivity plus
   macOS-generated qualification evidence; `dataloom-android` needs its
   separately-tracked KMP Android target blocker resolved
   (`docs/android/kmp-android-target-blocker.md`).
10. **Wire publication metadata for all twelve published coordinates**, add
    BOM constraints, API/ABI checks against the frozen baseline, and staged
    external-consumer verification — this is ADR-0002's own migration step 8,
    explicitly sequenced last, "only after boundaries are proven."

## What is not in question

- ADR-0002's target graph is not missing design work; both tables are
  complete and internally consistent. This investigation found no defect in
  the ADR.
- Every module that does exist and was investigated here is functioning and
  tested at its documented scope — this document found no code-quality gap,
  only the absence of module-boundary extraction and publication wiring.
- This finding does not change `#93`'s percentage. It sharpens the "Still
  pending" wording in `docs/status/market-readiness.md` to name the precise
  blocker (business/governance decisions routed through `DL-046` and the
  license question, plus a precise module-by-module gap table) instead of
  the previous approximate "~20 separate enforced modules" phrasing.

## References

- [ADR-0002: V1 artifact and foundation architecture](../adr/ADR-0002-v1-artifact-and-foundation-architecture.md)
- [Module architecture](./modules.md) — current-state module graph and rules
- `docs/android/kmp-android-target-blocker.md` — the same "investigated and
  confirmed blocked, recorded so a future attempt starts from a different
  angle" pattern this document follows
- `docs/api/configuration-resolver-caller-investigation.md` — the structural
  precedent this document follows
- GitHub issue `#93` — DL-039 implementation gate
- GitHub issue `#100` — DL-046 immutable V1 release (namespace/license/signing
  authority)
