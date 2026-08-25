# DL-045 (`#99`) enterprise governance: investigated, no bounded slice available yet

[Market-readiness dashboard](./market-readiness.md)

## Status

**Investigated (2026-08-25). No genuinely bounded, decision-free
implementation slice found for `#99` itself.** This document is the scoping
deliverable in its place: a precise, evidence-based gap table
cross-referencing every FR-ENT requirement and acceptance-criteria item named
in GitHub issue `#99` against what actually exists on `main` today, following
[`docs/architecture/artifact-graph-bom-gap-analysis.md`](../architecture/artifact-graph-bom-gap-analysis.md)'s
(`#93`, `#354`) and
[`docs/status/dl-046-release-readiness-checklist.md`](./dl-046-release-readiness-checklist.md)'s
(`#100`, `#358`) gap-table structure and rigor.

`#99`'s row in `docs/status/market-readiness.md` is **unchanged at 10%** by
this document — a gap-analysis document alone is not shipped progress, per
this session's own established precedent. Only the row's "Still pending"
wording is left as-is, since it already accurately names the still-open
concerns this document confirms in detail (it does not overstate or
understate anything worth correcting).

## Why `#99` is structurally different from a normal foundation slice

`#99` (DL-045) is enterprise governance — explicitly the broadest,
most cross-cutting gate in the V1 set outside of `#100` itself.
`docs/audits/DL-AUDIT-004-v1-production-readiness.md` (line 438) records its
own dependency line plainly: *"`#93`–`#98`, `#101`, and `#102`"* must be
substantially closed before `#99`'s FR-ENT-001–012 and `AC-FUNC-010` can pass
on required targets. The issue body's own "Delivery and validation" section
adds a second, independent constraint: *"Implement only on the shared
policy/state/security/event/plugin foundations; do not add parallel
enterprise-only mechanisms."* Together these mean `#99` cannot be built as an
isolated value-type island the way `AssetManifest` or `PluginManifest`
were — it must extend already-shipped shared primitives (`PolicySet`,
`ConfigurationSchema`, `DurableStateStore`, the retry/circuit administration
pattern), and several of the primitives it would extend have already, in
their own shipped documentation, explicitly deferred the exact concepts
`#99` needs as "materially more speculative" (see Part 3(c) below) — a much
stronger structural signal than an ordinary "not started yet" gate.

## Part 1 — verifying the "foundations exist" claim precisely

`#99`'s dashboard row currently reads: *"Tenant identifiers and limited
retry/circuit administration foundations exist."* Rather than trust that at
face value, each item was checked directly against the source.

| Named foundation | Verified? | Evidence |
|---|---|---|
| Tenant identifiers | Confirmed, narrower than "isolation" implies | `TenantId` (`dataloom-model/src/commonMain/kotlin/io/dataloom/api/identifier/Identifiers.kt`) is a real, validated (`require(value.isNotBlank())`) value class. It appears as an **optional** field on `ExecutionContext` (`tenantId: TenantId? = null`) and on `CircuitBreakerScope` (required only for the `TENANT_PROVIDER_OPERATION` scope kind; absent from `GLOBAL`, `PROVIDER`, `PROVIDER_OPERATION`, and `WORKFLOW`). Nothing in the codebase makes tenant context mandatory at any boundary, and nothing enforces isolation between two contexts carrying different `TenantId` values — a caller can omit it everywhere today with no diagnostic. This matches the issue's own framing exactly: *"an identifier is not tenant isolation."* |
| Limited retry/circuit administration foundations | Confirmed | `RetryAdministrationAuthorizer`/`CircuitAdministrationAuthorizer` (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/retry/RetryAdministration.kt`, `.../circuit/CircuitAdministration.kt`) are real, host-owned, deny-by-default (`AuthorizationDecision` is `Authorized`/`Denied`, no implicit default) authorization boundaries, each backed by a durable `*StateStore` (compare-and-set, versioned, idempotent-by-command-id) and a durable `*Executor`. `DataLoomRetryAdministration`/`DataLoomCircuitAdministration` wire these into the `DataLoom` facade as opt-in `Spec`s. This is genuine, tested, shipped administrative-command infrastructure — but it covers exactly two operation families (retry/requeue; circuit open/close/reset), not the eleven operation kinds FR-ENT-004 names (view, pause, resume, cancel, quarantine, retry/requeue, configure, export, support), and it has no tenant-scoping field on either `RetryAdministrationRequest` or `CircuitAdministrationRequest` today (only `CircuitAdministrationRequest.scope` can optionally carry a `TenantId`, and only for one of five scope kinds). |

**Conclusion: the row's "foundations exist" claim is accurate as written** —
both named items have real, shipped, tested evidence, not just documentation.
Neither constitutes tenant isolation or RBAC by itself, and the row's own
"Still pending" column already says so.

## Part 2 — FR-ENT-001–012 and acceptance-criteria cross-reference

Each FR-ENT item (full descriptions from
`docs/audits/DL-AUDIT-004-v1-production-readiness.md`, lines 371–382) and
each unchecked acceptance-criteria box from `#99`'s issue body, checked
against current evidence.

| FR | Requirement | Status | Evidence / blocked by |
|---|---|---|---|
| FR-ENT-001 | Tenant isolation for queue, configuration, credentials, storage, telemetry, policy | **Missing, correctly** | `TenantId` exists but is optional everywhere it appears (Part 1) and zero code checks that two different tenant contexts cannot observe or mutate each other's state. No queue, configuration, credential, storage, or telemetry boundary in `dataloom-runtime` reads `ExecutionContext.tenantId` today (confirmed by grep: `TenantId` appears only in identifier/context/scope declarations, their tests, and platform circuit-store internals — never in a conditional isolation check). |
| FR-ENT-002 | Signed, versioned, centrally governed policy packs with rollout/rollback/precedence | **Missing, and explicitly deferred by name already** | `docs/api/policy-foundation.md`'s own "Deliberately not included" section states verbatim: *"Signed/versioned 'policy packs.' Distributing, signing, and versioning sets of checks across a fleet is a materially more speculative concern than evaluating an already-assembled `PolicySet` in memory, and depends on durable storage and supply-chain primitives this slice doesn't build."* `PolicySet`/`PolicyEvaluator`/`PolicyDecision`/`DurablePolicyDecisionLog` (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/policy/`) are real, shipped, and reusable as the evaluation engine a future policy-pack mechanism would sit on top of — but distribution, signing, versioning, rollout, and rollback are all still open, by the primitive's own author's stated scope boundary, not merely unattempted. |
| FR-ENT-003 | Immutable, tamper-evident, redaction-safe governance and operational audit trail | **Partially evidenced for retry/circuit only, not tamper-evident, not governance-scoped** | `RetryAdministrationCommandState`/`CircuitAdministrationCommandState` are real durable, versioned, compare-and-set-guarded records of who requested what administrative action, when, and its outcome — genuine operational-audit-shaped evidence for exactly the two operation families FR-ENT-010 also covers. Neither is tamper-evident (no hash chaining or signature over the record sequence) and neither covers governance actions (RBAC grants, policy-pack rollout, configuration locks) because none of those subsystems exist yet. `DurablePolicyDecisionLog` is commit-once-per-execution, not an append-only audit stream. |
| FR-ENT-004 | RBAC for view, pause, resume, cancel, quarantine, retry/requeue, configure, export, support | **Missing outside retry/circuit; no cross-subsystem RBAC vocabulary exists** | `RetryAdministrationAuthorizer`/`CircuitAdministrationAuthorizer` are real, per-subsystem, host-owned authorization hooks — deny-by-default, but scoped to one operation family each, with no shared role or permission vocabulary between them. Nothing resembling a `Role`/`Permission` type exists anywhere in the repository outside `PluginPermission` (`dataloom-plugin-api`, scoped narrowly to a plugin's own declared capability requests, not an operator RBAC matrix) and `PolicyCheckId` (a check identifier, not a role). Nine of FR-ENT-004's eleven named operations (view, pause, resume, cancel, quarantine, configure, export, support) have no administrative-command type at all yet to authorize. |
| FR-ENT-005 | Fleet diagnostics: versions, configuration, health, backlog, failures, without payload/secret leakage | **Missing** | No `HealthSnapshot`/`Dashboard`/fleet-aggregation type exists anywhere in the repository (confirmed by grep). `#96`'s own row (47%, IN PROGRESS) is scoped to per-process "canonical envelope/redaction contracts... in-process event dispatch" — single-process observability, not cross-fleet aggregation. FR-ENT-005 needs `#96`'s redaction rules (explicitly named as the reference in `#99`'s own "Required behavior" section: *"Support/fleet outputs must use the same redaction/cardinality rules as `#96`"*) plus a fleet-aggregation layer that does not exist in either gate today. |
| FR-ENT-006 | Declared LTS, maintenance, vulnerability, upgrade policy | **Missing, not an engineering artifact** | This is a published policy document, not code — the same category `#100`'s gap analysis found for license text and namespace ownership: a business/operations decision this task is not positioned to make unilaterally, let alone encode as a value type. |
| FR-ENT-007 | Certified provider/plugin catalog backed by `#98` evidence | **Structurally blocked on `#98`** | `#99`'s own issue text names `#98`'s evidence as the input. `#98` is 15%/NOT STARTED, and its own investigation (`docs/api/plugin-platform-first-slice-investigation.md`) already found that every remaining `#98` item — including "the certification kit/catalog" — is unimplemented behavior work, not a value-type gap. FR-ENT-007 cannot exist before `#98` produces the certification evidence it would catalog. |
| FR-ENT-008 | Durable bounded offline audit buffer, integrity, later delivery, explicit overflow behavior | **Missing** | No `OfflineAudit`/audit-buffer type exists (confirmed by grep). `DurableOperationalEventOutbox` (`#96`, shipped) is the nearest existing shape — a durable, bounded, overflow-aware outbox with retention policy — and is a plausible foundation to extend, but FR-ENT-008 is scoped to *audit* records specifically (tamper-evident, governance/operational actions) which do not exist yet (FR-ENT-003), so there is nothing for an audit-specific buffer to durably hold today. |
| FR-ENT-009 | Enforceable data-residency controls for storage, transfer, telemetry | **Missing** | `policy-foundation.md` names "residency" as one of six eventual `PolicyCheck` consumers but ships no residency check, allowlist, or region model. No `Residency`-named type exists anywhere in the repository outside that one sentence of forward-looking documentation. |
| FR-ENT-010 | Authorized, idempotent, audited operational overrides | **Partially shipped for two operation kinds — the strongest existing evidence for any FR-ENT item** | `RetryAdministration`/`CircuitAdministration` genuinely satisfy authorized + idempotent (by `commandId`, compare-and-set guarded) + audited (durable command state with status/timestamp/rejection reason) for retry/requeue and circuit open/close/reset specifically. This is real, tested, shipped behavior — not a gap — but it is two operation kinds out of the pause/resume/cancel/quarantine/configure/export/support set FR-ENT-004 and FR-ENT-010 both imply, and it carries no tenant scoping on the request type itself (Part 1). |
| FR-ENT-011 | Explicitly authorized, redacted support bundles/snapshots | **Missing** | No `SupportBundle`/snapshot-export type exists. Depends on FR-ENT-005 (fleet diagnostics) existing first, since a support bundle is a redacted export of that same diagnostic data. |
| FR-ENT-012 | Enterprise configuration locks/signatures preventing unauthorized local override | **Missing, and premature even as a bare value type** | No `ConfigurationLock` or equivalent type exists. `docs/api/configuration-resolver-caller-investigation.md` (round 16, `#93`) independently confirmed that `ConfigurationSource`'s `LOCAL_OVERRIDE` scope — the exact mechanism FR-ENT-012 asks to "prevent unauthorized" mutation of — has **zero real producers anywhere in `dataloom-runtime` today**: nothing in this codebase currently reads a local-override file, environment variable, or equivalent. A lock type would be protection infrastructure for a mechanism that does not yet exist in practice. See Part 3(a) for the full investigation of this specific candidate. |
| AC-FUNC-010 | Tenant isolation across state, credentials, configuration, policy, telemetry | **Blocked** | Depends on FR-ENT-001, itself blocked (above). |

**Zero of the twelve FR-ENT requirements can be marked complete today.** One
(FR-ENT-010) has real, substantial partial evidence for a subset of its
scope. Two (FR-ENT-003, FR-ENT-004) have narrow partial evidence confined to
the same retry/circuit subsystem. The remaining nine are fully unstarted, and
three of those nine (FR-ENT-002, FR-ENT-006, FR-ENT-007) are blocked by an
explicit prior scope decision, a non-engineering business artifact, or
another still-open gate respectively, not merely undone.

## Part 3 — investigated candidates for a bounded first slice today

The task that produced this document named three candidates to verify
concretely rather than assume, mirroring `#98`'s own investigation
(`docs/api/plugin-platform-first-slice-investigation.md`) before it
concluded no slice remained to carve out. All three were investigated here;
none is genuinely bounded and decision-free for `#99`.

### (a) A `TenantId`-scoped configuration-lock value type

The most concrete-looking candidate: "this configuration key is locked by
tenant X, optionally with an expiry" as a pure value type, no enforcement
engine attached — the same posture `AssetEncryptionMetadata` used to defer
its algorithm choice.

Two independent problems disqualify it:

1. **It would protect a mechanism that has no real caller.** As Part 2's
   FR-ENT-012 row details, `configuration-resolver-caller-investigation.md`
   already found that `ConfigurationSource`'s `LOCAL_OVERRIDE` scope — the
   exact thing a "configuration lock" exists to guard — has zero production
   producers in this codebase today. Building a lock type now would be the
   same category of problem that document already rejected for the resolver
   itself: *"a `ConfigurationSchema` validation pass wrapped around a value
   that was already fully valid by construction... producing no behavior a
   real application could observe or configure differently than today."* A
   lock with nothing to lock against is exactly that.
2. **FR-ENT-012's own text pairs "locks" with "signatures" as one
   requirement** ("enterprise configuration locks/**signatures**"), and
   signing is the same "materially more speculative" concern
   `policy-foundation.md` already named and deferred for policy packs
   (Part 2, FR-ENT-002). Shipping a lock-only type without any signature
   concept would understate what FR-ENT-012 actually asks for; shipping a
   signature concept now would require deciding a signing scheme and
   key-custody model — precisely the class of decision `#100`'s gap analysis
   already escalated rather than decided unilaterally (signing-identity/key
   custody is named there as one of three human business decisions this
   session correctly declined to make).

### (b) An RBAC role/permission identifier shape mirroring `PluginPermission`

Investigated whether a bare, bounded/extensible `Role`/`Permission` token
type (deferring the closed taxonomy, exactly how `PluginPermission` defers
which permissions V1 actually supports) could ship today.

This does not clear the bar for two reasons:

1. **The generic identifier need is already met.** `UserId` already exists
   as "canonical identifier for user scope" and is a valid RBAC principal
   today; `PolicyCheckId` already exists as the identifier for one
   deterministic rule, explicitly scoped by its own KDoc to cover
   "administrative overrides" as one of its six intended consumers;
   `RetryAdministrationPrincipalId`/`CircuitAdministrationPrincipalId`
   already exist as the principal identifiers for the two operation
   families that do have administrative commands today. A new bare
   `Role`/`Permission` value class would duplicate this existing identifier
   vocabulary rather than fill a gap in it.
2. **A genuinely useful role/permission *shape* needs the concrete
   taxonomy `#99` cannot decide unilaterally.** `policy-foundation.md`
   explicitly scopes "the six subsystems' concrete rules" — of which
   administrative overrides is one — out of the shared foundation as
   "each eventual consumer's own adoption work." Deciding which
   operations exist as privileged actions, which roles exist, and how they
   compose is exactly that adoption work: a real product decision (what
   the RBAC matrix contains), not a value-only freeze. Shipping an empty
   token type with no concrete role/permission vocabulary behind it would
   be the identifier-shaped equivalent of ADR-0002's already-rejected
   "artifact name without owned behavior" anti-pattern (cited in
   `docs/status/dl-046-release-readiness-checklist.md`'s SBOM discussion for
   the same reasoning).

### (c) A signed-policy-pack manifest shape

Investigated whether a manifest recording *"this policy set, this version,
signed by this token"* could defer the actual cryptographic scheme the way
`AssetEncryptionMetadata` deferred algorithm choice.

This candidate is the most directly foreclosed of the three:
`docs/api/policy-foundation.md` — the shipped primitive this manifest would
extend — already states, in its own "Deliberately not included" section,
verbatim: *"Signed/versioned 'policy packs.' Distributing, signing, and
versioning sets of checks across a fleet is a materially more speculative
concern than evaluating an already-assembled `PolicySet` in memory, and
depends on durable storage and supply-chain primitives this slice doesn't
build."* This is not silence or an unexamined gap — it is `#93`'s own
authors, in the same session, already investigating this exact question and
recording a "not yet, and here is why" answer. Re-deciding it now under
`#99` without new information would either contradict that documented
finding or duplicate it. The issue's own "do not add parallel
enterprise-only mechanisms" instruction (quoted above) reinforces the same
conclusion from the opposite direction: any policy-pack manifest `#99` ships
must be built as an extension of `PolicySet`/`PolicyEvaluator`, and the
extension itself — distribution, signing, versioning, rollout order,
rollback — is precisely the open design surface `policy-foundation.md`
already named, not a shape that can be frozen independently of those
decisions.

**Conclusion: no genuinely bounded, decision-free implementation slice
exists for `#99` today.** All three candidates either protect a mechanism
with no real caller (a), duplicate existing identifiers while deferring the
one genuinely open question to a later product decision (b), or re-open a
question `#93`'s own shipped primitive has already investigated and
explicitly deferred as too speculative (c). This matches the same structural
finding this session already recorded for `#100`
(`docs/status/dl-046-release-readiness-checklist.md`) and `#98`
(`docs/api/plugin-platform-first-slice-investigation.md`) — investigated and
confirmed blocked, not merely unattempted.

## Ordered checklist for future rounds

Independently schedulable except where a dependency is named. None of these
may be started as a silent side effect of another gate's slice.

1. **Make `TenantId` mandatory at the boundaries that need it, and add real
   isolation checks.** The narrowest genuinely engineering-shaped starting
   point: decide (a product/architecture decision, not unilateral) which
   `ExecutionContext`-consuming boundaries must reject a missing or
   mismatched `TenantId` outright, then add fail-closed checks — FR-ENT-001's
   own literal ask, and the prerequisite for `AC-FUNC-010`.
2. **Extend `RetryAdministrationRequest`/`CircuitAdministrationRequest` (or
   their next FR-ENT-004 sibling command types) with a required tenant
   field**, closing the one concrete tenant-scoping gap Part 1 found in
   otherwise-solid existing administration infrastructure.
3. **Design and decide the RBAC role/permission taxonomy** for the nine
   FR-ENT-004 operations that have no administrative-command type yet
   (view, pause, resume, cancel, quarantine, configure, export, support) —
   the open product decision Part 3(b) found blocking a bare identifier
   type. Once decided, model authorization as `PolicyCheck` implementations
   evaluated through the existing `PolicyEvaluator`, per the issue's own
   "shared foundations" instruction, rather than a new parallel mechanism.
4. **Once 1–3 exist for at least one operation family, extend
   `DurablePolicyDecisionLog`/the retry-circuit administration state
   pattern into a real append-only, tamper-evident audit stream** —
   FR-ENT-003 — by adding hash-chaining or signing over the record
   sequence, a concrete design this document does not attempt to make.
5. **Close `#98`** (certified plugin/provider catalog) before attempting
   FR-ENT-007 — structurally blocked until then, per `#98`'s own
   investigation.
6. **Build fleet-level diagnostics aggregation on top of `#96`'s existing
   redaction/cardinality rules** — FR-ENT-005 — once `#96` itself
   progresses past per-process event dispatch into something with a
   cross-instance aggregation surface to build on.
7. **Design and decide the residency policy model** (FR-ENT-009) as a new
   `PolicyCheck` consumer of the shared foundation, and the offline audit
   buffer's integrity/overflow semantics (FR-ENT-008) as an audit-specific
   extension of `DurableOperationalEventOutbox`'s existing bounded/overflow
   pattern — both real design surfaces, not value-type freezes.
8. **Build FR-ENT-011 (support bundles) only after FR-ENT-005 exists**,
   since a support bundle is a redacted export of that same diagnostic data.
9. **Resolve the signing-identity/key-custody decision `#100`'s gap analysis
   already escalated**, then revisit FR-ENT-002 and the signature half of
   FR-ENT-012 together — both depend on the same unresolved decision, so
   solving it once unblocks both.
10. **Obtain FR-ENT-006 (LTS/support/maintenance/vulnerability/upgrade
    policy) as a published operations document** — not an engineering
    artifact, and not something this or any single engineering pass should
    draft unilaterally.

## What is not in question

- Both foundations named in the dashboard row (`TenantId`, retry/circuit
  administration) genuinely exist and are fully tested (Part 1) — this
  document found no defect in either, only precision about what each does
  and does not cover.
- `RetryAdministration`/`CircuitAdministration` are the strongest existing
  evidence for any FR-ENT item (FR-ENT-010) — real, shipped, tested
  authorized-idempotent-audited behavior, not a gap. This document does not
  understate that.
- This finding does not change `#99`'s percentage (10%, unchanged) or status
  (`NOT STARTED`, unchanged) — a gap-analysis document is scoping work, not
  shipped governance progress, per this session's established precedent.
- This document does not attempt to resolve the RBAC taxonomy, residency
  policy model, signing scheme, or LTS policy decisions it names as
  blocking. Those remain real product/business decisions for a future round
  or the user, already correctly escalated rather than decided unilaterally.

## References

- GitHub issue `#99` — DL-045 full requirements, ownership boundary,
  required behavior, and acceptance criteria
- `docs/audits/DL-AUDIT-004-v1-production-readiness.md` (lines 371–382,
  438) and `docs/audits/DL-AUDIT-005-current-v1-conformance.md`
  (lines 314–325) — source of the FR-ENT-001–012 descriptions and
  dependency line cited in Part 2 and the introduction
- [Policy foundation](../api/policy-foundation.md) — `#93`'s shipped
  `PolicySet`/`PolicyEvaluator` primitive, including its own "Deliberately
  not included" section ruling out signed/versioned policy packs, cited
  throughout Parts 2 and 3
- [Configuration resolver caller investigation](../api/configuration-resolver-caller-investigation.md) —
  the `#93` investigation confirming `LOCAL_OVERRIDE` has no real producer,
  central to Part 3(a)
- [Plugin platform first-slice investigation](../api/plugin-platform-first-slice-investigation.md) —
  the `#98` investigation this document's Part 3 methodology and structure
  follows most directly
- [Artifact graph/BOM gap analysis](../architecture/artifact-graph-bom-gap-analysis.md)
  and [DL-046 release-readiness checklist](./dl-046-release-readiness-checklist.md) —
  the `#93`/`#100` gap-table structure and rigor this document follows
- `dataloom-model/src/commonMain/kotlin/io/dataloom/api/identifier/Identifiers.kt`,
  `dataloom-api/src/commonMain/kotlin/io/dataloom/api/context/ExecutionContext.kt`,
  `dataloom-api/src/commonMain/kotlin/io/dataloom/api/circuit/CircuitBreakerScope.kt` —
  source of Part 1's `TenantId` optionality findings
- `dataloom-api/src/commonMain/kotlin/io/dataloom/api/retry/RetryAdministration.kt`,
  `dataloom-api/src/commonMain/kotlin/io/dataloom/api/circuit/CircuitAdministration.kt` —
  source of Part 1 and Part 2's retry/circuit administration findings
- `docs/status/market-readiness.md` — full V1 gate table and current
  per-gate percentages cited throughout
