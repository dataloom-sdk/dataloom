# `#98` `DataLoomBuilder` wiring investigation

[API reference index](./README.md)

## Status

**Investigated directly against source; found genuinely blocked by a
mechanically enforced module-ownership rule, not merely "undesigned."** This
is not a re-hash of [`plugin-registry.md`](./plugin-registry.md)'s existing
"[No wiring into `DataLoomBuilder` yet](./plugin-registry.md#no-wiring-into-dataloombuilder-yet)"
note, which only observes that no wiring exists. This document goes one step
further and asks *why not*, and finds a concrete, previously unstated reason:
wiring `PluginRegistry`/`PluginLifecycleStateTracker`/
`PluginExecutionBoundsEnforcer` into `DataLoomBuilder` the way the task
describes — exposing them, or their result/request types, through a new
`DataLoom` property — is not just unbuilt, it is a **build failure today**
under this repository's own enforced `dataloom-runtime` public-ABI boundary
check, because every behaviorally meaningful type `#98`'s engine has shipped
so far lives in `io.dataloom.core.plugin`, and `dataloom-core` types are
categorically forbidden from `dataloom-runtime`'s public API surface.

No code changed as a result of this investigation. Per this session's own
percentage discipline, this is an investigation-only PR and does not bump
`#98`'s percentage.

## What was investigated

Per this round's assignment, five specific questions were checked directly
against source rather than assumed:

1. `DataLoomBuilder`'s existing opt-in-spec convention (read in full).
2. Whether `dataloom-runtime` already depends on `dataloom-core`.
3. Whether a `DataLoomPluginConfigurationSpec`/`DataLoom.pluginEngine`
   design (as sketched in the task) is buildable.
4. Whether build-time-eager-vs-lazy plugin validation has a real precedent
   to follow.
5. Whether `DataLoomPlugin`'s invocation interface is frozen yet.

Findings for each, in order:

### 1. The opt-in-spec convention

Confirmed directly against
`dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomBuilder.kt`
(1,823 lines, read in full). Every optional capability
(`conflictDetectionConfiguration`, `retryAdministrationConfiguration`,
`circuitAdministrationConfiguration`, `conflictAdministrationConfiguration`,
`strategyDiagnosticsConfiguration`,
`strategyDecisionOutcomeHistoryConfiguration`,
`strategyAdmissionPolicyConfiguration`, the five operational-event-outbox
spec methods, `queueWorkerConfiguration`, `queueSubmissionConfiguration`,
`providerProtectionConfiguration`) follows the identical shape: a private
nullable `...Spec` field, a builder method that stores the spec unchanged
(no validation at the setter), and a `build()`-time `spec?.let { ... }`
block that constructs the real collaborator only when configured, wiring the
result into `DefaultDataLoom`'s constructor. When a spec is never supplied,
behavior is byte-for-byte unchanged from before the capability existed —
every doc comment for every one of these methods says this explicitly. This
convention is real, consistent, and the correct one to mirror for a plugin
spec if wiring were otherwise unblocked.

### 2. `dataloom-runtime` → `dataloom-core` dependency

Confirmed: already exists.
`dataloom-runtime/build.gradle.kts` line 21:
`implementation(project(":dataloom-core"))`. No new module dependency would
be needed to *reference* `PluginRegistry` et al. from `DataLoomBuilder`'s
implementation. This is not the blocker — see below for what is.

### 3. Whether the sketched `DataLoom.pluginEngine` design is buildable

**This is where the genuine blocker was found.** `dataloom-runtime`'s own
build (`build-logic/src/main/java/io/dataloom/buildlogic/DataLoomKotlinMultiplatformLibraryPlugin.java`,
lines 88–124) registers a `checkPublicAbiBoundaries` task, wired into
`check`, that scans **both** `dataloom-runtime`'s JVM ABI dump
(`build/kotlin/abi/dataloom-runtime.api`) **and** its Kotlin/Native `.klib`
ABI dump (`dataloom-runtime.klib.api`, generated on Apple hosts or with
`-Pdataloom.appleKlibCrossCompile=true`) for two forbidden namespace markers:

```java
task.getForbiddenMarkers().set(
        Set.of(
                "io/dataloom/core/",
                "io/dataloom/testing/",
                "io.dataloom.core.",
                "io.dataloom.testing."
        )
);
```

If either marker appears in either dump, the task fails and `check` fails
with it. This is exactly the enforcement mechanism
`docs/architecture/modules.md` alludes to ("`dataloom-runtime` exposes no
`dataloom-core` or `dataloom-testing` type in either baseline, and a build
task rejects either namespace if it appears later") — confirmed here by
reading the actual task registration, not just the doc's prose description
of it.

Every type `#98`'s engine has shipped that carries real behavioral meaning —
not just identifiers it borrows from `dataloom-plugin-api` — lives in
`io.dataloom.core.plugin`. A repository-wide enumeration of every
`public class`/`interface`/`object`/`sealed`/`data class` declaration in
that package confirms the complete list:

| Type | File | Package |
|---|---|---|
| `PluginRegistry` | `PluginRegistry.kt` | `io.dataloom.core.plugin` |
| `PluginLifecycleStateTracker` | `PluginLifecycleStateTracker.kt` | `io.dataloom.core.plugin` |
| `PluginLifecycleTransitionResult` (sealed: `Allowed`/`Rejected`/`PermissionDenied`/`AuthorizationDenied`) | `PluginLifecycleTransition.kt` | `io.dataloom.core.plugin` |
| `PluginLifecycleTransitions` | `PluginLifecycleTransition.kt` | `io.dataloom.core.plugin` |
| `PluginExecutionBoundsEnforcer` | `PluginExecutionBoundsEnforcement.kt` | `io.dataloom.core.plugin` |
| `PluginExecutionBoundsResult<T>` (sealed: `Completed`/`TimedOut`/`ConcurrencyLimitExceeded`) | `PluginExecutionBoundsEnforcement.kt` | `io.dataloom.core.plugin` |
| `PluginLifecycleTransitionRequest` | `PluginLifecycleAdministration.kt` | `io.dataloom.core.plugin` |
| `PluginLifecycleAdministrationAuthorizer` | `PluginLifecycleAdministration.kt` | `io.dataloom.core.plugin` |
| `PluginLifecycleAdministrationAuthorizationDecision` (sealed: `Authorized`/`Denied`) | `PluginLifecycleAdministration.kt` | `io.dataloom.core.plugin` |
| `PluginLifecycleAdministrationOperationalEventBridge` | `PluginLifecycleAdministrationOperationalEventBridge.kt` | `io.dataloom.core.plugin` |
| `PluginPermission.asCapability()` | `PluginPermissionEnforcement.kt` | `io.dataloom.core.plugin` |

Every single one of these is in the forbidden namespace. The task's sketch
— "have `DataLoomBuilder` construct and expose a
`PluginRegistry`/`PluginLifecycleStateTracker`/`PluginExecutionBoundsEnforcer`
triple as a new `DataLoom.pluginEngine` ... property" — would place three
`io.dataloom.core.plugin` types directly into a public `DataLoom` property
type. That alone fails `checkPublicAbiBoundaries` immediately, on both the
JVM and Kotlin/Native dumps. But the problem is not limited to the three
named types: **any** public method on any new facade that returns
`PluginLifecycleTransitionResult`, accepts or returns
`PluginLifecycleTransitionRequest`, returns
`PluginLifecycleAdministrationAuthorizationDecision`, or returns
`PluginExecutionBoundsResult` has the identical problem, because those
result/request/decision types — the ones that actually carry the engine's
observable behavior — are *also* `io.dataloom.core.plugin` types. There is
no way to expose "did this transition succeed, and if not, why" or "did this
invocation time out or hit its concurrency ceiling" through
`dataloom-runtime`'s public API today without first relocating those types
out of `dataloom-core`.

### Why the established provider precedent does not transfer here

`#98`'s own docs (and this task's own framing) repeatedly point to
`io.dataloom.core.provider.ProviderRegistry`/`ProviderLifecycleCoordinator`
as the shape `#98`'s plugin engine mechanically applies for plugins instead
of providers. That precedent is real and was checked directly against
source — but it does **not** license the wiring step the same way, because
the provider precedent made a design choice `#98`'s plugin engine did not
mirror:

`ProviderLifecycleCoordinator` (`dataloom-core`,
`io.dataloom.core.provider`) is itself exactly as "forbidden" a type as
`PluginLifecycleStateTracker` — and indeed it is never exposed publicly.
But its **result and state types are not `dataloom-core` types**:

```kotlin
// dataloom-core/src/commonMain/kotlin/io/dataloom/core/provider/ProviderLifecycleCoordinator.kt
public class ProviderLifecycleCoordinator(...) {
    public val state: ProviderLifecycleCoordinatorState   // io.dataloom.api.provider — NOT dataloom-core
    public suspend fun initialize(): ProviderLifecycleResult  // io.dataloom.api.provider — NOT dataloom-core
    public suspend fun shutdown(): ProviderLifecycleResult    // io.dataloom.api.provider — NOT dataloom-core
}
```

`ProviderLifecycleCoordinatorState` and `ProviderLifecycleResult` were
deliberately placed in `dataloom-api` from the start — a module
`dataloom-runtime` is free to expose publicly — even though the coordinator
that produces them lives in `dataloom-core`. This is exactly why
`DataLoom.providerLifecycleState` (in
`dataloom-runtime/.../facade/DataLoom.kt`) can expose
`ProviderLifecycleCoordinatorState` directly with zero translation code:
the internal engine already returns a publicly-exposable type.

`#98`'s plugin engine made the opposite choice. `PluginLifecycleTransitionResult`,
`PluginExecutionBoundsResult`, `PluginLifecycleTransitionRequest`, and
`PluginLifecycleAdministrationAuthorizationDecision` were all placed in
`io.dataloom.core.plugin` alongside the engine itself, rather than in
`dataloom-plugin-api` (which already holds `PluginId`, `PluginLifecycleState`,
`PluginManifest`, etc. — the module a `dataloom-runtime`-facing result type
could safely have lived in) or `dataloom-api`. That placement was reasonable
and defensible at the time each piece shipped — every plugin-registry.md
round to date evaluated each slice purely as `dataloom-core`-internal,
tested-in-isolation infrastructure, explicitly *because* no real caller or
wiring point existed yet to make the placement's downstream consequences
concrete. This round is the first to actually attempt the wiring step, and
in doing so surfaces the consequence: the placement decision, made
correctly for its own scope at the time, now blocks the exact wiring step
this task was asked to attempt.

### The two ways to unblock this, and why neither is a bounded slice

**Option A — relocate the result/request/decision types to
`dataloom-plugin-api` or `dataloom-api`.** This mirrors the provider
precedent exactly (engine stays in `dataloom-core`; its outputs move to a
publicly-exposable module). But it means *removing* public declarations
from `dataloom-core`'s own already-shipped, already-baselined API —
`PluginLifecycleTransitionResult`, `PluginExecutionBoundsResult`,
`PluginLifecycleTransitionRequest`,
`PluginLifecycleAdministrationAuthorizationDecision`, and
`PluginLifecycleAdministrationOperationalEventBridge` (which references
`PluginLifecycleTransitionRequest`/`Result` in its own signature and would
need to move too, or be split) — and re-adding them under a different
package/module. `updateKotlinAbi` treats that as **deletions**, not the
purely additive diff every prior `#98` round in this file's own
"Verification" sections has been careful to confirm and call out
(`109/110 inserted lines respectively, zero deletions`, `checkKotlinAbi`
confirmed additive-only, `updateKotlinAbi` run and diff reviewed — repeated
verbatim across the 2026-08-26/08-28/08-30 rounds). Doing this correctly
also means deciding whether `PluginLifecycleAdministrationAuthorizer`
itself (a host-implemented interface, not just a data/result type) moves
too, since a public method accepting an authorizer parameter has the exact
same ABI-boundary problem as one returning a result. This is a real,
non-trivial module-ownership decision — not a wiring task — and one this
task should not make unilaterally, for the same reason earlier rounds
declined to invent a canonical `RuntimeVersion` format unilaterally for
compatibility validation: it has consequences (a breaking-shaped diff to an
already-shipped module's ABI baseline) beyond this slice's own scope.

**Option B — build a second, hand-translated copy of every result/request
type inside `dataloom-runtime`,** mirroring
`RetryAdministrationResult`/`CircuitAdministrationResult` (which are
`dataloom-runtime`-native types in the first place, since
`RetryAdministrationCoordinator`/`CircuitAdministrationCoordinator`
themselves live in `dataloom-runtime`, not `dataloom-core` — confirmed by
their import paths in `DataLoomRetryAdministration.kt`/
`DefaultDataLoomRetryAdministration.kt`, `io.dataloom.runtime.retry`, not
`io.dataloom.core.*`). This is *not* actually the same precedent: those
coordinators were never `dataloom-core`-internal in the first place, so no
translation was ever needed for them. Building one for plugins means
maintaining two parallel copies of `PluginLifecycleTransitionResult`'s four
variants (and `PluginExecutionBoundsResult`'s three, and the authorization
decision's two) forever, with a manual mapping function between them and a
real risk of the two drifting out of sync as `#98` continues to evolve the
`dataloom-core` originals in future rounds. This is buildable, but it is
meaningfully more than "wire a spec into `DataLoomBuilder`" — it is
designing and committing to a permanent duplicate-type boundary-crossing
layer for one specific engine, a real architectural pattern decision with
its own maintenance cost, not a mechanical extension of the existing
opt-in-spec convention.

Neither option is a same-round bounded slice. Both require a real decision
this task's own instructions say not to make unilaterally when one exists
("investigate carefully whether this has a genuine undecided design
question... follow \[the established convention\] rather than inventing a
new one" — there is no established convention here to follow, because no
prior `dataloom-runtime` facade has ever needed to cross this specific kind
of boundary for an engine whose outputs were placed in `dataloom-core`
rather than `dataloom-api`).

### 4. Eager vs. lazy build-time validation precedent

Investigated for completeness, though it is moot while (3) blocks any
wiring at all: every existing opt-in capability in `DataLoomBuilder` (queue
worker, retry/circuit/conflict administration, provider protection)
validates **eagerly, at `build()` time**, and throws
`DataLoomBuildException` for a structurally invalid configuration (e.g. a
missing or mistyped provider binding) rather than deferring the check to
first use. `PluginRegistry`'s own constructor already follows this same
"validate eagerly, fail closed" posture (duplicate `PluginId`s, unresolved
dependencies, and dependency cycles are all rejected at
`PluginRegistry` construction, not at some later resolution call) — so if
wiring were otherwise unblocked, throwing `DataLoomBuildException` from
`build()` when plugin registration itself fails (wrapping the
`IllegalArgumentException` `PluginRegistry`'s constructor already throws)
would be the convention-consistent choice, exactly mirroring how
`ProviderRegistry`'s duplicate-ID `IllegalArgumentException` is already
allowed to propagate unwrapped from `build()` today per this file's own
KDoc ("Duplicate provider IDs throw `IllegalArgumentException` from
`ProviderRegistry`"). This finding is preserved here so a future round does
not have to re-derive it once (3) is actually resolved.

### 5. `DataLoomPlugin`'s invocation interface

Re-confirmed directly against `dataloom-plugin-api/src/commonMain/kotlin/io/dataloom/api/plugin/DataLoomPlugin.kt`
(referenced via `docs/api/plugin-api.md`, itself re-read in full this
round): `DataLoomPlugin` exposes exactly `manifest` and `executionBounds`.
No `initialize`/`activate`/`disable`/hook-invocation method exists.
`plugin-api.md`'s own "Why hook callback signatures aren't defined yet"
section explains this is deliberate, not an oversight, since each hook
family's signature depends on a consuming subsystem (`#93` policy, `#95`,
`#96`, the runtime pipeline) that has not adopted an extension point.

This compounds (3) rather than being an independent blocker: even if the
ABI-boundary question were resolved today, a wired `DataLoomBuilder` plugin
spec could register plugins, resolve their dependency graph, and track
their lifecycle state — but could never actually *run* one, since there is
still no method on `DataLoomPlugin` to call. A registry with nothing to
invoke is real, bounded value on its own (as `plugin-registry.md` already
argues for `PluginExecutionBoundsEnforcer` shipping ahead of hook-point
dispatch), but it sets the ceiling on how much wiring is actually worth
doing right now even after (3) is resolved: exposing `stateOf`/
`registeredPluginIds` (both already publicly-exposable — `PluginId` and
`PluginLifecycleState` are `dataloom-plugin-api` types, not
`dataloom-core`) would be real and ABI-clean, but exposing the ability to
*drive* a transition still requires solving (3) first, since
`transition(...)`'s return type is the blocked
`PluginLifecycleTransitionResult`.

## Conclusion

Wiring `#98`'s plugin engine into `DataLoomBuilder`, as sketched in this
round's own task description, is not yet a bounded slice. The blocker is
concrete and mechanically verifiable — `dataloom-runtime`'s
`checkPublicAbiBoundaries` build task (wired into `check`) fails on any
public exposure of an `io.dataloom.core.plugin.*` type, and every one of
`#98`'s engine's result/request/decision types (the parts of the engine
that actually carry observable behavior) live in that forbidden namespace —
rather than the general "no wiring exists yet" observation
`plugin-registry.md` already recorded. Resolving it requires a real
module-ownership decision (relocate types out of `dataloom-core`, accepting
a breaking-shaped ABI diff to an already-shipped module; or build and
permanently maintain a duplicate translation layer inside
`dataloom-runtime`) that this task should not make unilaterally, consistent
with how earlier `#98` rounds declined to invent a canonical `RuntimeVersion`
format or an execution-bounds/lifecycle integration policy unilaterally.

No production code was changed. `docs/api/plugin-registry.md`'s existing
"[No wiring into `DataLoomBuilder` yet](./plugin-registry.md#no-wiring-into-dataloombuilder-yet)"
section has been updated to point here for the concrete reason why, rather
than duplicating this analysis in place.

## References

- [Plugin registry and lifecycle state tracking](./plugin-registry.md) —
  the engine this investigation attempted to wire.
- [Plugin SPI (`dataloom-plugin-api`)](./plugin-api.md) — confirms
  `DataLoomPlugin`'s invocation interface is still unfrozen (finding 5).
- `docs/architecture/modules.md` — the module-dependency and ABI-boundary
  rules this investigation confirmed directly against
  `build-logic/src/main/java/io/dataloom/buildlogic/DataLoomKotlinMultiplatformLibraryPlugin.java`.
- `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomBuilder.kt`,
  `DataLoom.kt`, `DataLoomRetryAdministration.kt`,
  `DefaultDataLoomRetryAdministration.kt` — the opt-in-spec convention and
  the precedent for a `dataloom-runtime`-native (not `dataloom-core`
  -translated) result type.
- `dataloom-core/src/commonMain/kotlin/io/dataloom/core/provider/ProviderLifecycleCoordinator.kt` —
  the provider precedent showing the pattern that *would* have avoided this
  blocker, had `#98`'s result types been placed the same way.
- GitHub issue `#98` — DL-044 plugin platform implementation gate.
