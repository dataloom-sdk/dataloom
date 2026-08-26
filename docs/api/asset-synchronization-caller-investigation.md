# Asset synchronization: a real caller, re-investigated — still blocked, plus a new streaming-integrity gap found

[API reference index](./README.md)

## Status

**Investigated (2026-08-26). No genuinely bounded, safe-to-ship slice found.**
This round re-approached `#97` (DL-043) from a different angle than
[`asset-provider-contract-investigation.md`](./asset-provider-contract-investigation.md)
(round 21, `#360`): instead of asking whether a contract-only `AssetProvider`
interface could be added, this asks whether a **real caller** exists or could
exist for `AssetManifest`/`DurableAssetManifestHistory` — a coordinator or
pipeline that actually constructs and applies a manifest revision during
synchronization, the way `#353` found `StrategySynchronizationExecutionCoordinator`
as a genuine caller for `PolicyEvaluator`. It converges on the same root
blocker `#360` already found, confirmed independently rather than assumed,
and surfaces one genuinely new, previously undocumented finding along the
way: half of FR-ASSET-004 (per-chunk integrity verification) is already
fully achievable today with **zero new code**, while the other half
(whole-object integrity verification) is blocked by a real, specific,
previously unnamed gap — no streaming/incremental digest capability exists
anywhere in this codebase, and adding one would reverse an already-made,
explicitly documented architectural decision. No code was added.
`docs/status/market-readiness.md`'s `#97` row is unchanged at 5% — nothing
new shipped, matching this session's own investigation-doc precedent.

## The three candidates this round was asked to check

1. A real caller/pipeline that constructs and applies `AssetManifest`
   revisions during synchronization.
2. A transport-layer counterpart to `AssetChunkLayout` (chunk transfer/upload
   primitives).
3. Whether `DurableAssetManifestHistory` has a real caller yet.

All three were investigated directly against source, not assumed from the
existing docs.

## Candidates 1 and 3 collapse to the same blocker

`DurableAssetManifestHistory.apply(assetId, manifest)` is the only "apply a
revision" capability that exists anywhere in the codebase — reading
[`DurableAssetManifestHistory.kt`](../../dataloom-api/src/commonMain/kotlin/io/dataloom/api/asset/DurableAssetManifestHistory.kt)
in full confirms it has exactly the shape `docs/api/durable-asset-manifest-history.md`
already documents: monotonic-version enforcement, bounded retention, CAS
retry — and nothing beyond that. A genuine caller for it during
synchronization would need to *construct* a real `AssetManifest` describing
bytes that were actually transferred. Nothing in this codebase performs an
actual chunked transfer (`FR-ASSET-002`) — confirmed by a repository-wide
search for `AssetId` (nine files total: the three `dataloom-api` asset
source/test files, one Room integration test, and `Identifiers.kt`/its own
test — `AssetId`'s declaration site — with zero hits in `dataloom-runtime`,
`dataloom-model`'s `ChangeEvent`/`ChangeSet` types, or any
`SynchronizationPipeline`). No entity, `ChangeEvent`, or `ChangeSet` in this
codebase carries any asset-attachment concept that would create a natural
need to invoke `DurableAssetManifestHistory.apply` from an existing sync
pipeline the way `#272` found `readLocalConflictCandidate` had a real
pipeline (`InboundPullSynchronizationPipeline`) waiting for it.

Wiring a caller today would therefore mean one of two things, both rejected:

- **Inventing a caller that constructs a manifest describing bytes nobody
  actually moved.** This would not be "a real caller/pipeline that actually
  constructs and applies `AssetManifest` revisions during synchronization" —
  it would be a caller that *simulates* synchronization to give
  `DurableAssetManifestHistory.apply` something to call, exactly the "empty
  artifact wrapper... an artifact name without owned behavior" anti-pattern
  [ADR-0002](../adr/ADR-0002-v1-artifact-and-foundation-architecture.md)'s
  "Rejected alternatives" section already names, and `#360` already applied
  to the adjacent `AssetProvider` question.
- **Requiring a caller to supply an already-fully-formed `AssetManifest`
  from outside DataLoom** (e.g., an application that already uploaded an
  asset through its own mechanism and just wants the revision recorded).
  This is technically possible today — nothing stops an application from
  calling `DurableAssetManifestHistory.apply` directly, which is exactly why
  `#360` found no missing capability here, only a missing *caller*. But it
  is not a synchronization capability DataLoom provides; it is an
  already-fully-exposed public API a host application can already use
  as-is. There is no coordinator-shaped gap to fill on DataLoom's side —
  adding a thin `AssetSynchronizationCoordinator` that does nothing but
  forward to `apply` would be the same redundant-wrapper shape `#360`
  rejected for `current(assetId)`, just on the write side instead of the
  read side.

## Candidate 2: chunk transfer primitives re-confirm `#360`'s finding, not new information

`AssetChunkLayout`/`AssetChunkDescriptor`
([`AssetManifest.kt`](../../dataloom-api/src/commonMain/kotlin/io/dataloom/api/asset/AssetManifest.kt))
describe chunk *geometry* only — index, byte offset, length, and a per-chunk
`DataLoomDigest` — with zero transport-facing fields: no session identifier,
no per-chunk transfer status, no upload/download handle or URL, no
in-flight/completed/failed state. A transport-layer counterpart would need
exactly the settled vocabulary `#360` already found absent: a chunked
upload/download request/result shape (`FR-ASSET-002`), a durable resumable
session shape (`FR-ASSET-003`), and a streaming source/sink contract
(`FR-ASSET-005`). Nothing new was found here beyond re-confirming `#360`'s
conclusion from the chunk-geometry side rather than the provider-interface
side.

## A new finding: FR-ASSET-004 integrity verification splits into an achievable half and a genuinely blocked half

Neither `#360` nor either shipped asset slice's own docs previously
addressed integrity *verification* (as opposed to integrity *recording*, which
`AssetManifest`/`AssetChunkDescriptor` already do via `DataLoomDigest`
fields). Investigating this directly surfaced a real split:

### Per-chunk verification is already fully achievable today — no new code needed

A single chunk's bytes are bounded by definition (a chunk is, by
construction, a bounded piece of the asset). Verifying one already exists
as directly composable existing capability:
`calculator.digest(chunkDescriptor.checksum.algorithm, chunkBytes).contentEquals(chunkDescriptor.checksum)`,
using [`DataLoomDigestCalculator.digest`](../../dataloom-model/src/commonMain/kotlin/io/dataloom/api/security/DataLoomDigestCalculator.kt)
(already documented as "the integrity primitive future asset-transfer work
... is expected to consume") and
[`DataLoomDigest.contentEquals`](../../dataloom-model/src/commonMain/kotlin/io/dataloom/api/security/DataLoomDigest.kt)
(already implemented, already tested). Wrapping this one-line composition in
a dedicated `AssetChunkVerifier` type was evaluated and rejected: there is no
new logic to add beyond an equality check already fully expressible from two
already-shipped, already-tested primitives, and no known caller wanting the
abstraction — the same "redundant wrapper around already-served capability"
finding `#360` made for `current(assetId)`, applied here to write-once
verification instead of read.

### Whole-object verification is genuinely blocked — and the blocker is more specific than "FR-ASSET-002/003 aren't designed yet"

`AssetManifest.checksum`'s own KDoc defines it precisely: *"whole-object
integrity digest over the asset's bytes"* — a digest of the actual raw
bytes, not a digest computed over the chunk digests. Verifying it against an
asset received in bounded-memory chunks (the FR-ASSET-005 requirement
`AssetManifest`'s own asset-shape design already anticipates) would require
either:

- **buffering the entire asset in memory before verifying** — directly
  violating this issue's own "Never require a whole asset in memory"
  requirement, or
- **an incremental/streaming digest capability that updates a running hash
  across chunks and finalizes once** — which does not exist anywhere in this
  codebase today.

Confirmed by reading both production `DataLoomDigestCalculator`
implementations directly:

- **JVM** ([`SystemDataLoomDigestCalculator.kt`](../../dataloom-model/src/jvmMain/kotlin/io/dataloom/api/security/SystemDataLoomDigestCalculator.kt))
  calls `MessageDigest.getInstance(...).digest(input)` — the one-shot
  convenience method. The underlying JCA `MessageDigest` class *does*
  support incremental `update()`/`digest()` calls, but `DataLoomDigestCalculator`'s
  multiplatform contract deliberately exposes only the one-shot method.
- **Apple/Kotlin-Native** ([`AppleDataLoomDigestCalculator.kt`](../../dataloom-model/src/iosMain/kotlin/io/dataloom/api/security/AppleDataLoomDigestCalculator.kt))
  is explicit about this being a deliberate choice, not an oversight — its
  own KDoc states it uses CommonCrypto's one-shot convenience functions
  (`CC_SHA256`/`CC_SHA512`) rather than the `Init`/`Update`/`Final` stateful
  trio, *specifically* because "the stateful trio would require holding a
  native context struct alive across separate Kotlin calls, real added
  complexity this simpler, one-call-per-digest shape does not need."

So this is not simply "streaming digest support hasn't been built yet" — it
is an already-made, already-documented architectural decision to keep
`DataLoomDigestCalculator` one-shot, specifically to avoid native-context
lifecycle complexity on the Apple target. Adding incremental hashing would
mean reversing that decision (or adding a second, parallel stateful
contract alongside it) — deciding the multiplatform API shape (a stateful
accumulator type? a new method returning one? who owns/disposes its native
context on Apple if a caller abandons it mid-stream?), which values this
finding reports honestly rather than deciding unilaterally in this round.
This is real, scoped, and much narrower than `FR-ASSET-002`/`FR-ASSET-003`'s
open session/streaming design — but it is still a genuine design decision
this investigation is not the right place to make unprompted, given this
session's own discipline against shipping unbounded API reversals without a
clear caller need driving the shape.

## What is not in question

- `AssetManifest` and `DurableAssetManifestHistory` remain fully implemented
  and tested exactly as their own docs describe. This investigation found no
  defect in either.
- `#360`'s `AssetProvider`-contract conclusion is confirmed, not
  contradicted, by this investigation's different angle — both converge on
  the same root cause (`FR-ASSET-002`/`FR-ASSET-003`'s chunked-transfer and
  session vocabulary remain undesigned).
- `DataLoomDigestCalculator`/`DataLoomDigest` are not in question either —
  both work exactly as documented for their existing one-shot use. This
  investigation's finding is narrower: their deliberately one-shot shape is
  incompatible with bounded-memory whole-object verification of a
  multi-chunk asset, a combination nothing needed to reconcile until now.
- This finding does not change `#97`'s percentage (still 5%) or add new
  scope to `FR-ASSET-002`/`-003`'s already-listed "Still pending" items — it
  adds one more specific, previously unnamed sub-item (streaming/incremental
  digest support) to what full FR-ASSET-004 will eventually need, without
  claiming it blocks everything else in the gate.

## What would need to exist first

To close either remaining candidate for real:

1. `FR-ASSET-002`/`FR-ASSET-003`'s chunked-transfer and durable-session
   design (unchanged from `#360`'s own "What would need to exist first"
   section — this investigation did not find a way around it).
2. If and when whole-object streaming verification is needed, a decided
   multiplatform incremental-digest API shape — informed by whatever
   `FR-ASSET-005`'s streaming source/sink contract ends up looking like,
   since the two are naturally the same design conversation (a streaming
   sink that writes chunks somewhere is the natural place to also feed an
   incremental hasher, so designing them separately risks a mismatched
   shape).

## References

- [`AssetProvider` contract investigation](./asset-provider-contract-investigation.md)
  (`#360`) — the prior round's investigation this one confirms from a
  different angle.
- [Asset manifest](./asset-manifest.md) and
  [durable asset manifest history](./durable-asset-manifest-history.md) —
  the two shipped primitives this investigation searched for a real caller
  on top of.
- `dataloom-model/src/commonMain/kotlin/io/dataloom/api/security/DataLoomDigestCalculator.kt`,
  `dataloom-model/src/jvmMain/kotlin/io/dataloom/api/security/SystemDataLoomDigestCalculator.kt`,
  `dataloom-model/src/iosMain/kotlin/io/dataloom/api/security/AppleDataLoomDigestCalculator.kt`
  — the one-shot digest contract and both platform implementations this
  investigation read to establish the streaming-verification gap.
- GitHub issue `#97` — its "Never require a whole asset in memory" and
  "per-chunk and whole-object integrity verification" (`FR-ASSET-004`/`-005`)
  requirements.
