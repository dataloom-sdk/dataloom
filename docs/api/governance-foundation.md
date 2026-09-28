# DataLoom Governance Foundation

[API reference index](./README.md)

> **Status:** Available foundation (slices 1 and 2 of `#99`). Pure common Kotlin
> in the `dataloom-governance` module; JVM tests run, Apple targets
> cross-compile (Apple test execution needs macOS CI). Slice 2 added signed
> policy packs and an opt-in `DataLoom.governance` capability. Nothing in the
> runtime *enforces* governance at a boundary yet, and this is not enterprise
> governance as a whole: see [What is not included](#what-is-not-included).
> Decisions are recorded in
> [ADR-0005](../adr/ADR-0005-enterprise-governance-foundation.md) and
> [ADR-0010](../adr/ADR-0010-governance-signed-policy-packs-and-runtime-wiring.md).

**Audience:** engineers integrating or extending governance.
**Packages:** `io.dataloom.governance.rbac`, `io.dataloom.governance.audit`,
`io.dataloom.governance.policy`.

## Overview

| Concern | Types |
|---|---|
| RBAC model | `Principal`, `PrincipalId`, `Action`, `ResourceType`, `Permission`, `Role`, `RoleId`, `RoleBinding`, `RbacPolicy` |
| Evaluation | `RbacEvaluator`, `AccessRequest`, `ResourceRef`, `AccessDecisionReason` |
| Tenant isolation | `TenantGuard` |
| Audit | `AuditEvent`, `AuditRecord`, `AuditAnchor`, `AuditLog`, `AuditChainVerifier`, `AuditStore`, `InMemoryAuditStore` |
| Signed policy packs | `PolicyPackManifest`, `SignedPolicyPack`, `PolicyPackVerifier`, `PolicyPackVerificationResult`, `PolicyPackKeyResolver` |
| Runtime wiring (`dataloom-runtime`) | `DataLoomGovernanceSpec`, `DataLoomGovernance`, `DataLoomBuilder.governanceConfiguration`, `DataLoom.governance` |

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

## Signed policy packs

A policy pack is a `PolicyPackManifest` (a `PolicySetId`, a positive version, the
signing `KeyReference`, an ordered unique list of `PolicyCheckId`, and bounded
metadata) encoded to canonical bytes and signed with HMAC-SHA256. A manifest
**names** checks; it carries no code. The host resolves each named check to its own
`PolicyCheck`, exactly as it supplies plugin instances.

```kotlin
val manifest = PolicyPackManifest(PolicySetId("residency"), 3L, KeyReference("policy-2026q3"), listOf(PolicyCheckId("region-allowlist")))
val pack = SignedPolicyPack.sign(manifest, hmacCalculator, signingKeyBytes)   // control plane

val result = PolicyPackVerifier(hmacCalculator).verify(pack, PolicyPackKeyResolver { id -> keyStore[id] })
```

`verify` never throws. Its result and the order of checks:

| Result | Meaning |
|---|---|
| `Malformed(reason)` | Not a well-formed pack: wrong domain tag, truncated or empty input, overrunning length, non-canonical UTF-8, trailing bytes, duplicate metadata key, or a manifest outside its bounds. `reason` is static text, never input content. |
| `UnsupportedVersion(formatVersion)` | Well-formed, but a format version this build does not support (older or newer). Refused even when validly signed. |
| `UnknownKeyId(keyId)` | The resolver returned `null` or empty key material. No signature comparison was attempted. |
| `InvalidSignature` | The signature does not authenticate the received bytes under the resolved key: modified pack, modified signature, or wrong key (indistinguishable by design). |
| `Valid(manifest)` | Authentic. Only this outcome exposes a manifest. |

The signature covers the exact received bytes and is compared through
`DataLoomHmacCalculator.verify` (constant time). The layout is length-prefixed,
domain-tagged (`dataloom.governance.policypack`) and versioned, and is pinned by
known-answer tests whose MACs were computed with OpenSSL; see
[ADR-0010](../adr/ADR-0010-governance-signed-policy-packs-and-runtime-wiring.md)
for the byte layout.

Limits worth knowing:

- **No rollback protection.** The manifest version is signed and returned but the
  verifier is stateless, so a validly signed older pack still verifies. The host
  must compare versions against what it has admitted.
- The MAC is symmetric: anyone who can verify a pack on a device can also sign
  one. Use per-tenant or per-device keys; asymmetric signatures remain deferred.
- Key custody, distribution, rotation (several key ids may resolve at once) and
  revocation (stop resolving an id) belong to the host. There is no expiry.
- Turning a verified manifest into a `PolicySet` is the host's job in this slice.

## Runtime wiring

```kotlin
val dataLoom = DataLoomBuilder()
    /* ... */
    .governanceConfiguration(
        DataLoomGovernanceSpec(
            rbacPolicy = policy,                       // optional
            auditStore = InMemoryAuditStore(),         // optional, with auditKey
            auditKey = auditKeyBytes,
            hmacCalculator = hmacCalculator,           // audit and/or policy-pack verification
        ),
    )
    .build()

dataLoom.governance?.rbacEvaluator?.evaluate(request)
dataLoom.governance?.auditLog?.append(event)
dataLoom.governance?.policyPackVerifier?.verify(pack, keyResolver)
```

`DataLoom.governance` is `null` unless the spec is supplied, and its three
properties are independently nullable, each present only when its own piece was
configured. The audit log is timestamped by the builder's runtime clock. `build()`
performs no store I/O. The spec requires at least one piece, an audit store and key
together, and an HMAC calculator whenever an audit store is given. Nothing in the
runtime consults these at a boundary; hosts call them.

## What is not included

Ordered next slices: (1) durable audit persistence through `DurableStateStore`
with retention, overflow and delivery semantics (FR-ENT-008), and operational-event
bridging; (2) a `PolicyCheck` adapter so RBAC decisions flow through
`PolicyEvaluator` into `DurablePolicyDecisionLog`, plus pack admission into a
`PolicySet`, rollout/rollback and a version floor; (3) configuration locks (only
worthwhile once `LOCAL_OVERRIDE` has a producer); (4) residency; (5) support/fleet
diagnostics; (6) LTS/catalog governance; (7) `AC-FUNC-010` cross-subsystem
tenant-isolation acceptance.

## Verification

- `./gradlew :dataloom-governance:jvmTest`: table-driven and exhaustive
  cross-product RBAC tests, tenant-guard tests, audit tamper tests, and signed
  policy pack tests (modified byte at every offset, wrong key, truncation, empty
  input, downgraded format version, unknown key id) against the real
  `SystemDataLoomHmacCalculator`.
- `:dataloom-runtime:jvmTest --tests '*DataLoomBuilderGovernanceTest*'`: wiring
  tests with a fake HMAC calculator.
- `:dataloom-governance:compileTestKotlinIosSimulatorArm64` with
  `-Pdataloom.appleKlibCrossCompile=true`: shared tests compile against the real
  `AppleDataLoomHmacCalculator`; running them requires macOS.

## Related documentation

- [ADR-0005](../adr/ADR-0005-enterprise-governance-foundation.md)
- [Policy foundation](./policy-foundation.md)
- [DL-045 gap analysis](../status/dl-045-enterprise-governance-gap-analysis.md)
