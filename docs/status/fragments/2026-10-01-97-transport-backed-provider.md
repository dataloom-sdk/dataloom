# Fragment: gate #97, transport-backed AssetProvider (2026-10-01)

## (a) Proposed "Recently shipped" row

| 2026-10-01 | `#97`: a transport-backed `AssetProvider` (new module `dataloom-assets-transport-ktor`). Every prior `AssetProvider` in this codebase — `InMemoryAssetProvider`, JVM/Android `FileAssetProvider`, Apple `AppleFileAssetProvider` — is local storage simulating the provider contract; none transfers a byte over a network. `KtorAssetProvider` is the first one that does: it makes real Ktor HTTP calls (`PUT`/`POST`/`DELETE`/`GET`) and performs no digest/quota/conflict verification itself — that remains the remote server's job, exactly as it would for a real asset store. Since no DataLoom asset-serving backend exists to conform to, the module documents its own minimal REST-ish wire protocol as an explicit design decision (endpoint table, manifest wire encoding reusing the existing `AssetManifestHistoryStateCodec` rather than inventing a second serialization, and an `X-DataLoom-Asset-Error` response header carrying the exact `AssetErrorKind` name as the authoritative failure signal, since `AssetErrorKind` has more members than HTTP status codes alone can distinguish) — see `docs/api/ktor-asset-provider.md`. Deliberately scoped to a plain JVM module (mirroring `dataloom-transport-retrofit`/`dataloom-transport-grpc`'s shape, not `dataloom-transport-ktor`'s KMP one): this is new, unproven code, and claiming Apple cross-compilation or ABI-baseline coverage this slice never exercised would not be honest. **Verified locally (JVM, executed):** the provider runs through the exact, unmodified `AssetProviderContractKit` — the same kit the three local-storage providers pass — via a `MockEngine`-backed test HTTP server (`AssetHttpTestServer`) that itself delegates all business-rule verification to a real `InMemoryAssetProvider` and only translates HTTP on top, so the contract run genuinely exercises the client's own request/response/error-mapping code over a real `suspend` HTTP call stack, not a second copy of the provider contract's rules; plus 4 dedicated network-failure-mode tests (a connection dropped mid-upload, a non-2xx response without the documented error header, an unrecognised header value, and a real client-side request timeout via `HttpTimeout`, using `runBlocking` rather than `runTest` for the timeout case since it depends on real wall-clock timing) — all mapping to typed, recoverable-or-not `AssetTransferError`s, never an uncaught exception or silent data loss. Two deliberate regressions (dropping the 5xx→`PROVIDER_UNAVAILABLE` fallback; discarding the server's reported committed-chunk indices) were introduced and confirmed to fail exactly the expected tests, then reverted, as a revert-and-observe proof the tests are not vacuous. **Not verified, and not verifiable in this slice:** behavior against any real backend (none exists), TLS/certificate handling, and any Apple/iOS build of this provider (out of scope by the module-shape decision above). | `#97` |

## (b) Gate row percentage

`#97`: at the time this fragment was written, `main`'s dashboard row shows
**50%** (the 2026-10-01 parallel-transfer fragment has already been folded
in, "round 5"). Two further sibling fragments had landed on `main` but were
**not yet folded in**: `2026-10-01-97-content-policy-hooks.md` (proposes
**50% → 55%**) and `2026-10-01-97-apple-aes-gcm-investigation.md` (proposes
**unchanged**, investigation only). This slice closes the "Still pending"
list's *"a transport-backed provider"* item specifically, bounded as
described above (JVM/Android only, local test server, no real backend).
Proposed as one additional +5-point band on top of whichever baseline is
current when folded — **current-baseline → current-baseline + 5**, i.e.
**55% → 60%** if folded after the content-policy-hooks fragment (the
expected order, since it landed first), or **50% → 55%** if folded before
it. Held to a single band, not more, because: no real backend exists to
validate the documented protocol against, the provider is JVM/Android-only
(no Apple), and `ProviderType`/lifecycle wiring (`#94`/`#96` integration),
`AC-FUNC-005` end to end, and Apple AES-256-GCM all remain completely
untouched by this slice.

## (c) "Still pending" text

Wherever the current pending text for `#97` lists *"a transport-backed
provider"* (the dashboard's gate-6 row, and item 2 of both pending sibling
fragments' "Still pending" lists), narrow it to:

> A transport-backed `AssetProvider` now exists (`KtorAssetProvider`,
> `dataloom-assets-transport-ktor`, JVM/Android only), passing the same
> `AssetProviderContractKit` the local-storage providers pass, plus
> dedicated network-failure-mode coverage — but only against a local
> `MockEngine` test server, since no real DataLoom asset-serving backend
> exists yet to validate the documented wire protocol against. Still open:
> a real backend (or a reference server implementation) to point this at,
> and an Apple/iOS build of this provider.

`ProviderType`/lifecycle for asset providers and `#94`/`#96` integration
around the engine remain separately open, unaffected by this slice.
