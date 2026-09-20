# Fragment: gate #97, asset transfer slice 1 (2026-09-19)

## (a) Proposed "Recently shipped" row

| 2026-09-19 | Unblocked `#97` (DL-043) by implementing the lead's decisions D5/D6 ([ADR-0006](../adr/ADR-0006-asset-transfer-and-streaming-digest.md)), reversing the "one-shot digest only" design and closing the `FR-ASSET-002`/`-003` design gap both prior investigations named. New `dataloom-assets` module (ADR-0002's coordinate; jvm plus iOS targets, not wired into `DataLoomBuilder`): `AssetChunkPlan`/`AssetChunkSizeBounds` (fixed-size chunk geometry, provider-negotiated bounds, 1 MiB default), bounded-memory `AssetSource`/`AssetSink`, a pure transfer-session state machine (`AssetTransferSession.reduce`; CREATED, TRANSFERRING, VERIFYING, COMPLETED, FAILED, CANCELLED; idempotent, exhaustively table-tested plus a randomised invariant test), a compare-and-set `AssetTransferSessionStore` seam (in-memory only), the `AssetProvider` SPI with a quota-enforcing contract, a sequential resumable `AssetTransferEngine` (upload/download/cancel), an in-memory reference provider, a framework-neutral `AssetProviderContractKit`, and compression/AEAD-cipher SPIs with identity implementations only. `dataloom-model` gains `DataLoomIncrementalDigestCalculator`/`DataLoomDigestAccumulator` (JVM over `MessageDigest`; Apple over CommonCrypto `Init`/`Update`/`Final` with a `nativeHeap` context freed by `finish`/`close` plus a `Cleaner` backstop); the one-shot digest is unchanged. **Verified locally on the JVM:** 110 `dataloom-assets` tests (resume after partial commit, lost local record, provider that lost chunks, coroutine and explicit cancel, chunk and whole-object digest mismatch in both directions, quota exceeded and reservation release, full sink, bounded buffers on a 6 MiB asset) and 17 new incremental-digest tests; mutation spot-checks confirmed the tests fail when integrity checks or terminal-state rules are broken; iOS main and test source sets cross-compile for both modules (`iosSimulatorArm64`); ABI baselines regenerated and checked. **Not verified:** no Apple-target test has been executed (the CommonCrypto accumulator and every `dataloom-assets` test on iOS run only in the macOS CI `build`); nothing here is exercised by a real transport, durable store, file source/sink or Android/iOS app. | `#97` |

## (b) Gate row percentage

`#97`: **5% -> 25%** (banded to 5%). `FR-ASSET-001` was already done; this slice adds tested contracts and in-memory reference behaviour for `FR-ASSET-002`, `-003` (in-memory only), `-004` (per-chunk and whole-object verification, no quarantine hook), `-005` (contracts and in-memory implementations only), `-010` (provider-enforced quota contract, reference enforcement) and `-011` (cooperative and explicit cancellation, no durable cleanup), plus SPIs for `-007`/`-008`. `-006`, `-009`, `-012`, durable persistence, real algorithms, builder wiring and `AC-FUNC-005` are untouched, so this is a judgement, not a measurement.

## (c) "Still pending" text

Remove from the `#97` row's "no upload/download, chunking execution, streaming, ..." and "blocked on FR-ASSET-002/-003 design" language, and the two investigation references as blockers (both docs are marked superseded). Replace the pending list with, in order:

1. Durable session persistence (`AssetTransferSessionStore` over `DurableStateStore`).
2. Concrete compression and encryption algorithms wired into the engine (and the logical-versus-wire digest decision, ADR-0006 open decision 1).
3. Secure temp files, atomic promotion, crash/abandoned-session cleanup, and file-backed source/sink (`FR-ASSET-009`).
4. Parallel transfer with concurrency limits and fairness (`FR-ASSET-006`).
5. Content allow/deny/scan/quarantine hooks (`FR-ASSET-012`).
6. `DataLoomBuilder` wiring (`ProviderType` for asset providers, `#94` retry/circuit, `#96` events), and a transport-backed provider.
7. `AC-FUNC-005` on Android and iOS, and public docs/samples for the full lifecycle.
