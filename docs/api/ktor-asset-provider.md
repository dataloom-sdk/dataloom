# DataLoom Ktor Asset Provider

[API reference index](./README.md)

> **Status:** Available reference module (new). This module demonstrates one
> Ktor-backed `AssetProvider` implementation; applications may use it, fork
> it, or replace it with another provider. It has never been pointed at a
> real DataLoom asset-serving backend -- none exists yet -- only at a local
> test server (see "Verification" below).

`dataloom-assets-transport-ktor` is an optional JVM/Android Kotlin module
that implements `AssetProvider` (`io.dataloom.assets`, `dataloom-assets`)
with real Ktor HTTP calls. Every other `AssetProvider` in this codebase --
`InMemoryAssetProvider`, JVM/Android `FileAssetProvider`, Apple
`AppleFileAssetProvider` -- is local storage; none of them transfers a byte
over a network. `KtorAssetProvider` is the first one that does.

## Module boundary

- Depends on `dataloom-assets` (the contract this implements) plus Ktor
  client artifacts only.
- Plain JVM module (the `org.jetbrains.kotlin.jvm` plugin), not Kotlin
  Multiplatform -- the same shape as `dataloom-transport-retrofit` and
  `dataloom-transport-grpc`, deliberately narrower than
  `dataloom-transport-ktor`'s KMP (JVM + iOS) shape. Ktor's client is itself
  multiplatform, but this module is new; scoping it to JVM/Android avoids
  claiming Apple cross-compilation or ABI-baseline coverage this slice never
  exercised. A KMP version mirroring `dataloom-transport-ktor` exactly is a
  reasonable follow-up.
- Does not become a mandatory dependency of `dataloom-assets`, `dataloom-core`,
  or `dataloom-runtime`. Strictly opt-in.
- Performs no digest, length, quota, or session-conflict verification itself.
  That is the remote server's job -- exactly as it would be for a real asset
  store -- and is exactly what `AssetProvider`'s own contract documents as
  provider responsibility. This client only encodes requests, decodes
  responses, and maps failures.

## Wire protocol -- a documented design decision, not an adopted spec

There is no published DataLoom asset-serving backend to conform to, so the
protocol below is `KtorAssetProvider`'s own deliberate, minimal, REST-ish
design decision (see its KDoc for the full rationale), the same way
`AssetManifest`'s own KDoc documents its open design choices rather than
assuming they are settled.

| Operation | Method | Path | Request body | Success body |
|---|---|---|---|---|
| `openUpload` | `PUT` | `/assets/uploads/{sessionId}` | manifest (see below) | committed chunk indices (see below) |
| `uploadChunk` | `PUT` | `/assets/uploads/{sessionId}/chunks/{index}` | raw chunk bytes | committed chunk indices |
| `completeUpload` | `POST` | `/assets/uploads/{sessionId}/complete` | (none) | the committed manifest |
| `abortUpload` | `DELETE` | `/assets/uploads/{sessionId}` | (none) | (empty) |
| `readManifest` | `GET` | `/assets/{assetId}/manifest?version={version}` (query omitted for the latest version) | (none) | the manifest |
| `readChunk` | `GET` | `/assets/{assetId}/versions/{version}/chunks/{index}` | (none) | raw chunk bytes |

`sessionId`, `assetId`, `index`, and `version` are percent-encoded path
segments (`AssetId`/`AssetTransferSessionId` place no character restriction
on their value, so this provider encodes rather than assumes a safe
alphabet).

A manifest travels as the exact text
`io.dataloom.api.asset.AssetManifestHistoryStateCodec` already produces for
one manifest, content type `application/vnd.dataloom.asset-manifest+text` --
reusing an existing, already-tested bounded V1 text codec (the same one
`AssetTransferSessionCodec` already embeds for durable session persistence)
rather than inventing a second manifest serialization. Committed chunk
indices travel as ascending comma-separated text (`"0,2,5"`, or an empty body
for none), content type `text/plain`.

## Error mapping

`AssetErrorKind` has more members than generic HTTP status semantics can
distinguish (`SESSION_CONFLICT` and `ASSET_VERSION_CONFLICT` are both
naturally `409`, for example). Every non-2xx response therefore carries a
`X-DataLoom-Asset-Error` header whose value is the exact `AssetErrorKind`
name; the provider decodes that header directly rather than approximating the
kind from the status code. The HTTP status is still chosen with conventional
meaning so the protocol stays inspectable with ordinary HTTP tooling, but it
is never the authoritative signal.

| Condition | Mapped `AssetErrorKind` |
|---|---|
| Header present and recognised | exactly that kind |
| Header missing/unrecognised, status `5xx` | `PROVIDER_UNAVAILABLE` (recoverable) |
| Header missing/unrecognised, any other status | `PROVIDER_REJECTED` (non-recoverable) |
| Transport-level failure (no response at all): connection refused/reset, DNS failure, request/connect/socket timeout | `PROVIDER_UNAVAILABLE` (recoverable) |
| A 2xx response whose body does not parse as the expected shape | `PROVIDER_REJECTED` (non-recoverable) |

`CancellationException` is never caught or translated; it always propagates.
This mirrors how `io.dataloom.transport.ktor.KtorTransportProvider` -- this
module's sibling for change-event push/pull -- separates "no response"
network failures from classified HTTP failures.

## Verification

There is no real DataLoom asset-serving backend to run this against. Proof is
two-layered:

1. **The real, unmodified provider contract.** `KtorAssetProviderContractTest`
   runs `io.dataloom.assets.testkit.AssetProviderContractKit` -- the exact
   same kit `InMemoryAssetProvider`, JVM `FileAssetProvider`, and Apple
   `AppleFileAssetProvider` all pass -- against a real `KtorAssetProvider`
   making real Ktor HTTP calls through a `MockEngine` test server
   (`AssetHttpTestServer`). That test server delegates every business rule
   (digests, quota, conflicts, visibility) to a real, already-contract-tested
   `InMemoryAssetProvider` and only translates HTTP on top of it, so the test
   genuinely exercises `KtorAssetProvider`'s own request/response/error
   mapping over a real `suspend` HTTP call stack, not a second copy of the
   provider contract's own rules.
2. **Network-shaped failure modes no local-storage provider has.**
   `KtorAssetProviderNetworkFailureTest` covers a connection dropped
   mid-upload, a non-2xx response without the documented error header, an
   unrecognised header value, and a client-side request timeout -- each
   proven (by deliberately breaking the corresponding mapping logic and
   observing the test fail, then reverting) to produce a typed
   `AssetTransferError`, never an uncaught exception or a silent success.

Not verified, and not verifiable without a real backend: behavior against an
actual production asset server, TLS/certificate handling, retry/backoff
policy (this provider makes exactly one HTTP call per operation and leaves
retry to the caller, like `KtorTransportProvider`), and any Apple/iOS target.

From the repository root:

```bash
./gradlew :dataloom-assets-transport-ktor:test
```
