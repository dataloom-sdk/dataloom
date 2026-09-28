# Asset transfer (`dataloom-assets`)

> **Status:** Slices 1 to 3 of `#97` (DL-043). Contracts, in-memory reference
> behaviour, (slice 2) durable session persistence on `DurableStateStore` plus
> opt-in `DataLoomBuilder.assetTransferConfiguration` wiring, and (slice 3)
> per-chunk zlib compression and AES-256-GCM encryption wired into the engine
> (encryption is JVM/Android only; Apple returns a typed `Unsupported`). Still
> no file-backed source/sink, no parallelism, no content-policy hooks, and no
> transport-backed provider. The decisions behind it are in
> [ADR-0006](../adr/ADR-0006-asset-transfer-and-streaming-digest.md) (transfer
> design), [ADR-0008](../adr/ADR-0008-durable-asset-transfer-sessions.md)
> (durable sessions and wiring) and
> [ADR-0014](../adr/ADR-0014-asset-chunk-transforms-and-digest-domain.md)
> (transforms and the digest domain), which list the ordered next slices.
> `AssetManifest` itself is documented in [asset-manifest.md](./asset-manifest.md).

**Module:** `dataloom-assets` (`io.dataloom.assets`, plus `.memory`,
`.transform`, `.testkit`). Targets: JVM (also serves Android) and the three iOS
targets. The incremental digest lives in `dataloom-model`
(`io.dataloom.api.security`).

## Types

| Type | Purpose |
|---|---|
| `AssetChunkPlan`, `AssetChunkSizeBounds` | Fixed-size chunk geometry; provider-negotiated chunk-size limits; default 1 MiB |
| `AssetSource`, `AssetSink`, `AssetReadable` | Bounded-memory, position-based source and read-back-able sink |
| `AssetTransferSession`, `AssetTransferEvent`, `AssetTransferPhase` | Pure session state machine (`reduce`); see the transition table in the ADR |
| `AssetTransferSessionStore`, `InMemoryAssetTransferSessionStore` | Compare-and-set persistence seam on `AssetTransferSession.revision`; volatile implementation |
| `DurableAssetTransferSessionStore`, `AssetTransferSessionCodec` | Durable implementation on `DurableStateStore` (scope `AssetTransferSessionId`, state `AssetTransferSession`), typed `trySave`/`tryLoad` outcomes, versioned fail-closed codec; never persists payload bytes |
| `DataLoomAssetTransferSpec` (`dataloom-runtime`) | Opt-in builder configuration; yields `DataLoom.assetTransfer` |
| `AssetProvider`, `AssetQuota` | Provider SPI and the quota it enforces |
| `AssetIntegrityVerifier` | Per-chunk and streaming whole-object verification; manifest preparation |
| `AssetTransferEngine`, `AssetTransferOutcome` | Sequential resumable upload/download/cancel |
| `AssetErrorKind`, `AssetTransferError` | Closed failure classes as canonical `DataLoomError`s |
| `AssetCompressor`, `AssetChunkCipher` (+ identity test doubles) | Compression and AEAD-style cipher SPIs; each reports `isSupported` |
| `DeflateAssetCompressor`, `AesGcmAssetChunkCipher`, `AssetKeyResolver` | zlib/DEFLATE and AES-256-GCM (JVM/Android; typed `Unsupported` on Apple); host-supplied keys |
| `AssetTransferTransforms`, `AssetWireFormat` | Transforms an engine applies; frame constants and `isTransformed(manifest)` for providers |
| `InMemoryAssetProvider`, `InMemoryAssetSource`, `InMemoryAssetSink` | Reference implementations for tests and samples (not bounded-memory stores) |
| `AssetProviderContractKit` | Provider test kit (framework-neutral) |

## Using the engine

```kotlin
val digests = SystemDataLoomDigestCalculator()          // or AppleDataLoomDigestCalculator()
val engine = AssetTransferEngine(
    provider = provider,                                 // any AssetProvider
    sessions = InMemoryAssetTransferSessionStore(),      // or a DurableAssetTransferSessionStore (below)
    digests = digests,
    chunkSizeBytes = 1024 * 1024,                        // clamped into provider.chunkSizeBounds
)

// Upload. The same call resumes the session if it already exists.
when (val outcome = engine.upload(sessionId, assetId, version = 1, mediaType, source)) {
    is AssetTransferOutcome.Completed   -> /* verified; outcome.session.manifest */ Unit
    is AssetTransferOutcome.Interrupted -> /* transient; call upload again to resume */ Unit
    is AssetTransferOutcome.Failed      -> /* terminal; outcome.error; use a new session id */ Unit
    is AssetTransferOutcome.Cancelled   -> Unit
    is AssetTransferOutcome.NotStarted  -> /* no session created; outcome.error */ Unit
    is AssetTransferOutcome.SessionStoreFailure -> /* store failed; if recoverable, call again */ Unit
}

// Download the latest version into a sink; likewise resumable.
engine.download(sessionId, assetId, version = null, sink)

// Explicit, permanent cancel (provider abort / sink discard).
engine.cancel(sessionId, sink)
```

Cancelling the *calling coroutine* is cooperative and leaves the session
resumable; only `engine.cancel` makes the decision permanent. Retry policy is
the caller's (or a later slice's): `Interrupted` simply means "safe to call
again".

## Resuming after a restart

Give the engine a `DurableAssetTransferSessionStore` over a platform
`DurableStateStore`; on Android that is `RoomDurableStateStore`:

```kotlin
val durableStore = RoomDurableStateStore(
    database,
    namespace = "asset-transfer-session",                // unique per domain
    scopeKeyEncoder = DurableAssetTransferSessionStore.KeyEncoder,
    codec = AssetTransferSessionCodec(),
)
val sessions = DurableAssetTransferSessionStore(durableStore)
```

After a restart, call `upload`/`download` again with the same session id: the
persisted committed chunks are skipped and each chunk is re-verified against the
persisted manifest. Only structure is persisted (identifiers, phase, committed
chunk indices, digests, failure kind, revision), never asset bytes. A session
that was `VERIFYING` re-completes on the provider (upload) or re-reads and
re-hashes the staged bytes (download), because a running hash has no
serialisable form. A storage failure surfaces as `SessionStoreFailure`, never a
crash; a corrupt persisted record surfaces as `SESSION_STATE_CORRUPT` and is
left untouched (start a new session id). Every applied event is one durable
write.

## Opt-in builder wiring

```kotlin
val dataLoom = DataLoomBuilder()
    // ...
    .assetTransferConfiguration(
        DataLoomAssetTransferSpec(provider, sessions, digests, chunkSizeBytes = 1024 * 1024),
    )
    .build()

val engine: AssetTransferEngine? = dataLoom.assetTransfer   // null when not configured
```

Absent the spec, `DataLoom.assetTransfer` is `null` and nothing changes.
Building performs no provider or store I/O. `AssetProvider` is not yet a
`DataLoomProvider`, so the host still owns its lifecycle.

## Implementing a provider

Implement `AssetProvider`, honouring the idempotency, integrity, quota and
visibility contract in its KDoc, and prove it with the kit from any test
framework:

```kotlin
@Test
fun contract() = runTest {
    AssetProviderContractKit(digests) { quota -> MyProvider(quota = quota) }
        .run()
        .assertAllPassed()
}
```

The factory must return a fresh, empty provider enforcing the given
`AssetQuota` on every call. `InMemoryAssetProvider` is the executable
reference for the contract.

## Incremental digests

```kotlin
digests.newAccumulator(DigestAlgorithm.SHA_256).use { acc ->
    for (piece in pieces) acc.update(piece, 0, piece.size)
    val digest = acc.finish()   // equals the one-shot digest of the concatenation
}
```

Single-owner, not thread-safe. `finish()` closes the accumulator; `close()` is
idempotent. A running digest cannot be snapshotted, so a resumed download
re-reads the staged bytes to verify.

## Compression and encryption

```kotlin
val transforms = AssetTransferTransforms(
    compressor = DeflateAssetCompressor(),                        // zlib/DEFLATE, all platforms
    cipher = AesGcmAssetChunkCipher(keyResolver, secureRandom),   // AES-256-GCM, JVM/Android only
    keyReference = KeyReference("asset-key-2026"),                // recorded in the manifest
)
val engine = AssetTransferEngine(provider, sessions, digests, transforms = transforms)
// or: DataLoomAssetTransferSpec(provider, sessions, digests, transforms = transforms)
```

- **Keys are the host's.** `AssetKeyResolver` maps a `KeyReference` to 32 key
  bytes when a chunk is sealed or opened (typically from a platform keystore).
  DataLoom never generates, stores, caches or persists key material, and does not
  modify the array you return. The same reference must keep resolving to the same
  key for the life of an asset version. `secureRandom` is a
  `DataLoomSecureRandom` (`SystemDataLoomSecureRandom` on JVM/Android,
  `AppleDataLoomSecureRandom` on Apple); each chunk gets a fresh 96-bit nonce.
- **Order.** Upload compresses then encrypts each chunk into a versioned frame;
  download reverses it. A chunk that does not compress is stored raw, so
  incompressible data is not enlarged beyond the 3-byte header (plus 28 bytes when
  encrypted).
- **Digests are over the logical bytes and verified by the client.** The
  manifest's per-chunk and whole-object digests describe the plaintext, so a
  provider that stores ciphertext cannot check them. The client checks each
  chunk on upload before transforming and each decoded chunk plus the whole
  assembled object on download; AES-GCM's tag protects the stored bytes.
- **Provider obligations for transformed manifests** (`AssetWireFormat.isTransformed`):
  store each frame exactly as received, return it exactly as stored, skip the
  length and digest checks, do not verify the whole-object digest at completion.
  `AssetProviderContractKit` has a scenario for it.
- **What is bound as associated data:** frame version and flags, chunk index and
  count, asset id, asset version and size (not the transfer session id, which
  differs between uploader and downloader). Moving a chunk, or taking it from
  another asset or version, fails authentication.
- **Failures.** `TRANSFORM_UNSUPPORTED` (platform cannot run the algorithm, the
  engine lacks a transform the manifest needs, or configured transforms differ
  from a persisted session's) is raised before anything is transferred or, for a
  resumed upload, fails the session rather than resuming it un-encrypted.
  `ENCRYPTION_KEY_UNAVAILABLE` is resumable. `ENCRYPTION_KEY_INVALID`,
  `CHUNK_AUTHENTICATION_FAILED` and `TRANSFORM_FRAME_INVALID` are terminal and
  discard the download sink.
- **Apple:** compression works; `AesGcmAssetChunkCipher.isSupported` is `false`
  and any transfer configured with it returns `NotStarted(TRANSFORM_UNSUPPORTED)`.
  See the ADR for why and for what closing the gap needs.
- **Memory** is a small constant number of chunk-sized buffers, independent of
  asset size. The provider's quota reservation counts the logical size; frames
  can exceed it by up to 31 bytes per chunk.

## Not yet implemented

AES-GCM on Apple, secure temp files and atomic
promotion (and cleanup of abandoned persisted sessions), parallel transfer and
fairness, content-policy hooks, `ProviderType`/lifecycle for asset providers, a
transport-backed provider, and `AC-FUNC-005`. See
[ADR-0008](../adr/ADR-0008-durable-asset-transfer-sessions.md) for the order.
