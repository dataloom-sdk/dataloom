# Plugin registry and lifecycle state tracking (`dataloom-core`)

[API reference index](./README.md)

## Status

**Available foundation — `#98`'s first real runtime component.** `dataloom-plugin-api`
([`plugin-api.md`](./plugin-api.md)) freezes the plugin manifest/identifier/
compatibility-range/dependency/execution-bounds/hook-point/lifecycle-label
*contract shapes* with zero behavior, by design — that module's own
`build.gradle.kts` says the loading/registration/enforcement/isolation
engine is `#98`'s job, built on top of those contracts. This page documents
that engine's growing slice: `io.dataloom.core.plugin.PluginRegistry`,
`PluginLifecycleTransitions`, `PluginLifecycleStateTracker`, and
`PluginExecutionBoundsEnforcer` (`dataloom-core`).

This is a genuinely bounded slice of `#98`, not the whole gate. See
[What remains open](#what-remains-open) below for everything this slice
does not do.

**Update (2026-08-26):** the "permission enforcement genuinely is blocked"
finding immediately below has since been re-checked and found stale — see
[Permission-grant enforcement](#permission-grant-enforcement).

**Update (2026-08-28):** execution-bounds enforcement — timeout cancellation
and concurrency limiting over `PluginExecutionBounds` — has shipped. See
[Execution-bounds enforcement](#execution-bounds-enforcement). Compatibility
validation and hook-point dispatch were re-checked directly against source
this round and remain genuinely blocked exactly as described below.

**Update (2026-08-30):** authorized transitions ("authorized hot disable")
and a plugin-lifecycle operational-event audit bridge have shipped. Neither
was assumed blocked or unblocked without re-checking directly against
source: see
[Authorized transitions ("authorized hot disable")](#authorized-transitions-authorized-hot-disable)
and
[Audit records (operational-event bridge)](#audit-records-operational-event-bridge)
below. The certification kit, `DataLoomBuilder` wiring, hook-point dispatch,
and compatibility validation remain exactly as described in
[What remains open](#what-remains-open).

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
| `PluginExecutionBoundsEnforcer` | `PluginExecutionBoundsEnforcement.kt` | Wraps an arbitrary `suspend () -> T` invocation of a registered plugin with coroutine-cancellation timeout enforcement (`maximumExecutionMillis`) and per-plugin concurrency limiting (`maximumConcurrentInvocations`), returning a non-throwing `PluginExecutionBoundsResult`. See [Execution-bounds enforcement](#execution-bounds-enforcement). |
| `PluginLifecycleAdministrationAuthorizer` / `PluginLifecycleTransitionRequest` | `PluginLifecycleAdministration.kt` | Host-owned, deny-by-default authorization boundary for *who* may request a `PluginLifecycleStateTracker.transition` call, consulted via the tracker's authorizer-aware `transition(request, authorizer)` overload. See [Authorized transitions ("authorized hot disable")](#authorized-transitions-authorized-hot-disable). |
| `PluginLifecycleAdministrationOperationalEventBridge` | `PluginLifecycleAdministrationOperationalEventBridge.kt` | Stateless mapping from a `PluginLifecycleTransitionRequest`/`PluginLifecycleTransitionResult` pair to a redacted `OperationalEventEnvelope`, for a caller to append into `DurableOperationalEventOutbox`. See [Audit records (operational-event bridge)](#audit-records-operational-event-bridge). |

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
  not the *plugin's own declared capabilities*. This is now covered
  separately — see
  [Authorized transitions ("authorized hot disable")](#authorized-transitions-authorized-hot-disable)
  below — by a distinct tracker overload, deliberately not fused into this
  one.
- **Does not perform denied-operation diagnostics beyond naming missing
  permissions at transition time.** There is no ongoing enforcement once a
  plugin is `ACTIVE` — no per-invocation capability check, since there is
  no plugin invocation mechanism yet (`DataLoomPlugin`'s lifecycle
  callbacks are not frozen — see `docs/api/plugin-api.md`).
- **Does not itself audit denied or granted transitions.** A caller that
  wants a durable audit record of a `PermissionDenied` result (or any other
  `PluginLifecycleTransitionResult`) can bridge it via
  [Audit records (operational-event bridge)](#audit-records-operational-event-bridge)
  below.

## Authorized transitions ("authorized hot disable")

`#98`'s "authorized hot disable" acceptance criterion asks *who* may
request a `PluginLifecycleStateTracker.transition` call — distinct from
what the transition itself checks (structural legality, already covered by
[Lifecycle transition graph](#lifecycle-transition-graph); the plugin's own
declared permissions, already covered by
[Permission-grant enforcement](#permission-grant-enforcement) above).

This was investigated, not assumed either way, against this codebase's
existing "authorized command" precedents:
`io.dataloom.api.retry.RetryAdministrationAuthorizer`,
`io.dataloom.api.circuit.CircuitAdministrationAuthorizer`, and
`io.dataloom.api.conflict.ConflictAdministrationAuthorizer` (each paired
with a `RetryAdministrationCoordinator`/`CircuitAdministrationCoordinator`/
`ConflictAdministrationCoordinator` in `dataloom-runtime`) all establish the
same shape: a host-supplied, deny-by-default authorization boundary that
DataLoom itself invents no identity or permission system for. The core of
that shape — the authorizer interface itself — turned out to transfer
mechanically. What those precedents *also* carry — a durable, idempotent,
compare-and-set command store with replay detection and bounded contention
retry — deliberately does **not** transfer here, because
`PluginLifecycleStateTracker` itself is not durable: it already documents
itself as in-memory, caller-serialized state with no compare-and-set loop
of its own (see its own "Thread-safety boundary" documentation).
Reproducing that full durable-coordinator apparatus around a tracker that
has neither a state store nor a notion of replay would have invented
durability this engine does not have, rather than mechanically applying
the part of the precedent that does transfer.

`PluginLifecycleAdministration.kt` adds:

- `PluginLifecycleTransitionRequest` — an immutable request naming
  `pluginId`, `target`, a caller-chosen `commandId` (a stable
  correlation/audit key, mirroring `RetryAdministrationCommandId` in shape;
  unlike that precedent, nothing in this tracker uses it for durable replay
  detection, since there is no durable store to replay against), a
  `principalId`, a caller-supplied `requestedAt`, and a bounded `reason`.
- `PluginLifecycleAdministrationAuthorizer` — a host-owned
  `suspend fun authorize(request): PluginLifecycleAdministrationAuthorizationDecision`
  (`Authorized` / `Denied(reasonCode)`), with no default implementation, by
  design.
- `PluginLifecycleStateTracker`'s new authorizer-aware
  `transition(request, authorizer)` overload: structural legality is
  checked first (an illegal transition returns `Rejected` and the
  authorizer is never consulted), then `authorizer.authorize(request)` is
  called; a `Denied` decision leaves tracked state unchanged and returns
  the new `PluginLifecycleTransitionResult.AuthorizationDenied(from, to,
  reasonCode)` variant, and `Authorized` applies the transition exactly as
  the two-argument overload would.

### Why this gates any transition, not only `DISABLED`

"Hot disable" is the acceptance criterion's motivating case, but nothing
about *who may command a lifecycle transition* is specific to the
`DISABLED` target. `CircuitAdministrationAuthorizer` — the precedent this
type mirrors most directly — authorizes every
`CircuitAdministrationAction` (`OPEN`, `CLOSE`, `RESET`) through one
uniform boundary rather than singling out one privileged action; forcing
an `ACTIVE` plugin into `DEGRADED`, or recovering a `DEGRADED` plugin back
to `ACTIVE`, is exactly as privileged an operation as disabling it
outright. Scoping this authorizer to `DISABLED` alone would have meant
inventing an inconsistent boundary — protected for one target, wide open
for every other transition — disconnected from the actual security
question ("who may drive this plugin's lifecycle"). `PluginLifecycleAdministrationAuthorizer`
therefore gates
`PluginLifecycleStateTracker.transition(request, authorizer)` for any
structurally legal target, and a real `DISABLED` request is simply one
instance of that general call, not a special case.

### Relationship to the capability-aware overload

This overload is deliberately independent of the capability-aware
`transition(id, target, grantedCapabilities)` overload — who may *ask* for
a transition, versus what the plugin itself may *do* once `ACTIVE`, are
orthogonal concerns this page already treats separately, and fusing them
into one overload would force every authorized-transition caller to also
supply a capability grant even for targets (like `DISABLED`) that overload
never gates anyway. A caller that needs both protections for entry into
`ACTIVE` invokes both checks itself; composing them into one call is left
to a future slice once a real call site makes the composition concrete.

## Audit records (operational-event bridge)

`#98`'s "audit records" acceptance criterion was investigated against this
codebase's established operational-event-bridge precedent:
`io.dataloom.runtime.observation.operational.RetryCircuitAdministrationOperationalEventBridge`
and
`io.dataloom.runtime.observation.operational.ConflictResolutionOperationalEventBridge`
both bridge an administration command's request and terminal outcome into
`io.dataloom.api.operational.OperationalEventEnvelope`, the canonical DL-042
envelope `DurableOperationalEventOutbox` persists.

New `io.dataloom.core.plugin.PluginLifecycleAdministrationOperationalEventBridge`
is the mechanical "sixth bridge" that precedent predicted: a stateless
`toEnvelope(request: PluginLifecycleTransitionRequest, result:
PluginLifecycleTransitionResult): OperationalEventEnvelope` covering every
outcome the authorizer-aware overload (and, for `PermissionDenied`, the
capability-aware overload) can produce — `Allowed`, `Rejected`,
`PermissionDenied`, and `AuthorizationDenied` — not only denials, following
the precedent's own "bridge every terminal outcome" convention.

Unlike the retry/circuit/conflict precedents, this bridge lives in
`dataloom-core` itself rather than `dataloom-runtime`: the request and
result types it bridges from (`PluginLifecycleTransitionRequest`,
`PluginLifecycleTransitionResult`) already live in this module, and
`OperationalEventEnvelope` lives in `dataloom-api`, which `dataloom-core`
already depends on directly — no new module dependency was needed, unlike
execution-bounds enforcement's `kotlinx-coroutines-core` addition.

`OperationalEventEnvelope.id` is derived from
`PluginLifecycleTransitionRequest.commandId` (never freshly generated),
`correlationId` reuses that same identifier unchanged, and `occurredAt` is
always `requestedAt` — a caller-supplied timestamp, never a clock read.
Every attribute is classified and passes through `StrictDataLoomRedactor`
before being placed into the envelope, following the retry/circuit
precedent's own rules: lifecycle-state enum names are `PUBLIC`;
`pluginId`/`principalId` are `INTERNAL`; `Rejected.reason`,
`AuthorizationDenied.reasonCode`, and `PermissionDenied.missingPermissions`
are all `INTERNAL` (none is a codebase-closed enum, the same conservative
reading the precedent gives `rejectionReasonCode`); the caller-supplied
free-text `PluginLifecycleTransitionRequest.reason` is never included at
all, exactly as the precedent excludes `RetryAdministrationReason`/
`CircuitAdministrationReason`.

### No wiring yet

Nothing in this codebase calls `toEnvelope` today. Exactly like
`PluginExecutionBoundsEnforcer` before it, this is available
infrastructure for a future facade — mirroring
`io.dataloom.runtime.facade.DefaultDataLoomRetryAdministration`'s "swallow
append failures" posture — once one is wired into `DataLoomBuilder` for the
plugin engine. This was investigated specifically, not assumed: today
nothing calls `PluginLifecycleStateTracker.transition` from application
code at all (see [No wiring into `DataLoomBuilder` yet](#no-wiring-into-dataloombuilder-yet)
below), so there is no real caller yet to also wire into
`DurableOperationalEventOutbox`. The bridge itself does not need a real
caller to be genuinely real, tested infrastructure, though — the same
reasoning that already justified shipping `PluginExecutionBoundsEnforcer`
ahead of hook-point dispatch.

## Execution-bounds enforcement

`io.dataloom.core.plugin.PluginExecutionBoundsEnforcer` wraps an arbitrary
plugin invocation with the timeout cancellation and concurrency limiting
`docs/api/plugin-registry.md`'s own prior round named as the most promising
remaining bounded slice, precisely because it has a directly analogous
precedent already shipped in this codebase:
`io.dataloom.runtime.retry.TimeoutEnforcingSchedulerProvider`, which wraps
every `SchedulerProvider` call in coroutine-cancellation timeout enforcement
via `CoroutineRetryTimeoutExecutor`'s `kotlinx.coroutines.withTimeoutOrNull`,
converting an expired timeout into a canonical, non-throwing failure result.

```kotlin
public class PluginExecutionBoundsEnforcer(private val registry: PluginRegistry) {
    public suspend fun <T> execute(
        id: PluginId,
        operation: suspend () -> T,
    ): PluginExecutionBoundsResult<T>
}
```

### Why a generic `operation` parameter, not a `DataLoomPlugin` callback

`DataLoomPlugin` deliberately declares no lifecycle or hook-invocation
callback methods yet — see `docs/api/plugin-api.md`: those signatures depend
on the execution context this engine designs and are not frozen. Unlike
`SchedulerProvider`, there is today no fixed "invoke this plugin" method to
decorate. `execute` is therefore written generically over any
`suspend () -> T` block representing one invocation of the plugin
registered under a given `PluginId`. When a real invocation call site exists
(hook-point dispatch, still blocked — see
[What remains open](#what-remains-open)), it is expected to route its
invocation through this type rather than reimplementing bounds enforcement.

### Timeout enforcement

`PluginExecutionBounds.maximumExecutionMillis` is enforced with
`kotlinx.coroutines.withTimeoutOrNull` — the exact mechanism
`CoroutineRetryTimeoutExecutor` uses for providers. An operation that blocks
without a suspension or other cancellation checkpoint cannot be preempted by
this timeout, the same documented limitation that executor already carries.
A timed-out invocation returns `PluginExecutionBoundsResult.TimedOut(pluginId,
maximumExecutionMillis)` rather than throwing.

### Concurrency limiting

`PluginExecutionBounds.maximumConcurrentInvocations` is enforced with one
`kotlinx.coroutines.sync.Semaphore` per registered plugin, built once,
immutably, at construction from the registry's registered plugins. A call
that would exceed the ceiling is rejected immediately
(`Semaphore.tryAcquire()` returning `false`, before `operation` is ever
invoked) rather than suspended to wait for a free slot: a fail-fast
bulkhead, not a queue, so one busy or slow plugin cannot silently stall an
unrelated caller. A rejected call returns
`PluginExecutionBoundsResult.ConcurrencyLimitExceeded(pluginId,
maximumConcurrentInvocations)`. Each plugin's ceiling is independent — one
plugin at capacity never affects another plugin's own invocations.

The acquired concurrency slot is always released before `execute` returns,
including when `operation` throws, times out, or is cancelled.

### What this does not do

- **Does not check `PluginLifecycleState`.** This type enforces declared
  time/concurrency bounds only, independent of `PluginLifecycleStateTracker`.
  It does not require a plugin to be `ACTIVE` before running `operation`,
  and — this is a deliberate, investigated omission, not an oversight — it
  does not decide what happens to an already-in-flight invocation when a
  plugin's tracked state changes mid-execution (for example
  `ACTIVE -> DEGRADED` or `ACTIVE -> DISABLED`).

  This was investigated as a candidate open design question before shipping
  this slice, per this session's own "resolve it or flag it, don't assume"
  discipline, and found to be a genuine open question rather than a
  mechanical extension: `io.dataloom.core.provider.ProviderLifecycleCoordinator`,
  the precedent this gate's own lifecycle types already follow, has no
  analogous "cancel work in flight when state changes" behavior to mirror
  either — it only documents that `CancellationException` during its own
  `initialize`/`shutdown` calls propagates normally with an undefined
  post-cancellation state, not a policy for cancelling unrelated in-flight
  work on a state transition. More fundamentally, there is no real
  invocation call site at all today — hook-point dispatch remains blocked
  (see [What remains open](#what-remains-open)) — so there is no concrete
  in-flight invocation this scenario could apply to yet, and inventing an
  answer unilaterally here, ahead of any real caller, would be exactly the
  kind of speculative design this project avoids building ahead of a
  concrete consumer. Wiring this enforcer together with
  `PluginLifecycleStateTracker` is left to whichever future slice adds a
  real invocation call site, once that call site's own semantics make the
  question concrete instead of hypothetical.
- **Does not perform failure isolation/bulkheading beyond concurrency
  limiting.** A plugin operation throwing an ordinary exception propagates
  normally, uncaught — exactly as `TimeoutEnforcingSchedulerProvider` leaves
  "unexpected programming exceptions" to propagate rather than converting
  them into a bounded result.
- **Does not audit timeout or concurrency-rejection events.**
  `PluginExecutionBoundsResult` is not bridged by
  [Audit records (operational-event bridge)](#audit-records-operational-event-bridge)
  above — that bridge covers `PluginLifecycleTransitionResult` only.
  Bridging execution-bounds outcomes too is separate follow-up work, not
  addressed by this page's most recent round.

### Thread-safety

Unlike `PluginLifecycleStateTracker` (which requires callers to serialize
`transition` calls), `execute` is safe to call concurrently, for the same or
different plugin IDs: `kotlinx.coroutines.sync.Semaphore` is itself safe
under concurrent `tryAcquire`/`release`, and the per-plugin semaphore map is
built once, immutably, at construction — concurrency limiting is this
type's whole purpose, so it must tolerate the concurrent calls it exists to
bound.

### Module dependency change

`dataloom-core` did not previously depend on `kotlinx-coroutines-core` at
compile time (its existing `suspend fun`s use only `kotlin.coroutines`
stdlib types). Real cancellation-capable timeout enforcement needs
`withTimeoutOrNull`, and real concurrency limiting needs
`kotlinx.coroutines.sync.Semaphore`, both of which live in that artifact —
so `dataloom-core/build.gradle.kts` now declares
`implementation(libs.kotlinx.coroutines.core)`, the same dependency
`dataloom-runtime` already declares for the same purpose. This is an
external-library addition only; no new DataLoom module dependency was
added, and `dataloom-core`'s documented module-dependency rules
(`docs/architecture/modules.md`: may depend on `dataloom-model`,
`dataloom-provider-api`, `dataloom-plugin-api`, `dataloom-api`; must not
depend on `dataloom-runtime` or `dataloom-testing`) are unaffected.

## What remains open

Everything this slice does not cover remains exactly as
`plugin-platform-first-slice-investigation.md` described it, except
permission enforcement and execution-bounds enforcement (both now shipped,
see above):

- **Compatibility validation before activation** — re-checked directly
  against source this round (every `RuntimeVersion(...)` construction site
  repository-wide), not just assumed still accurate: still blocked on a
  canonical, parseable `RuntimeVersion` format decision. Current call sites
  still use `"1.0.0"`, `"runtime-1.0.0"`, `"1.2.3"`, and plain
  `"2.0.0"`/`"1.2.3"` inconsistently, confirming the same finding
  `plugin-platform-first-slice-investigation.md` and this page's earlier
  rounds already made.
- **Hook-point callback signatures and dispatch** — re-checked directly
  against source this round (a repository-wide search for `PluginHookPoint`):
  still referenced only inside `dataloom-plugin-api` itself and its own
  documentation, with zero adoption by any consuming subsystem (`#93`
  policy, `#95`, `#96`, the runtime pipeline). Still genuinely blocked,
  unchanged.
- **The certification kit** — its own unstarted design surface (what a
  repeatable certification kit emits as evidence). Authorized transitions
  and audit records — the other two items previously named alongside it —
  have both shipped; see
  [Authorized transitions ("authorized hot disable")](#authorized-transitions-authorized-hot-disable)
  and [Audit records (operational-event bridge)](#audit-records-operational-event-bridge)
  above.
- **Wiring `PluginExecutionBoundsEnforcer` to `PluginLifecycleStateTracker`**
  — see [What this does not do](#what-this-does-not-do-1) above: a genuine
  open design question (what happens to an in-flight invocation on a state
  transition away from `ACTIVE`), deliberately left unresolved until a real
  invocation call site exists to make it concrete.
- **A reference non-provider plugin** — demonstrating the full lifecycle
  end to end needs a real invocation call site (hook-point dispatch) to
  exist first; execution-bounds enforcement alone is not sufficient without
  something that actually calls a plugin.

## No wiring into `DataLoomBuilder` yet

`PluginRegistry`/`PluginLifecycleStateTracker`/`PluginExecutionBoundsEnforcer`/
`PluginLifecycleAdministrationAuthorizer`/
`PluginLifecycleAdministrationOperationalEventBridge` are not referenced
from `DataLoomBuilder` or any other composition root. There is still no
application-facing way to register a plugin with the DataLoom runtime —
these types are `#98`'s internal engine building blocks, verified in
isolation, not yet a public plugin-registration API. Wiring a public
registration surface is separate follow-up work, still likely gated on
hook-point dispatch existing first (execution-bounds enforcement alone has
nothing to wrap without a real invocation call site).

## Verification

- `dataloom-core:jvmTest` (`io.dataloom.core.plugin.*`): 88 tests, 0
  failures (`PluginRegistryTest`: 16, `PluginLifecycleTransitionsTest`: 17,
  `PluginLifecycleStateTrackerTest`: 24, `PluginPermissionEnforcementTest`: 2,
  `PluginExecutionBoundsEnforcerTest`: 10,
  `PluginLifecycleAdministrationTest`: 9,
  `PluginLifecycleAdministrationOperationalEventBridgeTest`: 10).
- `compileTestKotlinIosArm64`/`compileTestKotlinIosSimulatorArm64`/
  `compileTestKotlinIosX64` (`-Pdataloom.appleKlibCrossCompile=true`):
  independently re-verified clean on all three targets, including test
  sources — `checkKotlinAbi` alone does not compile test sources, a lesson
  from a real Kotlin/Native-only test-compilation failure found in this
  page's own prior round. That same lesson repeated live this round: three
  new test names originally contained a comma (Kotlin/Native's symbol
  mangling rejects punctuation the JVM test runner tolerates); caught by
  this exact iOS test-compile step and fixed before merge, not by
  `checkKotlinAbi` or the JVM test run, neither of which would have caught
  it.
- `checkKotlinAbi -Pdataloom.appleKlibCrossCompile=true`: additive-only
  baseline change to `dataloom-core`'s JVM `.api` and Kotlin/Native
  `.klib.api` baselines only (109/110 inserted lines respectively, zero
  deletions); no other module's baseline changed. `updateKotlinAbi` run and
  the diff reviewed (new `PluginLifecycleAdministration*`/
  `PluginLifecycleTransitionResult.AuthorizationDenied`/
  `PluginLifecycleAdministrationOperationalEventBridge` declarations and the
  new `PluginLifecycleStateTracker.transition(request, authorizer)`
  overload; nothing removed or changed).

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
- `io.dataloom.runtime.retry.TimeoutEnforcingSchedulerProvider`/
  `CoroutineRetryTimeoutExecutor` (`dataloom-runtime`) — the directly
  analogous, already-shipped precedent
  [Execution-bounds enforcement](#execution-bounds-enforcement) above
  follows for plugins instead of scheduler providers.
- `io.dataloom.api.retry.RetryAdministrationAuthorizer`,
  `io.dataloom.api.circuit.CircuitAdministrationAuthorizer`,
  `io.dataloom.api.conflict.ConflictAdministrationAuthorizer` (`dataloom-api`) —
  the host-supplied, deny-by-default authorizer shape
  [Authorized transitions ("authorized hot disable")](#authorized-transitions-authorized-hot-disable)
  above mechanically applies for plugin lifecycle transitions instead of
  retry/circuit/conflict administration commands.
- `io.dataloom.runtime.observation.operational.RetryCircuitAdministrationOperationalEventBridge`/
  `ConflictResolutionOperationalEventBridge` (`dataloom-runtime`) — the
  operational-event-bridge precedent
  [Audit records (operational-event bridge)](#audit-records-operational-event-bridge)
  above follows as its "sixth bridge."
- GitHub issue `#98` — DL-044 plugin platform implementation gate.
