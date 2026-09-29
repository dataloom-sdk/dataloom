# ADR-0016: File-backed asset transfer storage (secure temp files, atomic promotion, cleanup)

- **Status:** Accepted; implemented for JVM/Android
- **Date:** 2026-09-29
- **Gate:** [`#97` / DL-043 asset synchronization](https://github.com/dataloom-sdk/dataloom/issues/97), slice 4
- **Builds on:** [ADR-0006](./ADR-0006-asset-transfer-and-streaming-digest.md)
  (`AssetSource`/`AssetSink`/`AssetProvider` contracts, resolves its open
  decision 3), [ADR-0008](./ADR-0008-durable-asset-transfer-sessions.md) and
  [ADR-0014](./ADR-0014-asset-chunk-transforms-and-digest-domain.md), whose
  "next slice" item ("secure temp files, atomic promotion of a verified sink,
  and crash/abandoned-session cleanup ... with real file-backed
  `AssetSource`/`AssetSink`") this record delivers for JVM/Android

> This ADR records what slice 4 shipped and why, including what it does not
> cover. It does not claim `FR-ASSET-009` is met on every platform: see
> [What is still open](#what-is-still-open).

## Context

Slices 1 to 3 defined bounded-memory `AssetSource`/`AssetSink` streaming
contracts and an `AssetProvider` SPI, but the only implementations were
in-memory (`InMemoryAssetSource`/`InMemoryAssetSink`/`InMemoryAssetProvider`),
explicitly documented as "not bounded-memory ... for tests, samples and
development, never production." Nothing wrote asset bytes to a real
filesystem, so `FR-ASSET-009` (secure temp files, atomic promotion, cleanup)
had no implementation to make decisions against.

## Decisions

### D28: one write-to-temp-then-rename helper, reused by source, sink and provider

`io.dataloom.assets.file.FileAssetIo` (internal) centralises the idiom this
repository already uses twice — `dataloom-storage-file`'s JVM
`FileSystemFacade` (`Files.move` with `ATOMIC_MOVE`/`REPLACE_EXISTING`) and,
on Apple, `AppleQueueFileIo`'s `appleQueueWriteUtf8FileAtomically` (POSIX
`open`/`write`/`fsync`/`rename`) — rather than inventing a third variant:
create a same-directory temp file (owner-only permissions when the
filesystem supports POSIX; the JVM has no portable stronger guarantee on a
filesystem that does not, for example NTFS), write it fully, then promote it
onto its destination with one `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`.
Every new type in this slice is built on it, so "never a partially-written
file at a final path" is proven once, not three times.

### D29: three new types, JVM/Android only, no Apple implementation this slice

- **`FileAssetSource`** (`AssetSource`): random-access reads of a real file on
  disk, for uploading a local file as-is.
- **`FileAssetSink`** (`AssetSink`), plus `promote()`: stages a download in a
  secure temp file next to its destination; `promote()` is additional API on
  the concrete class (not an `AssetSink` override — that interface predates a
  file-backed implementation and has no promotion hook) that the caller
  invokes once, and only once, `AssetTransferEngine.download` has returned
  `AssetTransferOutcome.Completed`. Until `promote()` runs, the destination
  path does not exist; `discard()` (already called by the engine on failure or
  cancellation) deletes the temp file instead.
- **`FileAssetProvider`** (`AssetProvider`): a filesystem-backed sibling of
  `InMemoryAssetProvider` that passes the same
  `AssetProviderContractKit`. Chunks are staged as per-chunk temp files under
  `uploads/<safe sessionId>/`; `completeUpload` streams them (one
  `readBufferBytes`-sized buffer, independent of asset size) into one
  assembled temp file, verifies the whole-object digest exactly like
  `InMemoryAssetProvider` for an untransformed manifest, and only then
  promotes that file onto `committed/<safe assetId>/<version>/asset.bin`
  with one atomic rename. `readChunk` re-derives each chunk's byte range from
  lengths recorded during assembly (not from the manifest descriptor's
  *logical* length, which a transform frame's actual byte length may exceed
  by up to `AssetWireFormat.MAX_FRAME_OVERHEAD_BYTES`), so it is correct for
  both plain and transformed manifests.

These three types live in `dataloom-assets`' `jvmMain` source set, which is
also the source set Android consumes (the module has no separate Android
target; see ADR-0014). There is **no Apple implementation** in this slice:
building the POSIX-based equivalent (mirroring `AppleQueueFileIo`) for three
new types together with the file-assembly and sweep logic below was judged
too large to fold into one reviewable PR alongside the JVM/Android
implementation, and nothing in the V1 gate ordering requires it before the
other pending slices (parallel transfer, content-policy hooks, provider
lifecycle). It is listed under [What is still open](#what-is-still-open) as
its own slice, not silently dropped.

### D30: cleanup is eager on a terminal transition, plus a bounded, host-driven sweep for genuinely abandoned sessions

ADR-0006 left "provider-side expiry of abandoned upload sessions" as an open
decision (its open decision 3). This slice resolves it for `FileAssetProvider`
with two mechanisms, deliberately not a background timer this class would own:

1. **Eager, on a terminal transition.** `abortUpload` deletes the session's
   upload directory immediately. `completeUpload` deletes it too, once its
   chunk files have been read into the newly committed asset file — their
   bytes now live at the committed path, so the per-chunk temp files serve no
   further purpose. Both cases are covered by tests that check the
   filesystem directly, not just the returned `ProviderOperationResult`.
2. **A bounded, host-driven sweep for outright abandonment** (the client
   crashes or is uninstalled and never calls `abortUpload`, so mechanism 1
   never runs): `FileAssetProvider.sweepAbandonedUploads(olderThan: Duration)`
   deletes upload directories whose last filesystem modification predates the
   cutoff, using filesystem timestamps rather than the in-memory `uploads`
   map — a restarted process remembers none of its previous sessions, which
   is exactly the case this sweep exists for. It runs once per call (one
   directory listing, one delete per stale entry) and is bounded; the host
   decides when and how often to call it (a periodic job, or once at
   startup). This class starts no timer of its own.

`FileAssetSink`'s own temp file has no equivalent sweep: a caller staging many
concurrent downloads is responsible for its own cleanup pass over its temp
directory if that matters to it, the same way it already owns calling
`discard()` on failure. Only `FileAssetProvider`, which owns a directory tree
for potentially many unrelated callers' sessions, gets a built-in sweep.

### D31: `FileAssetSink.read` treats a real file's sparseness as the contract's "no data yet", tracked as bounded ranges

`AssetSink.read` must return `-1` "at or beyond the end of the available
bytes." `InMemoryAssetSink` enforces this exactly with a per-byte
`BooleanArray`. A real file has no such signal on its own — reading a byte
range no `write` ever touched succeeds and returns zeros, because the
filesystem does not distinguish "never written" from "written as zero." A
per-byte bitmap over a real file would cost roughly one bookkeeping byte per
asset byte, which defeats the entire point of a file-backed sink for a
multi-gigabyte asset.

`FileAssetSink` instead tracks the **written byte ranges themselves**, merged
and non-overlapping, in a small in-memory `TreeMap`. Memory is bounded by the
number of non-contiguous writes — in practice the chunk count, not the asset
size — while `read` still returns `-1` for any position outside a written
range, matching `InMemoryAssetSink`'s observable behaviour. This has no
bearing on correctness even if it were skipped: a resumed download that
re-reads corrupted or missing bytes as zeros would still fail the manifest's
whole-object digest and be rejected (ADR-0006's resume semantics already
require that path), so this is a fidelity choice for the contract's exact
wording, not a safety-critical one.

## What is still open

Ordered remaining slices for `#97` (unchanged from ADR-0008/ADR-0014 except
item 1, delivered here for JVM/Android):

1. ~~Secure temp files, atomic promotion, cleanup, file-backed
   `AssetSource`/`AssetSink` for JVM/Android~~ **JVM/Android done by this ADR.**
   The equivalent Apple (POSIX-based) implementation is still open.
2. Parallel chunk transfer with concurrency limits and fairness
   (`FR-ASSET-006`).
3. Content allow/deny/scan/quarantine hooks (`FR-ASSET-012`).
4. `ProviderType`/lifecycle for asset providers, `#94` retry/circuit and `#96`
   events/metrics around the engine, and a transport-backed provider.
5. Apple AES-GCM (ADR-0014).
6. `AC-FUNC-005` on Android and iOS end to end, and public docs and samples.

## Consequences

- `FR-ASSET-009` is implemented for JVM and Android; a real file-backed
  provider now exists to develop and test against without holding whole
  assets in memory.
- Public surface added to `dataloom-assets`' JVM/Android target only (no
  common or Apple API changed): `io.dataloom.assets.file.FileAssetSource`,
  `FileAssetSink` and `FileAssetProvider`. `dataloom-assets`' JVM ABI baseline
  (`api/dataloom-assets.api`) was regenerated; its Kotlin/Native ABI baseline
  (`api/dataloom-assets.klib.api`) is unchanged (byte-identical), confirming
  no common or Apple surface moved. The module still has only one `api/`
  layout (no Android-specific baseline file exists for it, matching its
  "consumes the JVM target directly" status from ADR-0014).
- Two committed asset versions of the same asset id never share files (each
  version gets its own `committed/<asset>/<version>/asset.bin`), so quota
  accounting and version-conflict semantics from ADR-0006 carry over
  unchanged.

## Rejected alternatives

- **One file per committed chunk instead of one assembled object.** Makes
  atomic promotion of "the whole object" require either a directory rename
  (weaker atomicity guarantees across some filesystems/`java.nio`
  implementations than a single-file rename) or N separate renames (not
  atomic as a unit). A single assembled file makes the "never partially
  visible" property a property of one `Files.move` call.
- **A per-byte written bitmap for `FileAssetSink.read`, matching
  `InMemoryAssetSink` literally.** Rejected for memory cost (D31).
- **A background thread inside `FileAssetProvider` running the abandoned-session
  sweep on a timer.** Every other durable/cleanup mechanism in this codebase
  (queue drain, retry administration) is host-triggered, not
  self-scheduling; a provider silently spawning a timer thread is a surprise
  a host cannot easily disable, test, or account for.
- **Implementing the Apple equivalent in this same PR.** Three new
  POSIX-based types plus the file-assembly and sweep logic, all unrunnable
  locally and only compile-verified until macOS CI, was judged too large to
  review alongside the JVM/Android implementation in one PR; see
  [What is still open](#what-is-still-open).

## Validation

Verified locally on the JVM: round trips through real files at and around
chunk boundaries (a partial last chunk and an exact multiple); a completed,
verified download that is not yet promoted leaves nothing at the final path,
checked directly against the filesystem, and `promote()` afterwards leaves
the exact source bytes at the final path with the temp file gone; `discard()`
deletes the temp file and never leaves anything at the final path; sparse-read
semantics (`-1` for an untouched range, real bytes for a written one);
`FileAssetSource` random-access reads including a short final read and end of
data; the full `AssetProviderContractKit` (20+ scenarios) against
`FileAssetProvider`; `abortUpload` deleting a session's temp files eagerly; a
tampered whole-object digest leaving nothing at the committed path and
cleaning up the failed session's temp files; a completed upload's bytes
appearing at the committed path in full with its temp files gone; and
`sweepAbandonedUploads` removing only sessions older than its threshold,
leaving a fresh session untouched and making the swept session id
subsequently report `SESSION_NOT_FOUND`.

`dataloom-assets` compiles for `iosArm64`, `iosSimulatorArm64` and `iosX64`
(main and test) unchanged by this slice, confirming the new JVM-only source
set introduces no regression to the module's Apple targets; the JVM ABI
baseline was regenerated and the Kotlin/Native one is unchanged.

**Not verified locally:** any Android instrumentation or emulator run (the
module has no Android-specific source set to run one against; the JVM test
run is the applicable verification per ADR-0014); any Apple execution
(no Apple implementation exists in this slice).

## References

- [ADR-0006](./ADR-0006-asset-transfer-and-streaming-digest.md),
  [ADR-0008](./ADR-0008-durable-asset-transfer-sessions.md),
  [ADR-0014](./ADR-0014-asset-chunk-transforms-and-digest-domain.md)
- [`asset-transfer.md`](../api/asset-transfer.md)
- `dataloom-storage-file`'s internal `FileSystemFacade` (JVM
  write-to-temp-then-rename precedent)
- `dataloom-runtime`'s `AppleQueueFileIo.appleQueueWriteUtf8FileAtomically`
  (Apple write-to-temp-then-rename precedent, not reused directly in this
  slice but the pattern a future Apple implementation should mirror)
- `FR-ASSET-009` in
  [DL-AUDIT-005](../audits/DL-AUDIT-005-current-v1-conformance.md)
