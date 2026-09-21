# Fragment: gate #97, durable asset transfer sessions (2026-09-20)

## (a) Proposed "Recently shipped" row

| 2026-09-20 | `#97` slice 2 (decision D17, [ADR-0008](../adr/ADR-0008-durable-asset-transfer-sessions.md)): asset transfer sessions are now durable. `DurableAssetTransferSessionStore` adopts the `DurableStateStore` domain pattern exactly as `DurableUnresolvedConflictLog`/`DurableAssetManifestHistory` did (scope `AssetTransferSessionId`, state `AssetTransferSession`; per-scope compare-and-set retry loop mapping the record version to the session revision; typed `Saved`/`StaleRevision`/`PersistenceFailure`/`ContentionLimitReached` and `Missing`/`Found`/`PersistenceFailure` outcomes) with `AssetTransferSessionCodec`, a versioned, bounded, fail-closed text codec that never persists payload bytes (committed chunk indices, phase, failure kind, revision and the manifest's digests only; the manifest is embedded through the existing `AssetManifestHistoryStateCodec`). A store failure is now an engine outcome (`AssetTransferOutcome.SessionStoreFailure`) rather than a crash. Also the opt-in `DataLoomAssetTransferSpec` / `DataLoomBuilder.assetTransferConfiguration` / `DataLoom.assetTransfer` (absent is inert; build does no I/O), and a mocked-DAO `RoomDurableStateStore` integration test for the new domain. **Verified locally:** 47 new `dataloom-assets` JVM tests over a codec-backed in-memory durable store, covering crash between chunks, crash between provider accept and record, restart while VERIFYING (both directions), terminal-state replay, two workers racing on one session (an observed CAS conflict, converging), store failure then recovery, corrupt/out-of-order/truncated/tampered payload rejection and codec round trips; 7 builder tests; 2 Room-store tests (Android unit tests, `DATALOOM_ANDROID_BUILD=true`); mutation spot-checks (removing the revision check or the ascending-order check makes tests fail). **Not verified:** any Apple-target execution (macOS CI only); persistence through a real Room database or device; an Apple file-backed durable store for this domain (none built). Persisted sessions of abandoned transfers are not yet cleaned up. | `#97` |

## (b) Gate row percentage

`#97`: **25% -> 35%** (banded to 5%, a judgement). `FR-ASSET-003` (durable resumable sessions and restart recovery) moves from in-memory to durable with restart-shaped tests, and the capability is now reachable from `DataLoomBuilder`; `FR-ASSET-006`, `-007`/`-008` algorithms, `-009`, `-012`, a real provider, and `AC-FUNC-005` remain.

## (c) "Still pending" text

Remove "durable session persistence" and "`DataLoomBuilder` wiring" (the opt-in configuration part) from the `#97` pending list. Remaining, in order:

1. Concrete compression and encryption algorithms wired into the engine, and the logical-versus-wire digest decision.
2. Secure temp files, atomic promotion, cleanup of abandoned sessions and their persisted records, file-backed source/sink (`FR-ASSET-009`).
3. Parallel transfer with concurrency limits and fairness (`FR-ASSET-006`).
4. Content allow/deny/scan/quarantine hooks (`FR-ASSET-012`).
5. `ProviderType`/lifecycle for asset providers, `#94` retry/circuit and `#96` events around the engine, and a transport-backed provider.
6. `AC-FUNC-005` on Android and iOS, and public docs/samples.
