# DataLoom Durable Asset Manifest History

[API reference index](./README.md)

> **Status:** Bounded second slice of `#97` (DL-043). Durable, transactional,
> versioned history of an asset's applied
> [`AssetManifest`](./asset-manifest.md) revisions, backed by a
> [`DurableStateStore`](./durable-state-contracts.md) — the fifth real domain
> adoption of that contract, reusing `RoomDurableStateStore` with zero new
> Room code. This does not add upload/download, chunking execution,
> streaming, sessions, quotas, cancellation, or content-policy logic — see
> [asset manifest](./asset-manifest.md)'s own "Deliberately not included" and
> issue `#97` for everything this slice still does not attempt.

**Package:** `io.dataloom.api.asset` (`dataloom-api`).

## Overview

[`AssetManifest`](./asset-manifest.md) is deliberately permissive: it carries
a `version: Long` but documents that "nothing here enforces monotonicity
across revisions" and defers durable history as "a future durable-history
concern, not this type's." `DurableAssetManifestHistory` is that concern,
arriving now — the same split
[`ConfigurationSnapshot`](./configuration-snapshots.md#configurationsnapshot)
(permissive) versus
[`DurableConfigurationHistory`](./configuration-snapshots.md#durableconfigurationhistory)
(durable, monotonic, retained) already establishes for a different domain.
`AssetManifest` itself is unchanged by this slice — direct construction with
a non-monotonic `version` remains legal; the discipline only applies to
callers that route revisions through this history.

| Type | Purpose |
|---|---|
| [`DurableAssetManifestHistory`](#durableassetmanifesthistory) | Durable, monotonic, bounded-retention history of one asset's `AssetManifest` revisions |
| [`AssetManifestHistoryState`](#assetmanifesthistorystate) | The durable `TState`: every currently retained revision for one `AssetId`, oldest first |
| [`AssetManifestHistoryStateCodec`](#assetmanifesthistorystatecodec) | Reference text codec for use with a generic string-payload `DurableStateStore` |

---

## Design decisions

This slice required two decisions `AssetManifest` itself deliberately left
open, both investigated against existing precedent rather than assumed:

### Should monotonicity be enforced here, even though `AssetManifest` doesn't?

Yes. `AssetManifest`'s own KDoc anticipates exactly this split: the value
type stays permissive (any positive `version` is a legal `AssetManifest`),
and a durable log layered on top adds the ordering discipline — identical to
how `DurableConfigurationHistory.apply` rejects a `ConfigurationSnapshot`
whose version does not strictly exceed the scope's current one, while
`ConfigurationSnapshot` itself enforces nothing about ordering across
instances. `DurableAssetManifestHistory.apply` applies the same rule to
`AssetManifest.version`.

### List-valued state per `AssetId`, not one scope key per revision

A `DurableStateStore` persists exactly one `TState` value per scope key via
atomic load/compare-and-set; it has no native concept of multiple rows per
scope. Two shapes could still give this domain real multi-revision history:

1. **One scope key per `(AssetId, version)` pair.** Rejected: it loses "what
   is the current revision" queryability without a separate index, and that
   index would itself need to be kept consistent under concurrent writers —
   a materially bigger piece of work than this slice's scope.
2. **One scope key per `AssetId`, whose single `TState` value carries every
   currently retained revision as a bounded list.** Chosen — the same shape
   [`ConfigurationHistoryState`](./configuration-snapshots.md#durableconfigurationhistory)
   and
   [`OperationalEventOutboxState`](./operational-envelope-redaction.md#durable-outbox-bounded-first-slice)
   already use for "a bounded list of historical records in one CAS-written
   value." One atomic compare-and-set both appends the new revision and
   evicts whatever `maxRetainedVersions` no longer allows, with no separate
   index to keep consistent.

Retention itself follows `DurableConfigurationHistory`'s simple count bound
(`maxRetainedVersions`, default `10`) rather than
`DurableOperationalEventOutbox`'s count-*and*-age pair: an asset manifest
revision is a versioned snapshot of one logical thing over time, like a
configuration snapshot, not an open-ended event stream — the simpler bound
is the better fit for this shape.

---

## `DurableAssetManifestHistory`

**Package:** `io.dataloom.api.asset` · see also
[durable state contracts](./durable-state-contracts.md)

```kotlin
public class DurableAssetManifestHistory(
    store: DurableStateStore<AssetId, AssetManifestHistoryState>,
    maxRetainedVersions: Int = 10,
    schemaVersion: Int = 1,
    maximumStateUpdateAttempts: Int = 8,
) {
    public suspend fun current(assetId: AssetId): ProviderOperationResult<AssetManifest?>
    public suspend fun retainedVersions(assetId: AssetId): ProviderOperationResult<List<Long>>
    public suspend fun history(assetId: AssetId): ProviderOperationResult<List<AssetManifest>>
    public suspend fun apply(assetId: AssetId, manifest: AssetManifest): DurableAssetManifestApplyOutcome

    public companion object {
        public val KeyEncoder: DurableStateScopeKeyEncoder<AssetId>
    }
}
```

- **`TScope` is plain `AssetId`, reused directly** — not a new composed
  wrapper type. Unlike `ConfigurationHistoryScope`/`PolicyDecisionScope`
  (which had to compose a scope because neither domain's value type carried
  a natural single-field identity), `AssetManifest` already carries exactly
  the right shape in `AssetManifest.assetId`: an `AssetId` identifies one
  logical asset across its whole version history by design. This mirrors
  `DurableUnresolvedConflictLog` reusing `ConflictId` and
  `DurableStrategyDecisionEventLog` reusing `StrategyDecisionId` rather than
  inventing a wrapper.
- **`apply`** accepts `manifest` for `assetId` only when
  `manifest.assetId == assetId` and `manifest.version` strictly exceeds the
  scope's current version, persisting it and discarding the oldest retained
  revision once `maxRetainedVersions` is exceeded.
  - A mismatched `AssetManifest.assetId` (a manifest applied under the wrong
    scope) is rejected as `DurableAssetManifestApplyOutcome.AssetIdMismatch`
    *before* the store is touched — a check specific to this domain, since
    `AssetManifest` carries its own `assetId` field unlike
    `ConfigurationSnapshot`.
  - A non-monotonic version is rejected as
    `DurableAssetManifestApplyOutcome.VersionNotMonotonic`.
- **`current`** returns the latest retained revision, or `null` before any
  successful `apply`.
- **`retainedVersions`** returns every retained revision's version number,
  oldest first (mirrors `DurableConfigurationHistory.retainedVersions`).
- **`history`** returns every retained `AssetManifest`, oldest first — the
  actual "retrieve history" capability this type exists to provide, and a
  deliberate small addition beyond `DurableConfigurationHistory`'s own public
  surface (which only ever exposes bare version numbers, never full
  snapshots, because no caller has needed full snapshot readback yet). An
  asset manifest's full revision — chunk layout, checksum, compression/
  encryption metadata — is exactly what a caller reading asset history
  actually wants back, not just a version number.
- **No `rollbackToLastKnownGood`.** `DurableConfigurationHistory` has one
  because its in-memory precedent, `DataLoomConfigurationHistory`, already
  had one. No in-memory `AssetManifest` history precedent exists, and no
  caller need for "roll back to a previous asset manifest" has been
  identified — adding it now would be unjustified scope, not adoption of an
  existing pattern.
- Same bounded load-evaluate-compare-and-set retry loop every other
  `DurableStateStore` adopter in this codebase uses: on a compare-and-set
  conflict, the whole operation re-reads current state and retries, up to
  `maximumStateUpdateAttempts` times, before giving up with
  `DurableAssetManifestApplyOutcome.ContentionLimitReached`.

---

## `AssetManifestHistoryState`

**Package:** `io.dataloom.api.asset`

```kotlin
public data class AssetManifestHistoryState(
    public val retainedManifests: List<AssetManifest>,
)
```

The durable `TState` persisted per `AssetId`: every currently retained
`AssetManifest` revision for that asset, oldest first — the same
"every currently retained value, oldest first" shape
`ConfigurationHistoryState` already uses for a different domain.

---

## `AssetManifestHistoryStateCodec`

**Package:** `io.dataloom.api.asset`

```kotlin
public class AssetManifestHistoryStateCodec : DurableStateCodec<AssetManifestHistoryState>
```

Deterministic, bounded (1 MiB) V1 text codec for use with a generic
string-payload `DurableStateStore` — see
[`RoomDurableStateStore`](./durable-state-contracts.md#roomdurablestatestore-dataloom-queue-room)
in `dataloom-queue-room`.

### Integrity — a different shape from `ConfigurationHistoryStateCodec`

`ConfigurationHistoryStateCodec.decode` recomputes each decoded snapshot's
checksum from its own decoded entries via `ConfigurationSnapshot.create`,
because a configuration snapshot's checksum is defined *over its own
fields*. `AssetManifest.checksum` is different: it is a whole-object digest
over the asset's actual bytes, which this codec never has access to (it only
ever sees manifest metadata) — there is nothing for `decode` to
independently recompute the way the configuration codec does.

Instead, `decode` reconstructs every `AssetManifest` (and its nested
`AssetChunkLayout`) through their real public constructors, which already
enforce every cross-field invariant those types define — `version` positive,
`sizeBytes` equal to `chunkLayout.totalSizeBytes`, chunk geometry contiguous
and gap/overlap free, and each `DataLoomDigest`'s byte length matching its
algorithm. Storage-layer corruption that still parses as well-formed fields
but violates any of those invariants still fails closed — just via the
domain type's own constructor validation rather than an independently
recomputed checksum.

### What this never encodes

`AssetEncryptionMetadata.keyReference` is an opaque `KeyReference` label,
never key bytes; its nonce is a nonce/IV, not secret key material. This
codec persists both exactly as `AssetManifest` itself already exposes them —
never anything beyond that.

---

## Testing

`DurableAssetManifestHistoryTest` (`dataloom-api`) covers `apply`'s
monotonic-versioning and `AssetIdMismatch` rejection, `current`/
`retainedVersions`/`history` readback, bounded retention, distinct-scope
independence, and the compare-and-set retry/failure/contention paths — the
same shape `DurableConfigurationHistoryTest` already proves for a different
domain. `AssetManifestHistoryStateCodecTest` covers round-tripping every
`AssetManifest` shape (single/multi-chunk, with and without compression/
encryption, an `AssetId` containing this codec's own delimiter characters)
and fail-closed decoding of a tampered header, chunk count, digest algorithm
name, and a post-encode-tampered `sizeBytes` that no longer agrees with its
chunk layout.

`RoomDurableStateStoreAssetManifestHistoryIntegrationTest`
(`dataloom-queue-room`) proves the fifth real domain adoption of
`RoomDurableStateStore` with zero new Room DAO/entity code: insert/round-trip
through the generic store, a restart proof (a freshly constructed
`RoomDurableStateStore` instance, sharing only the underlying
`DataLoomRoomDatabase`, recovers a previously committed multi-revision
`AssetManifestHistoryState`), and an end-to-end `DurableAssetManifestHistory`
proof applying a second revision through the real store.

---

## Deliberately not included

- **A real caller.** No subsystem constructs and applies real `AssetManifest`
  revisions yet — the same "primitive, not pipeline" posture
  `AssetManifest` itself, and `DurableConfigurationHistory`/
  `DurablePolicyDecisionLog` before their own adoption, shipped with.
- **`rollbackToLastKnownGood`.** See `DurableAssetManifestHistory`'s own
  documentation above for why this is a deliberate omission, not an
  oversight.
- **`AppleFileDurableStateStore` adoption.** This slice, like every other
  `DurableStateStore` domain adoption so far, is proven against
  `RoomDurableStateStore` only.
- **Everything `AssetManifest`'s own "Deliberately not included" section
  already lists** — upload/download, chunking execution, streaming,
  durable resumable sessions, parallelism, quotas, cancellation, and
  content-policy hooks. This slice adds durable history for a manifest
  *value*; it does not move any asset bytes.
