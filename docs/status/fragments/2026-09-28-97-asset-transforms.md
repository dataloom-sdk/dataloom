# Fragment: gate #97, asset chunk transforms (2026-09-28)

## (a) Proposed "Recently shipped" row

| 2026-09-28 | `#97` slice 3 (decisions D24 and D25, [ADR-0014](../adr/ADR-0014-asset-chunk-transforms-and-digest-domain.md)): compression and encryption are wired into the asset transfer engine. **D24:** chunk and whole-object digests stay over the logical (plaintext, uncompressed) bytes and are verified by the client; a provider storing ciphertext cannot verify them, and AES-GCM's tag protects the stored bytes (resolves the open decision in ADR-0006). **D25:** `DeflateAssetCompressor` (zlib/DEFLATE; `java.util.zip` on JVM/Android, system zlib through `platform.zlib` on Apple) and `AesGcmAssetChunkCipher` (AES-256-GCM, per-chunk random 96-bit nonce from `DataLoomSecureRandom`, host-supplied keys through the new `AssetKeyResolver`, never generated or stored by the SDK) behind the existing SPIs, in a versioned frame (upload compress-then-encrypt, download the reverse; incompressible chunks stored raw; asset id, version, chunk index and count bound as associated data). `AssetTransferEngine`/`DataLoomAssetTransferSpec` take `AssetTransferTransforms`; the in-memory provider and the provider contract kit encode the transformed-manifest obligations. **Apple AES-GCM is NOT delivered:** Kotlin/Native's `platform.CoreCrypto` has no GCM mode and CryptoKit is Swift-only, so on Apple the cipher reports `isSupported = false` and a transfer configured with it returns `NotStarted(TRANSFORM_UNSUPPORTED)` (typed, no plaintext fallback); Apple compression is implemented. **Verified locally (JVM):** 56 new tests across zlib (round trips at chunk boundaries, incompressible data not blowing up, standard-stream interop, bomb/corrupt/truncated/trailing-byte rejection), AES-256-GCM (two published GCM known-answer vectors; flipped ciphertext/tag/nonce bit, truncation, wrong key, wrong chunk index, wrong associated data all rejected; 500 seals without a repeated nonce), and the engine (round trips at chunk boundaries in compress-only/encrypt-only/both modes, digests over logical bytes, provider never sees plaintext, 60 identical chunks with distinct nonces, tamper/truncation/wrong key/moved chunk/other asset/other version/downgrade rejection, unsupported transform refused before any I/O, transform mismatch on resume, key unavailable then recovered, upload resumed after partial commit and a download verifying the whole-object digest, staged-byte rot detected on resumed download); a mutation spot-check (dropping the asset id from the associated data) fails the cross-asset test. **Not verified:** execution of any Apple test, including `platform.zlib` compress/decompress (compile-verified for `iosArm64`, `iosSimulatorArm64`, `iosX64`, main and test; macOS CI only); Android device execution; any real provider. Deviation from the brief: the transfer session id is not bound as associated data because uploader and downloader use different session ids (asset id and version are bound instead). Side effects: `AssetEncryptionMetadata` now accepts an empty nonce (per-chunk nonces travel in each frame; no ABI change); `dataloom-runtime`'s `api/jvm` baseline also gained the governance entries that were missing from main's Android-layout baseline. | `#97` |

## (b) Gate row percentage

`#97`: **35% -> 45%** (banded to 5%, a judgement). `FR-ASSET-007` (compression) is implemented on all platforms and `FR-ASSET-008` (encryption) on JVM/Android; the digest-domain design question is closed. Held back from more because Apple encryption is unsupported and unrun-on-Apple code is compile-verified only; `FR-ASSET-006`, `-009`, `-012`, a real provider and `AC-FUNC-005` remain.

## (c) "Still pending" text

Remove "concrete compression and encryption algorithms" and the digest-domain decision from the `#97` pending list. Add "Apple AES-256-GCM (needs a CryptoKit shim or a vetted binding)". Remaining, in order:

1. Secure temp files, atomic promotion, cleanup of abandoned sessions and their persisted records, file-backed source/sink (`FR-ASSET-009`).
2. Parallel transfer with concurrency limits and fairness (`FR-ASSET-006`).
3. Content allow/deny/scan/quarantine hooks (`FR-ASSET-012`).
4. `ProviderType`/lifecycle for asset providers, `#94` retry/circuit and `#96` events around the engine, and a transport-backed provider.
5. `AC-FUNC-005` on Android and iOS, and public docs/samples.
6. Apple AES-256-GCM.

## ADR index rows the lead should add (docs/adr/README.md was not touched)

| ADR-0014 | Asset chunk transforms (compression and encryption) and the digest domain | Accepted; D24, D25; partial on Apple |
