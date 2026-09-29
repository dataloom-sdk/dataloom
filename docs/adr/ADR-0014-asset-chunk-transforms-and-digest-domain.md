# ADR-0014: Asset chunk transforms (compression and encryption) and the digest domain

- **Status:** Accepted (decisions D24 and D25, taken by the project lead on
  2026-09-28); implemented for the JVM and Android, partially implemented for
  Apple (compression only, encryption typed `Unsupported`; see
  [Platform support](#platform-support))
- **Date:** 2026-09-28
- **Gate:** [`#97` / DL-043 asset synchronization](https://github.com/dataloom-sdk/dataloom/issues/97), slice 3
- **Resolves:** open decision 1 of [ADR-0006](./ADR-0006-asset-transfer-and-streaming-digest.md)
  ("digest domain when transforms are enabled") and delivers its "next slice 2"
  (concrete compression and encryption algorithms wired into the engine)

> This ADR records what slice 3 shipped and why, including the parts that did
> not ship. It does not claim `FR-ASSET-007` or `FR-ASSET-008` are met on every
> platform: see [What is still open](#what-is-still-open).

## Context

Slice 1 shipped the `AssetCompressor` and `AssetChunkCipher` SPIs with identity
implementations only, and recorded one open question: chunk and whole-object
digests are defined over the asset's *logical* bytes, but a provider that stores
sealed bytes cannot verify a logical digest. The question had to be settled
before the first real algorithm could be wired into the engine.

## Decisions

### D24: digests are over logical bytes and are verified by the client

The per-chunk digests and the whole-object digest in `AssetManifest` cover the
**logical** (plaintext, uncompressed) bytes, exactly as before and exactly as
`AssetManifest.sizeBytes` is already documented. They are verified by the
client, never by a provider that stores ciphertext.

- **Upload.** `AssetIntegrityVerifier.prepareManifest` still hashes the source.
  The engine then annotates the manifest with `AssetCompressionMetadata` and
  `AssetEncryptionMetadata`. On every chunk (including on resume) the engine
  verifies the source chunk against the manifest descriptor *before*
  transforming it, so a source that changed fails with
  `SOURCE_CONTENT_CHANGED` instead of uploading mismatched data.
- **Download.** The engine reverses the transforms, then verifies each decoded
  chunk against its descriptor and, after the last chunk, the whole assembled
  object read back from the sink. Both are logical-domain checks.
- **Provider.** For a manifest with a transform
  (`AssetWireFormat.isTransformed`), chunk bytes are opaque frames. The
  provider stores each frame exactly as received and returns it exactly as
  stored; it does not apply the length-equals-descriptor or digest checks, and
  `completeUpload` does not verify the whole-object digest (it still requires
  every chunk to be committed). A provider may reject a frame that is empty or
  longer than the descriptor length plus `AssetWireFormat.MAX_FRAME_OVERHEAD_BYTES`
  (256). The `AssetProvider` KDoc, `InMemoryAssetProvider` and one new
  `AssetProviderContractKit` scenario encode this.
- **What protects the stored bytes.** For an encrypted asset, the AES-GCM
  authentication tag. Corruption, truncation or substitution of a stored frame
  fails authentication on download. For a compress-only asset there is no tag;
  corruption is caught by zlib's own checks or by the logical digest.
- **Consequence to accept.** An upload of a transformed asset is not verified
  end to end at completion: nothing but the provider's own storage guarantees
  holds the frames until they are downloaded and authenticated. The whole
  object digest is checked on the next download.

### D25: DEFLATE/zlib and AES-256-GCM, bounded

- **Compression: zlib/DEFLATE** (`DeflateAssetCompressor`, label `zlib-deflate`,
  RFC 1950 stream around RFC 1951 DEFLATE, level 1 to 9, default 6). JVM and
  Android use `java.util.zip`; Apple uses the system zlib through Kotlin/Native's
  `platform.zlib` (`compress2`, `uncompress2`). Both emit standard zlib streams,
  so a chunk compressed on one platform decompresses on the other (a shared test
  decodes an externally produced zlib stream). Apple's `Compression` framework was
  not used: its `COMPRESSION_ZLIB` is raw DEFLATE (no zlib header or checksum), and
  the Kotlin/Native distribution has no binding for it.
  Decompression is bounded by the manifest-derived expected size (a
  decompression-bomb guard) and rejects wrong size, corruption, truncation and
  trailing bytes.
- **Encryption: AES-256-GCM** (`AesGcmAssetChunkCipher`, label `AES-256-GCM`).
  Per-chunk random 96-bit nonce from the injected `DataLoomSecureRandom` (the
  existing secure-random primitive), 128-bit tag, output `ciphertext || tag`.
  Keys are supplied by the host through the new `AssetKeyResolver`
  (`KeyReference` to key bytes); DataLoom never generates, stores, caches or
  persists key material and never modifies the array it is handed. AES-256 needs
  exactly 32 bytes. Random nonces bound a key to about 2^32 chunks
  (NIST SP 800-38D); the host rotates keys well before that.
- **Order.** Compress, then encrypt on upload; decrypt, then decompress on
  download. Compressing first is what makes compression useful at all; the
  compress-before-encrypt side channel (ciphertext length reveals compressed
  length) is inherent to the choice. It matters only when an attacker can both
  influence chunk content and observe stored lengths; a host with that threat
  should not enable compression together with encryption.

## The frame (version 1)

```
byte 0        frame version (1)
byte 1        flags: bit 0 = payload is compressed, bit 1 = payload is sealed; other bits must be 0
byte 2        nonce length N (0 unless sealed)
bytes 3..3+N  nonce
rest          payload: the (compressed) chunk, or ciphertext || tag when sealed
```

An untransformed asset uses no frame at all: its chunks are the raw logical
bytes, byte for byte as in slices 1 and 2, so nothing existing changes.

- **Incompressible data does not blow up.** If compression does not shrink a
  chunk, the raw bytes are stored and bit 0 is clear. A frame therefore exceeds
  its logical chunk by at most `3 + 12 + 16 = 31` bytes.
- **Downgrade resistance.** A reader whose manifest says "encrypted" rejects an
  unsealed frame (`TRANSFORM_FRAME_INVALID`), and a frame claiming compression
  where the manifest records none is rejected likewise.
- **Versioning.** Unknown version or flag bits are rejected, so a future frame
  version cannot be misread as this one.

### Associated data

The cipher authenticates: 4-byte chunk index (added by the cipher itself) and,
from the pipeline, `frame version, flags, chunk index, chunk count, asset
version, asset size, asset id`. Consequences, each covered by a test: a chunk
moved to another position, taken from another asset or from another version of
the same asset, or with a flipped flag, fails authentication.

**Deviation from the brief: the transfer session id is not bound.** The task
asked for the chunk index and session id as associated data. The session id is
chosen by each caller for each direction: the uploader's id and the
downloader's are different values (usually on different devices), so an AAD
that included it could never be reproduced on download and every download would
fail. The asset id and version, which every party reads from the manifest, are
bound instead, which gives the same anti-swap, anti-replay property the session
id was meant to give.

### Manifest metadata

`AssetEncryptionMetadata.nonce` must previously be non-empty. Nonces here are
per chunk and live in each frame, so there is no single asset-wide nonce to
record. That validation was relaxed (an empty nonce now means "per-chunk nonces
travel with each chunk"); nothing else in `dataloom-api` changed, the public
signature and ABI are identical, and one test changed from "rejects" to
"accepts" an empty nonce. `AssetCompressionMetadata.uncompressedSizeBytes`
records the logical size.

## Engine behaviour

- `AssetTransferEngine(..., transforms = AssetTransferTransforms(compressor, cipher, keyReference))`,
  default `NONE`; `DataLoomAssetTransferSpec.transforms` passes it through the builder.
- **Unsupported is refused up front.** A transform whose `isSupported` is false
  fails an upload with `NotStarted(TRANSFORM_UNSUPPORTED)` before the source is
  read, the provider is opened or a session is created. A download of an asset
  whose manifest needs a transform this engine cannot reverse does the same.
  There is no fallback to plaintext.
- **A session is never silently resumed with different transforms.** Resuming an
  upload whose persisted manifest disagrees with the configured algorithms or
  key reference fails the session with `TRANSFORM_UNSUPPORTED`. In particular a
  session begun encrypted cannot be resumed as plaintext.
- **Failure classification** (five new `AssetErrorKind`s): `TRANSFORM_UNSUPPORTED`
  (terminal), `ENCRYPTION_KEY_UNAVAILABLE` (resumable: a locked keystore
  leaves the session intact), `ENCRYPTION_KEY_INVALID` (terminal), `CHUNK_AUTHENTICATION_FAILED`
  (terminal, sink discarded), `TRANSFORM_FRAME_INVALID` (terminal, sink discarded).
  A failed authentication never commits a chunk.
- **Memory** stays independent of asset size but is now a small constant number
  of chunk-sized buffers (chunk, compressed form, frame) instead of one.
- **Quota.** The provider's reservation still counts the manifest's logical
  size; a frame can exceed it by at most 31 bytes per chunk. A provider that
  needs exact accounting adds that slack itself.

## Platform support

| | JVM | Android | Apple (iOS) |
|---|---|---|---|
| zlib/DEFLATE | `java.util.zip` | same (consumes the JVM target) | system zlib via `platform.zlib`; compile-verified, unrun |
| AES-256-GCM | `javax.crypto` `AES/GCM/NoPadding` | same | **Unsupported** |

**Why Apple has no AES-GCM.** Kotlin/Native's bundled `platform.CoreCrypto`
(CommonCrypto) binding exposes `kCCModeCBC/ECB/CFB/CFB8/CTR/OFB/RC4` and no GCM
mode, and no `CCCryptorGCM*` symbol is bound (checked against the bundled klib
for Kotlin/Native 2.4.10). CryptoKit
(`AES.GCM`) is Swift-only with no Objective-C surface. On Apple,
`AesGcmAssetChunkCipher.isSupported` is `false`, `seal`/`open` throw the typed
`AssetTransformUnsupportedException`, and the engine reports
`TRANSFORM_UNSUPPORTED`; nothing degrades. Compression-only transfers work on
Apple.

**Rejected: GCM built from CommonCrypto AES-CTR plus a hand-written GHASH.** It
is implementable in Kotlin, and its logic would be testable on the JVM, but a
hand-rolled AEAD that no platform test can run against a reference on Apple is a
security risk that the requirement does not justify. The honest, typed
`Unsupported` result is preferred. Closing the gap needs either a small Swift or
Objective-C shim over CryptoKit shipped in the Apple artifact, or a vetted
third-party crypto binding; either is a separate decision.

Test doubles: on Apple the shared engine tests run against `TestAeadCipher`, a
toy AEAD that exists only in test sources, so that the frame, associated-data and
engine logic is still exercised there. It is not a claim about AES-GCM on Apple.

## What is still open

1. **Apple AES-GCM** (above).
2. **Provider-side transformed-object verification** is impossible by design
   (D24); an end-to-end upload-time check would need a keyed provider-side
   protocol and is not planned.
3. **The manifest itself is not authenticated.** A provider that controls the
   manifest it serves can withhold or replace whole assets consistently with its
   own manifest; it cannot forge ciphertext for a key it does not hold. Signing
   manifests is part of the governance work, not this slice.
4. **Policy on plaintext downloads.** A host that requires encryption cannot yet
   make the engine refuse an unencrypted asset; downloads follow the manifest.
5. Slices 4 to 7 of the ADR-0006 list are unchanged: secure temp files and
   atomic promotion, parallel transfer, content-policy hooks, a real provider
   and lifecycle, `AC-FUNC-005`.

## Consequences

- `FR-ASSET-007` (compression) is implemented on all platforms; `FR-ASSET-008`
  (encryption) on JVM and Android only.
- Public surface added to `dataloom-assets`: `AesGcmAssetChunkCipher`,
  `DeflateAssetCompressor`, `AssetKeyResolver`, `AssetTransferTransforms`,
  `AssetWireFormat`, three transform exceptions, five `AssetErrorKind`s, an
  `isSupported` member (default `true`) on both SPIs, and a `transforms` argument on
  `AssetTransferEngine` and `DataLoomAssetTransferSpec`. ABI baselines regenerated.
- `dataloom-assets` gains `jvmMain` and `iosMain` source sets (the platform
  primitives); it still has no Android target of its own.

## Validation

Verified locally on the JVM: zlib round trips at and around chunk boundaries for
compressible, incompressible and zero data; a standard zlib stream produced elsewhere;
bomb, corrupt, truncated, empty and trailing-byte rejection; AES-256-GCM against
two published GCM known-answer vectors and round trips; flipped ciphertext, tag and
nonce bits, truncation, wrong key, wrong chunk index and wrong associated data
each rejected; 500 seals with no repeated nonce; an injected secure random supplying
exactly 12 bytes; typed key failures; engine round trips at chunk boundaries in
compress-only, encrypt-only and both modes; digests over logical bytes; the provider
seeing no plaintext; incompressible chunks stored raw; 60 identical chunks each with a
distinct nonce; tamper, truncation, wrong key, moved chunk, other asset, other
version, downgrade and unknown version or flags on download; unsupported transform
refused before any I/O; transform mismatch on resume; key unavailable then recovered;
upload resumed after partial commit followed by a download that verifies the
whole-object digest; a resumed download detecting rotted staged bytes; the durable
session codec round trip of a transformed manifest; a builder test through
`DataLoomAssetTransferSpec`. A mutation spot-check (dropping the asset id from the
associated data) makes the cross-asset test fail.

Compile-verified but **not run**: everything on `iosArm64`, `iosSimulatorArm64` and
`iosX64` (main and test), including the `platform.zlib` bindings and
`AppleTransformSupportTest`. Apple tests run only in the macOS CI job. No Apple
execution of zlib compress or decompress has been observed.

## References

- [ADR-0006](./ADR-0006-asset-transfer-and-streaming-digest.md),
  [ADR-0008](./ADR-0008-durable-asset-transfer-sessions.md)
- [`asset-transfer.md`](../api/asset-transfer.md),
  [`asset-manifest.md`](../api/asset-manifest.md),
  [`integrity-and-key-references.md`](../api/integrity-and-key-references.md)
- NIST SP 800-38D (GCM); RFC 1950 (zlib) and RFC 1951 (DEFLATE)
- `FR-ASSET-007` and `FR-ASSET-008` in
  [DL-AUDIT-005](../audits/DL-AUDIT-005-current-v1-conformance.md)
