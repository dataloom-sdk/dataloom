# Fragment: `#96` deployable operations dashboard/adaptor -- Prometheus text exporter (2026-10-01)

For the lead to fold into `docs/status/market-readiness.md`. Not edited by
this PR itself. Builds on the 2026-09-30 re-audit,
[`docs/status/dl-042-qualification-matrix.md`](../dl-042-qualification-matrix.md),
whose section 3.6 confirmed "nothing resembling a deployable service, HTTP
endpoint, or exporter to an external monitoring system exists" and whose
section 5.2 named this item as **"genuinely unscoped: no decision has been
recorded anywhere in `docs/adr/` on which export protocol ... or which
deployment shape ... this SDK should target"** -- a product/architecture
decision the audit itself declined to bound into one PR. This PR makes that
choice explicitly (Prometheus text exposition format, pull-based, no HTTP
server shipped by the SDK) and ships the bounded pull/export-on-demand core
the audit's own backlog language describes, rather than the larger push/
OTLP/dashboard-service scope the audit declined to bound. This fragment's
baseline is `#96` at **60%**, the value the still-unfolded
[`2026-10-01-96-assets-outbox-bridge.md`](2026-10-01-96-assets-outbox-bridge.md)
fragment recommended for bridging `dataloom-assets` into the outbox; the live
row is physically still at 55% pending the lead's fold-in of that fragment,
and this PR's recommendation in (b) below composes on top of it, not instead
of it.

## (a) Proposed "Recently shipped" row

| 2026-10-01 | `#96`: ships the deployable operations dashboard/adaptor's bounded pull-export core, the one item the 2026-09-30 re-audit's section 3.6/5.2 confirmed as fully open and explicitly declined to scope as a single PR (no decision on export protocol or deployment shape existed anywhere in `docs/adr/`). New top-level function `dataLoomPrometheusMetrics(snapshot: DataLoomHealthSnapshot): String` (`dataloom-runtime`, `io.dataloom.runtime.observation.health.DataLoomPrometheusHealthExporter.kt`) renders an already-built `DataLoomHealthSnapshot` (see the 2026-08-25 health-snapshot entry above) as [Prometheus text exposition format](https://github.com/prometheus/docs/blob/main/content/docs/instrumenting/exposition_formats.md): `dataloom_health_severity` (overall roll-up), `dataloom_health_component_severity{component="..."}` (one line per `DataLoomHealthComponent` value, `0` when that component raised no finding), `dataloom_outbox_pending_entries`/`dataloom_outbox_acknowledged_retained_entries`/`dataloom_outbox_severity{scope="..."}` (per outbox scope, the first two omitted -- never zero -- for a scope this process has never observed), `dataloom_queue_worker_runs_in_flight`/`_consecutive_failed_runs`/`_severity` (only when queue-worker health is present), and `dataloom_provider_health_status{provider="..."}` (sorted by provider id for deterministic output). The function performs no I/O, never suspends, reads no clock, and adds no new instrumentation or collection pipeline -- every value it renders already existed in `DataLoomHealthSnapshot` before this PR; it is purely a formatting layer, following the same opt-in, host-wires-the-transport shape this SDK already applies to `RetryCircuitStructuredLogExporter`/`RetryCircuitTraceExporter` (host supplies the logger/tracer sink) and every `*OperationalEventOutboxSpec` (host supplies the store): DataLoom produces correctly-formatted text, a host application decides how (or whether) to serve it from its own `/metrics` HTTP handler -- this SDK is a Kotlin Multiplatform library and still runs no HTTP server of its own. HELP/TYPE comment lines and label-value escaping (backslash, double-quote, line feed) follow the exposition format's own syntax rules. 4 new tests in `DataLoomPrometheusHealthExporterTest` (`dataloom-runtime`), including one that builds a real `DataLoomBuilder`-built `DataLoom`, drives one real queue entry through a real queue worker and a real durable-outbox bridge (`DataLoomQueueLifecycleOperationalEventOutboxSpec`, real `OperationalEventOutboxHealthTracker`/`QueueWorkerHealthTracker` instances), calls a real provider's `health()` (returning `DEGRADED` to exercise a non-`HEALTHY` path), assembles the resulting `DataLoomHealthSnapshot` via `dataLoomHealthSnapshot`, and asserts the exporter's output byte-for-byte against the full expected Prometheus text; the other three pin the all-`HEALTHY`/nothing-supplied case, the never-observed-scope omission rule, and label-value escaping. Purely additive ABI change to `dataloom-runtime` (JVM, JVM/Android-gated variant, and iOS klib baselines all regenerated via `updateKotlinAbi -Pdataloom.appleKlibCrossCompile=true`, run both without and with `DATALOOM_ANDROID_BUILD=true`, confirmed byte-identical via `cmp`, and `checkKotlinAbi` verified clean in both configurations). See `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/observation/health/DataLoomPrometheusHealthExporter.kt` and `dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/observation/health/DataLoomPrometheusHealthExporterTest.kt` | `#96` feature |

## (b) Row percentage

`#96`: 60% (pending fold-in of the assets-bridge fragment) -> **65%**, recommended.

Justification: this closes the one item the 2026-09-30 audit's own verdict
(section 1, point 4) and backlog (section 5.2) separately flagged as
*unscoped* rather than merely *unimplemented* -- the audit explicitly
declined to recommend any percentage movement for it because no protocol/
deployment-shape decision existed to build against. This PR removes that
blocker by making and documenting the choice (Prometheus text, pull-based,
no bundled server) and shipping the resulting bounded core with real,
byte-exact test coverage against a real `DataLoomBuilder`-built instance.
A five-point bump, smaller than the assets-bridge PR's own five points,
reflects that:
- What ships is read-only export over state that already existed (no new
  producer, unlike the assets bridge's real new `AssetTransferObserver`
  wiring) -- genuinely new capability, but a thinner slice.
- The exported surface is exactly what `DataLoomHealthSnapshot` already
  covers today: it does **not** add assets, configuration/policy history,
  scheduler, or plugin-engine signal to health (per the audit's own
  health-aggregation finding, that remains a separate, still-open mechanism
  from outbox bridging, unaffected by this PR either way).
- Subscription/push delivery, cross-scope enumeration, and batch/authorized
  replay -- the gate's other three named-open items -- are completely
  untouched by this PR and should not be read as partially addressed.
- No HTTP server, OTLP/push exporter, or dashboard-service code ships here,
  by design (see (c)); a host must still do the "deployable" wiring itself.

## (c) "Still pending" text

Starting from the still-unfolded 2026-10-01 assets-bridge fragment's own
proposed replacement text, apply one further edit: narrow the dashboard/
adaptor clause from "fully open" to what remains after this PR, and correct
its parenthetical (it no longer describes current reality once this PR
lands).

Replace only this clause:

> and a deployable operations dashboard/adaptor (no Prometheus, OpenTelemetry,
> or other exporter-service code exists anywhere in the repository; only
> in-process structured-log/trace exporter adapters do).

with:

> and the remainder of a deployable operations dashboard/adaptor beyond its
> bounded pull-export core (`dataLoomPrometheusMetrics` now renders an
> already-built `DataLoomHealthSnapshot` as Prometheus text-exposition-format
> metrics -- severity roll-ups per subsystem, per-scope outbox depth, queue-
> worker gauges, per-provider status -- but this SDK still runs no HTTP
> server, ships no OTLP/push exporter, dashboard-service, or scrape
> scheduling, and the exported surface is bounded by `DataLoomHealthSnapshot`
> itself, so it does not cover assets, configuration/policy history,
> scheduler, or plugin-engine signal any more than that snapshot already
> does; a host application must still wire the rendered text into its own
> `/metrics` endpoint on its own schedule).

Every other clause in that cell (outbox bridging, replay, subscription
delivery/cross-scope enumeration, health aggregation) is **unchanged** by
this PR and should be left exactly as the assets-bridge fragment (or the live
row, if that fragment is folded first) already states it.

## FR code check

No `NFR-OBS-00x` table exists anywhere in the repository to check against
(confirmed independently by the 2026-09-30 audit, section 2, and
re-confirmed by this PR: `grep -rn "NFR-OBS-0" docs/` still matches only the
one `DL-AUDIT-004` summary line). `DL-AUDIT-004-v1-production-readiness.md:435`
is the only in-repo source naming "observability ... exporter failure
isolation" as part of `#96`'s intended scope, and it predates this PR by
design (it is a summary line, not a `FR`/`NFR` table). This PR is tracked
only via the `#96`/DL-042 dashboard row's "Still pending" prose and the
qualification matrix's backlog table (`docs/status/dl-042-qualification-matrix.md`,
section 5.2, "Deployable operations dashboard/adaptor"), both addressed above.

## What this PR deliberately defers (not a silent scope cut)

Per the qualification matrix's section 5.2 framing of this item as a
product/architecture decision, and per this task's own instruction to scope
down rather than attempt the larger push/service shape, the following are
explicitly **not** part of this PR and remain open for a future, separately
scoped slice:
- An actual HTTP server or `/metrics` route shipped by this SDK -- DataLoom
  is a library; serving the text this function produces is the host
  application's responsibility, exactly like every other host-wires-the-
  transport boundary in this codebase.
- OTLP or any other push-based export protocol.
- A bespoke JSON operations-dashboard payload or Grafana dashboard definition.
- Extending `DataLoomHealthSnapshot`'s own aggregated surface (assets,
  configuration/policy history, scheduler, plugin engine) -- this PR exports
  exactly what that snapshot already contains, unchanged.
- Caching, rate-limiting, or scrape-interval concerns -- `dataLoomPrometheusMetrics`
  is a pure, synchronous, call-on-demand function; running it on every scrape
  is the host's choice to make.
