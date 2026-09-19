# DataLoom Governance Foundation

[API reference index](./README.md)

> **Status:** Available foundation (slice 1 of `#99`). Pure common Kotlin in the
> new `dataloom-governance` module; JVM tests run, Apple targets cross-compile
> (Apple test execution needs macOS CI). It is **not** wired into
> `DataLoomBuilder`, nothing else depends on it, and it is not enterprise
> governance as a whole: see [What is not included](#what-is-not-included).
> Decisions are recorded in
> [ADR-0004](../adr/ADR-0004-enterprise-governance-foundation.md).

**Audience:** engineers integrating or extending governance.
**Packages:** `io.dataloom.governance.rbac`, `io.dataloom.governance.audit`.

## Overview

| Concern | Types |
|---|---|
| RBAC model | `Principal`, `PrincipalId`, `Action`, `ResourceType`, `Permission`, `Role`, `RoleId`, `RoleBinding`, `RbacPolicy` |
| Evaluation | `RbacEvaluator`, `AccessRequest`, `ResourceRef`, `AccessDecisionReason` |
| Tenant isolation | `TenantGuard` |
| Audit | `AuditEvent`, `AuditRecord`, `AuditAnchor`, `AuditLog`, `AuditChainVerifier`, `AuditStore`, `InMemoryAuditStore` |

## RBAC and tenant isolation

```kotlin
val policy = RbacPolicy(
    roles = listOf(
        Role(RoleId("operator"), allow = setOf(Permission(Action.EXECUTE, ResourceType("retry.command")))),
    ),
    bindings = listOf(RoleBinding(PrincipalId("alice"), TenantId("acme"), RoleId("operator"))),
)
val outcome: PolicyCheckOutcome = RbacEvaluator(policy).evaluate(
    AccessRequest(
        principal = Principal(PrincipalId("alice"), TenantId("acme")),
        action = Action.EXECUTE,
        resource = ResourceRef(TenantId("acme"), ResourceType("retry.command")),
    ),
)
```

`RbacEvaluator.evaluate` returns the existing
[`PolicyCheckOutcome`](./policy-foundation.md#policycheckoutcome) (`Allow` or
`Deny` only); the reason is `outcome.accessDecisionReason`.

Rules, in order:

1. **Tenant guard.** Principal tenant must equal resource tenant; otherwise a
   hard `Deny` (`TENANT_MISMATCH`) before any role is consulted. V1 has no
   cross-tenant grant. Denial text and metadata never contain a tenant id.
2. **Bindings.** Only roles bound to exactly `(principal id, principal tenant)`
   count. None means `Deny` (`NO_ROLE_BINDING`).
3. **Explicit deny wins.** Any bound role denying the `(action, resource type)`
   pair means `Deny` (`EXPLICIT_DENY`), even if other roles allow it.
4. **Union of allows.** Any bound role allowing the pair means `Allow`
   (`ALLOWED_BY_ROLE`); the sorted deciding role ids are in
   `AccessDecisionMetadata.DECIDING_ROLES_KEY`.
5. **Default deny** (`NO_MATCHING_PERMISSION`).

Matching is exact: no wildcards, no role inheritance, no action hierarchy
(`ADMINISTER` does not imply `READ`). Identifiers use a closed charset, and all
collections are bounded (`Role.MAXIMUM_PERMISSIONS_PER_SET`,
`RbacPolicy.MAXIMUM_ROLES`, `RbacPolicy.MAXIMUM_BINDINGS`). `RbacPolicy` fails
fast on duplicate role ids, bindings to undefined roles, and roles that both
allow and deny one permission.

## Tamper-evident audit log

```kotlin
val log = AuditLog(InMemoryAuditStore(), hmacCalculator, clock, hostSuppliedKey)
val record = log.append(AuditEvent(tenantId, principalId, AuditEventType("access.denied")))
val anchor = log.anchor()          // store this somewhere the record store's writers cannot edit
// later, offline, with only records + key (+ anchor):
AuditChainVerifier(hmacCalculator).verify(records, key, anchor)
```

Each record's MAC is `HMAC-SHA256(key, canonical(sequence, recordedAt,
previousMac, event))` using `DataLoomHmacCalculator`; the canonical layout is
documented on `AuditCanonicalEncoding` and pinned by known-answer tests.
Timestamps come from the injected `DataLoomClock`; callers cannot backdate.

| Tampering | Result |
|---|---|
| Modified record or MAC | `MAC_MISMATCH` at that index |
| Forged previous link | `PREVIOUS_LINK_MISMATCH` |
| Reordered, duplicated, or mid-chain/first-record deletion | `SEQUENCE_MISMATCH` |
| Wrong key | `MAC_MISMATCH` at index 0 |
| Tail truncation, anchor supplied | `ANCHOR_TRUNCATED` |
| Tail truncation, **no anchor** | not detected: reported as `Valid(anchorVerified = false)` |
| Chain replaced by another chain under the same key, anchor supplied | `ANCHOR_MISMATCH` |

Limits worth knowing: the MAC is symmetric, so a key holder can forge a
consistent chain (this is integrity, not non-repudiation); the key is
host-owned and DataLoom does not store, rotate, or resolve it; verification runs
from sequence 0; `AuditEvent.details` must not contain secrets or personal data.
`InMemoryAuditStore` is bounded and refuses (never drops) appends past capacity;
it is not durable.

## What is not included

Ordered next slices: (1) signed policy packs (D10: HMAC-SHA256 with a
host-supplied key, verified before admission); (2) `DataLoomBuilder` wiring
(governance behind a `PolicyCheck`, decisions into `DurablePolicyDecisionLog`);
(3) durable audit persistence through `DurableStateStore` and operational-event
bridging; (4) configuration locks; (5) residency; (6) support/fleet
diagnostics; (7) LTS/catalog governance; (8) `AC-FUNC-010` cross-subsystem
tenant-isolation acceptance.

## Verification

- `./gradlew :dataloom-governance:jvmTest`: table-driven and exhaustive
  cross-product RBAC tests, tenant-guard tests, and audit tamper tests against the
  real `SystemDataLoomHmacCalculator`.
- `:dataloom-governance:compileTestKotlinIosSimulatorArm64` with
  `-Pdataloom.appleKlibCrossCompile=true`: shared tests compile against the real
  `AppleDataLoomHmacCalculator`; running them requires macOS.

## Related documentation

- [ADR-0004](../adr/ADR-0004-enterprise-governance-foundation.md)
- [Policy foundation](./policy-foundation.md)
- [DL-045 gap analysis](../status/dl-045-enterprise-governance-gap-analysis.md)
