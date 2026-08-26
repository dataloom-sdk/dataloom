# Conflict-resolver policy precedence: investigated, no bounded slice yet

## Question

The `#95` market-readiness row names "policy precedence (entity > workflow >
tenant > global)" as a still-open gap, and
[`second-conflict-resolver-investigation.md`](./second-conflict-resolver-investigation.md)
(round 15 of this session) confirmed that
`ConflictResolverRegistry.lookup`
(`dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/conflict/ConflictResolverRegistry.kt`)
selects only by exact `ConflictResolverId`, with no per-conflict-type or
per-entity-type routing layer.

This investigation checked whether a genuinely bounded first slice of that
precedence contract — even the single narrowest tier, entity-type-scoped
selection alone — is achievable now, before attempting to build anything.

## Method

Read in full: `ConflictResolverRegistry`, `ConflictResolver`,
`SynchronizationConflict`, `EntityReference`, `ConflictResolutionRequest`,
`ConflictDetectionRequest`, `SynchronizationRequest`, `ExecutionContext`,
`DataLoomMetadata`, `SynchronizationConflictOrchestrator`,
`ConflictOrchestrationBindings`, `ConflictOrchestrationRequest`, and
`DataLoomConflictDetectionSpec` (the application-facing builder configuration
that wires all of the above together in `DataLoomBuilder.build()`).

## Finding 1: what identity information actually reaches conflict resolution

Contrary to the possibility this task flagged as worth checking — that
workflow/tenant identifiers might not reach conflict resolution at all —
`SynchronizationRequest` (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/model/SynchronizationRequest.kt`)
carries a **required** `workflowId: WorkflowId` and a **required**
`context: ExecutionContext`, and `ExecutionContext`
(`dataloom-api/src/commonMain/kotlin/io/dataloom/api/context/ExecutionContext.kt`)
carries an **optional** `tenantId: TenantId?`. Both `ConflictDetectionRequest`
and `ConflictResolutionRequest` carry the originating `synchronizationRequest`
in full, and `SynchronizationConflict.entity` (`EntityType`/`EntityId`) is
always present on a detected conflict. So by the time a resolver's `resolve()`
method actually runs, all four signals named in "entity > workflow > tenant >
global" are structurally reachable in principle:

| Signal | Reachable? | Source | Reliability |
|---|---|---|---|
| Entity type/ID | Yes | `SynchronizationConflict.entity` | Always present (non-null, validated at construction). |
| Workflow ID | Yes | `ConflictResolutionRequest.synchronizationRequest.workflowId` | Always present (required field, no default). |
| Tenant ID | Yes, when supplied | `ConflictResolutionRequest.synchronizationRequest.context.tenantId` | Optional (`TenantId?`); genuinely absent for any host that does not populate it, e.g. non-multi-tenant deployments. |
| Global | N/A | — | The existing unconditional fallback. |

This means the "tenant identifiers may not exist yet" hypothesis is only
half right: tenant is genuinely optional and cannot be relied on
unconditionally, but workflow is a required field and entity is
constructor-validated — both are reliable today. This investigation does not
confirm the premise that workflow/tenant identifiers are structurally
missing; the real gap is elsewhere (Finding 2).

## Finding 2: the identity information does not reach the *selection* step — and that boundary is deliberate, not accidental

The signals above reach a resolver's `resolve()` body — a single custom
`ConflictResolver` implementation could already branch on
`request.conflict.entity.type` or `request.synchronizationRequest.workflowId`
internally today. What they do **not** reach is the step that decides
**which** `ConflictResolver.resolve()` gets called in the first place. That
selection step is `ConflictResolverRegistry.lookup(id: ConflictResolverId)`,
called from `SynchronizationConflictOrchestrator.detectAndResolve` as:

```kotlin
val resolver = resolverRegistry.lookup(resolverId)
```

where `resolverId` comes from `ConflictOrchestrationBindings.resolverId` — a
single, static, optional `ConflictResolverId` supplied once, ahead of time,
for the whole orchestration invocation. No conflict, entity, workflow, or
tenant value is available to, or used by, this call.

This is not simply an unfilled slot; it is a documented, repeatedly-stated
architectural invariant across three separate components:

1. **`ConflictResolverRegistry`**'s own KDoc, "Selection key" section:
   > The explicit `ConflictResolverId` returned by `ConflictResolver.id` is
   > the only selection key. Resolvers are never selected automatically by
   > conflict type, class name, registration position, or ID sorting.
   > Resolution policy remains caller-controlled through explicit binding.

2. **`SynchronizationConflictOrchestrator`**'s KDoc "Required flow" section
   states the exact-ID lookup as a numbered, strict step (step 8): "Look up
   the resolver by exact `ConflictResolverId`" — not "by entity type, then
   exact ID."

3. **`DataLoomConflictDetectionSpec`**, the application-facing builder
   configuration surface that is the only real way any host application
   reaches this machinery today, states explicitly:
   > `[bindings]`: which detector (required) and resolver (optional) to use
   > for every detection call. **One binding applies to the whole pipeline
   > instance — there is no per-entity-type binding**, matching
   > `InboundPullConflictDetectionConfiguration`'s own documented scope.

All three statements predate this investigation and are consistent with each
other. This is a deliberately chosen, explicit design boundary, not an
oversight left to be silently completed — unlike `#352`/`#353`'s wiring gaps,
where a real producer and a real (unused) consumer already existed on each
end of the connection.

## Finding 3: no real caller supplies or would consume per-entity-type resolver preferences

Searched every construction site of `ConflictResolverRegistry` and
`ConflictOrchestrationBindings` in the repository (`DataLoomBuilder.build()`
is the only production call site; test files construct these types directly
for unit coverage). None supplies, or has any surrounding shape suggesting an
intent to supply, more than one resolver ID per pipeline instance. There is no
half-built precedence table, no unused per-entity-type parameter, and no
resolver implementation anywhere that already branches on entity type,
workflow ID, or tenant ID internally. Unlike the digest/HMAC/secure-random
investigation's illustrative-but-real consumer pairs, or `#352`/`#353`'s
already-existing unused producer and consumer, there is nothing today to
connect — a precedence layer here would be new capability from a blank slate,
not a missing wire between two already-real ends.

## Why even the narrowest (entity-type-only) tier is not a bounded slice today

Building only the first, narrowest tier — "prefer an entity-type-specific
resolver over the caller's exact-ID default, otherwise fall back to today's
behavior unchanged" — was evaluated concretely. Even bounded to
backward-compatible, opt-in, additive changes (existing single-arg
`ConflictResolverRegistry` constructor and `lookup(id)` signature preserved,
new optional entity-type map defaulting to empty, unconfigured behavior
byte-for-byte unchanged), it still requires:

- Adding a `lookup` overload (or a second parameter) that accepts
  `EntityType`, which the orchestrator's step-8 call site would need to
  start passing — meaning
  **`SynchronizationConflictOrchestrator`'s own documented, numbered flow
  changes from "look up the resolver by exact `ConflictResolverId`" to "look
  up the resolver by entity type, falling back to exact `ConflictResolverId`
  ,"** contradicting its own current KDoc contract (Finding 2, item 2).
- Reversing `ConflictResolverRegistry`'s explicit "the only selection key...
  never selected automatically by conflict type" statement (Finding 2, item
  1) — the new behavior is exactly the automatic-by-conflict-type selection
  that sentence currently disclaims. The KDoc would need a substantive
  rewrite, not an addition.
- Reversing, or at minimum directly contradicting without updating,
  `DataLoomConflictDetectionSpec`'s explicit "there is no per-entity-type
  binding" statement (Finding 2, item 3) — the only real application-facing
  path to configure this at all. Updating that spec's shape (to let an
  application supply an entity-type-to-resolver-ID map) is itself additive
  API surface on a public builder-configuration class, and its sibling
  `InboundPullConflictDetectionConfiguration`, which the same doc says
  matches its scope, would need the identical decision made consistently to
  avoid two contradictory statements existing side by side in the same
  codebase.

None of this is large in raw line count, but all three changes are reversals
of currently-true, explicitly-documented statements about how this
subsystem intentionally does not behave — spanning three components — with
no existing caller anywhere to migrate onto and validate the new shape
against. That is a genuine design decision (what the precedence-configuration
shape should look like on `DataLoomConflictDetectionSpec`, whether entity-type
mapping lives on the registry or the bindings, how ties or malformed
entity-type keys are diagnosed) rather than a mechanical wiring task, and per
this task's own explicit authorization, is not something this investigation
should decide unilaterally.

## What was not done, and why

No code was changed. No new `lookup` overload, entity-type map, or
`ConflictResolverRegistry`/`SynchronizationConflictOrchestrator`/
`DataLoomConflictDetectionSpec` signature change was implemented. Forcing the
narrowest tier in without a real caller to validate it against, and without
resolving which of the three documented invariants above gets rewritten and
how, would produce API surface nobody has asked for yet and that this
investigation cannot honestly justify as "bounded."

Workflow- and tenant-tier precedence remain further out than the entity tier:
workflow ID is reliably present (Finding 1) so a workflow tier is at least as
technically reachable as the entity tier once a precedence layer exists at
all, but tenant ID is genuinely optional and not guaranteed by every host
today, so a tenant tier's fallback behavior when `tenantId` is absent is an
additional open design question beyond what this investigation resolves.

## Postscript (2026-08-26): `#367`'s manual administration does not close this gap

`#367` shipped `ConflictAdministrationCoordinator`
(`dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/conflict/ConflictAdministrationCoordinator.kt`)
and `ConflictAdministrationRequest`
(`dataloom-api/src/commonMain/kotlin/io/dataloom/api/conflict/ConflictAdministration.kt`)
after this document was written, giving Finding 3's "no existing caller"
argument a genuinely new caller to check against — `#362` (this document) had
no such caller when it was written. This postscript records that check
rather than leaving it ambiguous.

**The two mechanisms are structurally disjoint, confirmed by reading, not
just by design intent.** `ConflictAdministrationRequest` carries a
`decision: ConflictResolutionDecision` supplied directly by the caller (an
operator, through whatever host-owned UI or tool constructs the request) —
the field is populated before `ConflictAdministrationCoordinator.execute` is
ever called, and the coordinator never derives it from anything. Read in
full, `ConflictAdministrationCoordinator` (all 459 lines), its supporting
`Eligibility`/outcome sealed types, `DefaultDataLoomConflictAdministration`,
and `DataLoomConflictAdministrationSpec`: none of them imports, references,
or calls `ConflictResolverRegistry` or `ConflictResolver` in any form. A
repository-wide search for `ConflictResolverRegistry`/`resolverRegistry`
returns exactly four hits — `ConflictResolverRegistry.kt` itself,
`SynchronizationConflictOrchestrator.kt`, and its two result/status support
files — and `ConflictAdministrationCoordinator.kt` is not among them.

Concretely, where `SynchronizationConflictOrchestrator` (the automatic,
live-inbound-pull path `#362` examined) calls
`resolverRegistry.lookup(resolverId)` to pick a `ConflictResolver` and then
invokes its `resolve()`, `ConflictAdministrationCoordinator` instead takes
`request.decision` as-is (rejecting only `Defer`, which is not a terminal
outcome — see `ConflictAdministrationRequest`'s own class doc) and hands it
straight to a host-owned `ConflictAdministrationExecutor.execute(...)`. There
is no selection step at all in this path for the registry's exact-ID lookup
to be extended, generalized, or routed by entity type — the "which resolver
handles this" question `#362` found unreachable never arises here, because
no resolver is ever selected. The decision already exists, per request,
before the coordinator does anything.

**Conclusion: `#362`'s finding stands untouched.** `#367` closes a different
gap — durable, authorized, idempotent application of an operator's
already-made decision to an already-recorded unresolved conflict — not
`#362`'s gap, which was the live pipeline's automatic, policy-driven
selection of *which* resolver to run by entity type (or workflow/tenant)
before a human is involved at all. These are two deliberately separate
mechanisms (see `ConflictAdministrationCoordinator`'s own "Relationship to
the live inbound pipeline" class doc, which independently confirms the same
separation from the live-pipeline side), not the same gap approached from a
second angle, so `#367` does not incidentally supply the missing caller
`#362`'s Finding 3 needed. `#95`'s policy-precedence gap is still open.

**A smaller, adjacent question was also checked and rejected as
speculative:** could `ConflictAdministrationRequest` grow room for a future
per-entity-type *default* decision (e.g. "always `UseRemote` for entity type
X without an operator manually intervening each time")? No — as read,
`ConflictAdministrationRequest` carries only `conflictId` (not an entity type
of its own; entity type is recovered from the durable
`UnresolvedConflictRecord` during eligibility checking, after the request
already exists) and a single already-made `decision`. Removing the "an
operator supplies `decision` per request" step so a default could apply
automatically by entity type would mean inventing a new, non-operator
caller that decides policy by entity type before durably recording
anything — which is exactly the same automatic, policy-driven,
entity-type-scoped selection problem `#362` already found un-bounded (a real
design decision spanning configuration shape and fallback behavior, not a
mechanical wiring task), just relocated from `ConflictResolverRegistry` to
`ConflictAdministrationRequest`. No decision-free, non-speculative shape for
it exists today, so — consistent with this document's own discipline of not
forcing a slice to justify a round — nothing is proposed here.

## Correction applied

`docs/status/market-readiness.md`'s `#95` row:

- "Still pending" cell's "Policy precedence (entity > workflow > tenant >
  global)" clause is sharpened to reference this document instead of standing
  as a bare, unexplained phrase.
- "Recently shipped" narrative gets a new dated entry recording this
  investigation, matching `#354`'s precedent for a no-implementation
  investigation outcome.

The `#95` percentage is unchanged (55% at the time; see the postscript above
for the 2026-08-26 re-check, which also does not change the percentage).
This is a documentation-accuracy change recording what was investigated and
why nothing was implemented, not a delivery of new functionality.

## References

- [`ConflictResolverRegistry`](../../dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/conflict/ConflictResolverRegistry.kt)
- [`SynchronizationConflictOrchestrator`](../../dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/conflict/SynchronizationConflictOrchestrator.kt)
- [`DataLoomConflictDetectionSpec`](../../dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/facade/DataLoomConflictDetectionSpec.kt)
- [`SynchronizationRequest`](../../dataloom-api/src/commonMain/kotlin/io/dataloom/api/model/SynchronizationRequest.kt)
- [`ExecutionContext`](../../dataloom-api/src/commonMain/kotlin/io/dataloom/api/context/ExecutionContext.kt)
- [`ConflictAdministrationCoordinator`](../../dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/conflict/ConflictAdministrationCoordinator.kt) (`#367`, checked in the postscript above)
- [`ConflictAdministration.kt`](../../dataloom-api/src/commonMain/kotlin/io/dataloom/api/conflict/ConflictAdministration.kt) (`#367`, checked in the postscript above)
- [Second conflict resolver investigation](./second-conflict-resolver-investigation.md)
- [Conflict resolution strategies](./conflict-resolution-strategies.md)
