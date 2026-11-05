# Fragment: `#97` -- AssetProviderContractKit covers the DataLoomProvider lifecycle (2026-11-05)

For the lead to fold into `docs/status/market-readiness.md`. Not edited by this PR.

## (a) Proposed "Recently shipped" row

| 2026-11-05 | `#97` slice: `AssetProviderContractKit` gains four `DataLoomProvider` lifecycle scenarios, closing the "provider-authoring test-kit section for asset-provider `initialize`/`health`/`close`" gap named in the row. The descriptor must be a stable `ProviderType.ASSET` descriptor; `initialize` must succeed and leave the provider fully usable (a complete upload and read follows it); `health` must return a result before and after traffic and must not report `UNHEALTHY` while every operation succeeds; `close` must succeed on an idle provider and on one with an upload session left open. The scenarios deliberately do not assert behaviour the interface leaves open (idempotent `initialize`, use after `close`), because the in-tree providers differ there. Six deliberately broken fake providers (wrong type, failing `initialize`, `initialize` that leaves the provider unusable, failing `health`, always-`UNHEALTHY`, failing `close`) are each reported by the intended scenario, so the new scenarios are shown to catch violations rather than only to pass. The unchanged `FileAssetProvider` and `KtorAssetProvider` also pass them. Kit-internal change: no public API or ABI change. |

## (b) Gate row percentages

`#97`: unchanged at 70% -- a test-kit completeness slice, not new product capability.

## (c) "Still pending" text

- Remove: "a dedicated provider-authoring test-kit section for asset-provider `initialize`/`health`/`close` lifecycle" (now exists).
- Keep, narrowed: circuit and retry protection around an `AssetProvider` is still proven by builder-level tests only. That is by design, because the circuit sits in the builder around the provider, not inside it, so a provider-level kit cannot express it.

## Evidence and limits

- Run on Windows/JVM: `:dataloom-assets:jvmTest` 291 tests, 0 failures (includes `FileAssetProviderContractTest` and the in-memory kit tests); `:dataloom-assets-transport-ktor:test` 5 tests, 0 failures (includes `KtorAssetProviderContractTest`). Read from the raw JUnit XML, not from console output.
- `:dataloom-assets:checkKotlinAbi` passes with no baseline change.
- Apple: the kit is `commonMain`, so `compileTestKotlinIosSimulatorArm64`, `IosArm64` and `IosX64` were cross-compiled with `-Pdataloom.appleKlibCrossCompile=true` (BUILD SUCCESSFUL). **Not executed on Apple**; `AppleFileAssetProviderContractTest` has not run the new scenarios (macOS CI is paused).
