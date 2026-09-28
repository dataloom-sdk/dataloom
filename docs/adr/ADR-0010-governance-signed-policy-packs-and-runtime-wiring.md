# ADR-0010: Signed policy packs and opt-in governance wiring

> **Numbering note.** `ADR-0009` is taken by the plugin version/dependency
> gating decision; this ADR uses `0010`. The slug
> `governance-signed-policy-packs-and-runtime-wiring` is the stable identifier if
> numbering changes before merge.

## Status

Accepted; implemented. Completes decision D10 of
[ADR-0005](./ADR-0005-enterprise-governance-foundation.md) and amends its
slice-1 statement that nothing depends on `dataloom-governance`.

## Date

2026-09-28

## Context

[ADR-0005](./ADR-0005-enterprise-governance-foundation.md) decided that policy
packs are signed with HMAC-SHA256 under a host-supplied key, verified before they
are admitted, and built on `PolicySet` / `PolicyEvaluator`, but it did not say
what a pack *contains*, what its wire format is, how a verifier reports failure,
or how governance reaches a running `DataLoom`. It also stated that
`dataloom-governance` must not become a dependency of an existing module. Slice 2
of `#99` needs all four answered.

## Decision

### A pack names checks; it never carries code

`PolicyPackManifest` holds a `PolicySetId`, a positive `Long` version, a
`KeyReference` naming the signing key, an ordered, unique, non-empty list of
`PolicyCheckId` (at most 64, the same bound `PolicySet` enforces), and bounded
`DataLoomMetadata`. It reuses the `#93` identifiers and the existing
`KeyReference`; no parallel identifier types were added. `PolicyCheck`
implementations are host code, exactly as `DataLoomPlugin` instances are: DataLoom
never loads or executes code from a pack. Verifying a pack therefore proves *which
checks a central authority approved, in which order*, not how those checks behave.

### Wire format, version 1

`PolicyPackCanonicalEncoding` follows the audit chain's encoding (big-endian
integers, u32 length prefixes, UTF-8, well-formed Unicode only) with an explicit
domain tag and format version:

```
string  "dataloom.governance.policypack"
u32     format version (1)
string  keyId
string  policySetId
u64     manifest version
u32     check count, then each checkId in evaluation order (never sorted)
u32     metadata count, then key/value pairs sorted by key
```

The MAC is `HMAC-SHA256(key, exactly those bytes)`, so it covers the domain tag,
format version, key id, and every field. The domain tag differs from the audit
chain's in its very first length prefix, so a MAC over one can never be presented
as a MAC over the other under a shared key. Known-answer vectors, with MACs
computed independently with OpenSSL, pin the layout.

### Verification result and order

`PolicyPackVerifier.verify` never throws and returns `Valid(manifest)`,
`InvalidSignature`, `UnknownKeyId`, `Malformed`, or `UnsupportedVersion`.

1. Strictly decode the bytes. Wrong domain tag, truncation, an overrunning length,
   non-canonical UTF-8, trailing bytes, duplicate metadata keys, or a manifest that
   violates its own bounds is `Malformed`. A well-formed but unrecognised format
   version is `UnsupportedVersion`, whether older or newer, and is refused even if
   the pack carries a valid signature.
2. Resolve the key through a host-supplied `PolicyPackKeyResolver`. `null` or empty
   key material is `UnknownKeyId`.
3. Verify the signature over the received bytes (never a re-encoding) with the
   existing `DataLoomHmacCalculator.verify`, which compares in constant time.

Decoding precedes the signature check because the key id lives inside the
signed bytes. The decoder is bounded: every read is checked against the remaining
input before allocating, counts are capped, and no path can throw past the
verifier. Nothing decoded is exposed unless the signature verifies.

### What this slice does not do

- **Rollback protection is not implemented.** The manifest version is signed and
  returned, but the verifier is stateless and compares it with nothing, so a
  validly signed older pack still verifies. Enforcing a monotonic floor needs the
  host's admitted version, which is durable state and belongs with rollout and
  rollback. "Downgrade" in this slice means an older or newer *format* version.
- **Admission into a `PolicySet` is host-composed.** A host resolves the verified
  manifest's check ids to its own `PolicyCheck` instances and builds the `PolicySet`.
  A DataLoom-provided admitter was left out to keep the slice to verification.
- Keys are symmetric: whoever can verify a pack can also sign one. Per-tenant or
  per-device keys limit the blast radius; asymmetric schemes remain deferred (D10).
- There is no expiry or revocation beyond the host no longer resolving a key id.

### Opt-in runtime wiring

`dataloom-runtime` now depends on `dataloom-governance` through `api(...)`, the
same shape as `dataloom-plugin` and `dataloom-assets`, because
`DataLoom.governance`'s public signature uses governance types. This is a
dependency-direction change; `dataloom-governance` still depends only on
`dataloom-api` (and transitively `dataloom-model`).

`DataLoomBuilder.governanceConfiguration(DataLoomGovernanceSpec)` sets a nullable
`DataLoom.governance`. Its three pieces are independently optional and returned
unchanged: `rbacEvaluator` (from an `RbacPolicy`), `auditLog` (from an `AuditStore`
and key, timestamped by the runtime clock), and `policyPackVerifier` (from an HMAC
calculator). Omitting the spec leaves `governance` null and behavior unchanged,
and `build()` performs no store I/O. Nothing in the runtime enforces governance at
a boundary yet; hosts consult it. The Apple umbrella does not export
`dataloom-governance`, matching the treatment of `dataloom-assets`.

### Durable audit persistence is not in this slice

An `AuditStore` needs `head()` and `readAll()` over a growing sequence, but
`DurableStateStore` offers only `load(scope)` and `compareAndSet`, with no
enumeration. A per-record layout probing for the head is possible but freezes a
contiguous-from-zero layout before the retention, overflow, and delivery questions
of FR-ENT-008 are decided (a full bounded audit log fails closed on every audited
action). It stays the next slice.

## Consequences

- Fleet-distributed policy has a verifiable, tamper-evident, versioned container
  with a stable wire format, without a second evaluation engine.
- A host can wire governance in one call, with no cost when unused.
- Stale-pack replay is still possible until the host enforces a version floor;
  this is documented rather than hidden.

## Rejected alternatives

- **Packs that carry executable checks or plugin code.** Rejected: code loading and
  its supply-chain risk are out of scope, and `PolicyCheck` is host code.
- **A new `PolicyPackId` type.** Rejected: `PolicySetId` already names the set.
- **Version embedded only in the domain tag string.** Rejected: a numeric field
  lets the verifier report `UnsupportedVersion` distinctly from `Malformed`.
- **A stateful verifier tracking the highest admitted version.** Deferred to the
  rollout/rollback slice, where the durable state lives.

## References

- [ADR-0005](./ADR-0005-enterprise-governance-foundation.md)
- [Governance foundation API](../api/governance-foundation.md)
- [Policy foundation](../api/policy-foundation.md)
- GitHub issue #99 (DL-045)
