# Fragment: `#96` evidence-based re-audit (2026-09-30)

For the lead to fold into `docs/status/market-readiness.md`. Not edited by
this PR itself. Full evidence in
[`docs/status/dl-042-qualification-matrix.md`](../dl-042-qualification-matrix.md).

## (a) Proposed "Recently shipped" row

| 2026-09-30 | `#96` (audit): re-derived an evidence-based qualification matrix for the durable-outbox/health/observability gate, the same rigor `#94` and `#102` recently got. Found the row's own cited source, `docs/api/outbox-replay-investigation.md:153`, already says "~~Head-of-line blocking~~ -- done (opt-in processor policy)" -- shipped and tested in PR `#421` (`OperationalEventOutboxOrderingPolicy.BLOCK_WORKFLOW_ON_UNFINISHED_ENTRY`) -- yet the row's own "Still pending" cell still lists head-of-line blocking as open, contradicting a document it cites by name; that PR's own fragment explicitly told the lead to remove the clause and it was not removed. In the other direction, the same cited document's next line ("Subscription delivery and cross-scope enumeration (unchanged from before)") names two gaps that are still real (confirmed by source: no subscription/push mechanism exists anywhere, and every `DurableOperationalEventOutbox` read method takes exactly one scope with no way to enumerate scopes or read across them) but that the current row text has silently dropped rather than closed. Also confirmed: policy-decision and queue-worker-scheduling bridging (`#421`) and the plugin-engine's two bridges (`#419`, integrated `#435`) are real, tested, and wired into `DataLoomBuilder`, not merely planned -- but the plugin engine itself still has no real caller in application code (hook-point dispatch is `#98`'s still-blocked gap), so that bridge exists without anything upstream to produce events yet, the same "landed but not yet consumed" posture the lead already applied to policy/scheduler when declining to raise the percentage for `#421`. Health aggregation's explicit list -- assets, configuration/policy history, scheduler, and the plugin engine contribute nothing to `dataLoomHealthSnapshot`'s severity roll-up -- was independently re-verified against the five-field `DataLoomHealthSnapshot` data class and holds. The deployable-operations-dashboard/adaptor claim was independently re-verified (no Prometheus/OTLP/exporter-service code anywhere in the repository) and holds. One test suite most relevant to the corrected clause was run on this Windows host (`:dataloom-runtime:jvmTest --tests PolicyAndSchedulerOperationalEventBridgesTest`, `BUILD SUCCESSFUL`); the rest of this audit's citations are read-only against source, with no platform (Android emulator/iOS Simulator) run required since nothing in this gate's remaining scope, as re-derived, needs one. `#96` unchanged at 55% -- the two corrections roughly offset (one item removed from "Still pending" as resolved, two restored as still open) rather than represent new capability | `#96` audit |

## (b) Row percentage

`#96`: 55% -> **unchanged**, recommended. Justification: this audit adds no
new production capability -- it is a read-accuracy correction. The two
factual corrections found (head-of-line blocking resolved and should be
removed from "Still pending"; subscription delivery and cross-scope
enumeration still open and should be restored to "Still pending") pull in
opposite directions and roughly cancel against the row's implied scope of
remaining work. If the lead judges the plugin/policy/scheduler bridges (all
landed since the 55% figure was last set, all previously left unbumped for
"landed but not consumed" reasons the lead already documented in `00b724b`)
deserve a small catch-up bump now that this audit has confirmed all three are
real and wired rather than partial, a band to 60% would be defensible -- but
this audit does not recommend inventing that bump itself, consistent with the
conservative discipline the `#94` and `#102` audits also held to.

## (c) "Still pending" text

Replace the row's current "Still pending" cell in full with:

> Wire the outbox to every remaining subsystem's events (synchronization
> events, retry/circuit administration commands, strategy-decision
> diagnostics, both queue-worker paths' lifecycle, conflict resolution,
> policy decisions, queue-worker wake-up scheduling, and the plugin engine's
> lifecycle transitions and bounded invocations are all bridged and wired
> into `DataLoomBuilder`; the plugin engine's bridge has no real caller yet
> since hook-point dispatch remains blocked under `#98`; assets
> (`dataloom-assets`) remain genuinely unbridged, and configuration-version
> history has no runtime producer to bridge from yet, so both are unchanged);
> batch/by-workflow replay and replay authorization (single-entry replay with
> no authorization concept exists; head-of-line blocking shipped as an
> opt-in processor ordering policy in `#421` and is no longer pending);
> subscription/push delivery and cross-scope enumeration (no push mechanism
> exists, and every outbox read method is scoped to exactly one scope with no
> way to enumerate or query across scopes -- both unchanged since before
> `#421`, mistakenly dropped from this cell in an earlier sync rather than
> closed); SDK-wide health aggregation for the remaining subsystems (outbox
> and queue-worker state aggregate with a severity roll-up as of 2026-09-20;
> assets, configuration/policy history, scheduler, and the plugin engine
> still contribute nothing to `dataLoomHealthSnapshot`'s severity, regardless
> of their outbox-bridging status above -- health aggregation and outbox
> bridging are two separate mechanisms and a subsystem can have one without
> the other); and a deployable operations dashboard/adaptor (no Prometheus,
> OpenTelemetry, or other exporter-service code exists anywhere in the
> repository; only in-process structured-log/trace exporter adapters do).

See `docs/status/dl-042-qualification-matrix.md` for full citations.
