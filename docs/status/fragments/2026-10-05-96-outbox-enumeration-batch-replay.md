# Fragment: `#96` outbox cross-scope enumeration and authorizer-gated batch replay (2026-10-05)

For the lead to fold into `docs/status/market-readiness.md`. Not edited by this PR itself.

## (a) Proposed "Recently shipped" row

| 2026-10-05 | `#96`: `DurableOperationalEventOutbox` gains two additive methods (public `dataloom-api` surface; new file `OperationalEventOutboxEnumeration.kt`). **`enumerate(query, after)`**: read-only, cursor-paginated fan-out over a caller-named set of scopes (1 to 100 distinct), filterable by workflow id and `PENDING`/`ACKNOWLEDGED`, page size capped at 500 (default 100, rejected outside the cap), ordered `(scope, ordering key, sequence)`; the cursor is the last entry's intrinsic identity (scope, ordering key, never-reused sequence) so it stays valid across appends, acknowledgements, replays and eviction of the cursor's own entry; stops loading scopes once the page is full; any scope load failure fails the whole call. **`replayBatch(request, authorizer)`**: reopens up to `maximumEntries` (cap 500) acknowledged entries chosen by scope/workflow filter, one compare-and-set per scope at the original position and sequence, with a *required, no-default* host-supplied `OperationalEventOutboxReplayAuthorizer` (consulted per candidate, before any write; a throwing authorizer denies; denied entries do not consume the budget; a CAS retry only re-applies the authorized ids). Reports replayed / denied / notReplayable / per-scope failures / budgetExhausted. **Not delivered, deliberately:** discovery of scopes the caller does not name -- `DurableStateStore` has only `load(scope)`/`compareAndSet` and cannot list scopes, so real discovery needs a scope-listing operation on `RoomDurableStateStore` (DAO query) and `AppleFileDurableStateStore`, verifiable only on emulator/macOS CI. There is also no failed/dead-letter state in the outbox (entries are pending or acknowledged; a consumer-failed entry is still pending), so batch replay has no status filter. Single-entry `replay` is unchanged and still has no authorization. Verified on Windows: new `DurableOperationalEventOutboxEnumerationAndBatchReplayTest` (24 tests, version-checked in-memory store) and the full `:dataloom-api:jvmTest --rerun` (1058 tests, 0 failures); `:dataloom-runtime:compileTestKotlinIosSimulatorArm64` (cross-compile, includes the new `AppleFileDurableOperationalEventOutboxEnumerationTest`, which exercises both methods over the real `AppleFileDurableStateStore` with a fresh store instance); `updateKotlinAbi` in both configurations (JVM baselines byte-identical, klib regenerated) and whole-build `checkKotlinAbi` in both configurations. Revert-and-observe: with five mutations applied together (cursor `>` to `>=`, page cap off by one, authorizer bypassed, retry replaying every acknowledged entry instead of the authorized ids, denied entries consuming budget) 10 of the 24 tests failed; restored from backup. The mutations were applied together, not one at a time. Not run: the Apple file-store test (macOS CI only); no Room claim (the repo has no real-database JVM Room test, only a mocked-DAO one). See `docs/api/outbox-replay-investigation.md` | `#96` feature |

## (b) Row percentage

`#96`: **unchanged**. Justification: closes two named pending items in a bounded form (batch/by-workflow replay with an authorization hook, enumeration across caller-named scopes), but scope discovery, subscription/push delivery and the HTTP/OTLP dashboard service remain, and the new tests prove the logic on an in-memory store, with the real-file-store proof unrun until macOS CI.

## (c) "Still pending" text

Replace

> batch/by-workflow replay and replay authorization (single-entry replay with no authorization concept exists) ... cross-scope enumeration (every outbox read method is scoped to exactly one scope with no way to enumerate or query across scopes)

with

> discovery of outbox scopes the caller does not already name (`enumerate`/`replayBatch` fan out over caller-named scopes; `DurableStateStore` cannot list scopes, so discovery needs a scope-listing capability on the Room and Apple file stores); single-entry `replay` remains ungated (batch replay requires an authorizer); no replay audit trail

## Not closed / honest limits

- Scope discovery (needs a `DurableStateStore` SPI addition plus Room/Apple implementations, emulator/macOS verification).
- No replay audit trail: a replay is not itself recorded as an outbox event.
- `enumerate` order across ordering keys is not scope append order (the price of a cursor stable under mutation).
- Neither method is exposed through the `DataLoom` facade or `DataLoomBuilder`; they are outbox-level APIs.
