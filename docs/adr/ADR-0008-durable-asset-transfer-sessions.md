# ADR-0008: Durable asset transfer sessions and opt-in builder wiring

- **Status:** Accepted (decision D17, taken by the project lead on 2026-09-20);
  implemented
- **Date:** 2026-09-20
- **Gate:** [`#97` / DL-043 asset synchronization](https://github.com/dataloom-sdk/dataloom/issues/97), slice 2
- **Builds on:** [ADR-0006](./ADR-0006-asset-transfer-and-streaming-digest.md),
  whose "next slice 1" (durable session persistence) and part of "next slice 6"
  (`DataLoomBuilder` wiring) this record delivers

> This ADR records what slice 2 shipped and why. It does not claim the V1 asset
> requirements are met; see [What is still open](#what-is-still-open).

## Context

Slice 1 made the transfer session a pure state machine (`AssetTransferSession`)
behind a compare-and-set seam (`AssetTransferSessionStore`) with only an
in-memory implementation, so a process restart lost every in-flight transfer.
`FR-ASSET-003` requires durable resumable sessions and restart recovery.

The repository already has one way to make a domain durable, the
`DurableStateStore` domain-adoption pattern (`DurableUnresolvedConflictLog`,
`DurableAssetManifestHistory`, the strategy diagnostics logs, and others), so
this slice follows it rather than inventing a parallel mechanism.

## Decision

**`DurableAssetTransferSessionStore` adopts `DurableStateStore` for transfer
sessions.**

- **Scope and state.** `TScope` is `AssetTransferSessionId` (reused, not
  wrapped); `TState` is `AssetTransferSession` itself. The state carries the
  manifest (per-chunk and whole-object *digests*), the ascending committed chunk
  indices, the phase, the failure kind and the revision. It never carries
  payload bytes, source or sink locations, or key material.
- **Codec.** `AssetTransferSessionCodec` is a versioned, deterministic, bounded
  text codec, fail-closed on decode. The manifest is embedded through the
  existing `AssetManifestHistoryStateCodec` rather than re-implemented, so a
  persisted manifest is validated by the code that already guards persisted
  manifest history. All session invariants (indices in range, `VERIFYING` and
  `COMPLETED` need every chunk, failure kind only on `FAILED`) are enforced by
  the real `AssetTransferSession` constructor on decode; the codec additionally
  requires committed indices strictly ascending (the canonical form), so an
  out-of-order or duplicated list is treated as corruption. Bound: 2 Mi
  characters per session (the embedded manifest is limited to 1 Mi by its own
  codec), which covers assets of roughly ten thousand chunks; larger assets need
  a larger chunk size.
- **Two version counters, mapped.** The store record `version` is the
  storage-level CAS counter; `AssetTransferSession.revision` is the domain
  counter the engine uses. `trySave` reads the record, accepts the write only if
  the persisted `revision` equals the caller's expected revision, then writes
  with a CAS on the record `version` it just read; a CAS conflict reloads and
  re-checks, up to `maximumStateUpdateAttempts` (default 8). This keeps the
  engine and state machine unchanged over either store.
- **Typed outcomes.** `DurableAssetTransferSessionSaveOutcome`: `Saved`,
  `StaleRevision(current)`, `PersistenceFailure`, `ContentionLimitReached`;
  `DurableAssetTransferSessionLoadOutcome`: `Missing`, `Found`,
  `PersistenceFailure`. `AlreadyRecorded` and a domain `Conflict` were not
  needed: the state machine already makes redelivered events idempotent
  (`Unchanged`), and a lost race is `StaleRevision`.
- **Corruption is never guessed at.** A record persisted under a different
  schema version, or stored under another session's scope, is reported as
  `AssetErrorKind.SESSION_STATE_CORRUPT` (non-recoverable) and is left in place,
  not overwritten.

**Store failures are an outcome, not a crash.** `AssetTransferSessionStore`
implementations report a non-race failure by throwing
`AssetTransferSessionStoreException(error)`. `AssetTransferEngine.upload`,
`download` and `cancel` catch it and return the new
`AssetTransferOutcome.SessionStoreFailure(error)`. Because the store keeps the
last successfully persisted state and every provider operation is idempotent,
a recoverable failure (`SESSION_STORE_FAILURE`, or the platform store's own
recoverable error) means "repeat the call". Choosing an exception at this seam,
rather than threading a result type through every `load`/`save`/`advance` call
in the engine, kept the slice-1 engine and its tests untouched apart from one
guard. A dedicated sessionless outcome was used instead of overloading
`NotStarted`, whose meaning is "no session was created".

**What resume uses.**

- *Committed chunk indices and per-chunk digests:* persisted in the session; the
  engine skips committed chunks and re-verifies each chunk it reads against the
  persisted manifest.
- *Whole-object digest state:* an in-flight hash accumulator has no portable
  serialisable form (ADR-0006), so what is persisted is the manifest's
  whole-object digest and the `VERIFYING` phase. After a restart in `VERIFYING`,
  an upload calls the provider's idempotent `completeUpload` again and sends no
  chunk; a download re-reads the staged bytes from the sink and re-hashes them.
- *Terminal states:* `COMPLETED`, `FAILED` and `CANCELLED` are persisted and
  replay as their outcome without touching the provider.
- *Crash between the provider accepting a chunk and the record being written:*
  on resume the provider's committed set reconciles the record (unchanged from
  slice 1), so the chunk is not resent.
- *Two workers:* both may drive one session; exactly one write of a revision
  wins, the other's save is `StaleRevision`, the engine reloads and its event is
  an idempotent `Unchanged`. Neither corrupts the other; they converge.

**Opt-in builder wiring.** `DataLoomAssetTransferSpec` (provider, session store,
digest calculator, chunk size, digest algorithm, verify buffer),
`DataLoomBuilder.assetTransferConfiguration(spec)` and
`DataLoom.assetTransfer: AssetTransferEngine?` follow the existing `*Spec`
pattern: absent means `null` and inert; building performs no provider or store
I/O and does not initialize the provider. `dataloom-runtime` now depends on
`dataloom-assets` (`api`, because `AssetTransferEngine` is in the facade's public
signature). The engine class is exposed directly rather than behind a new
interface: it already is the intended surface, and a wrapper would add nothing.

## Consequences

- An interrupted transfer survives a process restart when the host supplies a
  `DurableAssetTransferSessionStore` over a platform durable store (for example
  `RoomDurableStateStore` with `AssetTransferSessionCodec` and
  `DurableAssetTransferSessionStore.KeyEncoder`).
- Every applied session event is one durable write (one per chunk commit). That
  is the cost of restart-safe resume; batching commits is a later optimisation,
  not a correctness requirement.
- The codec is a text format with no checksum of its own. Integrity of
  *content* is guarded by the manifest digests and provider verification; the
  codec's job is structural validity. A corrupt record that still decodes to a
  legal different state is not detectable by it, which is the same posture as
  the other durable codecs in the repository.
- `AssetTransferOutcome` gained a variant; exhaustive `when` expressions over it
  must add a branch (pre-V1, no external consumers).

## What is still open

Ordered remaining slices for `#97`:

1. Concrete compression and encryption algorithms wired into the engine, with
   the logical-versus-wire digest decision (ADR-0006 open decision 1).
2. Secure temp files, atomic promotion of a verified sink, crash and
   abandoned-session cleanup, and file-backed `AssetSource`/`AssetSink` for
   Android and iOS (`FR-ASSET-009`). Persisted sessions of abandoned transfers
   currently remain in the store; cleanup policy belongs here.
3. Parallel chunk transfer with concurrency limits and fairness
   (`FR-ASSET-006`).
4. Content allow/deny/scan/quarantine hooks (`FR-ASSET-012`).
5. `ProviderType`/lifecycle for asset providers (so the builder can own the
   provider lifecycle), `#94` retry/circuit and `#96` events/metrics around the
   engine, and a transport-backed provider.
6. `AC-FUNC-005` on Android and iOS end to end, and public docs and samples.

## Rejected alternatives

- **Persist an `AssetTransferSession` field-by-field in new Room tables.** The
  generic `DurableStateStore` already exists and other domains adopted it with
  zero new Room code.
- **Use the record `version` as `AssetTransferSession.revision`.** Couples the
  domain counter to a storage detail (a Room row starts at 0, another store may
  not) and would make the in-memory and durable stores behave differently.
- **Thread `ProviderOperationResult` through the engine's store calls.** A large
  rewrite of a working, tested engine for the same observable behaviour.
- **Persist digest accumulator state.** Not portable (ADR-0006).
- **Copy the manifest line format into a second codec.** Two places to keep in
  sync; the history codec already validates it.

## Validation

Verified locally on the JVM (see the pull request for exact commands and
results): codec round trips for every phase, direction, failure kind and
metadata combination; rejection of out-of-order, duplicate, out-of-range and
malformed committed lists, phase and failure inconsistencies, truncated and
oversized payloads, and tampered embedded manifests; typed save and load
outcomes including stale revisions and contention limits; the engine over the
durable store (crash between chunks, crash between provider accept and record,
restart while `VERIFYING` for both directions, terminal-state replay, two
workers racing on one session with an observed CAS conflict, store failure then
recovery, a corrupt record, a record under another scope); a check that no
persisted row contains asset bytes; the builder wiring; and the new domain
through `RoomDurableStateStore` with a mocked DAO. The Room test proves the
codec and revision check survive the store's entity mapping and a fresh store
instance, not Room SQL.

**Not verified locally:** execution of any Apple-target test (macOS CI only);
persistence through a real Room database on a device or emulator; an Apple
file-backed durable store for this domain (none exists for other domains
either, beyond the proof modules for the conflict and circuit-breaker logs).
