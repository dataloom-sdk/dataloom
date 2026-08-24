# `AssetProvider` contract: investigated, not achievable as a bounded slice yet

[API reference index](./README.md)

## Status

**Investigated (2026-08-24). No genuinely narrow, decision-free, non-redundant
`AssetProvider` contract slice found.** This documents why, so a future
attempt does not re-derive the same conclusion from scratch, and names
exactly what would need to exist first. No interface was force-added where no
genuine need for one exists yet — inventing a provider-shaped wrapper around
an operation that already has a perfectly serviceable concrete caller
(`DurableAssetManifestHistory.current`) would not actually prove what
"formalizing a provider contract" claims to prove, so none was written.
`docs/status/market-readiness.md`'s `#97` row's percentage is unchanged by
this document — it stays at 5%, with nothing new shipped this round.

## What this compares against

[`AssetManifest`](./asset-manifest.md) (bounded first slice) and
[`DurableAssetManifestHistory`](./durable-asset-manifest-history.md) (bounded
second slice) both explicitly carry no upload/download, streaming, session,
resume, quota, cancellation, or provider logic, deferring all of it to `#97`
(DL-043) `FR-ASSET-002` through `FR-ASSET-012`. This investigation asked
whether a bounded, contract-only `AssetProvider` interface — mirroring
[`StorageProvider`](./provider-spi.md)'s own precedent as a narrow, focused
provider SPI shipped ahead of any single concrete implementation — could be
the next slice, without guessing at any of that still-open behavior.

## The `StorageProvider` precedent is narrower in name than in substance

`StorageProvider`'s first commit (`53d0f54`, DL-009, issue `#18`) already
shipped two full operations — `readOutboundChanges` and
`applyInboundChanges` — each backed by request/result value types
(`OutboundChangeReadRequest`, `OutboundChangeReadResult`,
`InboundChangeApplyRequest`) built on a domain vocabulary
(`ChangeEvent`, `ChangeSet`, entity-type restrictions) that had already been
designed and shipped *before* the provider interface was written. The
interface was narrow in method count, not in how settled the shapes behind
its signatures were. The two later additions (checkpoint read/write in
`3b880ea`, `readLocalConflictCandidate` in `3826611`) followed the same
pattern: each new method arrived only once its own supporting vocabulary
(`SynchronizationCheckpoint`, `CheckpointKey`) already existed.

`AssetProvider` has no equivalent settled vocabulary to mirror. The only
asset domain concepts that exist today are `AssetManifest` (a value type
describing a shape) and `DurableAssetManifestHistory` (durable storage of
manifests). Nothing in this codebase has ever designed what a chunked-upload
request looks like, what identifies a resumable session, what a streaming
source/sink boundary's method signatures are, or what a partial-progress
result reports — the FR-ASSET-002 through -012 requirements this issue lists
are all still open questions, not yet-unwired implementations of settled
shapes. Any `AssetProvider` method covering those operations today would be
inventing the shape from nothing, not mirroring an established pattern the
way `StorageProvider`'s first two methods did.

## The narrowest candidate: a read-only manifest lookup — already served, non-redundant to formalize

The one operation genuinely free of FR-ASSET-002+ design questions is "given
an `AssetId`, resolve its current `AssetManifest`" — and this already exists,
fully implemented and tested, as
`DurableAssetManifestHistory.current(assetId): ProviderOperationResult<AssetManifest?>`.
Formalizing this *as a separate `AssetProvider`-shaped interface*, distinct
from the concrete `DurableAssetManifestHistory` class, was evaluated and
rejected:

- **No known second implementation.** `StrategyProviderSet` composes
  provider *interfaces* (`StorageProvider?`, `TransportProvider?`,
  `SchedulerProvider?`, `ConnectivityProvider?`, `QueueProvider?`) because
  each of those has, or is designed to have, more than one real backend an
  application might substitute (Room vs. SQLDelight storage, different
  transport stacks, and so on) — the provider *interface* is the seam that
  substitutability needs. `DurableAssetManifestHistory` has no equivalent
  need: its actual pluggable seam already exists one layer down, at the
  `DurableStateStore<AssetId, AssetManifestHistoryState>` constructor
  parameter it already takes (Room today, a future Apple file store or other
  backend later). Wrapping `current` in a new interface would add a second,
  redundant substitution point above a class that is already substitutable
  where it actually needs to be.
- **No known caller wanting the abstraction.** Both `AssetManifest.md` and
  `DurableAssetManifestHistory.md` state plainly that no subsystem
  constructs or applies real `AssetManifest` revisions yet. An interface
  exists to let a caller depend on an abstraction instead of a concrete
  type; with zero real callers, there is nothing to decouple from
  `DurableAssetManifestHistory` today. This is exactly the "artifact
  without real behavior" pattern [ADR-0002](../adr/ADR-0002-v1-artifact-and-foundation-architecture.md)'s
  "Rejected alternatives" section already names — "Create only empty
  artifact wrappers... an artifact name without owned behavior... does not
  satisfy V1" — except here the wrapper would have one real method
  delegating to an existing concrete implementation, not zero methods; the
  underlying problem is the same.
- **`DataLoomProvider` membership brings obligations disproportionate to one
  read method.** Every `DataLoomProvider` (the base interface every provider
  SPI in this codebase implements, including `StorageProvider`) requires
  `descriptor: ProviderDescriptor`, `initialize(context)`, `health()`, and
  `close()` — a full provider lifecycle. `ProviderDescriptor.type` must be a
  `ProviderType`, a **closed enum** (`STORAGE`, `TRANSPORT`, `SCHEDULER`,
  `CONNECTIVITY`, `AUTHENTICATION`, `SERIALIZATION`, `ENCRYPTION`,
  `COMPRESSION`, `LOGGING`, `MONITORING`, `QUEUE`) with no `ASSET` category
  today. Making `AssetProvider` a real `DataLoomProvider` would require
  either adding a new enum entry (a real, if small, product decision: what
  exactly does an asset provider's category mean, and does it also cover
  the eventual upload/download provider or a different one) or misusing an
  existing category. It would also require deciding what `initialize`,
  `health`, and `close` mean for a subsystem that has no connection,
  credential, or session concept yet — those questions only have real
  answers once FR-ASSET-002's session design exists. A single read-only
  lookup method does not need any of this; grafting it onto the
  `DataLoomProvider` lifecycle to make it "provider-shaped" would import
  four undesigned obligations to serve one already-served one.

## Issue `#97` itself says a contract-only SPI is not the goal

The issue's own Context section states directly: *"No production asset
synchronization subsystem exists in the audited baseline. V1 requires upload
and download with chunking, streaming, resume, integrity, quotas,
cancellation, and safety — not a contract-only SPI."* This is not merely
permissive of skipping a contract-only slice; it is the issue author's
explicit statement that a contract-only SPI — narrow or otherwise — is not
what closes this gap. That reinforces, rather than merely permits, this
investigation's conclusion.

## What would need to exist first

A genuine `AssetProvider` contract needs, at minimum, one of FR-ASSET-002
through `-006`'s designs settled first — specifically:

1. **A chunked upload/download request and result shape** (FR-ASSET-002),
   including how a caller supplies or receives asset bytes without loading
   the whole object into memory (FR-ASSET-005's bounded-memory streaming
   source/sink contracts) — this is the vocabulary `readOutboundChanges`/
   `applyInboundChanges` had *before* `StorageProvider` was written, and
   asset sync does not have its equivalent yet.
2. **A durable resumable session shape** (FR-ASSET-003) — what identifies an
   in-progress transfer, what state it durably tracks, and how restart
   recovery resumes it — before any method signature naming a "session" can
   be more than a guess.
3. **A settled `ProviderType` decision** for where an asset provider fits in
   the closed provider-category enum, once it's clear whether "asset
   provider" is one category or splits into narrower roles (e.g., a
   storage-backend role separate from a policy/quota role).

None of these exists in the repository today, and each is itself a real
design decision — not a bounded slice addable the way `AssetManifest` and
`DurableAssetManifestHistory` were.

## What is not in question

- `AssetManifest` and `DurableAssetManifestHistory` are both fully
  implemented and tested exactly as documented in their own pages. This
  investigation found no defect in either — only the absence of a genuine,
  non-redundant provider contract to build on top of them today.
- `StorageProvider` itself is not in question either; it is a real, working
  precedent. This investigation's finding is narrower: that its "narrow
  interface" shape depended on settled domain vocabulary that does not yet
  exist for assets, not that provider contracts in general are unwise.
- This finding does not change `#97`'s overall percentage or its "Still
  pending" wording — `FR-ASSET-002` through `-012` were already listed as
  fully open before this investigation, and remain so after it.

## References

- [Asset manifest](./asset-manifest.md) and
  [durable asset manifest history](./durable-asset-manifest-history.md) —
  the two bounded slices this investigation searched for a provider-contract
  slice on top of.
- [Provider SPI](./provider-spi.md) and the `StorageProvider` source
  (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/storage/StorageProvider.kt`)
  — the precedent shape this investigation compared against.
- [Configuration resolver caller investigation](./configuration-resolver-caller-investigation.md)
  — the investigation-doc precedent this document follows in structure and
  posture.
- `docs/adr/ADR-0002-v1-artifact-and-foundation-architecture.md`'s "Rejected
  alternatives" section — "Create only empty artifact wrappers" — the
  anti-pattern this investigation weighed the redundant-wrapper candidate
  against.
- GitHub issue `#97` — its own "not a contract-only SPI" statement.
