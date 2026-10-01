# Fragment: `#96` bridge `dataloom-assets` into the durable operational-event outbox (2026-10-01)

For the lead to fold into `docs/status/market-readiness.md`. Not edited by
this PR itself. Builds on
[`docs/status/fragments/2026-09-30-96-qualification-audit.md`](2026-09-30-96-qualification-audit.md)
and the audit it carries,
[`docs/status/dl-042-qualification-matrix.md`](../dl-042-qualification-matrix.md),
which confirmed `dataloom-assets` as the one genuinely, mechanically
unbridged subsystem (section 3.3/5.2 of that audit) with no technical
blocker, only an ownership/sequencing question. This PR answers that
question by shipping the bridge.

## (a) Proposed "Recently shipped" row

| 2026-10-01 | `#96`: bridges `dataloom-assets` into the durable operational-event outbox, the subsystem the 2026-09-30 re-audit named as the one remaining genuinely unbridged candidate with no technical blocker. `AssetTransferEngine.upload`/`.download`/`.cancel` gain a new optional `observer: AssetTransferObserver? = null` constructor parameter (additive, default `null`, behavior unchanged when absent) notified once per call with the exact `AssetTransferOutcome` (`Completed`/`Interrupted`/`Failed`/`Cancelled`/`NotStarted`/`SessionStoreFailure`) the call is about to return -- the same "bridge the engine's own already-computed outcome, not a new parallel event type" judgment `QueueLifecycleOperationalEventBridge` already applies to `QueueEntryExecutionOutcome`. New `AssetTransferOperationalEventBridge` (`dataloom-runtime`) maps that outcome to an `OperationalEventEnvelope`; since this domain has no `ExecutionContext` to read a correlation id from (unlike every other bridged subsystem), it reuses the transfer's own `AssetTransferSessionId` as `correlationId` rather than inventing one, and derives the envelope id from `(sessionId, operation, session.revision)` for the four session-bearing outcomes so a resumed call's new progress produces a genuinely new envelope while an unchanged retry stays idempotent. New `AssetTransferOperationalEventRecorder` durably appends via `DurableOperationalEventOutbox`, swallowing its own failures (never `CancellationException`) exactly like every sibling recorder. Wired into `DataLoomBuilder` the same opt-in shape as every other bridge: a new `assetTransferOperationalEventOutboxConfiguration(DataLoomAssetTransferOperationalEventOutboxSpec)` builder method has no effect unless `assetTransferConfiguration` is also configured, matching `queueLifecycleOperationalEventOutboxConfiguration`'s own precedent exactly. 30 new tests across four files (`AssetTransferEngineObserverTest` (6) in `dataloom-assets`; `AssetTransferOperationalEventBridgeTest` (14), `AssetTransferOperationalEventRecorderTest` (6), `DataLoomBuilderAssetTransferOperationalEventOutboxTest` (4) in `dataloom-runtime`), including a real builder-wired upload that durably appends one envelope, append-failure isolation, and a revert-and-observe proof (removing the builder's `observer =` wiring makes the wiring-level test fail with the expected assertion, confirmed, then restored). Purely additive ABI change to `dataloom-assets` and `dataloom-runtime` (JVM + iOS klib + Android-gated JVM variant, all three `checkKotlinAbi` configurations verified). Health aggregation for assets (`dataLoomHealthSnapshot`'s severity roll-up) is a separate mechanism from outbox bridging per the 2026-09-30 audit's own finding and is **not** addressed by this PR; `dataLoomHealthSnapshot` still has no `assets` field. See `dataloom-assets/src/commonMain/kotlin/io/dataloom/assets/AssetTransferObserver.kt` and `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/observation/operational/AssetTransferOperationalEventBridge.kt` | `#96` feature |

## (b) Row percentage

`#96`: 55% -> **60%**, recommended.

Justification: the 2026-09-30 audit explicitly declined to bump the
percentage for the already-landed-but-previously-uncredited policy/scheduler/
plugin bridges on their own ("landed but not yet consumed" -- the plugin
bridge in particular has no real caller yet), while separately noting "a band
to 60% would be defensible" if the lead judged a catch-up warranted. This PR
is different in kind from that catch-up question: it ships a **genuinely new**
bridge for a subsystem independently confirmed, twice now (the 2026-09-20 and
2026-09-30 fragments), as the clearest bounded, unblocked remaining gap in
this gate's outbox-bridging scope, with a real, tested, wired producer (not
merely a bridge with nothing upstream calling it, unlike the plugin engine) --
`InMemoryAssetProvider`-backed uploads in the new builder-wiring test produce
real `AssetTransferOutcome.Completed` values that are genuinely appended.
Recommending the midpoint of the audit's own previously-stated defensible
band (55-60%) rather than the top of it, since several of this gate's other
named gaps (subscription/push delivery, cross-scope enumeration, batch/
authorized replay, SDK-wide health aggregation for assets specifically, and
the deployable operations dashboard/adaptor) remain fully untouched by this
PR and should not be read as partially addressed by this bump.

## (c) "Still pending" text

Starting from the 2026-09-30 fragment's own proposed replacement text (which
the lead had not yet folded in as of this PR), apply one further edit: remove
`dataloom-assets` from the parenthetical naming subsystems that "remain
genuinely unbridged" in the outbox-bridging clause, since it no longer is.
The health-aggregation clause's own, separate mention of "assets" is
**unchanged** -- `dataLoomHealthSnapshot` still has no `assets` field, and
outbox bridging and health aggregation remain two separate mechanisms per the
audit's own finding (a subsystem can have one without the other).

Full replacement text for the "Still pending" cell:

> Wire the outbox to every remaining subsystem's events (synchronization
> events, retry/circuit administration commands, strategy-decision
> diagnostics, both queue-worker paths' lifecycle, conflict resolution,
> policy decisions, queue-worker wake-up scheduling, asset transfers, and the
> plugin engine's lifecycle transitions and bounded invocations are all
> bridged and wired into `DataLoomBuilder`; the plugin engine's bridge has no
> real caller yet since hook-point dispatch remains blocked under `#98`;
> configuration-version history has no runtime producer to bridge from yet,
> so it remains unbridged); batch/by-workflow replay and replay authorization
> (single-entry replay with no authorization concept exists; head-of-line
> blocking shipped as an opt-in processor ordering policy in `#421` and is no
> longer pending); subscription/push delivery and cross-scope enumeration (no
> push mechanism exists, and every outbox read method is scoped to exactly
> one scope with no way to enumerate or query across scopes); SDK-wide health
> aggregation for the remaining subsystems (outbox and queue-worker state
> aggregate with a severity roll-up as of 2026-09-20; assets, configuration/
> policy history, scheduler, and the plugin engine still contribute nothing
> to `dataLoomHealthSnapshot`'s severity, regardless of their outbox-bridging
> status above -- health aggregation and outbox bridging are two separate
> mechanisms and a subsystem can have one without the other); and a
> deployable operations dashboard/adaptor (no Prometheus, OpenTelemetry, or
> other exporter-service code exists anywhere in the repository; only
> in-process structured-log/trace exporter adapters do).

## FR code check

No `FR-ASSET-00x` code (`docs/audits/DL-AUDIT-005-current-v1-conformance.md:274-285`)
or `FR-EVENT-00x` code names "bridge asset transfers into the operational-event
outbox" specifically -- the `FR-ASSET` table covers the asset-transfer
capability itself (manifests, chunking, resume, integrity, quotas, content
policy), not its observability bridging, and no `NFR-OBS` table exists
anywhere in the repository to check against (confirmed by the 2026-09-30
audit, section 2). This PR is tracked only via the `#96`/DL-042 dashboard
row's own "Still pending" prose and the qualification matrix's backlog table
(`docs/status/dl-042-qualification-matrix.md`, section 5.2, "Assets bridged
into the outbox"), both addressed above.
