# ADR-0004: Enterprise governance foundation (RBAC, tenant isolation, tamper-evident audit, signed policy packs)

> **Numbering note.** `main` currently holds only ADR-0001 and ADR-0002.
> ADR-0003 (`ADR-0003-plugin-engine-module`) exists only on the open `#98` PR
> branch (`feat/98-plugin-module-relocation`), so `0004` was chosen to avoid a
> collision with it. If ADR numbering changes before merge, reconcile it; the
> slug `enterprise-governance-foundation` is the stable identifier. This ADR is
> deliberately not yet listed in the [decision index](./README.md) (that table
> is edited by the `#98` PR too); the row is left for the merge that lands
> second.

## Status

Accepted (decisions D7–D10 were delegated to and taken by the project lead on
2026-09-19). Only the parts marked *implemented* below exist in source; the rest
is accepted direction.

## Date

2026-09-19

## Context

`#99` (DL-045, enterprise governance) stalled because its first pieces looked
structurally blocked on decisions no implementation session could make
unilaterally: an RBAC taxonomy, a signing scheme for policy packs, and
configuration locks with no producers
([gap analysis](../status/dl-045-enterprise-governance-gap-analysis.md)). The
shared foundations it must build on now exist: `#93`'s policy vocabulary
(`PolicyCheckOutcome`, `PolicyDecision`, `PolicySet`, `DurablePolicyDecisionLog`),
tenant identifiers, and the digest / HMAC / secure-random primitives in
`dataloom-model`. The issue's own constraint is to extend those foundations, not
add a parallel enterprise-only mechanism.

[ADR-0002](./ADR-0002-v1-artifact-and-foundation-architecture.md) does not name
a governance source module (it names `dataloom-policy` as an internal policy
*engine*, which is not what this is). It also requires that source-module names
change only through an approved ADR; this ADR is that approval for
`dataloom-governance`.

## Decision

### Module

A new source module, `dataloom-governance`, package `io.dataloom.governance`.

- It depends on `dataloom-api` (for the policy vocabulary) and, transitively,
  `dataloom-model` (identifiers, clocks, digest/HMAC primitives), plus
  `kotlinx.coroutines` for the audit log's writer mutex.
- Nothing depends on it yet. It is not wired into `DataLoomBuilder`, and it must
  not become a mandatory dependency of an existing module.
- Platform-independent: no `expect`/`actual` in production code. The HMAC
  calculator is injected; the real JVM and Apple implementations from
  `dataloom-model` are what the tests bind.
- Publication treatment (its own artifact versus packaged into another) is **not**
  decided here; it is a DL-046 / release decision.

### D7 — RBAC taxonomy (*implemented*)

A small, closed, explicit model:

| Type | Meaning |
|---|---|
| `Principal` | `PrincipalId` + exactly one `TenantId` |
| `Action` | closed enum: `READ`, `WRITE`, `EXECUTE`, `ADMINISTER`; no action implies another |
| `ResourceType` | explicit identifier token, closed charset `[A-Za-z0-9._:-]` (a wildcard is unrepresentable) |
| `Permission` | `(Action, ResourceType)` |
| `Role` | `RoleId` + allowed and explicitly denied permission sets (bounded, no overlap) |
| `RoleBinding` | `(PrincipalId, TenantId, RoleId)` |
| `RbacPolicy` | validated, bounded set of roles and bindings |
| `RbacEvaluator` | deterministic evaluator returning the existing `PolicyCheckOutcome` |

Evaluation is deny-by-default; an explicit deny in any bound role beats an allow
in any other; permissions from multiple roles are unioned; no wildcards, no role
inheritance, no action hierarchy. The evaluator returns only
`PolicyCheckOutcome.Allow` / `Deny`, with the reason carried in outcome metadata
(`AccessDecisionReason`) rather than a second outcome vocabulary.

Non-normative mapping of the FR-ENT-004 operations onto the coarse actions: view
= `READ`; pause / resume / cancel / quarantine / retry / requeue = `EXECUTE` on
the resource type acted on; configure = `WRITE` on a configuration type; export
= `READ` on an export type; support = `ADMINISTER` on a support type. The
concrete resource-type vocabulary is chosen per subsystem when that subsystem
adopts governance.

### D8 — Tenant isolation (*implemented*)

`TenantGuard` makes cross-tenant access a hard denial at the governance boundary:
a principal's tenant must equal the resource's tenant. `RbacEvaluator` applies it
before consulting any role, and bindings are keyed by `(principal, tenant)`, so
the same principal id in two tenants is two principals and a role in one tenant
never applies in another.

V1 has **no cross-tenant grant type**. If one is ever introduced it must be an
explicit, audited type; it must not be a wildcard tenant or a special tenant
value. Denials do not include either tenant id in their text or metadata.

### D9 — Tamper-evident audit (*implemented*)

An append-only, hash-chained record type. Each record's MAC is
`HMAC-SHA256(key, canonical(sequence, recordedAt, previousMac, event))`, so every
record commits to the entire history before it, using the existing
`DataLoomHmacCalculator` / `DataLoomMac` primitives. The canonical encoding is a
documented, length-prefixed binary layout with a version-tagged domain string, and
is pinned by known-answer tests whose expected MACs were computed with an
independent implementation (OpenSSL).

`AuditChainVerifier` is offline: it needs only the records, the key, and an
optional external `AuditAnchor` (the sequence and MAC of a known-good head). It
detects:

| Attack | Detected | How |
|---|---|---|
| Modify a record or its MAC | yes | MAC mismatch at that record |
| Forge a previous link | yes | link mismatch |
| Reorder / duplicate records | yes | sequence mismatch |
| Delete a record other than the newest ones (including the first) | yes | sequence mismatch |
| Verify with the wrong key | yes | MAC mismatch at record 0 (indistinguishable from modification, by design) |
| Delete the newest records (tail truncation) | **only with an anchor** | anchor beyond the end of the chain |
| Replace the whole chain with another chain under the same key | **only with an anchor** | anchor MAC differs |

Without an anchor a truncated chain is a valid shorter chain; the verification
result says `anchorVerified = false` rather than implying otherwise. Anchors must
be stored where an attacker who can edit the record store cannot also edit them.
Anyone holding the key can forge a consistent chain; the symmetric MAC gives
integrity against parties *without* the key, not non-repudiation.

Only an in-memory, bounded store ships in this slice. The store port refuses
appends that do not extend the head (`HEAD_CONFLICT`) or that exceed capacity
(`CAPACITY_EXCEEDED`); nothing is dropped or overwritten silently. Durable
persistence is a later slice. The chain always verifies from sequence 0;
verifying retention-trimmed suffixes and key rotation (`KeyReference` in the
record) are later design work.

### D10 — Signed policy packs (*accepted, not yet implemented*)

The V1 signature is **HMAC-SHA256 with a host-supplied key** via the existing
`DataLoomHmacCalculator`. The scheme is symmetric; key custody, distribution, and
rotation belong to the host, exactly as for the audit key. A pack is verified
before it is admitted, and an unverified pack is never evaluated. Policy packs
build on `PolicySet` / `PolicyEvaluator` (they package and admit sets of checks;
they do not add a second evaluation engine).

**Asymmetric signatures (public-key verification, so that a verifier need not
hold a signing secret) are explicitly deferred.** They need a key-identity and
key-distribution decision, and platform primitives DataLoom does not have yet.
The choice of HMAC for V1 does not preclude adding them later, because the pack
manifest will name its signature scheme.

### Later slices (not decided or built here)

Configuration locks, data-residency controls, support / fleet diagnostics, LTS
and catalog governance, and `AC-FUNC-010` remain later slices. In particular a
configuration lock is only worth building once `LOCAL_OVERRIDE` has a real
producer.

## Consequences

### Positive

- `#99` has a real, tested foundation instead of a gap document, built on shared
  policy and security primitives rather than a parallel mechanism.
- Authorization answers reuse `PolicyCheckOutcome`, so a later slice can put
  governance behind a `PolicyCheck` and record decisions in
  `DurablePolicyDecisionLog` without translation.
- The audit chain is verifiable offline on JVM/Android and Apple from the same
  canonical bytes.

### Costs and risks

- The taxonomy is deliberately coarse; some subsystems will want finer
  distinctions. Those belong in `ResourceType`, and adding an `Action` is a
  deliberate, documented change.
- Symmetric MACs mean the verifier holds the signing key; that is accepted for V1
  and documented, not hidden.
- Apple behavior of the audit chain is covered by shared tests that compile for
  Apple targets but can only *run* there in macOS CI.

## Rejected alternatives

- **Open-ended role and permission tokens (`PluginPermission`-style).** Rejected:
  it defers the taxonomy question this ADR exists to answer and gives an
  evaluator nothing to be exhaustive about.
- **A second outcome vocabulary for governance decisions.** Rejected: `#93`'s
  `PolicyCheckOutcome` already expresses allow / deny with a justification and
  metadata.
- **Wildcard tenants or a "platform admin" cross-tenant role.** Rejected for V1:
  it reintroduces exactly the ambient authority tenant isolation removes.
- **A plain (unkeyed) hash chain.** Rejected: an attacker who can edit the store
  could recompute every later digest. A keyed chain requires the key to forge.
- **Asymmetric signatures for V1 policy packs.** Deferred, see D10.

## References

- GitHub issue #99 — DL-045 enterprise governance
- [DL-045 gap analysis](../status/dl-045-enterprise-governance-gap-analysis.md)
- [Policy foundation](../api/policy-foundation.md)
- [Governance foundation API](../api/governance-foundation.md)
- [ADR-0002](./ADR-0002-v1-artifact-and-foundation-architecture.md)
