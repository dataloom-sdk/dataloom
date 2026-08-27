# Plugin registry and lifecycle state tracking (`dataloom-core`)

[API reference index](./README.md)

## Status

**Available foundation — `#98`'s first real runtime component.** `dataloom-plugin-api`
([`plugin-api.md`](./plugin-api.md)) freezes the plugin manifest/identifier/
compatibility-range/dependency/execution-bounds/hook-point/lifecycle-label
*contract shapes* with zero behavior, by design — that module's own
`build.gradle.kts` says the loading/registration/enforcement/isolation
engine is `#98`'s job, built on top of those contracts. This page documents
that engine's first slice: `io.dataloom.core.plugin.PluginRegistry`,
`PluginLifecycleTransitions`, and `PluginLifecycleStateTracker`
(`dataloom-core`).

This is a genuinely bounded slice of `#98`, not the whole gate. See
[What remains open](#what-remains-open) below for everything this slice
does not do.

**Update (2026-08-26):** the "permission enforcement genuinely is blocked"
finding immediately below has since been re-checked and found stale — see
[Permission-grant enforcement](#permission-grant-enforcement).

## Why this slice, now

[`docs/api/plugin-platform-first-slice-investigation.md`](./plugin-platform-first-slice-investigation.md)
(a prior round of this same effort) concluded no further bounded,
dependency-free slice remained for `#98` and lumped every remaining item —
deny-by-default registration, the lifecycle state machine, permission
enforcement, execution-bounds enforcement, dependency ordering/cycle
rejection, compatibility validation, hook-point dispatch, audit/hot-disable/
certification — into one undifferentiated "real engineering/design work"
bucket.

Re-checking each item directly against source (rather than trusting that
prior prose summary, per this session's own standing discipline after
`BuiltInSynchronizationStrategyEvaluator` conflations were found in `#93`/
`#95`) shows the bucket was not uniform:

- **Permission enforcement** was originally found blocked here on
  `dataloom-plugin-api` lacking a dependency path to the policy foundation.
  Re-checked directly against source (round 23) and found stale: the engine
  this permission check needs to live in is not `dataloom-plugin-api` at all
  — it is `dataloom-core`, this page's own module, which already depends on
  both `dataloom-plugin-api` (`PluginPermission`) and `dataloom-model`
  (`Capability`/`GrantedCapabilities`/`isAuthorized`) directly. No new module
  dependency was actually needed. See
  [Permission-grant enforcement](#permission-grant-enforcement) below.
- **Compatibility validation** genuinely is blocked on an undecided design
  question, not just unbuilt: `RuntimeVersion` (`dataloom-model`) is a
  plain non-blank `String` with no guaranteed semantic-version shape.
  Existing call sites across this codebase use `"1.0.0"`, `"runtime-1.0.0"`,
  and `"1.2.3"` — inconsistent formats a real comparator would need a
  canonical parseable convention to resolve first, which is a product
  decision this task should not make unilaterally.
- **Hook-point callback dispatch** genuinely is blocked: each `PluginHookPoint`
  family's callback signature depends on the subsystem it extends (`#93`'s
  policy foundation, `#95`, `#96`, the runtime pipeline), none of which have
  adopted a plugin extension point yet.
- **Deny-by-default registration, the lifecycle state machine's transition
  enforcement, and deterministic dependency ordering/cycle rejection** are
  *not* blocked on anything external. Each is a pure, structural operation
  over types `dataloom-plugin-api` already ships (`PluginManifest`,
  `PluginLifecycleState`, `PluginDependency`) with a directly analogous,
  already-shipped, already-reviewed precedent in this exact codebase:
  `io.dataloom.core.provider.ProviderRegistry` (duplicate-ID rejection,
  deterministic ordering) and `io.dataloom.core.provider.ProviderLifecycleCoordinator`
  (explicit state machine with documented exceptional transitions). This
  page's three types are that precedent applied to plugins instead of
  providers.

## What exists here

| Type | File | Purpose |
|---|---|---|
| `PluginRegistry` | `PluginRegistry.kt` | Immutable registry of `DataLoomPlugin` instances. Rejects duplicate `PluginId`s and unresolved dependencies at construction; computes a deterministic, dependency-respecting `resolutionOrder` (dependencies before dependents, ties broken by registration order); rejects dependency cycles (including self-dependency) with the full cycle path in the exception message. |
| `PluginLifecycleTransitions` | `PluginLifecycleTransition.kt` | Stateless object enforcing which `PluginLifecycleState` transitions are structurally legal, mirroring `PluginLifecycleState`'s own documented `LOADED → VALIDATED → INITIALIZING → ACTIVE ⇄ DEGRADED → DISABLED → UNLOADED` order plus explicit failure-escape edges to `DISABLED` from every pre-`ACTIVE` state. |
| `PluginLifecycleStateTracker` | `PluginLifecycleStateTracker.kt` | Tracks each plugin in a `PluginRegistry` through its `PluginLifecycleState`, starting every plugin at `LOADED` (never implicitly `ACTIVE`) and enforcing `PluginLifecycleTransitions` on every `transition` call. Its capability-aware `transition` overload also enforces permission grants — see [Permission-grant enforcement](#permission-grant-enforcement). |
| `PluginPermission.asCapability()` | `PluginPermissionEnforcement.kt` | Extension function mapping a `PluginPermission` label onto a `Capability` of the same label, connecting `dataloom-plugin-api`'s permission contract to `dataloom-model`'s least-privilege primitive. |

## Deny-by-default registration and enablement

Registering a plugin in `PluginRegistry` grants it no lifecycle state by
itself — `PluginRegistry` does not track lifecycle state at all.
`PluginLifecycleStateTracker` is what tracks state, and every plugin it
tracks starts at `PluginLifecycleState.LOADED` — "the plugin's manifest has
been discovered or registered but not yet validated," per
`PluginLifecycleState`'s own KDoc. Reaching `ACTIVE` requires an explicit,
individually legal `LOADED → VALIDATED → INITIALIZING → ACTIVE` sequence of
`transition` calls; there is no shortcut and no default grant.

## Lifecycle transition graph

```text
LOADED       -> VALIDATED, DISABLED
VALIDATED    -> INITIALIZING, DISABLED
INITIALIZING -> ACTIVE, DISABLED
ACTIVE       -> DEGRADED, DISABLED
DEGRADED     -> ACTIVE, DISABLED
DISABLED     -> UNLOADED
UNLOADED     -> (terminal; no outgoing transitions)
```

The failure-escape edges to `DISABLED` from `LOADED`/`VALIDATED`/
`INITIALIZING` mirror `ProviderLifecycleCoordinator`'s own documented
exceptional transitions (`INITIALIZING → FAILED`, `SHUTTING_DOWN → FAILED`):
validation or initialization can fail, and a failed plugin must land in a
definite, inert state rather than an undefined one.

`PluginLifecycleTransitions.validate(from, to)` returns
`PluginLifecycleTransitionResult.Allowed`/`Rejected` (with a
human-readable reason) rather than throwing — callers decide how to react
to an illegal request. `PluginLifecycleStateTracker.transition` uses this
to update or reject tracked state.

## Dependency resolution and cycle rejection

`PluginRegistry` validates the dependency graph declared across every
registered plugin's `PluginManifest.dependencies`:

- Every `PluginDependency.pluginId` must reference a plugin registered in
  the same registry. An unresolved reference throws
  `IllegalArgumentException` naming both the requesting plugin and the
  missing dependency — deny-by-default, not silently ignored.
- The graph must be acyclic. A cycle (including a plugin depending on
  itself) throws `IllegalArgumentException` naming the full cycle path,
  e.g. `a -> b -> c -> a`.
- `resolutionOrder` is a deterministic topological ordering: every plugin
  appears after every plugin it (directly or transitively) depends on.
  Plugins with no dependency relationship to each other are ordered by
  registration order, the same determinism rule `ProviderRegistry` applies
  to its own `providers` list.

This validates the dependency **graph shape** only. It does not compare a
`PluginDependency`'s declared `PluginCompatibilityRange` against the
depended-upon plugin's actual `PluginManifest.version` — that is
compatibility validation, and per the section above it is blocked on an
undecided canonical version-format question.

## Permission-grant enforcement

`PluginLifecycleStateTracker` has a second, capability-aware `transition`
overload:

```kotlin
public fun transition(
    id: PluginId,
    target: PluginLifecycleState,
    grantedCapabilities: GrantedCapabilities,
): PluginLifecycleTransitionResult
```

Structural legality is still checked first via `PluginLifecycleTransitions`,
exactly as the two-argument overload already does — an illegal transition
returns `Rejected` and no permission check runs at all.

When the requested `target` is specifically `PluginLifecycleState.ACTIVE`
(covering both the ordinary `INITIALIZING -> ACTIVE` path and the
`DEGRADED -> ACTIVE` recovery edge), the tracker additionally requires
`grantedCapabilities` to hold every one of the plugin's declared
`PluginManifest.permissions`, checked via `isAuthorized` after mapping each
`PluginPermission` onto a `Capability` of the same label with
`asCapability()`. `ACTIVE` is the one state in which a plugin actually
executes — every earlier state is preparatory per `PluginLifecycleState`'s
own KDoc — so gating there is the one point that actually protects
something.

- If every declared permission is held, the transition proceeds exactly as
  the two-argument overload would: tracked state updates to `target` and
  `Allowed` is returned.
- If any declared permission is missing, tracked state is left unchanged
  and `PluginLifecycleTransitionResult.PermissionDenied(from, to,
  missingPermissions)` is returned, naming exactly which permissions were
  missing — never a partial grant, never a silent downgrade.
- A plugin with no declared permissions (`PluginManifest.permissions`
  empty) always passes this check regardless of what is granted — there is
  nothing to authorize.
- Non-`ACTIVE` targets (`VALIDATED`, `INITIALIZING`, `DISABLED`, `UNLOADED`)
  are never gated on permissions at all, even with the three-argument
  overload — only entry into `ACTIVE` is.

This method never throws for an illegal transition or a denied permission
set — the same "reject, don't throw" posture the two-argument overload
already establishes.

### What this does not do

- **Does not decide who may call `transition` at all.** This is
  authentication/authorization of the *caller requesting a transition*,
  not the *plugin's own declared capabilities* — `#98`'s "authorized hot
  disable" acceptance criterion remains open and is a separate concern.
- **Does not perform denied-operation diagnostics beyond naming missing
  permissions at transition time.** There is no ongoing enforcement once a
  plugin is `ACTIVE` — no per-invocation capability check, since there is
  no plugin invocation mechanism yet (`DataLoomPlugin`'s lifecycle
  callbacks are not frozen — see `docs/api/plugin-api.md`).
- **Does not audit denied or granted transitions.** Audit records remain
  an open `#98` item.

## What remains open

Everything this slice does not cover remains exactly as
`plugin-platform-first-slice-investigation.md` described it, except
permission enforcement (now shipped, see above):

- **Execution-bounds enforcement** — actual timeout cancellation,
  concurrency limiting, and failure isolation/bulkheading over
  `PluginExecutionBounds`' declared numbers. Not blocked on anything
  external, but deliberately left to a future slice to keep this one
  bounded — see [Deliberately deferred](#deliberately-deferred-not-blocked)
  below.
- **Compatibility validation before activation** — blocked on a canonical,
  parseable `RuntimeVersion` format decision.
- **Hook-point callback signatures and dispatch** — blocked on the
  consuming subsystems (`#93` policy, `#95`, `#96`, the runtime pipeline)
  adopting a plugin extension point.
- **Authorized hot disable, audit records, and the certification kit** —
  each its own unstarted design surface (who may request a transition and
  under what authorization — distinct from what a permitted caller's
  plugin may do once `ACTIVE`, which permission-grant enforcement above
  now covers; what an audit record schema looks like; what a repeatable
  certification kit emits as evidence).
- **A reference non-provider plugin** — demonstrating the full lifecycle
  end to end needs the still-open items above (execution-bounds
  enforcement, at minimum) to exist first.

### Deliberately deferred, not blocked

Execution-bounds enforcement (timeout/concurrency wrapping over
`PluginExecutionBounds`) has a directly analogous precedent already in this
codebase (`io.dataloom.runtime.retry.TimeoutEnforcingSchedulerProvider`)
and is not blocked on any open design question. It is left out of this
slice deliberately, to keep this change reviewable as one cohesive unit
(registry + state machine + dependency graph) rather than growing it into
every remaining bounded item at once — a genuinely separate follow-up
slice, not a "found it blocked" conclusion.

## No wiring into `DataLoomBuilder` yet

`PluginRegistry`/`PluginLifecycleStateTracker` are not referenced from
`DataLoomBuilder` or any other composition root. There is still no
application-facing way to register a plugin with the DataLoom runtime —
these types are `#98`'s internal engine building blocks, verified in
isolation, not yet a public plugin-registration API. Wiring a public
registration surface is separate follow-up work, likely gated on
execution-bounds enforcement existing first (an unenforced plugin has no
real safety boundary once actually invoked).

## Verification

- `dataloom-core:jvmTest` (`io.dataloom.core.plugin.*`): 53 tests, 0
  failures (`PluginRegistryTest`: 16, `PluginLifecycleTransitionsTest`: 17,
  `PluginLifecycleStateTrackerTest`: 18, `PluginPermissionEnforcementTest`: 2).
- `compileTestKotlinIosArm64`/`compileTestKotlinIosSimulatorArm64`/
  `compileTestKotlinIosX64` (`-Pdataloom.appleKlibCrossCompile=true`):
  independently re-verified clean on all three targets, including test
  sources — `checkKotlinAbi` alone does not compile test sources, a lesson
  from a real Kotlin/Native-only test-compilation failure found in this
  page's own prior round.
- `checkKotlinAbi -Pdataloom.appleKlibCrossCompile=true`: additive-only
  baseline change to `dataloom-core`'s JVM `.api` and Kotlin/Native
  `.klib.api` baselines; no other module's baseline changed.

## References

- [Plugin SPI (`dataloom-plugin-api`)](./plugin-api.md) — the contract
  types this engine is built on top of.
- [Plugin platform first-slice investigation](./plugin-platform-first-slice-investigation.md) —
  the prior round's investigation this page re-examines and partially
  supersedes.
- `docs/architecture/provider-lifecycle.md` and
  `io.dataloom.core.provider.ProviderRegistry`/`ProviderLifecycleCoordinator` —
  the directly analogous, already-shipped precedent this slice follows for
  providers instead of plugins.
- GitHub issue `#98` — DL-044 plugin platform implementation gate.
