# DataLoom Integrity and Key References

[API reference index](./README.md)

> **Status:** Available primitives with production JVM/Android and Apple
> implementations. Consumer wiring — for example a future asset-sync
> manifest that records per-chunk and whole-object digests, or an encryption
> metadata block that names which key protected an asset — remains separate,
> broader V1 work; this page documents the primitives themselves, not that
> any consumer has adopted them yet. See `FR-ASSET-004` (chunk and
> whole-object integrity) and `FR-ASSET-008` (encryption metadata/key
> references) in
> [DL-AUDIT-005](../audits/DL-AUDIT-005-current-v1-conformance.md).

**Package:** `io.dataloom.api.security`

## Overview

This page covers two of the `#93` security-primitives foundation gate's
required capabilities: **integrity** and **signature/key references**.
Redaction is already delivered — see
[operational envelope and redaction](./operational-envelope-redaction.md).
Encryption itself, a public-key infrastructure, and asset sync (`#97`) are
explicitly out of scope; see [Deliberately not included](#deliberately-not-included).

| Concern | Type | Purpose |
|---|---|---|
| Unkeyed integrity | [`DataLoomDigestCalculator`](#dataloomdigestcalculator) | Detect accidental corruption of bytes (chunk/whole-object hashing) |
| Keyed integrity ("signature") | [`DataLoomHmacCalculator`](#dataloomhmaccalculator) | Detect tampering and prove possession of a shared key (HMAC) |
| Key labeling | [`KeyReference`](#keyreference) | Record *which* application-managed key was used, without holding it |

DataLoom does not generate, store, rotate, or resolve cryptographic keys.
Every type below either takes key bytes the caller already resolved, or —
in the case of `KeyReference` — is purely an opaque label the application
assigns.

---

## `DataLoomDigestCalculator`

**Type:** `interface`

```kotlin
public interface DataLoomDigestCalculator {
    public fun digest(algorithm: DigestAlgorithm, input: ByteArray): DataLoomDigest
}
```

Computes an unkeyed cryptographic digest (hash) of `input`. One-shot and
stateless — mirrors `DataLoomSecureRandom.nextBytes`'s single-call shape
rather than an incremental hasher. Chunk integrity is handled directly with
one digest per chunk; whole-object integrity is achievable by digesting the
ordered concatenation of chunk digests, the same technique multipart
uploads and content-addressable stores already use.

### `DigestAlgorithm`

Closed enum: `SHA_256` (32-byte digest), `SHA_512` (64-byte digest). Closed
rather than an open type because each entry maps one-to-one onto a concrete
backing platform call — an arbitrary caller-supplied algorithm name would
not actually work against either platform's implementation.

### `DataLoomDigest`

Immutable, algorithm-labeled digest value returned by `digest()`.

- Defensive-copy pattern, matching `DataLoomPayload`: the constructor copies
  `bytes`, and `copyBytes()` returns a fresh copy.
- `toHex()` / `toString()` safely render the digest — unlike key material, a
  digest is a one-way, non-secret output.
- `contentEquals` / `equals` are ordinary (non-constant-time) comparison,
  which is safe: digest-comparison timing exposes nothing secret.
- Constructor throws `IllegalArgumentException` if `bytes` does not match
  the algorithm's fixed length (32 or 64 bytes).

---

## `DataLoomHmacCalculator`

**Type:** `interface`

```kotlin
public interface DataLoomHmacCalculator {
    public fun hmac(algorithm: HmacAlgorithm, key: ByteArray, input: ByteArray): DataLoomMac
    public fun verify(key: ByteArray, input: ByteArray, expected: DataLoomMac): Boolean
}
```

DataLoom's deliberate, practical stand-in for "signature" in the `#93`
foundation gate: keyed HMAC compute and constant-time verify. This gives an
application-supplied key proof of origin and tamper-evidence, without
DataLoom taking on asymmetric key generation, certificate handling, or a
PKI. See [why HMAC, not asymmetric signatures](#deliberately-not-included).

- `key` is a plain, transient `ByteArray` the caller already resolved — for
  example from a platform keystore entry named by a `KeyReference` — for
  the duration of one call only. Neither method accepts a `KeyReference`;
  DataLoom does no key resolution.
- `hmac()` and `verify()` both throw `IllegalArgumentException` if `key` is
  empty.
- `verify()` reads its algorithm from `expected.algorithm` rather than a
  second, independent parameter, so the two can never silently disagree.
- Implementations must compare tag bytes in constant time, regardless of
  where the first differing byte occurs.

### `HmacAlgorithm`

Closed enum: `HMAC_SHA_256` (32-byte tag), `HMAC_SHA_512` (64-byte tag). A
distinct type from `DigestAlgorithm` — not a shared "hash algorithm"
supertype — specifically so passing an unkeyed algorithm where a keyed one
is required, or vice versa, is a compile error, not a runtime failure.

### `DataLoomMac`

Immutable, algorithm-labeled MAC tag value returned by `hmac()`. Same
defensive-copy and render-safe shape as `DataLoomDigest` — a tag proves key
possession but is not itself secret, only the key is.

> **Security note — equality is not verification.** `DataLoomMac.equals` and
> `contentEquals` are ordinary array comparison, provided only for tests,
> collections, and logging-safe diagnostics. Verifying an untrusted tag
> against an expected value must go through
> `DataLoomHmacCalculator.verify()`, **never** `DataLoomMac == DataLoomMac`
> — an early-exit comparison can leak timing information an attacker could
> use to forge a tag byte by byte.

---

## `KeyReference`

**Type:** `value class`

```kotlin
@JvmInline
public value class KeyReference(public val value: String)
```

An opaque label for application-managed key material — a platform-keystore
alias, a KMS key ID, or an application-defined name. Never raw key bytes,
never resolved by DataLoom, and never accepted as a parameter anywhere in
`DataLoomHmacCalculator`. Directly serves `FR-ASSET-008` (encryption
metadata/key references): a future manifest can record which key protected
an asset without DataLoom taking on key custody.

An application resolves its own key material (from Android Keystore, iOS
Keychain, an external KMS, or wherever it already manages keys) and passes
the resulting bytes directly to `DataLoomHmacCalculator`; a `KeyReference`
is only for recording, in metadata, which key was used.

- `value` must not be blank or whitespace-only; the constructor throws
  `IllegalArgumentException` otherwise.
- `toString()` returns `value` — safe, because this is a reference, never
  the key itself.

Same flat, single-field shape as the identifiers in
[foundational contracts](./foundational-contracts.md) (`WorkflowId`,
`ChangeSetId`, and similar types), rather than a richer structure — no
concrete near-term need for extra fields (backend, version) was found.

---

## Production implementations

| Interface | JVM (also serves native Android today; see [clock docs](./clock.md) for why) | Apple (`iosArm64`, `iosSimulatorArm64`, `iosX64`) |
|---|---|---|
| `DataLoomDigestCalculator` | `SystemDataLoomDigestCalculator`, backed by `java.security.MessageDigest.getInstance("SHA-256"/"SHA-512")` | `AppleDataLoomDigestCalculator`, backed by CommonCrypto's `CC_SHA256`/`CC_SHA512` |
| `DataLoomHmacCalculator` | `SystemDataLoomHmacCalculator`, backed by `javax.crypto.Mac.getInstance("HmacSHA256"/"HmacSHA512")` + `SecretKeySpec` | `AppleDataLoomHmacCalculator`, backed by CommonCrypto's `CCHmac` |

All four live in `dataloom-model`, have no mutable state, and are safe to
share across threads.

- **JVM `verify()`** delegates to `java.security.MessageDigest.isEqual(byte[], byte[])`
  — the standard JCA constant-time comparison idiom, distinct from
  `Arrays.equals` and unrelated to `MessageDigest`'s own hashing role; it is
  used here purely as a comparison utility.
- **Apple `verify()`** hand-rolls a constant-time comparison (accumulate the
  bitwise OR of all byte differences without short-circuiting, then check
  once at the end), since CommonCrypto has no built-in equivalent to
  `MessageDigest.isEqual`.
- Both Apple implementations are reached through Kotlin/Native's built-in
  `platform.CoreCrypto` cinterop binding — bundled with the Kotlin/Native
  distribution since 1.3, no new `.def` file needed — and use CommonCrypto's
  one-shot convenience functions (`CC_SHA256`/`CC_SHA512`/`CCHmac`) rather
  than the stateful `Init`/`Update`/`Final` trio, keeping this at the same
  complexity level as `AppleDataLoomSecureRandom`'s `arc4random_buf` usage.

Neither pair is wired into `RuntimeDependencies`/`DataLoomBuilder`
automatically yet, and no current subsystem consumes these boundaries —
those remain open follow-up work, same as the equivalent note on the
[secure random](./secure-random.md) and [clock](./clock.md) pages.

---

## Deliberately not included

- **Asymmetric digital signatures (RSA/ECDSA) and a PKI.** No concrete
  near-term consumer needs non-repudiation or third-party verification;
  HMAC + `KeyReference` is the deliberate, explicit substitute — genuine
  authenticated integrity using only a byte-array key the caller supplies
  per call, with no key-pair generation, certificate handling, or PKI.
- **DataLoom owning key generation, storage, or rotation.** `KeyReference`
  is inert metadata; `hmac()`/`verify()` take caller-supplied, call-scoped
  key bytes only. No `generateKey()`, no persistence, no rotation or
  revocation contract.
- **A DataLoom-authored key-resolver implementation** (for example an
  Android-Keystore-backed resolver). Shipping one would reintroduce
  key-management decisions — aliasing, unlock policy — that this design
  avoids without a strong concrete reason.
- **Unkeyed digest algorithms beyond SHA-256/SHA-512, or extra HMAC
  variants.** V1 ships exactly the algorithms confirmed natively available
  on both target platforms without extra dependencies. Both enums are
  closed but additive: a new named constant is a non-breaking change.
- **Streaming/incremental digest or MAC APIs** (`update()`/`doFinal()`-style
  stateful hashers) and offset/length overloads. Whole-array one-shot calls
  suffice given bounded chunk sizes (once asset chunking is decided) and the
  hash-of-chunk-hashes technique for whole-object integrity. A genuine
  streaming hasher is a clean, additive future extension if a real
  non-chunked need appears.
- **Encryption/decryption and a cipher-algorithm selector** (AES-GCM, IVs,
  nonces). Confidentiality is a distinct half of `FR-ASSET-008` from "key
  references" and is outside this design's integrity/signature-reference
  scope.
- **Any change to asset sync itself (`#97`).** These are primitives only;
  no manifest schema, no chunk-boundary logic, no pipeline wiring.

---

## Testing

There is no shared `dataloom-testing` fake for these interfaces yet, same
as [secure random](./secure-random.md). `SystemDataLoomDigestCalculatorTest`
and `SystemDataLoomHmacCalculatorTest` cross-check production output against
known-answer vectors (NIST SHA-256/SHA-512 test strings, RFC 4231 HMAC test
case 1), independently recomputed via `javax.crypto`/`java.security`
directly rather than transcribed from memory. `AppleDataLoomDigestCalculatorTest`
and `AppleDataLoomHmacCalculatorTest` check CommonCrypto against the exact
same vectors, so the two platform implementations are required to agree.
