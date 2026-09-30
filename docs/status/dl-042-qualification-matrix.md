# DL-042 (`#96`) qualification matrix, re-derived from the current repository

**Audience:** the release lead and anyone deciding whether `#96`'s dashboard
row (`docs/status/market-readiness.md`, row 5) still describes reality.
**Supports:** correcting that row's "Still pending" text and judging whether
its 55% figure needs to move.
**Describes:** current code at `main` = `9a4e5c5` (2026-09-30), merged into
this audit's branch before writing. Status labels follow
[`docs/documentation-style.md`](../documentation-style.md): nothing here is a
"qualified" or "complete" claim.

This page is a docs-only audit. It changes no production code, no test, no
workflow and no ABI baseline. Every source citation below was opened and read
while writing it; one test run is cited in [Appendix A](#appendix-a-what-was-run).

## 1. Verdict

1. **The dashboard's own cited source already contradicts its "Still
   pending" cell.** `docs/api/outbox-replay-investigation.md:153` reads
   "~~Head-of-line blocking~~ -- done (opt-in processor policy)." The `#96`
   row's "Still pending" cell still lists "head-of-line blocking of a
   workflow's later events behind a `Skipped`/`Failed` one ... [as still open,
   grouped with] batch/authorized replay," even though the mechanism the
   clause describes shipped, tested, in PR `#421`
   (`OperationalEventOutboxOrderingPolicy.BLOCK_WORKFLOW_ON_UNFINISHED_ENTRY`,
   `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/operational/DurableOperationalEventOutboxProcessor.kt:184-214,592-608`).
   That PR's own fragment
   (`docs/status/fragments/2026-09-20-96-outbox-bridging-head-of-line.md`,
   section (c)) told the lead to "Remove: the head-of-line-blocking clause."
   It was not removed. This is the single most consequential finding of this
   audit (detail in section 3.1).
2. **The same cited document's very next line names two gaps the current
   dashboard row has silently dropped, not closed.**
   `docs/api/outbox-replay-investigation.md:155` reads "Subscription delivery
   and cross-scope enumeration (unchanged from before)." Neither phrase, nor
   any equivalent, appears anywhere in the current `#96` row text. No PR
   shipped either capability (section 3.2). The row currently reads as more
   complete than the repository is.
3. **Everything else the row claims as "Finished" or "Still pending" was
   checked directly against source and holds up**, with one nuance: the
   plugin engine's operational-event bridges (`PluginExecutionBoundsOperationalEventBridge`,
   `PluginLifecycleAdministrationOperationalEventBridge`) are real, tested,
   and wired into `DataLoomBuilder` (`pluginOperationalEventOutboxConfiguration`),
   not merely "owned elsewhere" as the 2026-09-20 fragment characterized them
   at the time it was written -- but the plugin engine itself has no real
   caller in application code yet (hook-point dispatch is a separate, still-
   blocked `#98` gap), so the bridge exists without anything to bridge in
   practice. This is a nuance the row's current vague "remaining unbridged
   subsystems" phrasing happens not to contradict, so it is not counted as a
   defect, but the rewritten "Still pending" text in the accompanying fragment
   states it precisely rather than leaving it implicit (section 4).
4. **Percentage.** No new capability was added by this audit. The two
   findings above are read-accuracy corrections that roughly cancel: removing
   the resolved head-of-line-blocking clause makes the row look more done;
   restoring the dropped subscription-delivery/cross-scope-enumeration clauses
   makes it look less done. Net effect on the actual, unweighted scope of what
   remains is close to zero. This audit recommends **55% unchanged** (section 5).

## 2. What `#96` / DL-042 is, and where its acceptance text lives

The repository does not carry a `#96`-specific reconciliation audit the way
`#94` and `#102` do. The nearest in-repo acceptance-criteria source is the
`FR-EVENT-001` to `FR-EVENT-012` table in two pre-existing, broader audits:

- `docs/audits/DL-AUDIT-004-v1-production-readiness.md:310-321`
- `docs/audits/DL-AUDIT-005-current-v1-conformance.md:255-266` (a later
  revision of the same table, still calling most `FR-EVENT` items "Missing")

**Both tables are now substantially stale for `FR-EVENT`, independent of this
audit's `#96`-row findings above.** They predate the entire outbox/health
body of work; several items they list "Missing" or "Partial" now have real,
tested, current-`main` implementations (`FR-EVENT-001`, `-002`, `-003`,
`-004`, `-007`, `-009`, `-011` -- section 3.3). Reconciling those two audit
documents is outside this page's scope (they cover all `#93`-`#102`
requirements, not just `#96`), but the finding is recorded here since the
task that produced this page asked to check FR codes against source the same
way `#94`'s `FR-RETRY` codes are tracked.

`docs/audits/DL-AUDIT-004-v1-production-readiness.md:435` names `#96`'s scope
directly: "Event delivery, observability, and operations read model ...
FR-EVENT-001-012 and NFR-OBS-001-012 pass with bounded delivery, platform
parity, and exporter failure isolation." No `NFR-OBS` table exists anywhere
in the repository (`grep -rn "NFR-OBS-0" docs/` matches only that one summary
line); this audit does not attempt to invent one.

## 3. Verified findings

### 3.1 Head-of-line blocking: closed, but still listed as open

**Claim in the current dashboard row:** "...head-of-line blocking of a
workflow's later events behind a `Skipped`/`Failed` one and batch/authorized
replay (per-workflow ordering and replay of acknowledged entries are now
implemented, see the 2026-09-19 log entry and
`docs/api/outbox-replay-investigation.md`)..." -- grouping head-of-line
blocking with batch/authorized replay as both still open.

**Source:**

- `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/operational/DurableOperationalEventOutboxProcessor.kt:184-214` --
  `OperationalEventOutboxOrderingPolicy` enum: `PRESENTATION_ORDER_ONLY`
  (default, unchanged behavior) and `BLOCK_WORKFLOW_ON_UNFINISHED_ENTRY`
  (holds a workflow's later entries back once an earlier one is left pending
  by a cycle -- `Skipped`, `Failed`, or `Processed` with a failed
  acknowledgement).
- Same file, `:520-608` -- `process(...)` takes `ordering:
  OperationalEventOutboxOrderingPolicy = PRESENTATION_ORDER_ONLY` and
  implements the block with a `blockedWorkflows: HashSet<WorkflowId>` accumulator
  (`:559-608`); `OperationalEventOutboxProcessingSummary.blocked` (`:173`)
  reports the count.
- `dataloom-runtime/src/commonTest/kotlin/io/dataloom/runtime/operational/DurableOperationalEventOutboxProcessorTest.kt` --
  27 `@Test` methods total in this file (grep count), including the ten the
  PR fragment names (default unchanged, interleaved workflows, skipped and
  failed, hold across cycles then in-order release, external-acknowledgement
  release, `maxEntries` accounting, acknowledgement failure, workflow-less,
  filter-rejected, summary counter). **Run on this Windows host for this
  audit** (Appendix A): `BUILD SUCCESSFUL`.
- Shipped by PR `#421`, commit `dd3147d` (2026-09-22), titled "`[#96] Bridge
  policy decisions and queue-worker scheduling into the outbox; opt-in
  head-of-line blocking`".
- `docs/api/outbox-replay-investigation.md:153-154` -- the document the
  dashboard row itself cites -- already records this: "~~Head-of-line
  blocking~~ -- done (opt-in processor policy). Still open: batch/by-workflow
  replay and replay authorization, if a real consumer needs them."
- `docs/status/fragments/2026-09-20-96-outbox-bridging-head-of-line.md`,
  section (c): "Remove: the head-of-line-blocking clause, and 'scheduler' /
  'policy decisions' from any list of unbridged subsystems." This instruction
  was given to the lead five commits before the current row text's last
  edit and was not acted on for the head-of-line-blocking half.

**Verdict:** the "Still pending" cell's head-of-line-blocking clause is
factually wrong today. What remains genuinely open, correctly, is
**batch/by-workflow replay and replay authorization** -- confirmed separately
in section 3.4.

### 3.2 Subscription delivery and cross-scope enumeration: still open, but silently dropped from the row

**Claim in the current dashboard row:** neither phrase appears. `grep -c`
for "subscription delivery" and "cross-scope enumeration" against the row's
raw text returns zero matches for both.

**Source that these are still genuinely open (unchanged, not merely
undocumented):**

- No push/subscription delivery mechanism exists anywhere in the repository.
  `grep -rln "class.*Subscri\|interface.*Subscri\|PushDelivery"
  --include="*.kt" .` (excluding `build/`) returns no matches. The only event
  delivery mechanisms are: (a) `SynchronizationEventDispatcher.dispatch(event)`
  (`dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/observation/SynchronizationEventDispatcher.kt:165-209`),
  a synchronous, one-shot, caller-invoked fan-out to already-registered
  observers with no push/subscribe semantics of its own; and (b) the durable
  outbox's pull-based `entries`/`pendingEntries`/`acknowledgedEntries`
  (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/operational/DurableOperationalEventOutbox.kt:449-480`),
  which a caller must poll.
- No cross-scope enumeration exists. Every read method on
  `DurableOperationalEventOutbox` --  `entries`, `pendingEntries`,
  `acknowledgedEntries` (same file, `:449,458,474`) -- takes exactly one
  `scope: OperationalEventOutboxScope` parameter. There are now **eight**
  independent outbox-configuration entry points on `DataLoomBuilder`, each
  with its own default scope string (`DataLoomBuilder.kt:483`
  `operationalEventOutboxConfiguration` "sync-events"-equivalent default,
  `:519` retry/circuit, `:557` strategy decision, `:576` policy decision,
  `:592` queue-worker scheduling, `:643` queue lifecycle, `:720` conflict
  resolution, `:975` plugin), and nothing in the outbox API can list "every
  scope this process has ever appended to" or read across more than one scope
  in a single call. A caller who wants "everything pending, across every
  bridged subsystem" must already know all eight scope values and issue eight
  separate calls.
- `docs/api/outbox-replay-investigation.md:155`: "Subscription delivery and
  cross-scope enumeration (unchanged from before)" -- the same document the
  dashboard cites for the (correctly-resolved) ordering/replay work confirms
  both remain open, dated alongside the 2026-09-19/20 fragments that also
  listed them as "keep, still open."

**Verdict:** both gaps are real today. The dashboard row should name them,
and currently does not. This is an understatement of remaining scope, the
opposite direction from the head-of-line-blocking overstatement in 3.1.

### 3.3 Outbox bridging: every subsystem the row could name is now covered by a real, wired, tested bridge -- except assets and configuration history, which the row already gets right

| # | Subsystem | Bridge class | `DataLoomBuilder` setter | Shipped | Wired to a real producer? |
|---|---|---|---|---|---|
| 1 | Synchronization events | `SynchronizationOperationalEventBridge` (`dataloom-runtime/.../observation/operational/SynchronizationOperationalEventBridge.kt`, 329 lines) | `operationalEventOutboxConfiguration` (`DataLoomBuilder.kt:483`) | `#324` (2026-08-18) | Yes -- every dispatched `SynchronizationEvent` |
| 2 | Retry/circuit administration | `RetryCircuitAdministrationOperationalEventBridge` (462 lines) | `retryCircuitAdministrationOperationalEventOutboxConfiguration` (`:519`) | `#335` (2026-08-22) | Yes -- `DefaultDataLoomRetryAdministration`/`DefaultDataLoomCircuitAdministration` results |
| 3 | Strategy-decision diagnostics | `StrategyDecisionOperationalEventBridge` (244 lines) | `strategyDecisionOperationalEventOutboxConfiguration` (`:557`) | `#336` (2026-08-22) | Yes, opt-in on top of `strategyDiagnosticsConfiguration` |
| 4 | Policy decisions (strategy-admission policy) | `PolicyDecisionOperationalEventBridge` (181 lines) | `policyDecisionOperationalEventOutboxConfiguration` (`:576`) | `#421` (2026-09-22) | Yes -- `StrategySynchronizationExecutionCoordinator`, opt-in on top of `strategyAdmissionPolicyConfiguration` (`StrategyAdmissionPolicyConfiguration.kt`) |
| 5 | Queue-worker wake-up scheduling | `QueueWorkerSchedulingOperationalEventBridge` (252 lines) | `queueWorkerSchedulingOperationalEventOutboxConfiguration` (`:592`) | `#421` (2026-09-22) | Yes -- wraps both `DataLoomQueueWorker.build()` and `DataLoomCircuitQueueWorker.build()` via `SchedulingEventBridgingQueueWorkers.kt` |
| 6 | Queue lifecycle (both worker paths) | `QueueLifecycleOperationalEventBridge` (375 lines) + `QueueLifecycleOperationalEventRecorder` | `queueLifecycleOperationalEventOutboxConfiguration` (`:643`) | `#339`/`#343` (2026-08-23) | Yes -- `DurableQueueExecutionProcessor` and `CircuitBreakerDurableQueueExecutionProcessor` |
| 7 | Conflict resolution | `ConflictResolutionOperationalEventBridge` (562 lines) | `conflictResolutionOperationalEventOutboxConfiguration` (`:720`) | `#96` PR (2026-08-24, per dashboard) | Yes -- `DurableConflictDetectionCoordinator` |
| 8 | Plugin engine (lifecycle transitions + bounded invocations) | `PluginExecutionBoundsOperationalEventBridge` (187 lines, `dataloom-plugin`) + `PluginLifecycleAdministrationOperationalEventBridge` (271 lines, `dataloom-plugin`) | `pluginOperationalEventOutboxConfiguration` (`:975`) | `#419` (2026-09-21), plugin-lifecycle half added in the integration commit `65a90de` (2026-09-29) | Mechanically yes (`DataLoomBuilderPluginOperationalEventOutboxTest.kt` proves it through a real built `DataLoom.pluginEngine`), but **the plugin engine has no real caller in application code** -- confirmed by the `#98` dashboard row itself: "no `DataLoomBuilder` wiring exists for this engine at all [for hook-point dispatch] ... no application-facing plugin-registration API exists yet." So results only exist if application code calls `DataLoom.pluginEngine.transition`/`.execute` directly; nothing inside the SDK's own synchronization pipeline produces them yet. |

Not bridged, confirmed by source search rather than absence of a name match:

- **Assets** (`dataloom-assets` module). `grep -rln "OperationalEvent\|Outbox"
  dataloom-assets` (excluding `build/`) returns nothing. The module's own
  `AssetTransferEvent` (`dataloom-assets/src/commonMain/kotlin/io/dataloom/assets/AssetTransferSession.kt`)
  is a closed sealed class fed into an internal `advance(sessionId, event)`
  state-machine transition function
  (`AssetTransferEngine.kt:245,307,309,350,353,360,381,413,415,426,459,478`)
  -- it is a state-machine label, not an operational/audit event, and has no
  bridge, no envelope construction, and no outbox reference anywhere. This
  matches the row's own claim.
- **Configuration/policy history.** `DurableConfigurationHistory`
  (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/configuration/DurableConfigurationHistory.kt:133`)
  is constructed nowhere outside its own file (`grep -rn
  "DurableConfigurationHistory(" --include="*.kt" .` matches only the
  constructor's own declaration). There is no runtime caller that records a
  configuration version, so there is no event of that kind to bridge -- not a
  missing bridge, a missing producer. This matches the row's own claim.
  (Note: **policy decisions**, the other half of the row's "configuration/
  policy history" phrase, are in fact bridged -- item 4 above. The compound
  phrase is only half accurate; see the rewritten text in the fragment.)
  `DurableAssetManifestHistory` (`dataloom-api/src/commonMain/kotlin/io/dataloom/api/asset/DurableAssetManifestHistory.kt:150`)
  is in the identical state: declared, never constructed outside its own file.

### 3.4 Replay: single-entry, unauthorized -- the row's remaining claim here is accurate

- `dataloom-api/src/commonMain/kotlin/io/dataloom/api/operational/DurableOperationalEventOutbox.kt:563-579` --
  `replay(scope, id)` operates on exactly one entry id, returns
  `Replayed`/`AlreadyPending`/`NotFound`.
- Same file, KDoc at `:325-326`: "`replay` is an explicit operation on one
  entry id. It has no authorization concept of its own, exactly like
  `acknowledge`: authorizing an operator is [the caller's responsibility]."
- No batch-replay method (`replayAll`, `replayWorkflow`, or similar) exists
  anywhere in `DurableOperationalEventOutbox` or
  `DurableOperationalEventOutboxProcessor`.

**Verdict:** "batch/authorized replay" is correctly still listed as pending.

### 3.5 Health aggregation: the row's explicit list of four non-contributing subsystems is accurate

- `dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/observation/health/DataLoomHealthSnapshot.kt:176-184` --
  `DataLoomHealthSnapshot` has exactly five fields: `providerLifecycleState`,
  `retryCircuitTelemetry`, `providerHealth`, `outboxHealth`,
  `queueWorkerHealth`. `grep -n
  "policyDecision\|queueWorkerScheduling\|pluginEngine\|PluginHealth\|
  SchedulerHealth\|PolicyHealth\|AssetHealth\|ConfigurationHealth"` against
  every file in `dataloom-runtime/.../observation/health/` returns nothing.
- This is a genuinely different mechanism from outbox bridging (3.3): a
  subsystem can durably append audit-trail envelopes to the outbox (as
  policy decisions, queue-worker scheduling, and the plugin engine now do)
  while still contributing zero severity/finding signal to
  `dataLoomHealthSnapshot`'s roll-up, because that function only reads
  `ProviderLifecycleCoordinator.state`, `BoundedRetryCircuitTelemetry
  .snapshot()`, caller-awaited `DataLoomProvider.health()` results, and the
  two trackers named above -- nothing about policy, scheduling, plugins,
  assets, or configuration reaches it.
- **Verdict:** "assets, configuration/policy history, scheduler and the
  plugin engine still contribute nothing" to health is accurate as written,
  even though (per 3.3) three of those four names are now misleading if read
  as a claim about *outbox bridging* rather than *health aggregation*
  specifically. The row's placement of this clause under the health-
  aggregation parenthetical, not the outbox-bridging clause, is correct as
  written; the risk is a future reader conflating the two, which the
  rewritten text in the fragment addresses by naming the two mechanisms
  explicitly.

### 3.6 Deployable operations dashboard/adaptor: confirmed absent

- `grep -rliE "prometheus|opentelemetry|otlp|grafana" --include="*.kt" .`
  (excluding `build/`): no matches anywhere in the repository.
- `find . -iname "*exporter*.kt"` (excluding `build/`): no matches by
  filename; the only classes matching `class.*Exporter`/`interface.*Exporter`
  are `RetryCircuitTelemetryExporter` (an in-process interface,
  `dataloom-runtime/.../observation/retry/RetryCircuitTelemetry.kt:175`) and
  its two adapters, `RetryCircuitStructuredLogExporter` and
  `RetryCircuitTraceExporter`
  (`RetryCircuitTelemetryAdapters.kt:50,81`) -- structured logging and
  tracing hooks a host application wires into its own logger/tracer, not a
  network-facing metrics/dashboard endpoint.
- `find . -iname "*dashboard*"` (excluding `build/`): no matches.
- **Verdict:** the row's claim is accurate. Nothing resembling a deployable
  service, HTTP endpoint, or exporter to an external monitoring system exists.

### 3.7 `FR-EVENT` cross-cutting mapping (informational; not itself a dashboard-row defect)

| FR | Requirement | Current state | Evidence |
|---|---|---|---|
| 001 | Canonical envelope | **Met** | `OperationalEventEnvelope` (`dataloom-api/.../operational/OperationalEventEnvelope.kt:119-149`): id, type, source, category, schemaVersion, occurredAt, correlationId, causationId, traceId, tenantId, workflowId, payload descriptor, redacted attributes |
| 002 | Event categories | **Met** | `OperationalEventCategory` enum, six values: `DOMAIN, LIFECYCLE, SYSTEM, AUDIT, TELEMETRY, DIAGNOSTIC` (same file, `:74-81`) |
| 003 | Ordered workflow events | **Met** | Per-key monotonic `sequence` assigned inside the persisting compare-and-set (`docs/api/outbox-replay-investigation.md` D2; `DurableOperationalEventOutbox`) |
| 004 | At-least-once delivery | **Met for the durable outbox path; not for the live dispatcher** | Outbox: append/acknowledge/replay with tombstone retention. Live `SynchronizationEventDispatcher.dispatch` (section 3.2) is one synchronous in-process pass with no persistence or redelivery of its own -- durability comes only from a caller also wiring the corresponding outbox bridge |
| 005 | Subscription filtering | **Partial** | The durable outbox processor takes a general predicate `OperationalEventOutboxEntryFilter` (`DurableOperationalEventOutboxProcessor.kt:116-123`), which can express a type/category filter but is not a structured type/workflow/tenant/severity filter. The live `SynchronizationObserverRegistry` has no filtering at all -- every observer receives every event (`SynchronizationObserverRegistry.kt:76-128`) |
| 006 | Back-pressure | **Not evidenced** | `SynchronizationEventDispatcher.dispatch` is fully synchronous with no bounded buffer, `Channel`, or overflow policy (`SynchronizationEventDispatcher.kt:104-109`); a slow observer blocks the caller rather than being shed. The outbox's count/age retention (`#344`/`#347`, 2026-08-23/24) bounds storage, not live delivery pressure |
| 007 | Sensitive-data redaction | **Met** | `StrictDataLoomRedactor`/`ClassifiedData`/`DataClassification` convention applied uniformly across every bridge and the health snapshot (`DataLoomHealthSnapshot.kt:22-46,397-423`) |
| 008 | Notification hooks | **Partial** (unchanged from the old audits) | General `SynchronizationObserver` callbacks exist; no policy-controlled hook/delivery semantics beyond "call every observer" |
| 009 | Schema evolution | **Met** | `OperationalEnvelopeUpcasterRegistry` (`dataloom-runtime/.../operational/OperationalEnvelopeUpcasting.kt:68-`) -- explicit per-type, per-source-version upcast steps, monotonic version requirement enforced at construction; outbox codec separately versioned (schema version 1 to 2, `docs/api/outbox-replay-investigation.md` D3) |
| 010 | Event persistence | **Met for the outbox; no query API beyond per-scope list** | Durable via `DurableStateStore`; retention, but no cross-scope query (3.2) |
| 011 | Event correlation | **Met** | `correlationId`, `causationId`, `traceId`, `tenantId`, `workflowId` all first-class envelope fields |
| 012 | Consumer isolation | **Partial** (unchanged from the old audits) | `SynchronizationEventDispatcher.deliverToObserver` isolates ordinary exceptions per observer (`:230-244`) but has no timeout, budget, or bulkhead -- a slow observer's cost is fully absorbed by the caller, unlike `PluginExecutionBoundsEnforcer`'s timeout/concurrency-limit pattern in the sibling plugin engine |

These map to the `#96` gate as a whole, not specifically to the current
dashboard row's own wording, and are included because the task behind this
audit asked for an FR-code check comparable to `#94`'s `FR-RETRY` table. They
do not change the verdict in section 1; they corroborate that the row's own
narrower claims (outbox bridging, health aggregation, deployable dashboard)
are the right things to have been tracking.

## 4. What the plugin-engine nuance means in practice

Section 3.3 item 8 is worth stating plainly since it is easy to
over-claim in either direction:

- **True:** if application code calls `DataLoom.pluginEngine.transition(...)`
  or `.execute(...)` today, with `pluginOperationalEventOutboxConfiguration`
  configured, a real `OperationalEventEnvelope` is durably appended for that
  call, redacted the same way every other bridge redacts. This is not a
  stub, mock, or unwired class -- `DataLoomBuilderPluginOperationalEventOutboxTest.kt`
  exercises it through a real built `DataLoomBuilder`.
- **Also true:** nothing in the SDK's own synchronization pipeline calls the
  plugin engine. Hook-point dispatch -- the mechanism that would let a
  provider, transport, or strategy execution actually invoke a registered
  plugin during a real sync -- is confirmed blocked repository-wide (the
  `#98` row's own text, re-verified for this audit by the same "no real
  caller" statement appearing in `DataLoomPluginOperationalEventOutboxSpec.kt`'s
  KDoc). So in a deployed application that has not itself written code
  calling the plugin engine directly, this bridge produces zero events, not
  because it is broken, but because nothing upstream of it fires yet.

This is the same "landed, not yet consumed" pattern the lead's own sync
commit (`00b724b`) already applied to policy decisions and queue-worker
scheduling when it chose not to raise `#96`'s percentage for `#421`. Applying
it consistently to the plugin bridge as well supports this audit's
recommendation not to raise the percentage for that capability either.

## 5. Ranked backlog

### 5.1 Bounded, engineering-only next slices (no human decision needed)

| Rank | Slice | Files | Proof | Environment |
|---:|---|---|---|---|
| 1 | Fix the two dashboard-row text defects this audit found: remove the head-of-line-blocking clause from "Still pending" (3.1); restore "subscription delivery" and "cross-scope enumeration" (3.2). Already drafted in this audit's fragment | `docs/status/market-readiness.md` row 5 (lead-owned; this audit does not edit it directly) | Text review against sections 3.1-3.2's citations | Windows, review only |
| 2 | Cross-scope enumeration: add a read method that lists every scope a `DurableOperationalEventOutbox`-backed store currently holds state for, or an explicit multi-scope `entries(scopes: Collection<...>)` convenience over the existing per-scope call | `dataloom-api/.../operational/DurableOperationalEventOutbox.kt`; depends on what `DurableStateStore` can already enumerate -- needs a design look at that contract first, but no external blocker | New unit tests over an in-memory multi-scope store | Windows, fully |
| 3 | Back-pressure/consumer isolation for the live `SynchronizationEventDispatcher` (FR-EVENT-006/012): a bounded per-observer timeout, mirroring `PluginExecutionBoundsEnforcer`'s `withTimeoutOrNull` pattern already established in `dataloom-plugin` | `SynchronizationEventDispatcher.kt`, `SynchronizationObserverDispatchFailureReason.kt` (new reason for timeout) | New tests: slow observer times out, others still delivered, no change to existing passing tests | Windows, fully (JVM unit tests; no platform dependency) |
| 4 | Batch/by-workflow replay: a `replayWorkflow(scope, workflowId)` or similar over the existing `acknowledgedEntries`/`replay` primitives, still with no built-in authorization (caller-gated, matching `replay`'s existing documented posture) | `DurableOperationalEventOutbox.kt` | New tests over the existing acknowledged-tombstone mechanism | Windows, fully |
| 5 | Structured subscription filter type (type/workflow/tenant/severity/category) as a convenience wrapper over the existing general `OperationalEventOutboxEntryFilter` predicate, if a real consumer is found to need one instead of writing the predicate directly | `dataloom-runtime/.../operational/DurableOperationalEventOutboxProcessor.kt` | New tests | Windows, fully |
| 6 | Reconcile `FR-EVENT-00x` verdicts in `docs/audits/DL-AUDIT-004-v1-production-readiness.md` and `-005-current-v1-conformance.md` against section 3.7 of this page (both currently predate the outbox/health work and call most of it "Missing") | Those two files (out of scope for this page to edit; broader than `#96` alone) | Doc review | Windows, review only |

### 5.2 Genuinely blocked on a decision or infrastructure this audit cannot supply

| Item | What is blocking it |
|---|---|
| Assets bridged into the outbox | Not a technical blocker -- `AssetTransferEvent`/`advance(...)` (`AssetTransferSession.kt`, `AssetTransferEngine.kt`) is a clean, closed sealed-class transition log an bridge could map from, the same shape `QueueLifecycleOperationalEventBridge` already bridges from `QueueEntryTransitionObserver`. This is an ownership/sequencing choice (the 2026-09-20 fragment states "assets ... owned elsewhere"), not a missing capability, so it belongs on whichever agent or lead currently owns `dataloom-assets` roadmap sequencing, not `#96`'s own backlog |
| Configuration-history and policy-history events to bridge | No runtime caller records a configuration version through `DurableConfigurationHistory` at all (3.3) -- there is no event yet, so there is nothing for `#96` to bridge until whatever gate owns configuration versioning (`#93`) builds that producer first |
| Deployable operations dashboard/adaptor | Genuinely unscoped: no decision has been recorded anywhere in `docs/adr/` on which export protocol (Prometheus pull, OTLP push, a bespoke JSON endpoint) or which deployment shape (embedded HTTP server, sidecar, host-supplied adapter interface) this SDK should target. This is a product/architecture decision, not an engineering gap this audit can bound into a single PR |
| Hook-point dispatch (blocks the plugin bridge's practical value) | Owned by `#98`, not `#96`; re-confirmed still blocked in this audit (section 4) but not this gate's own backlog item |
| `NFR-OBS-001` to `-012` | No such table exists anywhere in the repository to check against; `DL-AUDIT-004`'s one summary line (`docs/audits/DL-AUDIT-004-v1-production-readiness.md:435`) is the only in-repo mention. Someone with access to the original `#92`/Book 2 acceptance text would need to either transcribe it into the repository or confirm it was folded into the `FR-EVENT` list already |

## Appendix A: what was run

On this Windows host, for this audit:

- `:dataloom-runtime:jvmTest --tests
  "io.dataloom.runtime.observation.operational.PolicyAndSchedulerOperationalEventBridgesTest"`:
  `BUILD SUCCESSFUL`, exit code 0.

Not run (read only): every other test file cited above, including
`DurableOperationalEventOutboxProcessorTest` (27 tests, cited by name and
line only), `DataLoomBuilderPluginOperationalEventOutboxTest`,
`PluginExecutionBoundsOperationalEventBridgeTest`,
`PluginLifecycleAdministrationOperationalEventBridgeTest`. None of this
audit's conclusions depend on a platform (Android emulator or iOS Simulator)
run; every citation above is either JVM-testable source or a static-search
absence claim (`grep`/`find` over the whole repository, `build/` excluded).
Nothing in this gate's remaining scope, as re-derived here, requires macOS CI
or an Android emulator to make progress on -- the entire remaining backlog in
section 5.1 is commonMain/JVM-shaped.
