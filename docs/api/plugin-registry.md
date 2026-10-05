# Plugin registry and lifecycle state tracking (`dataloom-plugin`)

[API reference index](./README.md)

## Status

**Available foundation — `#98`'s first real runtime component.** `dataloom-plugin-api`
([`plugin-api.md`](./plugin-api.md)) freezes the plugin manifest/identifier/
compatibility-range/dependency/execution-bounds/hook-point/lifecycle-label
*contract shapes* with zero behavior, by design — that module's own
`build.gradle.kts` says the loading/registration/enforcement/isolation
engine is `#98`'s job, built on top of those contracts. This page documents
that engine's growing slice: `io.dataloom.plugin.PluginRegistry`,
`PluginLifecycleTransitions`, `PluginLifecycleStateTracker`, and
`PluginExecutionBoundsEnforcer` (`dataloom-plugin`; originally shipped in `dataloom-core` and relocated on 2026-09-19, see below).

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

**Update (2026-09-09):** `DataLoomBuilder` wiring was investigated directly
against source this round (not left as a standing "still unwired" note) and
found blocked by a concrete, mechanically enforced module-ownership rule,
not merely undesigned — see
[No wiring into `DataLoomBuilder` yet](#no-wiring-into-dataloombuilder-yet)
below and
[`docs/api/plugin-platform-databuilder-wiring-investigation.md`](./plugin-platform-databuilder-wiring-investigation.md)
for the full analysis. No code changed.

**Update (2026-09-22, [ADR-0009](../adr/ADR-0009-plugin-version-and-dependency-gated-activation.md), D19):**
dependency version compatibility and dependency-gated activation — the last
item this page's own "What remains open" section named after 2026-09-20 —
have shipped. `PluginVersion` is now canonical Semantic Versioning, and every
`PluginLifecycleStateTracker.transition` overload refuses `VALIDATED`/`ACTIVE`
while a declared dependency is missing, out of its declared range, or
retired. See
[Dependency version compatibility and dependency-gated activation (D19)](#dependency-version-compatibility-and-dependency-gated-activation-d19)
below.

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
  — it is the engine module (then `dataloom-core`, now `dataloom-plugin`), which already depends on
  both `dataloom-plugin-api` (`PluginPermission`) and `dataloom-model`
  (`Capability`/`GrantedCapabilities`/`isAuthorized`) directly. No new module
  dependency was actually needed. See
  [Permission-grant enforcement](#permission-grant-enforcement) below.
- **Compatibility validation** was blocked on an undecided design
  question: `RuntimeVersion` (`dataloom-model`) was a plain non-blank
  `String` with no guaranteed semantic-version shape. That is now decided
  (strict Semantic Versioning, decision D12) and shipped; see
  [Compatibility validation](#compatibility-validation-against-the-running-sdk-version).
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
| `PluginRegistry` | `PluginRegistry.kt` | Immutable registry of `DataLoomPlugin` instances. Rejects duplicate `PluginId`s at construction; computes a deterministic, dependency-respecting `resolutionOrder` (dependencies before dependents, ties broken by registration order, unresolved dependency ids excluded); rejects dependency cycles among *registered* plugins (including self-dependency) with the full cycle path in the exception message. An unresolved dependency is registered without throwing — see [Dependency version compatibility and dependency-gated activation (D19)](#dependency-version-compatibility-and-dependency-gated-activation-d19). |
| `PluginLifecycleTransitions` | `PluginLifecycleTransition.kt` | Stateless object enforcing which `PluginLifecycleState` transitions are structurally legal, mirroring `PluginLifecycleState`'s own documented `LOADED → VALIDATED → INITIALIZING → ACTIVE ⇄ DEGRADED → DISABLED → UNLOADED` order plus explicit failure-escape edges to `DISABLED` from every pre-`ACTIVE` state. |
| `PluginLifecycleStateTracker` | `PluginLifecycleStateTracker.kt` | Tracks each plugin in a `PluginRegistry` through its `PluginLifecycleState`, starting every plugin at `LOADED` (never implicitly `ACTIVE`) and enforcing `PluginLifecycleTransitions`, SDK compatibility, and dependency gating on every `transition` call. Its capability-aware `transition` overload also enforces permission grants — see [Permission-grant enforcement](#permission-grant-enforcement) and [Dependency version compatibility and dependency-gated activation (D19)](#dependency-version-compatibility-and-dependency-gated-activation-d19). |
| `PluginPermission.asCapability()` | `PluginPermissionEnforcement.kt` | Extension function mapping a `PluginPermission` label onto a `Capability` of the same label, connecting `dataloom-plugin-api`'s permission contract to `dataloom-model`'s least-privilege primitive. |
| `PluginExecutionBoundsEnforcer` | `PluginExecutionBoundsEnforcement.kt` | Wraps an arbitrary `suspend () -> T` invocation of a registered plugin with coroutine-cancellation timeout enforcement (`maximumExecutionMillis`) and per-plugin concurrency limiting (`maximumConcurrentInvocations`), returning a non-throwing `PluginExecutionBoundsResult` (`Completed`/`TimedOut`/`ConcurrencyLimitExceeded`/`NotActive`/`Failed`; an ordinary exception thrown by the operation is contained as `Failed`). See [Execution-bounds enforcement](#execution-bounds-enforcement). |
| `PluginLifecycleAdministrationAuthorizer` / `PluginLifecycleTransitionRequest` | `PluginLifecycleAdministration.kt` | Host-owned, deny-by-default authorization boundary for *who* may request a `PluginLifecycleStateTracker.transition` call, consulted via the tracker's authorizer-aware `transition(request, authorizer)` overload. See [Authorized transitions ("authorized hot disable")](#authorized-transitions-authorized-hot-disable). |
| `PluginLifecycleAdministrationOperationalEventBridge` | `PluginLifecycleAdministrationOperationalEventBridge.kt` | Stateless mapping from a `PluginLifecycleTransitionRequest`/`PluginLifecycleTransitionResult` pair to a redacted `OperationalEventEnvelope`, for a caller to append into `DurableOperationalEventOutbox`. See [Audit records (operational-event bridge)](#audit-records-operational-event-bridge). |
| `PluginExecutionBoundsOperationalEventBridge` | `PluginExecutionBoundsOperationalEventBridge.kt` | The execution-bounds counterpart: maps each `PluginExecutionBoundsResult` (plus a caller-minted `PluginExecutionInvocationId`) to a redacted `OperationalEventEnvelope`, never reading the plugin's output. See [Execution outcome audit records and outbox wiring](#execution-outcome-audit-records-and-outbox-wiring). |

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
registered plugin's `PluginManifest.dependencies`, at the **graph-shape**
level only:

- The graph must be acyclic among *registered* plugins. A cycle (including a
  plugin depending on itself) throws `IllegalArgumentException` naming the
  full cycle path, e.g. `a -> b -> c -> a`. A cycle makes an ordering
  impossible, so it stays a construction-time rejection.
- `resolutionOrder` is a deterministic topological ordering: every plugin
  appears after every *registered* plugin it (directly or transitively)
  depends on. Plugins with no dependency relationship to each other are
  ordered by registration order, the same determinism rule `ProviderRegistry`
  applies to its own `providers` list.
- A `PluginDependency.pluginId` that names a plugin **not** registered in
  this registry is *not* a construction error (changed 2026-09-22,
  [ADR-0009](../adr/ADR-0009-plugin-version-and-dependency-gated-activation.md)).
  The dependent plugin is still registered, the missing id contributes
  nothing to `resolutionOrder`, and it is reported, non-throwing, when a
  transition is attempted — see the next section. Treating a missing
  dependency as a property of one plugin rather than a graph defect matches
  how an incompatible SDK range is already handled.

This validates the dependency graph's **shape** only: neither a dependency's
declared version range nor its lifecycle state is inspected here. Both are
checked by `PluginLifecycleStateTracker` at transition time — see
[Dependency version compatibility and dependency-gated activation (D19)](#dependency-version-compatibility-and-dependency-gated-activation-d19).

## Dependency version compatibility and dependency-gated activation (D19)

**Added 2026-09-22**, closing the item this page named as blocked since
2026-09-19: `PluginVersion` had no canonical parseable format, so a
dependency's declared range could not be compared against anything.
[ADR-0009](../adr/ADR-0009-plugin-version-and-dependency-gated-activation.md)
decided both the format and the gating behavior together.

### Canonical `PluginVersion` and `PluginVersionRange`

`PluginVersion` (`dataloom-plugin-api`) is now strict Semantic Versioning
2.0.0, exactly like `RuntimeVersion`: the constructor throws for a
non-canonical value, `PluginVersion.parse` is the non-throwing path
returning a typed `PluginVersionParseResult`/`PluginVersionParseFailure`, and
`precedenceCompareTo` implements semver precedence. It delegates its
grammar to `RuntimeVersion` internally (`dataloom-plugin-api` already depends
on `dataloom-model`) rather than duplicating the parser, but exposes no
`RuntimeVersion` type in its own API. New `PluginVersionRange` is the
plugin-to-plugin counterpart of `PluginCompatibilityRange` (inclusive
`minimum`/optional `maximum`, kept as a distinct type since the two bound
different axes with different depended-upon types).
`PluginDependency.compatibilityRange: PluginCompatibilityRange` is renamed
and retyped to `supportedVersionRange: PluginVersionRange`.

### The gate

Every `PluginLifecycleStateTracker.transition` overload additionally refuses
to move a plugin into `VALIDATED` or `ACTIVE` (including the
`DEGRADED -> ACTIVE` recovery edge) while one of its declared
`PluginDependency` entries is unsatisfied, returning the new
`PluginLifecycleTransitionResult.DependencyUnsatisfied` and leaving state
unchanged. A dependency blocks the transition when:

| Reason (`PluginDependencyIssueReason`) | Meaning |
|---|---|
| `NOT_REGISTERED` | The depended-upon plugin is not registered in this registry. |
| `EMPTY_VERSION_RANGE` | The declared range's minimum is above its maximum: no version could ever satisfy it. |
| `VERSION_BELOW_MINIMUM` / `VERSION_ABOVE_MAXIMUM` | The depended-upon plugin's actual `PluginManifest.version` falls outside the declared `PluginVersionRange`, compared by the same inclusive, semver-precedence logic `PluginCompatibilityValidator` already applies to the SDK check (one shared internal helper, so the two checks cannot disagree). |
| `DISABLED` / `UNLOADED` | The depended-upon plugin is retired. |
| `NOT_ACTIVE` | (Only checked when entering `ACTIVE`.) The depended-upon plugin is registered, in range, and not retired, but is not itself `ACTIVE` yet. |

`DependencyUnsatisfied.issues` lists every blocking dependency (not only the
first), ordered by dependency id then reason name for determinism, and
carries only stable `PluginId`s and closed `PluginDependencyIssueReason`
values — never free text.

**`VALIDATED` is more permissive than `ACTIVE`:** entering `VALIDATED` only
needs a dependency to be present, in range, and not retired — it may itself
still be `LOADED`. This lets a host validate an entire plugin set before
activating any of it. Entering `ACTIVE` additionally requires the dependency
to itself be `ACTIVE`. Entering `DISABLED` (or any other target) is never
gated on dependencies, so a plugin can always be stopped.

**Check order** is structural legality, then SDK compatibility
(see [Compatibility validation against the running SDK version](#compatibility-validation-against-the-running-sdk-version)
below), then dependencies, then — for the overloads that have one — the
permission or authorizer check. Each earlier check short-circuits the ones
after it, so an incompatible or structurally illegal plugin never has its
dependencies inspected at all.

**Transitive chains are covered without a separate walk.** Only a plugin's
own declared dependencies are read, at the moment of the transition. A
dependency can only itself be `ACTIVE` if its own gate already passed, so
requiring "the dependency is `ACTIVE`" carries the check down a chain of any
length automatically.

**No cascading.** A dependency that becomes `DISABLED` after its dependent
is already `ACTIVE` is not retroactively degraded or disabled — the same
conservative default D13 already chose for in-flight invocations on a
state change. This is a deliberate deferral, not a silent gap.

The lifecycle audit bridge (see
[Audit records (operational-event bridge)](#audit-records-operational-event-bridge))
bridges `DependencyUnsatisfied` like every other outcome: the count of
unsatisfied dependencies and the distinct closed reason names are `PUBLIC`;
the specific `(dependency id, reason)` pairs are one `INTERNAL` attribute.

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

New `io.dataloom.plugin.PluginLifecycleAdministrationOperationalEventBridge`
is the mechanical "sixth bridge" that precedent predicted: a stateless
`toEnvelope(request: PluginLifecycleTransitionRequest, result:
PluginLifecycleTransitionResult): OperationalEventEnvelope` covering every
outcome the authorizer-aware overload (and, for `PermissionDenied`, the
capability-aware overload) can produce — `Allowed`, `Rejected`,
`PermissionDenied`, and `AuthorizationDenied` — not only denials, following
the precedent's own "bridge every terminal outcome" convention.

Unlike the retry/circuit/conflict precedents, this bridge lives in
the engine module (`dataloom-plugin`) itself rather than `dataloom-runtime`: the request and
result types it bridges from (`PluginLifecycleTransitionRequest`,
`PluginLifecycleTransitionResult`) already live in this module, and
`OperationalEventEnvelope` lives in `dataloom-api`, which the engine module
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

### Wiring

**Update (2026-09-20):** the "no caller yet" note that used to sit here is
resolved. `DataLoom.pluginEngine`'s transition path calls `toEnvelope` and
appends to `DurableOperationalEventOutbox` when the application supplies
`DataLoomBuilder.pluginOperationalEventOutboxConfiguration`; see
[Execution outcome audit records and outbox wiring](#execution-outcome-audit-records-and-outbox-wiring).

## Execution-bounds enforcement

`io.dataloom.plugin.PluginExecutionBoundsEnforcer` wraps an arbitrary
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

### Failure isolation

An ordinary `Exception` thrown by the operation is caught and returned as
`PluginExecutionBoundsResult.Failed(pluginId, cause)` instead of unwinding
through the caller (previously it propagated uncaught). The concurrency slot
is released as for every other outcome, so a plugin that always throws cannot
leak slots, and no failure reaches the host's call stack or another plugin's
invocation. A hung operation is already bounded by the timeout, so hung and
throwing plugins are both contained, per plugin.

Deliberate limits:

- A `kotlin.Error` (out of memory, stack overflow, assertion failure) still
  propagates: it signals a broken process, not a recoverable plugin failure.
- A `CancellationException` is contained as `Failed` only while the caller's
  coroutine is still active (a plugin that leaks an inner `withTimeout`, or
  throws one explicitly, is plugin behaviour). When the caller itself has been
  cancelled, it propagates unchanged so structured cancellation keeps working.
- `Failed.cause` is returned to the caller but never read by the audit bridge:
  a failure is recorded as event type `dataloom.plugin.execution.bounds.failed`
  next to the redacted plugin id, with no message and no exception type.
- On the JVM, coroutine stack-trace recovery may return a copy of the thrown
  exception (same type and message), so compare `cause` by type and message,
  not identity.
- On its own a failure never changes lifecycle state; repeated failures do only
  under the opt-in circuit below.

### Failure circuit (opt-in automatic degradation)

`PluginExecutionBoundsEnforcer(lifecycle, failureCircuit)` takes an optional
`PluginFailureCircuitPolicy(consecutiveFailureThreshold)` (default threshold
`DEFAULT_CONSECUTIVE_FAILURE_THRESHOLD` = 5; must be positive). With no policy,
which is the default, nothing below runs and behavior is unchanged. In the
facade it is `DataLoomPluginSpec.failureCircuit`.

| Result | Effect on the per-plugin consecutive-failure counter |
| --- | --- |
| `Failed`, `TimedOut` | +1 (a plugin fault) |
| `Completed` | reset to 0 |
| `ConcurrencyLimitExceeded`, `NotActive` | none: back-pressure and gating, the operation never ran |
| caller cancellation | none: it unwinds, no result |

When the count reaches the threshold the counter is reset and the plugin moves
`ACTIVE` -> `DEGRADED`, which already refuses new invocations. The result that
tripped it carries `degradedPlugin = true` (exactly one per trip).

- **Concurrency.** The counter is an atomic per plugin. The transition is
  `PluginLifecycleStateTracker.degradeIfActive(id)`, a compare-and-set on the
  tracker's (now atomic) state cell: if a manual transition, or another
  invocation's failure, already changed the state it is a no-op and never
  overwrites the newer state. This is what lets the concurrent `execute` path
  coexist with the caller-serialized `transition`. A failure that finishes
  while the plugin is no longer `ACTIVE` (in-flight work draining) is not
  counted. Being lock-free, the count can be off by at most the number of
  invocations in flight at the moment of a trip.
- **Recovery is manual only.** Return to `ACTIVE` is the existing authorized
  `transition` (its permission and dependency checks re-run). There is no
  time-based or probing auto-recovery; the counter is reset at trip time so a
  recovered plugin starts afresh.
- **No authorizer for the automatic step.** It is a system fault response that
  only removes capability; every path that adds capability stays authorized.
- **Audit.** The facade records an extra event of type
  `dataloom.plugin.failure.circuit.degraded` (via
  `PluginExecutionBoundsOperationalEventBridge.toDegradedEnvelope`) after the
  invocation's own `failed`/`timed_out` event, correlated to it by invocation
  id, with only the redacted plugin id: no count, threshold or cause.

### What this does not do

- **Does not check `PluginLifecycleState` (superseded 2026-09-19).** This
  page originally shipped the enforcer independent of
  `PluginLifecycleStateTracker`, treating "what happens to an in-flight
  invocation when its plugin's state changes" as an open design question.
  Decision D13 resolved it: the enforcer is now bound to the tracker, refuses
  new invocations unless the plugin is `ACTIVE`, and lets in-flight invocations
  drain. See [Lifecycle gating of execution (D13)](#lifecycle-gating-of-execution-d13).
- **Does not count failures or move a plugin to `DEGRADED` unless a
  `PluginFailureCircuitPolicy` is supplied** (ordinary exceptions are contained,
  see [Failure isolation](#failure-isolation); the opt-in circuit is described
  in [Failure circuit](#failure-circuit-opt-in-automatic-degradation)). With no
  policy each failure is reported once, to its own caller. There is no
  time-based auto-recovery.
- **Does not audit timeout or concurrency-rejection events (superseded
  2026-09-20).** `PluginExecutionBoundsOperationalEventBridge` now bridges
  every `PluginExecutionBoundsResult`; see
  [Execution outcome audit records and outbox wiring](#execution-outcome-audit-records-and-outbox-wiring).

### Thread-safety

Unlike `PluginLifecycleStateTracker` (which requires callers to serialize
`transition` calls), `execute` is safe to call concurrently, for the same or
different plugin IDs: `kotlinx.coroutines.sync.Semaphore` is itself safe
under concurrent `tryAcquire`/`release`, and the per-plugin semaphore map is
built once, immutably, at construction — concurrency limiting is this
type's whole purpose, so it must tolerate the concurrent calls it exists to
bound.

### Module dependency change

When this slice shipped (in `dataloom-core`), real cancellation-capable
timeout enforcement needed `withTimeoutOrNull` and real concurrency limiting
needed `kotlinx.coroutines.sync.Semaphore`, both in
`kotlinx-coroutines-core`, so `dataloom-core` gained
`implementation(libs.kotlinx.coroutines.core)`. That dependency (and
`dataloom-core`'s dependency on `dataloom-plugin-api`) existed only for this
engine and was removed from `dataloom-core` when the engine moved; the same
declarations now live in `dataloom-plugin/build.gradle.kts`.

## What remains open

Everything this slice does not cover remains as
`plugin-platform-first-slice-investigation.md` described it, except
permission enforcement, execution-bounds enforcement, compatibility
validation against the running SDK, tracker/enforcer lifecycle gating, and
dependency version compatibility/dependency-gated activation (all now
shipped, see above):

- **Hook-point callback signatures and dispatch** — a repository-wide search
  for `PluginHookPoint` still finds it referenced only inside
  `dataloom-plugin-api` itself and its own documentation, with zero adoption
  by any consuming subsystem (`#93` policy, `#95`, `#96`, the runtime
  pipeline). Still genuinely blocked, unchanged.
- **The certification kit** — its own unstarted design surface (what a
  repeatable certification kit emits as evidence).
- **Failure isolation/bulkheading** is now complete apart from automatic
  recovery: concurrency bulkhead, timeout, containment of thrown exceptions,
  and the opt-in failure circuit have shipped (see
  [Failure isolation](#failure-isolation) and
  [Failure circuit](#failure-circuit-opt-in-automatic-degradation)). Only
  time-based or probing recovery of a degraded plugin (today it is a manual,
  authorized transition) and a facade/health view of circuit state remain.
- **A reference non-provider plugin** — demonstrating the full lifecycle
  end to end needs a real invocation call site (hook-point dispatch) to
  exist first.

## Compatibility validation against the running SDK version

**Update (2026-09-19, decisions D12 and D13).**

### Canonical `RuntimeVersion` (D12)

`RuntimeVersion` (`dataloom-model`) is now strictly Semantic Versioning
2.0.0: `MAJOR.MINOR.PATCH` with optional `-PRERELEASE` and `+BUILD`, no
leading `v`, no label prefix, no leading zeros, core components within
`Int`, at most 128 characters. Its constructor throws
`IllegalArgumentException` for anything else, like every identifier type.
`RuntimeVersion.parse(input)` is the non-throwing path for untrusted input and
returns `RuntimeVersionParseResult.Parsed` or `Invalid(RuntimeVersionParseFailure)`,
never echoing the input. `precedenceCompareTo` implements semver precedence
(build metadata ignored, pre-release below its release); the type
deliberately is not `Comparable` because two versions differing only in build
metadata are unequal but tie on precedence.

Every ad-hoc call site was migrated: the three `"runtime-1.0.0"` uses (two
tests and their assertions, plus the identifier contract test) became
`"1.0.0"`/`"1.1.0"`, and the two docs that showed the old spelling were
corrected. `"1.0.0"`, `"1.2.3"`, and `"2.0.0"` were already canonical. The
stored-value decoders (`dataloom-queue-room` and the Apple queue codec)
construct `RuntimeVersion` from persisted strings and therefore now throw for
a non-canonical stored value; that cannot occur for values written by a
canonical `RuntimeVersion`, and nothing pre-V1 is published.

### The running SDK version

No version source existed anywhere (no Gradle project version, no generated
build-config). The least invasive single source is a constant in the module
that is "the running SDK": `DataLoomRuntimeVersion.CURRENT` in
`dataloom-runtime`, currently `0.1.0`. It is hand-maintained, is a
pre-release development value, and must be set to the real release version by
the release process (DL-046) before publication. Consequence: a plugin
declaring a minimum of `1.0.0` is correctly incompatible with a `0.x` SDK.

### Compatibility check

`PluginCompatibilityValidator.validate(range, sdkVersion)` is pure and
returns `PluginCompatibilityResult.Compatible` or
`Incompatible(sdkVersion, range, reason)`, never throwing. Both bounds are
inclusive and compared by semver precedence; an absent maximum is unbounded;
a minimum above the maximum is `EMPTY_RANGE` rather than a bound violation.

`PluginLifecycleStateTracker` now takes the running `sdkVersion` (required, so
there is no unchecked mode) and gates every `transition` overload on it:
entering `VALIDATED` for a plugin whose range does not admit the SDK returns
the new `PluginLifecycleTransitionResult.IncompatibleRuntime`, leaves state
unchanged, and (for the authorizer-aware overload) never consults the
authorizer. `VALIDATED` is the only route to `INITIALIZING`/`ACTIVE`, so an
incompatible plugin can never become active; it can still be `DISABLED`.
`compatibilityOf(id)` inspects without transitioning. The operational-event
bridge maps the new variant (event type `...incompatible_runtime`; the SDK
version and reason are public, plugin-declared bounds are `INTERNAL` and
redacted).

`DataLoomBuilder.pluginConfiguration` uses `DataLoomRuntimeVersion.CURRENT`;
`build()` does not reject an incompatible plugin, and `DataLoom.pluginEngine`
exposes `compatibilityOf(id)` and returns `IncompatibleRuntime` from
`transition`.

## Lifecycle gating of execution (D13)

`PluginExecutionBoundsEnforcer` is now constructed from a
`PluginLifecycleStateTracker` (it takes the tracker's registry) and consults
the tracker's state once, at the start of each `execute`:

- A plugin that is not `ACTIVE` (`LOADED`, `VALIDATED`, `INITIALIZING`,
  `DEGRADED`, `DISABLED`) returns the new
  `PluginExecutionBoundsResult.NotActive(pluginId, state)`. The operation never
  runs and no concurrency slot is consumed. The state check precedes the
  concurrency check.
- An invocation already in flight when its plugin leaves `ACTIVE` is not
  cancelled or shortened. It completes, or is cancelled by its own declared
  timeout (`TimedOut`) or by caller cancellation, and releases its
  concurrency slot on finishing, so a disabled plugin drains. Actively
  cancelling in-flight work on disable would be a separate, new policy.
- `DEGRADED` refuses new invocations until the plugin is `ACTIVE` again.

To make the enforcer's cross-thread state read safe, the tracker now stores
each plugin's state in a volatile cell over a fixed set of tracked plugins.
`stateOf` and `compatibilityOf` are safe concurrently with a transition;
`transition` calls still must be serialized by the caller.

## Execution outcome audit records and outbox wiring

**Update (2026-09-20).**

### `PluginExecutionBoundsOperationalEventBridge`

The execution-bounds counterpart of
`PluginLifecycleAdministrationOperationalEventBridge`, with the same shape: a
stateless `toEnvelope(pluginId, invocationId, result, occurredAt)` in
`dataloom-plugin`. Every `PluginExecutionBoundsResult` variant has its own
event type, all category `AUDIT`, source `dataloom.plugin.execution.bounds`:

| Result | Event type suffix | Attributes |
|---|---|---|
| `Completed` | `completed` | `request.pluginId` |
| `TimedOut` | `timed_out` | plus `result.maximumExecutionMillis` |
| `ConcurrencyLimitExceeded` | `concurrency_limit_exceeded` | plus `result.maximumConcurrentInvocations` |
| `NotActive` | `not_active` | plus `result.state` |

(Event types are prefixed `dataloom.plugin.execution.bounds.`.) Redaction
follows the lifecycle bridge: the plugin id is `INTERNAL` (so redacted), the
numeric bounds and the observed lifecycle state are `PUBLIC`, every attribute
passes through `ClassifiedData` and `StrictDataLoomRedactor`. `Completed.value`
is never read, and an exception thrown by an operation is not a result at all,
so plugin output and exception messages cannot reach an envelope. Only stable
ids and closed values are recorded.

Identity: the envelope id is `plugin.execution.<invocation id>` (sanitized and
capped at 128 characters), the correlation id reuses the invocation id, and
`occurredAt` is supplied by the caller. A bounded invocation has no natural
unique id, so the new `PluginExecutionInvocationId` value class is minted by
the caller. The lifecycle bridge is unchanged apart from its documentation.

### Builder wiring

`DataLoomBuilder.pluginOperationalEventOutboxConfiguration(DataLoomPluginOperationalEventOutboxSpec)`
follows the existing `*OperationalEventOutboxSpec` family (a store, a scope
defaulting to `plugin-events`, schema version, and attempt bound; the builder
supplies the runtime clock and the optional outbox health tracker). It is
opt-in and inert when absent: with no spec, or with the spec but no
`pluginConfiguration`, nothing is constructed, appended, or clocked.

With both configured, `DataLoom.pluginEngine` appends after each result exists:

- every `transition` result (`Allowed`, `Rejected`, `PermissionDenied`,
  `AuthorizationDenied`, `IncompatibleRuntime`, `DependencyUnsatisfied`),
  keyed by its command id;
- every `execute` result, keyed by an id the engine mints from the plugin id,
  the runtime clock's millisecond reading, and an in-process counter (guarded
  by a mutex, since `execute` is concurrent).

Append failures and envelope-construction failures are swallowed and never
change or break the returned result; only cancellation propagates. A call that
throws (an unregistered plugin id, a `kotlin.Error`, or caller cancellation)
records nothing; an ordinary exception from the operation is a `Failed` result
and is recorded as `dataloom.plugin.execution.bounds.failed`. Both kinds of event share one scope, so the outbox
assigns them consecutive per-key sequence numbers in append order (envelopes
carry no workflow id, so they use the global ordering key).

Known limits: reusing a lifecycle command id is idempotent (a differing
outcome for the reused id is dropped by the outbox as a conflict); an
invocation id could in principle collide across a restart only for the same
plugin, millisecond, and counter value, costing one dropped audit record; and
every completed invocation is recorded, which is high-volume for a busy
plugin, so bound it with the outbox's own retention and acknowledgement.

## Relocation to `dataloom-plugin` and `DataLoomBuilder` wiring

**Update (2026-09-19):** the module-ownership question the
[wiring investigation](./plugin-platform-databuilder-wiring-investigation.md)
left open is decided: the whole engine is relocated out of `dataloom-core`
into the new published module `dataloom-plugin`
([ADR-0003](../adr/ADR-0003-plugin-engine-module.md)), and wired into
`DataLoomBuilder` as an opt-in capability.

Before this change, `dataloom-runtime`'s `checkPublicAbiBoundaries` task
failed on any public exposure of an `io.dataloom.core.plugin.*` type, and every
result/request/decision type the engine returned lived in that namespace.
Everything now lives in `io.dataloom.plugin` (`dataloom-plugin`), unchanged
apart from the package, and the runtime exposes the engine's own types with no
translation layer.

What the wiring provides:

- `DataLoomBuilder.pluginConfiguration(DataLoomPluginSpec)`: opt-in. The spec
  carries the plugins to register and a host-supplied
  `PluginLifecycleAdministrationAuthorizer` (required; there is no
  authorizer-free path). Like every other `*Configuration`/`*Spec` opt-in, the
  spec is stored unvalidated and the collaborators are built in `build()`.
- `DataLoom.pluginEngine: DataLoomPluginEngine?`: `null` when
  `pluginConfiguration` was never called, so `DataLoom` behaves as before.
  When present it offers `resolutionOrder`, `stateOf(id)`, an authorizer-gated
  `transition(request)`, and a bounds-enforced `execute(id, operation)`. It does
  not expose the registry, tracker, enforcer, or authorizer.
- `build()` constructs the registry, tracker, and enforcer eagerly. An invalid
  plugin graph (duplicate id, unresolved dependency, cycle) throws the
  registry's `IllegalArgumentException` from `build()`, the same behavior as
  duplicate provider ids. Every plugin starts in `LOADED`; nothing is activated
  or invoked, and `build()` does no clock read, I/O, or coroutine launch.

What the wiring deliberately does not do:

- The capability-aware `transition(id, target, grantedCapabilities)` overload
  (permission enforcement on entry to `ACTIVE`) is not exposed; the facade
  offers only the authorizer-gated path.
- Audit recording is a separate opt-in
  (`pluginOperationalEventOutboxConfiguration`); see
  [Execution outcome audit records and outbox wiring](#execution-outcome-audit-records-and-outbox-wiring).
- No subsystem dispatches hook points to plugins. (The tracker and enforcer
  were integrated afterwards; see
  [Lifecycle gating of execution (D13)](#lifecycle-gating-of-execution-d13).)

## Verification

### Canonical `PluginVersion` and dependency-gated activation (2026-09-22)

Run on a Windows host with `-Pdataloom.appleKlibCrossCompile=true`:

- `dataloom-plugin-api:jvmTest`: 33 tests, 0 failures (16 existing plus 17 new
  `PluginVersionTest` tests: parsing, every rejection failure, semver
  precedence ordering).
- `dataloom-plugin:jvmTest`: 156 tests, 0 failures (137 existing, with two
  `PluginRegistryTest` cases updated for the unresolved-dependency behavior
  change, plus 19 new `PluginDependencyGatingTest` tests).
- `dataloom-runtime:jvmTest`: 2016 tests, 0 failures, including the new
  `DataLoomBuilderPluginDependencyGatingTest` (end to end through the real
  builder and the production `DataLoomRuntimeVersion.CURRENT`, including
  outbox wiring) and updated fixtures in `DataLoomBuilderPluginEngineTest`.
- `compileKotlinIos*`/`compileTestKotlinIos*` for all three iOS targets in
  `dataloom-plugin-api`, `dataloom-plugin`, and `dataloom-runtime`: clean.
- `checkKotlinAbi -Pdataloom.appleKlibCrossCompile=true`, module and
  whole-build scope, in both the normal and `DATALOOM_ANDROID_BUILD=true`
  baseline layouts: passes against regenerated baselines. `updateKotlinAbi`
  diff reviewed: `dataloom-plugin-api` gains `PluginVersion`,
  `PluginVersionParseResult`, `PluginVersionParseFailure`,
  `PluginVersionRange` and loses the old non-blank-only `PluginVersion`;
  `PluginDependency`'s field is renamed/retyped; `dataloom-plugin` gains
  `PluginDependencyIssueReason`, `PluginDependencyIssue`, and
  `PluginLifecycleTransitionResult.DependencyUnsatisfied`, and the
  audit-bridge event-type list gains `dependency_unsatisfied`;
  `dataloom-runtime`'s public surface is unchanged (no baseline diff).
- Not verified here: XCFramework assembly and Simulator execution (both need
  macOS CI).

### Relocation and wiring (2026-09-19)

Run on a Windows host with `-Pdataloom.appleKlibCrossCompile=true`:

- `dataloom-plugin:jvmTest`: 88 tests, 0 failures (the same seven test
  classes that were in `dataloom-core`, moved unchanged apart from package).
  `dataloom-core:jvmTest`: 133 tests, 0 failures.
- `dataloom-runtime:jvmTest`: 1840 tests, 0 failures, including 17 new tests in
  `DataLoomBuilderPluginEngineTest` (absence is inert, builder wiring,
  invalid-graph rejection, every transition and execution result variant
  returned unchanged).
- `compileKotlinIos*` and `compileTestKotlinIos*` for all three iOS targets in
  `dataloom-plugin`, `dataloom-core`, and `dataloom-runtime`;
  `compileKotlinIos*` in `runtime-external-consumer` and `dataloom-apple`: clean.
- `runtime-external-consumer:checkRuntimeExternalConsumer`,
  `dataloom-runtime:checkPublicAbiBoundaries`, and
  `dataloom-runtime:checkResolvedDependencyBoundaries`: pass.
- Whole-build `checkKotlinAbi`: passes. `updateKotlinAbi` diff reviewed:
  `dataloom-core` loses only plugin declarations (226 JVM and 248 klib lines
  removed, none added); `dataloom-plugin` has a new baseline; `dataloom-runtime`
  adds `DataLoom.pluginEngine`, `DataLoomBuilder.pluginConfiguration`,
  `DataLoomPluginSpec`, and `DataLoomPluginEngine` only (17 JVM and 23 klib
  lines, no deletions).
- Not verified here: XCFramework assembly with the new Apple exports, and any
  Simulator execution (both need macOS CI).

### Before the relocation (engine in `dataloom-core`, through 2026-09-08)

- `dataloom-core:jvmTest` (`io.dataloom.core.plugin.*`, before the relocation): 88 tests, 0
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
- [ADR-0009](../adr/ADR-0009-plugin-version-and-dependency-gated-activation.md) —
  canonical `PluginVersion` and dependency-gated activation (D19), decided
  and implemented in the same change.
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
