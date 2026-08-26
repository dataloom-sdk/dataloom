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

- **Permission enforcement** genuinely is blocked: `dataloom-plugin-api`
  cannot depend on `dataloom-api`'s policy foundation yet (confirmed in
  `dataloom-plugin-api/build.gradle.kts`'s own dependency block — `api(project(":dataloom-model"))`
  only), so routing a `PluginPermission` through a real grant decision has
  no foundation to call yet.
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
| `PluginLifecycleStateTracker` | `PluginLifecycleStateTracker.kt` | Tracks each plugin in a `PluginRegistry` through its `PluginLifecycleState`, starting every plugin at `LOADED` (never implicitly `ACTIVE`) and enforcing `PluginLifecycleTransitions` on every `transition` call. |

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

## What remains open

Everything this slice does not cover remains exactly as
`plugin-platform-first-slice-investigation.md` described it:

- **Permission enforcement** with denied-operation diagnostics — blocked
  on `dataloom-plugin-api` gaining a dependency path to the policy
  foundation.
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
  under what authorization; what an audit record schema looks like; what a
  repeatable certification kit emits as evidence).
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

- `dataloom-core:jvmTest` (`io.dataloom.core.plugin.*`): 43 tests, 0
  failures (`PluginRegistryTest`: 16, `PluginLifecycleTransitionsTest`: 17,
  `PluginLifecycleStateTrackerTest`: 10).
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
