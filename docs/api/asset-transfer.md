# Asset transfer (`dataloom-assets`)

> **Status:** Slices 1 and 2 of `#97` (DL-043). Contracts, in-memory reference
> behaviour, and (slice 2) durable session persistence on `DurableStateStore`
> plus opt-in `DataLoomBuilder.assetTransferConfiguration` wiring. Still no real
> compression/encryption, no file-backed source/sink, no parallelism, no
> content-policy hooks, and no transport-backed provider. The decisions behind
> it are in
> [ADR-0006](../adr/ADR-0006-asset-transfer-and-streaming-digest.md) (transfer
> design) and [ADR-0008](../adr/ADR-0008-durable-asset-transfer-sessions.md)
> (durable sessions and wiring), which list the ordered next slices.
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
| `AssetCompressor`, `AssetChunkCipher` (+ identity implementations) | Compression and AEAD-style cipher SPIs |
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

## Not yet implemented

Concrete compression and encryption algorithms, secure temp files and atomic
promotion (and cleanup of abandoned persisted sessions), parallel transfer and
fairness, content-policy hooks, `ProviderType`/lifecycle for asset providers, a
transport-backed provider, and `AC-FUNC-005`. See
[ADR-0008](../adr/ADR-0008-durable-asset-transfer-sessions.md) for the order.
