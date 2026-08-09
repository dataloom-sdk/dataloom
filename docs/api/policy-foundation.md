# DataLoom Deterministic Policy Foundation

[API reference index](./README.md)

> **Status:** Available in-memory contract with a production implementation
> (no platform-specific code is required — everything below is pure common
> Kotlin). This is a bounded first slice of `#93`'s "deterministic policy
> foundation" requirement, not the full target described in
> [ADR-0002](../adr/ADR-0002-v1-artifact-and-foundation-architecture.md)'s
> `### Policy` section. No existing type (`RetryPolicy`, `StrategyPolicy`,
> `ExistingSchedulePolicy`, `ConflictResolutionDecision`) is migrated onto
> this foundation, no decision is persisted, and none of the six eventual
> consumers' concrete rules are implemented here — see
> [Deliberately not included](#deliberately-not-included).

**Package:** `io.dataloom.api.policy`

## Overview

`#93`'s required scope names six eventual consumers of this foundation:
*"a deterministic policy foundation used by retry reclassification, conflict
selection, content policy, plugin permissions, residency, and administrative
overrides."* Each of those subsystems already has (or will have) its own
domain-specific rules; this foundation is the shared evaluation primitive
none of them should have to reinvent.

An application assembles a [`PolicySet`](#policyset) of
[`PolicyCheck`](#policycheck) implementations and evaluates it against one
[`PolicyEvaluationInput`](#policyevaluationinput) via
[`PolicyEvaluator`](#policyevaluator), producing one explainable
[`PolicyDecision`](#policydecision).

| Concern | Type |
|---|---|
| Immutable input | [`PolicyEvaluationInput`](#policyevaluationinput) |
| Per-check contract | [`PolicyCheck`](#policycheck) |
| Typed, explainable outcome | [`PolicyCheckOutcome`](#policycheckoutcome) |
| Ordered, bounded rule set | [`PolicySet`](#policyset) |
| Deterministic combinator | [`PolicyEvaluator`](#policyevaluator) |
| Time-bounded evaluation | [`PolicyEvaluationBudget`](#policyevaluationbudget) |
| Combined, explainable result | [`PolicyDecision`](#policydecision) |
| Precedence-override mechanism | [`PolicyConfigurationKeys`](#policyconfigurationkeys) |

This is a direct generalization of
[`RetryPolicy`](retry-policy.md)'s own `evaluate(request): RetryDecision`
shape — synchronous, deterministic, side-effect-free — extended from
"decide whether/when to retry" to "decide allow/deny/require-user-action/defer
for any of the six `#93` policy consumers."

---

## `PolicyEvaluationInput`

```kotlin
public data class PolicyEvaluationInput(
    val executionContext: ExecutionContext,
    val configurationSnapshot: ConfigurationSnapshot,
    val providerHealth: Map<ProviderId, ProviderHealth> = emptyMap(),
    val stateEvidence: DataLoomMetadata = DataLoomMetadata.Empty,
)
```

This is the generalized "immutable input" ADR-0002's `### Policy` section
describes — execution context, runtime state, provider health, a
configuration snapshot reference, and tenant/trace identity — composed from
already-shipped `#93` primitives rather than duplicating their fields:

- **Tenant and trace** are already first-class fields on `ExecutionContext`
  (`tenantId`, `traceId`, `correlationId`, `executionId`) — not repeated
  here.
- **Provider health** reuses `ProviderHealth`/`ProviderId` — the same
  generic provider health snapshot the provider SPI already defines.
- **Configuration** is a reference to an already-resolved, checksummed
  [`ConfigurationSnapshot`](./configuration-snapshots.md) — required, not
  nullable, because the precedence-override mechanism below is resolved
  entirely from it.
- **`stateEvidence`** is a plain `DataLoomMetadata` bag, not a typed evidence
  class like `StrategyRuntimeEvidence`. That type's enums are shaped around
  strategy selection specifically and do not generalize to plugin
  permissions, residency, or administrative overrides — reusing it here
  would relocate the "subsystem invents its own state model" problem this
  foundation exists to prevent, just in the opposite direction.

---

## `PolicyCheckOutcome`

```kotlin
public sealed interface PolicyCheckOutcome {
    val justification: String
    val metadata: DataLoomMetadata

    data class Allow(...) : PolicyCheckOutcome
    data class Deny(...) : PolicyCheckOutcome
    data class RequireUserAction(...) : PolicyCheckOutcome
    data class Defer(val delay: SchedulingDelay, ...) : PolicyCheckOutcome
}
```

The outcome of one `PolicyCheck` evaluation, and also the vocabulary
`PolicyDecision.outcome` uses for the *combined* result — one hierarchy, not
two parallel ones, since a decision is literally the winning check's own
outcome plus the evidence trail that produced it.

Every variant carries a required, non-blank `justification` (free text, not
a closed reason enum — a shared enum would have to anticipate retry,
conflict, content-policy, plugin-permission, residency, and
administrative-override reasons all at once, which is exactly the
out-of-scope subsystem-specific logic this foundation does not design) and
optional `DataLoomMetadata`. `Defer` additionally carries a real
`SchedulingDelay` — the same relative-delay type retry decisions already
use — rather than signaling postponement with no duration.

`Deny` deliberately does not require a `DataLoomError`: a deny is an
ordinary, expected, rule-based negative result (a plugin lacking a
permission is not an error), not necessarily a failure condition.

---

## `PolicyCheck`

```kotlin
public interface PolicyCheck {
    val id: PolicyCheckId
    fun evaluate(input: PolicyEvaluationInput): PolicyCheckOutcome
}
```

The extension point. A `PolicyCheck` implementation calculates its outcome
using only what's already in `input` — no network, storage, provider calls,
sleeping, or scheduling — exactly `RetryPolicy`'s own evaluation
restrictions. Implementations may receive configuration through constructor
injection.

This interface ships no concrete implementation: no residency allowlist
logic, no plugin signature verification, no content scanning. That is each
eventual consumer's own adoption work.

---

## `PolicySet`

```kotlin
public class PolicySet(
    val id: PolicySetId,
    checks: List<PolicyCheck>,
)
```

A deterministically ordered, bounded collection of checks, evaluated
together as one unit. Order is exactly the caller's supplied list order —
never reordered, unlike `ConfigurationSource`'s scope-based reordering.

- Must contain at least one check (an empty set is rejected rather than
  given implicit "always allow" or "always deny" semantics — what an empty
  set should mean is itself a policy choice this subsystem-agnostic
  foundation should not make on every future consumer's behalf) and at most
  64 (a construction-time safety rail, independent of the runtime
  elapsed-time budget below).
- Every `PolicyCheck.id` in the set must be unique.
- `toString()` never renders individual checks — only the set id and check
  count.

---

## `PolicyEvaluator`

```kotlin
public class PolicyEvaluator(
    monotonicClock: DataLoomMonotonicClock,
) {
    fun evaluate(
        policySet: PolicySet,
        input: PolicyEvaluationInput,
        budget: PolicyEvaluationBudget,
    ): PolicyDecision
}
```

The fixed combinator. Every check in `policySet.checks` is evaluated in
order — evaluation never short-circuits on the first non-`Allow` result, so
`PolicyDecision.evidence` reflects every check's evidence, not just the
first blocking one (mirroring `DataLoomConfigurationResolver.resolve`'s own
"exhaustive, not fail-fast" posture).

### Precedence

1. **`Deny` — unconditional.** If any evaluated check produced `Deny`, the
   final outcome is the first `Deny` in evaluation order, regardless of
   configuration or any other result. ADR-0002 states "deny dominates
   allow" as a flat, unqualified rule — in contrast to the immediately
   following, explicitly qualified "required user action dominates delay
   unless an approved configuration says otherwise." There is no key
   anywhere that touches deny's dominance.
2. **`RequireUserAction` vs. `Defer`.** Absent any `Deny`: if both are
   present, which wins is read from
   `input.configurationSnapshot[PolicyConfigurationKeys.DEFER_DOMINATES_REQUIRE_USER_ACTION]`
   — see [`PolicyConfigurationKeys`](#policyconfigurationkeys). Default
   (absent or `false`): `RequireUserAction` wins. `true`: `Defer` wins. If
   only one of the two is present, it wins outright.
3. **`Allow`.** Wins only when every evaluated check produced `Allow`.

Within whichever outcome kind wins, the earliest check in evaluation order
that produced that kind determines `PolicyDecision.winningCheckId`; later
checks of the same kind still appear in `evidence` but do not change the
winner.

### Time-bounded, fail-closed

Before evaluating each check, elapsed time since evaluation began (measured
with the injected `DataLoomMonotonicClock`) is compared against
`budget.maxElapsedNanoseconds`. If already exhausted, evaluation stops
without running the remaining checks and returns a `PolicyDecision` whose
outcome is a synthesized `Deny` explaining budget exhaustion, whose
`winningCheckId` is `null`, and whose `evidence` holds whatever prefix of
checks ran before the cutoff — a fail-closed default; exhausting the budget
can never itself produce `Allow`.

This bounds the *cumulative* time across an ordered set of otherwise
individually-fast checks. It cannot protect against one non-compliant
`PolicyCheck` implementation that blocks or sleeps inside a single
`evaluate()` call — the same limitation `RetryPolicy` already accepts for
its own evaluation-restriction contract, enforced by documentation and
review, not a runtime sandbox.

---

## `PolicyEvaluationBudget`

```kotlin
public class PolicyEvaluationBudget(val maxElapsedNanoseconds: Long)
```

This slice's concrete realization of ADR-0002's "evaluation is
time-bounded." Reuses `DataLoomMonotonicReading`'s elapsed-nanosecond shape
— the same primitive ADR-0002's `### Deterministic execution` section names
for exactly this purpose — rather than a new duration taxonomy. There is
deliberately no default constant: a time bound gating retry, conflict,
plugin, or administrative-override decisions is safety-relevant, and this
type does not silently pick a number on the caller's behalf.

---

## `PolicyDecision`

```kotlin
public data class PolicyCheckEvidence(val checkId: PolicyCheckId, val outcome: PolicyCheckOutcome)

public data class PolicyDecision(
    val policySetId: PolicySetId,
    val outcome: PolicyCheckOutcome,
    val winningCheckId: PolicyCheckId?,
    val evidence: List<PolicyCheckEvidence>,
)
```

Never opaque: `outcome` is always accompanied by the complete ordered
`evidence` trail, and `winningCheckId` names which check produced it — or is
`null` when the evaluator itself synthesized the outcome (currently, only on
budget exhaustion). `checkId` on each `PolicyCheckEvidence` entry is
attributed by `PolicyEvaluator` itself as it iterates `PolicySet.checks`,
not self-reported by the check — so there is no possibility of a check's
outcome disagreeing with its own identity, and no consistency-check
validation is needed for that mismatch.

A `PolicyDecision` is a plain in-memory value. Creating one does not commit,
persist, or act on it — see [Deliberately not included](#deliberately-not-included).

---

## `PolicyConfigurationKeys`

```kotlin
public object PolicyConfigurationKeys {
    val DEFER_DOMINATES_REQUIRE_USER_ACTION: ConfigurationKey
}
```

The one concrete, implementable mechanism for ADR-0002's "unless an approved
configuration says otherwise." "Approved" is not a new concept invented for
this slice: a `ConfigurationSnapshot` cannot be hand-assembled with an
arbitrary out-of-band value (its constructor is `internal`; every snapshot
is produced by `DataLoomConfigurationResolver.resolve` after schema
validation), so the override flag can only reach `PolicyEvaluator` by being
present in a snapshot that already went through that admission path. A host
application opts in by declaring this key as optional `BOOLEAN` in its own
`ConfigurationSchema` and setting it to `true` in whichever
`ConfigurationSource` layer should grant the override — absence means the
override is inactive.

This is deliberately the *only* key this foundation defines. `Deny`'s
dominance has no override key at all.

---

## Deliberately not included

- **Migrating `RetryPolicy`, `StrategyPolicy`, `ExistingSchedulePolicy`, or
  `ConflictResolutionDecision` onto this foundation.** No existing type is
  changed. Adoption is separate follow-up work, the same posture already
  established for every other `#93` primitive shipped so far.
- **Durable/transactional persistence of decisions or evidence.** ADR-0002's
  "the resulting decision/evidence is committed with the state transition"
  belongs to the ADR's separate "Durable state" foundation bullet, not this
  one. A `PolicyDecision` here is a plain in-memory value with no
  commit/store hook.
- **The six subsystems' concrete rules** (retry reclassification, conflict
  selection, content policy, plugin permissions, residency, administrative
  overrides). This ships the generic `PolicyCheck`/`PolicySet`/`PolicyEvaluator`
  primitive only.
- **Async/suspending evaluation.** `PolicyCheck.evaluate` and
  `PolicyEvaluator.evaluate` are both strictly synchronous, matching
  `RetryPolicy`'s own restriction.
- **Wiring into `RuntimeDependencies`/`DataLoomBuilder`.** Nothing in
  `dataloom-runtime` references these types yet.
- **A dynamic policy registry.** `PolicySet` is a fixed, constructor-validated
  list, not a mutable registry supporting runtime add/remove/reordering.
- **Built-in offline-first/remote-first/cache-first/network-only/hybrid/adaptive
  strategy selection expressed through this primitive.** ADR-0002 lists this
  as a target *use* of composable policy, not a requirement this slice ship
  any strategy-selection behavior. `StrategyPolicy`'s existing vocabulary is
  untouched.
- **Signed/versioned "policy packs."** Distributing, signing, and versioning
  *sets* of checks across a fleet is a materially more speculative concern
  than *evaluating* an already-assembled `PolicySet` in memory, and depends
  on durable storage and supply-chain primitives this slice doesn't build.

---

## Testing

No platform-specific implementation is required for this slice — everything
lives in `commonMain`. Tests use a deterministic, non-cryptographic
`DataLoomDigestCalculator` fake (needed only to build `ConfigurationSnapshot`
instances) and a scripted `DataLoomMonotonicClock` fake that returns a
pre-programmed sequence of readings, letting tests control elapsed-time
measurements exactly without a real timer — the same
fake-over-production-implementation posture as
[secure random](./secure-random.md)'s and
[configuration snapshots](./configuration-snapshots.md)'s testing notes.
