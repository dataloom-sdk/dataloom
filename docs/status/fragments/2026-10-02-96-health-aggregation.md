# Fragment: `#96` SDK-wide health aggregation for assets, scheduler, plugin engine (2026-10-02)

For the lead to fold into `docs/status/market-readiness.md`. Not edited by this PR itself.

## (a) Proposed "Recently shipped" row

| 2026-10-02 | `#96`: `dataLoomHealthSnapshot` now aggregates three more subsystems into its severity roll-up, following the existing caller-supplied/opt-in-tracker pattern (no I/O in the function, additive parameters). **Assets**: new `AssetTransferHealthTracker` (`dataloom-runtime`), fed through `AssetTransferEngine`'s single `AssetTransferObserver` seam by the new `DataLoomBuilder.assetTransferHealthTracker(tracker)` (composed with, never replacing, an already-configured `AssetTransferOperationalEventRecorder` via `withAssetTransferHealthTracking`); new `assetTransferObservation` parameter and `assetTransferHealth` snapshot section. Only consecutive `AssetTransferOutcome.SessionStoreFailure` outcomes drive severity (`DEGRADED` at 1, `UNHEALTHY` at 3, configurable); `Failed`/`Interrupted`/`NotStarted`/`Cancelled`/`Completed` are recorded for diagnostics but deliberately never counted, because `Failed` is the `NON_RECOVERABLE` set that routinely means caller input (quota, content policy) rather than engine health. **Plugin engine**: new `pluginHealth: Map<PluginId, PluginLifecycleState>` parameter (caller reads `DataLoomPluginEngine.stateOf` per id); only `DEGRADED` raises a finding (`PLUGIN_DEGRADED`, `DEGRADED`). **Scheduler**: no new code was needed -- `SchedulerProvider` already extends `DataLoomProvider` and the existing generic `providerHealth` map accepts any provider; the gap was that nothing exercised or documented it, now proven by a test and documented (the same holds for `AssetProvider.health()` after `#471`). New `DataLoomHealthComponent.ASSET_TRANSFER`/`PLUGIN`, three new finding codes, two new thresholds; the Prometheus exporter's per-component gauge picks the new components up automatically (its HELP text and exact-match tests updated). Breaking-but-pre-V1: `DataLoomHealthSnapshot` gains two constructor fields. Verified on Windows: `:dataloom-runtime:jvmTest --rerun` (full module, 0 failures; new builder-level test file `DataLoomBuilderAssetTransferHealthTrackerTest`, 5 tests, drives a real `DataLoomBuilder`-built engine against a session store that really throws and observes `DEGRADED`/`UNHEALTHY`, a healthy run, recovery reset, composition with the outbox recorder, and the unconfigured case), whole-build `checkKotlinAbi` in both configurations (JVM/klib/Android-gated JVM baselines regenerated after merging `origin/main`, the two JVM files byte-identical), iOS-simulator test cross-compile. Revert-and-observe: replacing the builder's `withAssetTransferHealthTracking` wiring with the bare recorder made 4 of the 5 builder tests fail (tracker stayed at its initial state), then restored. Not run: Apple/Android runtime execution (CI only). See `docs/api/health-snapshot.md` | `#96` feature |

## (b) Row percentage

`#96`: 60% -> **63%**, recommended (modest). Justification: closes three of the four subsystems named in the "Still pending" health-aggregation clause, but each is a small, bounded slice (the asset signal is deliberately narrow; scheduler needed only proof and docs). Subscription/push delivery, cross-scope enumeration, batch/authorized replay and the HTTP/OTLP dashboard service are untouched. Baseline on `main` at open time was 60%.

## (c) "Still pending" text

Replace the health-aggregation clause

> SDK-wide health aggregation for the remaining subsystems (outbox and queue-worker state aggregate with a severity roll-up as of 2026-09-20; assets, configuration/policy history, scheduler, and the plugin engine still contribute nothing to `dataLoomHealthSnapshot`'s severity, regardless of their outbox-bridging status above -- health aggregation and outbox bridging are two separate mechanisms and a subsystem can have one without the other)

with

> SDK-wide health aggregation for configuration/policy history (outbox, queue-worker, asset-transfer session-store failures, the plugin engine's `DEGRADED` state, and any `DataLoomProvider` including the scheduler now aggregate into `dataLoomHealthSnapshot`'s severity roll-up; configuration/policy history cannot: `DataLoomConfigurationResolver`/`DurableConfigurationHistory` have no runtime producer in `dataloom-runtime` -- see `docs/api/configuration-resolver-caller-investigation.md` -- so there is nothing to observe, and no fake signal was invented; asset-transfer health deliberately counts only session-store failures, so a high rate of `Failed`/`Interrupted` transfers is not reflected)

## Not closed / honest limits

- Configuration/policy history: blocked as above.
- Asset health is narrow by design (see KDoc on `AssetTransferHealthTracker`); an application wanting provider-level asset health passes `AssetProvider.health()` through `providerHealth`.
- `dataLoomPrometheusMetrics` renders only the per-component gauge for the new sections, not dedicated asset/plugin metrics.
