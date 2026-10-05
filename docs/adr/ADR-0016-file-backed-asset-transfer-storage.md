# ADR-0016: File-backed asset transfer storage (secure temp files, atomic promotion, cleanup)

- **Status:** Accepted; implemented for JVM/Android and Apple (iOS)
- **Date:** 2026-09-29 (JVM/Android); Apple added 2026-09-30
- **Gate:** [`#97` / DL-043 asset synchronization](https://github.com/dataloom-sdk/dataloom/issues/97), slices 4-5
- **Builds on:** [ADR-0006](./ADR-0006-asset-transfer-and-streaming-digest.md)
  (`AssetSource`/`AssetSink`/`AssetProvider` contracts, resolves its open
  decision 3), [ADR-0008](./ADR-0008-durable-asset-transfer-sessions.md) and
  [ADR-0014](./ADR-0014-asset-chunk-transforms-and-digest-domain.md), whose
  "next slice" item ("secure temp files, atomic promotion of a verified sink,
  and crash/abandoned-session cleanup ... with real file-backed
  `AssetSource`/`AssetSink`") this record delivers for JVM/Android and, as of
  the Apple addendum below, for Apple too

> This ADR records what slice 4 shipped and why, including what it does not
> cover, plus a 2026-09-30 addendum recording the Apple (POSIX) equivalent.
> See [What is still open](#what-is-still-open) for what remains.

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
target; see ADR-0014). There was **no Apple implementation** in this slice:
building the POSIX-based equivalent (mirroring `AppleQueueFileIo`) for three
new types together with the file-assembly and sweep logic below was judged
too large to fold into one reviewable PR alongside the JVM/Android
implementation, and nothing in the V1 gate ordering required it before the
other pending slices (parallel transfer, content-policy hooks, provider
lifecycle). It was listed under [What is still open](#what-is-still-open) as
its own slice, not silently dropped — delivered by the
[2026-09-30 Apple addendum](#addendum-2026-09-30-apple-ios-equivalent) below.

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
item 1, delivered here for JVM/Android and, as of the 2026-09-30 addendum,
Apple):

1. ~~Secure temp files, atomic promotion, cleanup, file-backed
   `AssetSource`/`AssetSink` for JVM/Android~~ **JVM/Android done by this ADR.**
   ~~The equivalent Apple (POSIX-based) implementation is still open.~~ **Apple
   done by the 2026-09-30 addendum**, with the caveat noted there: compile-
   verified only, no macOS/Xcode execution was available.
2. Parallel chunk transfer with concurrency limits and fairness
   (`FR-ASSET-006`).
3. Content allow/deny/scan/quarantine hooks (`FR-ASSET-012`).
4. `ProviderType`/lifecycle for asset providers, `#94` retry/circuit and `#96`
   events/metrics around the engine, and a transport-backed provider.
5. Apple AES-GCM (ADR-0014).
6. `AC-FUNC-005` on Android and iOS end to end, and public docs and samples.

## Consequences

- `FR-ASSET-009` is implemented for JVM, Android and (as of the 2026-09-30
  addendum) Apple; a real file-backed provider now exists on every platform
  DataLoom targets to develop and test against without holding whole assets
  in memory.
- Public surface added to `dataloom-assets`' JVM/Android target by this ADR
  (no common API changed): `io.dataloom.assets.file.FileAssetSource`,
  `FileAssetSink` and `FileAssetProvider`. `dataloom-assets`' JVM ABI baseline
  (`api/dataloom-assets.api`) was regenerated at the time; its Kotlin/Native
  ABI baseline (`api/dataloom-assets.klib.api`) was unchanged (byte-identical)
  by this original slice, confirming no common or Apple surface moved then.
  The module still has only one `api/` layout (no Android-specific baseline
  file exists for it, matching its "consumes the JVM target directly" status
  from ADR-0014). The 2026-09-30 addendum's Apple types are additive to the
  Kotlin/Native baseline only; see that section.
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
- **Implementing the Apple equivalent in the original slice's PR.** Three new
  POSIX-based types plus the file-assembly and sweep logic, all unrunnable
  locally and only compile-verified until macOS CI, was judged too large to
  review alongside the JVM/Android implementation in one PR; done instead as
  its own PR, see the [2026-09-30 addendum](#addendum-2026-09-30-apple-ios-equivalent).

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
run is the applicable verification per ADR-0014); any Apple execution (no
Apple implementation existed in this slice — see the addendum below for what
changed).

## Addendum (2026-09-30): Apple (iOS) equivalent

Gate `#97` slice 5. Delivers the Apple implementation [D29](#d29-three-new-types-jvmandroid-only-no-apple-implementation-this-slice)
and [What is still open](#what-is-still-open) item 1 left open.

### D32: `AppleFileAssetSource`, `AppleFileAssetSink` and `AppleFileAssetProvider`, built directly on POSIX (`platform.posix`), mirroring `AppleQueueFileIo`

Three new types in `dataloom-assets`' `iosMain` source set (shared by
`iosArm64`, `iosSimulatorArm64` and `iosX64`), behaviourally identical to
their JVM counterparts — same public API shape (`promote()` as additional API
on the concrete sink class, the same range-tracked `read` for unwritten
regions, the same eager-plus-swept cleanup on the provider) and the same
error semantics, verified by reusing the *exact same* `AssetProviderContractKit`
the JVM provider passes (no parallel contract test was written). Internally
they are built from scratch on `platform.posix` (`open`/`read`/`write`/`lseek`/
`rename`/`unlink`/`mkstemps`/`stat`/`opendir`+`readdir`), not on
`NSFileManager`/`NSData` — mirroring `dataloom-runtime`'s `AppleQueueFileIo`
(`open`/`write`/`fsync`/`rename`, `errno`-based error handling, `EINTR` retry
loops), which is this repository's established idiom for "stage in a
same-directory temp file, then promote with one atomic rename" on Apple, for
a different durable domain (the queue). A new internal `AppleFileAssetIo`
object (`dataloom-assets/src/iosMain/kotlin/io/dataloom/assets/file/AppleFileAssetIo.kt`)
plays the same centralising role the JVM's `FileAssetIo` plays: secure temp
file creation (`mkstemps`, mode `0600` by its own POSIX guarantee), atomic
promotion, best-effort recursive delete, and a standard unpadded-base64url
`safeName` that is byte-for-byte identical to the JVM helper's output for the
same input (both use the plain RFC 4648 section 5 alphabet), even though
JVM and Apple storage are never shared.

One deliberate, disclosed strengthening beyond the JVM implementation:
`AppleFileAssetIo.promoteAtomically` `fsync`s the temp file before the
`rename(2)` and `fsync`s the destination directory afterward, exactly as
`AppleQueueFileIo.appleQueueWriteUtf8FileAtomically` does. `java.nio.file.Files.move`
has no portable `fsync` hook, so the JVM `FileAssetIo.promoteAtomically` does
not do this. This does not change the observable atomic-visibility contract
(a caller never sees a partially-written file at the destination either way)
— it only makes the promotion more durable against a crash immediately after
the OS reports the rename as complete. It was judged in scope because the
task explicitly asked for the POSIX call shape to be mirrored, not invented
anew, and `AppleQueueFileIo` already establishes `fsync`-before-rename as this
codebase's Apple durability idiom.

One necessary substitution, not a compatibility gap: the JVM sink's
`java.util.TreeMap`-based written-range tracking (`floorEntry`/`tailMap`) has
no equivalent in the Kotlin/Native standard library, so `AppleFileAssetSink`
keeps the same merged, non-overlapping ranges in a small sorted
`MutableList<LongArray>` and re-merges with one linear pass per `write` —
still bounded by the number of non-contiguous writes (in practice the chunk
count), not by asset size, matching the memory bound the JVM class documents;
only the underlying data structure differs, not the observable contract.

### Contract kit compatibility

The shared `io.dataloom.assets.testkit.AssetProviderContractKit` (`dataloom-assets/src/commonMain/kotlin/io/dataloom/assets/testkit/AssetProviderContractKit.kt`)
required **no changes** to run against `AppleFileAssetProvider`: it is already
platform-neutral (`commonMain`, no JVM-specific assumption was found in it),
and the module's existing `platformDigests()`/`testAsset()`/`sessionId()`
test helpers (`dataloom-assets/src/commonTest/kotlin/io/dataloom/assets/TestSupport.kt`,
`TestDigests.kt`) already have Apple `actual` implementations from earlier
slices (`AppleDataLoomDigestCalculator`). No incompatibility to flag.

### Validation

**Verified locally (Windows host, Kotlin/Native cross-compilation):**
- `:dataloom-assets:compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`,
  `compileKotlinIosX64` — main sources compile for all three Apple targets.
- `:dataloom-assets:compileTestKotlinIosArm64`, `compileTestKotlinIosSimulatorArm64`,
  `compileTestKotlinIosX64` — the new `iosTest` sources (the reused contract
  kit run, the cleanup suite, the source/sink suite) compile for all three
  Apple targets.
- `:dataloom-assets:jvmTest` — the pre-existing JVM suite still passes
  unchanged, confirming this addendum touched no JVM/common code.
- `:dataloom-assets:updateKotlinAbi` then `:dataloom-assets:checkKotlinAbi`,
  both with `-Pdataloom.appleKlibCrossCompile=true` — the Kotlin/Native ABI
  baseline (`api/dataloom-assets.klib.api`) now lists exactly the three new
  public Apple types and nothing else; the JVM baseline
  (`api/dataloom-assets.api`) is untouched.
- Whole-build `checkKotlinAbi` (repository root) — passes for every module,
  confirming no downstream module (`dataloom-runtime`, which depends on
  `dataloom-assets`) is affected.

**Not verified, and not verifiable from this host:** actually *running* any
of the new `iosTest` scenarios. Kotlin/Native cross-compilation on Windows
produces linked test binaries for `iosArm64`/`iosSimulatorArm64`/`iosX64`, but
executing them needs a macOS host (a Simulator for the two simulator-capable
targets, real hardware or a signed run for `iosArm64`), which was not
available in this session. This means the POSIX call sequences in
`AppleFileAssetIo` (`mkstemps`, `opendir`/`readdir` recursion, `utimes`,
`lseek`-based random access) are type-checked and linked but have never
actually executed; a macOS CI run is the first time they will. This mirrors
exactly the boundary the original JVM/Android slice's validation section
already draws for that side.

## Addendum (2026-10-05): the committed index survives a restart

### D33: a per-version `manifest.dlc` commit marker; lazy, fail-closed index rebuild

**The defect this closes.** `FileAssetProvider` and `AppleFileAssetProvider`
kept their committed-asset index only in memory. The bytes were durable under
`committed/<safe assetId>/<version>/asset.bin`, but nothing persisted the
manifest or each chunk's stored offset and length, so a fresh instance over the
same directory answered `ASSET_NOT_FOUND` for every previously committed asset:
the "file-backed restart-recovery" claim held for bytes but not for service.

**Decision.**
- `completeUpload` writes `manifest.dlc` beside `asset.bin` with the same
  temp-then-rename discipline, *after* the asset file is promoted; the manifest
  file is the commit marker. Any stale manifest is removed first, so a crash
  between the two steps leaves an `asset.bin` with no manifest (skipped on
  restart), never a new `asset.bin` under an old manifest. A manifest-write
  failure deletes the just-promoted `asset.bin` and reports `PROVIDER_REJECTED`.
- The file is a small versioned text record
  (`DATALOOM_FILE_ASSET_COMMIT<TAB>1`, the per-chunk **stored** lengths, then
  one manifest in the existing `AssetManifestHistoryStateCodec` form), in
  `commonMain` as the `internal` `CommittedAssetRecordCodec`. The stored lengths
  are needed because a compressed or encrypted manifest's stored frames differ
  in size from its logical chunk lengths (ADR-0014); offsets are their running
  sum. No public API or ABI changes.
- **Lazy rebuild, not at construction**, on the first operation of an instance,
  under the provider's existing mutex. Construction therefore stays I/O-free,
  and a failing directory listing becomes a typed `PROVIDER_REJECTED` result
  (the scan is retried on the next call) instead of a constructor exception.
- **Fail closed.** An entry is indexed only if the manifest decodes under the
  real `AssetManifest` invariants, the stored lengths agree with the manifest,
  the directory names equal the manifest's asset id and version, and
  `asset.bin`'s size equals the recorded stored lengths. Anything else is
  skipped (counted in an `internal` `skippedCommittedEntryCount`), never
  served, and does not stop the scan. A later upload of the same id and version
  replaces a skipped entry.
- **No whole-object re-hash at scan time** (cost would grow with total stored
  data). Instead `readChunk` re-verifies every *untransformed* chunk against its
  manifest digest before returning it, so same-size bit rot is refused with
  `OBJECT_DIGEST_MISMATCH`. A transformed asset's frames are opaque to the
  provider; their integrity remains the client's authenticated-decryption /
  digest check, as at upload time.
- In-flight uploads are still not recovered: `uploads/` is never indexed, so an
  uncommitted upload is not exposed after a restart (a client opens a new
  session; `sweepAbandonedUploads` reclaims the bytes).

**Residual limits.** The Apple copy cannot tell an unreadable `committed/`
directory from an empty one (it reads as empty, which fails closed).
Neither implementation fsyncs the manifest on the JVM (`Files.move` has no
portable hook; the Apple helper already fsyncs). The Apple changes and the
`iosTest` restart suite are compile-verified only; see Validation above for the
standing boundary.

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
