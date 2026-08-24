# DataLoom Asset Manifest

[API reference index](./README.md)

> **Status:** Bounded first slice. `AssetManifest` and its nested value
> types describe an already-decided asset shape — identity, version, size,
> media type, whole-object and per-chunk integrity, chunk geometry, and
> optional compression/encryption labels. No upload/download, streaming,
> session, resume, quota, cancellation, or provider logic exists yet. See
> `FR-ASSET-001` through `FR-ASSET-012` in
> [DL-AUDIT-005](../audits/DL-AUDIT-005-current-v1-conformance.md) and
> issue `#97` for the full remaining scope this type does not attempt.

**Package:** `io.dataloom.api.asset` (`dataloom-api`), plus `AssetId` in
`io.dataloom.api.identifier` (`dataloom-model`).

## Overview

`#97` (DL-043, "asset synchronization") requires a versioned asset manifest
as its first listed requirement (`FR-ASSET-001`), alongside eleven other
requirements — chunked transfer, durable resumable sessions, streaming,
parallelism, quotas, cancellation, and content-policy hooks — that this
slice does not touch. This page documents only the manifest: a pure,
side-effect-free value type describing one asset's shape, the same way
[`ConfigurationSnapshot`](./configuration-snapshots.md) describes a
config's shape without deciding how the config was produced.

| Type | Purpose |
|---|---|
| [`AssetManifest`](#assetmanifest) | The manifest itself: identity, version, size, media type, checksum, chunk layout, optional compression/encryption |
| [`AssetId`](#assetid) | Canonical identifier for one logical asset across its version history |
| [`AssetMediaType`](#assetmediatype) | Bounded-token media type label, for example `image/png` |
| [`AssetChunkDescriptor`](#assetchunkdescriptor--assetchunklayout) | One contiguous byte range plus its own integrity digest |
| [`AssetChunkLayout`](#assetchunkdescriptor--assetchunklayout) | An ordered, contiguous, non-overlapping partition of an asset's bytes |
| [`AssetCompressionAlgorithm`](#compression-metadata) / [`AssetCompressionMetadata`](#compression-metadata) | Optional compression algorithm label |
| [`AssetEncryptionAlgorithm`](#encryption-metadata) / [`AssetEncryptionMetadata`](#encryption-metadata) | Optional encryption algorithm, key reference, and nonce — never key bytes |

---

## `AssetManifest`

**Type:** `data class`

```kotlin
public data class AssetManifest(
    public val assetId: AssetId,
    public val version: Long,
    public val sizeBytes: Long,
    public val mediaType: AssetMediaType,
    public val checksum: DataLoomDigest,
    public val chunkLayout: AssetChunkLayout,
    public val compression: AssetCompressionMetadata? = null,
    public val encryption: AssetEncryptionMetadata? = null,
)
```

- `version` must be positive.
- `sizeBytes` must be non-negative, and must equal
  `chunkLayout.totalSizeBytes` — construction fails otherwise. This is the
  one cross-field consistency check this type enforces; it does not imply
  DataLoom validated the bytes themselves, only that the manifest's own
  numbers agree with each other.
- `checksum` reuses [`DataLoomDigest`](./integrity-and-key-references.md)
  as-is — the same generic, algorithm-labeled digest `#234` already
  shipped — rather than inventing an asset-specific checksum
  representation.
- `compression` and `encryption` default to `null`, meaning uncompressed
  and unencrypted respectively.
- Never changes after construction. A new revision of the same logical
  asset is a new `AssetManifest` sharing `assetId` with a higher `version`;
  nothing here enforces monotonicity across revisions — that is a future
  durable-history concern (see
  [durable state contracts](./durable-state-contracts.md)), not this
  type's.

---

## `AssetId`

**Type:** `value class` (`io.dataloom.api.identifier`, `dataloom-model`)

```kotlin
@JvmInline
public value class AssetId(public val value: String)
```

Same flat, single-field shape as every other identifier in this module
(`WorkflowId`, `ChangeSetId`, `ConflictId`, and so on) — see
[foundational contracts](./foundational-contracts.md). Identifies one
logical asset across its whole version history: successive
`AssetManifest.version` values for the same `AssetId` describe successive
revisions of the same asset, not different assets. `value` must not be
blank; DataLoom does not generate asset identifiers.

---

## `AssetMediaType`

**Type:** `value class`

```kotlin
@JvmInline
public value class AssetMediaType(public val value: String)
```

A bounded token (non-blank, ≤256 characters, drawn from
`[A-Za-z0-9._+/-]`) rather than a closed enum — the universe of media types
is open and application/format-defined. Uses the same `isBoundedToken`
primitive `OperationalPayloadEncoding` and friends already use in
[operational envelope and redaction](./operational-envelope-redaction.md),
with `+` added to the allowed set so structured-syntax media types like
`application/vnd.api+json` are representable.

---

## `AssetChunkDescriptor` / `AssetChunkLayout`

**Type:** `data class` / `class`

```kotlin
public data class AssetChunkDescriptor(
    public val index: Int,
    public val offsetBytes: Long,
    public val lengthBytes: Long,
    public val checksum: DataLoomDigest,
)

public class AssetChunkLayout(chunks: List<AssetChunkDescriptor>)
```

`AssetChunkLayout` describes chunk geometry as an ordered list of
contiguous, non-overlapping byte ranges — not a single "chunk size" plus a
count. This deliberately does not presuppose uniform fixed-size chunking:
a future content-defined or variable-size chunking algorithm can still
produce a valid `AssetChunkLayout` describing its own output, because the
type only records the result, never how chunk boundaries were chosen.

Construction validation:

- `chunks` must be non-empty.
- Indexed `0` through `chunks.size - 1`, in that order.
- Each chunk's `offsetBytes` must immediately follow the previous chunk's
  end (`offsetBytes + lengthBytes` of chunk *n* equals `offsetBytes` of
  chunk *n + 1*) — no gaps, no overlaps.
- Each `AssetChunkDescriptor.lengthBytes` must be positive and
  `offsetBytes` non-negative.

`AssetChunkLayout.totalSizeBytes` (sum of every chunk's `lengthBytes`) is
what `AssetManifest.sizeBytes` is checked against. `AssetChunkLayout`
defensively copies its `chunks` list on construction and implements
content-based `equals`/`hashCode`.

Each `AssetChunkDescriptor.checksum` reuses `DataLoomDigest` again, giving
`FR-ASSET-004`'s "per-chunk ... integrity verification" a place to live —
this type only carries the digest, it never computes or verifies one.

---

## Compression metadata

**Types:** `AssetCompressionAlgorithm` (value class) / `AssetCompressionMetadata` (data class)

```kotlin
@JvmInline
public value class AssetCompressionAlgorithm(public val value: String)

public data class AssetCompressionMetadata(
    public val algorithm: AssetCompressionAlgorithm,
    public val uncompressedSizeBytes: Long? = null,
)
```

`AssetCompressionAlgorithm` is a bounded token, not a closed enum — mirrors
`AssetMediaType` and, further back, `OperationalPayloadEncoding`'s own
"name it without deciding the closed set up front" shape. **Which
compression algorithms V1 actually implements is an open product decision
this type does not make**; it only lets a manifest record, immutably,
which label a producer already used. `AssetManifest.compression == null`
means uncompressed; DataLoom never compresses or decompresses on the
strength of this metadata.

`uncompressedSizeBytes`, when present, must be non-negative. It is not
checked against `AssetManifest.sizeBytes` — that field is always the
logical (decompressed) size regardless of `compression`'s presence; this
one exists only for producers that want to redundantly record it here too.

---

## Encryption metadata

**Types:** `AssetEncryptionAlgorithm` (value class) / `AssetEncryptionMetadata` (class)

```kotlin
@JvmInline
public value class AssetEncryptionAlgorithm(public val value: String)

public class AssetEncryptionMetadata(
    public val algorithm: AssetEncryptionAlgorithm,
    public val keyReference: KeyReference,
    nonce: ByteArray,
)
```

Same "bounded token, not a closed enum" shape as compression — **which
encryption algorithms V1 actually implements is likewise an open product
decision this type does not make.** The key is named, never carried:
`keyReference` reuses
[`KeyReference`](./integrity-and-key-references.md) exactly as-is, the
same "DataLoom never resolves it to key bytes" boundary that type already
documents. `AssetManifest.encryption == null` means unencrypted, from
DataLoom's perspective; DataLoom never performs encryption or decryption
on the strength of this metadata.

`nonce` is defensively copied on construction (same pattern as
`DataLoomDigest`/`DataLoomMac`) and must not be empty. Because `algorithm`
is an open token rather than a closed enum, this type cannot validate a
fixed nonce length the way `DataLoomDigest` validates digest length against
a closed `DigestAlgorithm` — length validation, if ever needed, is a future
concern once V1 actually decides its encryption algorithm(s).

`toString()` never renders `nonce` bytes or key material — only `algorithm`
and the nonce's byte length (`copyNonceBytes()` returns a defensive copy of
the raw bytes for callers that need them).

---

## Deliberately not included

- **Upload/download, chunking execution, streaming source/sink contracts,
  durable resumable sessions, restart recovery, parallelism/fairness
  controls, quota enforcement, cancellation, or content-policy/scan/
  quarantine hooks** (`FR-ASSET-002`, `003`, `005`, `006`, `009`–`012`).
  None of this exists. `AssetManifest` describes a shape; nothing produces,
  transfers, verifies, or enforces policy on real asset bytes yet.
- **A closed compression or encryption algorithm enum.** Both are
  deliberately open, bounded tokens — picking a concrete algorithm set is
  a real product decision this slice does not make. See
  [compression](#compression-metadata) and [encryption](#encryption-metadata)
  above.
- **Encryption/decryption itself, key generation, or key resolution.**
  `AssetEncryptionMetadata` only records a `KeyReference` and algorithm
  label — exactly `KeyReference`'s own existing stance, unchanged here.
- **A fixed chunking algorithm.** `AssetChunkLayout` describes a result,
  not a chunking strategy; fixed-size, content-defined, or any other
  chunking approach can all produce a valid layout.
- **Durable history, monotonic-version enforcement, or a real caller.**
  No subsystem constructs an `AssetManifest` from real data yet, and no
  durable store persists one — matching the same "primitive, not
  pipeline" posture `ConfigurationSnapshot` and the integrity/key-reference
  primitives shipped with before any consumer adopted them.

---

## Testing

`AssetManifestTest` (`dataloom-api`) covers construction, the
`sizeBytes`/`chunkLayout.totalSizeBytes` cross-check, chunk-layout geometry
validation (empty, out-of-order, gapped, overlapping), compression/
encryption metadata bounds, `AssetEncryptionMetadata`'s defensive-copy and
render-safety behavior, and structural equality/immutability.
`IdentifierContractsTest` (`dataloom-model`) covers `AssetId` through the
same shared identifier-contract assertion every other identifier in that
file uses.
