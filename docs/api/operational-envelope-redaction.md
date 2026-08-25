# Operational Envelope and Redaction

Status: **available foundation**. The shared contracts, strict redactor,
canonical V1 wire codec, and schema-upcast registry are implemented. A
bounded first slice of durable persistence exists, including opt-in
count-based and age-based retention policies, operator-driven per-entry
acknowledgement, and an opt-in read-then-consume processing loop (see
"Durable outbox" below); replay, filtering, and complete subsystem adapters
remain V1 work.

## Purpose

`OperationalEventEnvelope` is the one canonical identity, routing, correlation,
and payload-description boundary for operational events, audit records,
telemetry, diagnostics, and future support output. It prevents retry, conflict,
assets, plugins, and enterprise governance from inventing incompatible envelope
or redaction formats.

The envelope contains:

- stable event ID, event type, source, category, and positive schema version;
- explicit UTC occurrence time;
- required correlation plus optional causation and trace identity;
- optional tenant and workflow routing scope;
- a non-sensitive descriptor for separately encoded payload content; and
- `RedactedAttributes`, which cannot be constructed from a raw map by a caller.

The envelope does not read a clock, generate identifiers, encode payload bytes,
or accept unrestricted metadata. Its diagnostic `toString()` excludes event,
correlation, trace, tenant, workflow, and attribute identities.

## Central redaction boundary

Every field first enters `ClassifiedData` with one of four classifications:

| Classification | Strict default |
|---|---|
| `PUBLIC` | Keep, subject to the configured value-length bound |
| `INTERNAL` | Replace with the constant mask |
| `CONFIDENTIAL` | Remove; a custom policy may mask but cannot keep it |
| `RESTRICTED` | Always remove; this cannot be weakened by configuration |

`StrictDataLoomRedactor` validates serialization-safe ASCII field keys, sorts
keys for deterministic results, bounds field and value output, reports masked,
removed, truncated, and overflow counts, and never renders input names or
values through diagnostics.

```kotlin
val attributes = StrictDataLoomRedactor().redact(
    ClassifiedData.of(
        mapOf(
            "status" to ClassifiedDataValue(
                value = "scheduled",
                classification = DataClassification.PUBLIC,
            ),
            "worker" to ClassifiedDataValue(
                value = "private-worker-id",
                classification = DataClassification.INTERNAL,
            ),
        ),
    ),
).attributes

val envelope = OperationalEventEnvelope(
    id = OperationalEventId("event-001"),
    type = OperationalEventType("dataloom.retry.scheduled"),
    source = OperationalEventSource("dataloom.runtime.retry"),
    category = OperationalEventCategory.TELEMETRY,
    schemaVersion = OperationalSchemaVersion(1),
    occurredAt = DataLoomInstant(1_000L),
    correlationId = CorrelationId("correlation-001"),
    payload = OperationalPayloadDescriptor(
        type = OperationalPayloadType("dataloom.retry.signal"),
        schemaVersion = OperationalSchemaVersion(1),
        encoding = OperationalPayloadEncoding("application/json"),
        classification = DataClassification.INTERNAL,
    ),
    attributes = attributes,
)
```

## Message content redaction

`ClassifiedData`/`StrictDataLoomRedactor` above redact already-classified,
*structured* key-value pairs — every field's sensitivity is known up front
by the caller. `MessageContentRedactor` (`io.dataloom.api.security`)
addresses a different, complementary case: *unstructured* free text, most
notably [`DataLoomError.message`](./error-model.md#sensitive-data-restrictions),
which carries no field-level classification to consult.

`DataLoomError.message` is documented to never include credentials,
tokens, keys, or personal data, but that is a convention each
implementation must uphold when constructing its own message — not
something the type system enforces. `MessageContentRedactor` exists as
defense-in-depth for the case where that convention is violated anyway:

```kotlin
public fun interface MessageContentRedactor {
    public fun redact(message: String): String
}
```

`PatternBasedMessageContentRedactor`, the reference implementation, is a
deterministic, bounded, fail-closed scan of free text for a fixed set of
common secret-shaped patterns — Bearer/Authorization tokens, JWT-shaped
three-segment tokens, AWS-style access key IDs, sensitive query-string
parameter values (only the value is masked; the parameter name is kept for
diagnosability), URL Basic-Auth embedded credentials, and email addresses.
Input longer than 8,192 characters is bounded before any pattern runs, so
cost stays predictable regardless of input size.

```kotlin
val redactor = PatternBasedMessageContentRedactor()
redactor.redact("GET https://api.example.test/data?token=SECRET123 failed")
// "GET https://api.example.test/data?token=[REDACTED] failed"
```

**This is not a general-purpose secret scanner.** It recognizes a fixed,
reference set of common patterns and nothing more — it will not detect
arbitrary opaque secrets, application-specific credential formats, or
deliberately obfuscated content. It is one layer of defense-in-depth
alongside — never instead of — each `DataLoomError` implementation's own
responsibility to never put sensitive content in `message` in the first
place.

No call site is wired to it yet. The one confirmed live violation of the
"`message` must not include credentials, tokens, keys, or personal data"
convention this codebase had (`ApolloErrorMapper`, forwarding a wrapped
exception's message after only truncating it) was already fixed by
removing the unsafe forwarding entirely rather than needing redaction —
see [error model](./error-model.md#sensitive-data-restrictions)'s "Closed
gap" note. The primitive exists and is tested; adopting it at a real call
site remains available, not forced, consistent with this codebase's
standing "don't build ahead of a concrete near-term consumer" discipline.

## Durable outbox (bounded first slice)

`DurableOperationalEventOutbox` (`io.dataloom.api.operational`) is a bounded
first slice of DL-042's durable-outbox requirement: it persists
`OperationalEventEnvelope` instances so they survive a process restart, and
reads back everything currently persisted for one
`OperationalEventOutboxScope`, oldest first.

```kotlin
val outbox = DurableOperationalEventOutbox(store)
val scope = OperationalEventOutboxScope("retry-events")

when (val outcome = outbox.append(scope, envelope)) {
    is DurableOperationalEventOutboxAppendOutcome.Appended -> Unit
    is DurableOperationalEventOutboxAppendOutcome.AlreadyAppended -> Unit // idempotent retry
    else -> Unit // Conflict / PersistenceFailure / ContentionLimitReached
}

val persisted = outbox.entries(scope) // every envelope appended so far, oldest first

when (val acknowledged = outbox.acknowledge(scope, envelope.id)) {
    is DurableOperationalEventOutboxAcknowledgeOutcome.Acknowledged -> Unit // removed
    is DurableOperationalEventOutboxAcknowledgeOutcome.NotFound -> Unit // already gone; safe no-op
    else -> Unit // PersistenceFailure / ContentionLimitReached
}
```

### Why this reuses `DurableStateStore`, not `QueueProvider`

An outbox needs ordered append plus "read everything currently persisted"
semantics. `io.dataloom.api.queue.QueueProvider` already has durable
enqueue/acquire semantics, but its `QueueEntry` is DataLoom's own
synchronization workflow execution record — it requires a
`SynchronizationRequest` and carries lease, retry-attempt, and strategy-plan
fields that have no meaning for an arbitrary operational event. Reusing it
here would mean either forcing events through work-item
lease/acquire/complete/fail semantics they do not need, or breaking
`QueueEntry` to make its synchronization fields optional.

`DurableStateStore`'s own documentation already names "event outbox/audit" as
a domain it was generalized to serve. `DurableOperationalEventOutbox` follows
the same per-scope load-evaluate-compare-and-set pattern this codebase's
other durable-state adopters
(`DurableConfigurationHistory`, `DurablePolicyDecisionLog`,
`DurableUnresolvedConflictLog`) already establish: the persisted `TState`
(`OperationalEventOutboxState`) itself holds the ordered list, so ordering
and multiplicity live inside one versioned record rather than requiring a new
provider contract. `OperationalEventOutboxStateCodec` encodes each entry with
the existing frozen `OperationalEnvelopeWireCodec` V1 frame — it never
reinvents the envelope's own byte layout, only Base64-wraps each frame to
join multiple entries into one text payload.

### Retention policy

`DurableOperationalEventOutbox` takes two independent, optional retention
constructor parameters — `maximumRetainedEntries: Int?` (count-based) and
`maximumRetainedAge: Duration?` (age-based) — plus a `clock: DataLoomClock?`
the age-based policy reads. All three default to `null`. With both
retention parameters `null`, behavior is byte-for-byte unchanged from before
either policy existed: entries accumulate without bound, limited only by
`OperationalEventOutboxStateCodec`'s own overall-encoded-length safety limit
(`MAX_ENTRY_COUNT` = 10,000 entries; 4 MiB encoded). A caller may configure
either policy alone, both together, or neither.

Both policies share the same two eviction guarantees: eviction happens as
part of the very same compare-and-set write that persists the new entry,
never a separate follow-up write, and it never evicts the entry an `append`
call is itself adding. Once either policy has evicted an entry, its
`OperationalEventEnvelope.id` is no longer visible to `append`'s same-id
`AlreadyAppended`/`Conflict` duplicate check — re-appending that identifier
is indistinguishable from a genuinely new entry. That is a deliberate
trade-off of bounding retention, not an oversight.

#### Count-based retention

A caller that sets `maximumRetainedEntries` gets a bounded first slice of
real retention:

```kotlin
val outbox = DurableOperationalEventOutbox(store, maximumRetainedEntries = 5_000)
```

Once an `append` would grow the retained entry count for a scope past
`maximumRetainedEntries`, the oldest entries are evicted first — as many as
needed to bring the retained count back down to the configured cap. This
mirrors `DurableConfigurationHistory`'s own `maxRetainedVersions` shape — a
count-based cap enforced by trimming the oldest entries off an ordered list
inside the same atomic update — which is this codebase's only existing
precedent for bounding an ordered `DurableStateStore`-backed list.
Eviction driven purely by the list's own `size`/position needs no clock
read: the entries to evict are always exactly the current list's leading
elements.

#### Age-based retention

A caller that sets `maximumRetainedAge` gets a second, independent bounded
retention mode. It must also supply `clock`, since age-based eviction
needs a "now" reference `DurableOperationalEventOutbox` did not previously
hold — `OperationalEventEnvelope` itself never reads a clock:

```kotlin
val outbox = DurableOperationalEventOutbox(
    store,
    maximumRetainedAge = 30.days,
    clock = SystemDataLoomClock(),
)
```

On each `append`, every already-persisted entry (never the entry that call
is itself adding) whose `OperationalEventEnvelope.occurredAt` is older than
`maximumRetainedAge` relative to `clock`'s current reading is evicted, as
part of that same compare-and-set write. `clock` is read at most once per
`append` call, never once per entry, and only when `maximumRetainedAge` is
configured.

This class previously argued a count-based cap over an age-based one
specifically because eviction driven by `size` alone needs no clock and no
dependency on `occurredAt` being a trustworthy "now" reference — that
trade-off has not disappeared just because an age-based mode now also
exists. `OperationalEventEnvelope` still documents that it never reads a
global clock and treats every time value as caller-supplied, not
authoritative: `maximumRetainedAge` takes `occurredAt` at face value as the
event's stated occurrence time, so a caller supplying an inaccurate,
backdated, or clock-skewed `occurredAt` gets correspondingly inaccurate
age-based eviction. Reusing `occurredAt` rather than inventing a new
per-entry "appended at" timestamp was a deliberate choice: it keeps
`OperationalEventOutboxState`'s persisted shape completely unchanged, where
a new field would have been a materially larger, separately-versioned
schema change for what remains a bounded first slice. A caller whose
events' `occurredAt` cannot be trusted as wall-clock-comparable should
prefer `maximumRetainedEntries` instead, or configure both so a single
runaway `occurredAt` value cannot defeat bounding entirely.

Because `occurredAt` is caller-supplied and not guaranteed to align with
append order, age-based eviction cannot reuse count-based retention's
"always the leading elements" shortcut — it evaluates every
already-persisted entry's `occurredAt` regardless of position.

Like count-based retention, age-based retention never evicts the entry an
`append` call is itself adding, even if that entry's own `occurredAt` is
already outside the retention window — for example, a deliberate historical
backfill. It still appears in `entries` immediately after that successful
append, and only becomes a candidate for age-based eviction on a later
`append` call, once it is no longer the entry being added.

#### Composing both policies

When both are configured, each `append` applies them in one fixed order, as
part of the same compare-and-set write: age-based eviction runs first, over
the already-persisted entries only; the new envelope is then appended; then
count-based eviction runs over the resulting list. An entry evicted by
either policy ends up evicted either way — age-based eviction can remove
entries count-based eviction alone would have kept (an old entry sitting
well within the count cap), and count-based eviction can remove entries
age-based eviction alone would have kept (a recent entry pushed out purely
by volume). This fixed order is itself part of the contract, not an
incidental implementation detail — since `occurredAt` need not align with
append order, evaluating the two policies in the opposite order could
otherwise surface a different surviving set.

#### Optionality

Both `maximumRetainedEntries` and `maximumRetainedAge` (with `clock`) are
optional constructor parameters rather than required ones, matching this
codebase's existing "optional collaborator" pattern for additive,
backward-compatible behavior (for example `QueueEntryTransitionObserver? = null`
on the queue-execution processors) — existing callers that construct
`DurableOperationalEventOutbox` without them see no behavior change at all.

### Acknowledgement

`DurableOperationalEventOutbox.acknowledge(scope, id)` removes exactly one
entry -- the one whose `OperationalEventEnvelope.id` matches -- from a
scope's retained list, as part of one atomic compare-and-set write, using the
same load-evaluate-compare-and-set retry loop `append` already uses:

```kotlin
val outcome = outbox.acknowledge(scope, envelope.id)
```

This is deliberately **operator-driven dismissal from view, not work-queue
completion**. Every real caller of this outbox today (the four bridges
documented below) durably appends an envelope purely for operator visibility
and debugging, as a side-record of something that already happened -- none
of them ever reads `entries` back to decide what to do next, and append
outcomes other than success are always swallowed rather than gated on.
`acknowledge` does not add or imply any "processing" contract this outbox
never had: it is the durable counterpart of an operator clearing a
diagnostic entry they have already reviewed out of a dashboard list. A
caller that genuinely needs lease/acquire/complete work-queue semantics
still belongs on `io.dataloom.api.queue.QueueProvider`, for the same reasons
this outbox reuses `DurableStateStore` rather than `QueueProvider` in the
first place (see above).

`acknowledge` composes with retention with no special-case handling needed:
both operate on the exact same persisted `OperationalEventOutboxState.entries`
list by producing a new list with some entries removed. Acknowledging an
entry retention already evicted, or retention evicting an entry that was
already acknowledged, are simply the same "entry no longer present" state
reached two different ways -- `acknowledge` reports
`DurableOperationalEventOutboxAcknowledgeOutcome.NotFound` for the former, a
well-defined no-op rather than a failure. Acknowledging the same id twice is
therefore safe: the second call also reports `NotFound`.

### Read-then-consume processing (opt-in)

`DurableOperationalEventOutboxProcessor` (`io.dataloom.runtime.operational`)
is the first real read-then-consume loop over
`DurableOperationalEventOutbox.entries`/`acknowledge` -- purely additive,
opt-in usage of that already-public API. `DurableOperationalEventOutbox`
itself is unmodified; nothing above changes for the five bridges, which still
only ever append.

```kotlin
val processor = DurableOperationalEventOutboxProcessor(outbox)
val result = processor.process(scope, maxEntries = 100) { envelope ->
    val delivered = downstreamSink.deliver(envelope) // caller-owned side effect
    if (delivered) {
        OperationalEventOutboxEntryOutcome.Processed
    } else {
        OperationalEventOutboxEntryOutcome.Skipped
    }
}
```

One `process` call reads at most `maxEntries` currently-retained entries for
`scope` (oldest first, exactly as `entries` already returns them), hands each
to the caller-supplied `OperationalEventOutboxEntryHandler` sequentially, and
acknowledges only the ones whose outcome is
`OperationalEventOutboxEntryOutcome.Processed`. `Skipped` and `Failed` both
leave the entry retained for a later pass -- distinct signals for a caller's
own diagnostics (`OperationalEventOutboxProcessingSummary`), but treated
identically by the loop. Entries beyond `maxEntries`, if any, are simply left
for a later call; an empty scope is a no-op that never invokes the handler.

This class lives in `dataloom-runtime`, not `dataloom-api`, deliberately:
`dataloom-api`'s own module rules say it "must not contain runtime
implementations," and a cycle that invokes an application-supplied handler
and decides per-entry durable transitions from that handler's outcome is
exactly that -- the same reasoning that already places
`DurableQueueExecutionProcessor` in `dataloom-runtime` even though the
`QueueProvider` it drives lives in `dataloom-api`. Unlike that queue
processor's five-variant `QueueEntryExecutionOutcome` (lease/retry-attempt/
dead-letter aware), `OperationalEventOutboxEntryOutcome` has exactly three
variants and no queue semantics, matching this outbox's own "operator-driven
dismissal, not work-queue completion" posture for `acknowledge` (see above).
A per-entry acknowledgement failure does not stop the cycle either -- each
`acknowledge` call is an independent, idempotent removal from a diagnostics
sink, so `process` continues to the next entry and reports the failure
through the summary instead.

**Concurrency.** `entries` is a snapshot read, not a live view, so two
concurrent `process` calls against the *same* scope may both read a batch
containing the same entry and invoke the handler for it more than once
combined. What they still guarantee, inherited directly from `acknowledge`'s
own compare-and-set retry loop: no entry is ever lost, and no entry is ever
double-acknowledged -- exactly one racing `acknowledge(scope, id)` call
observes `Acknowledged`, the other observes `NotFound` once it reloads and
finds the entry already gone. A caller whose handler has side effects that
are not themselves idempotent must serialize `process` calls per scope
itself; `process` calls against different scopes never interact.

**Filtering (opt-in).** `process` accepts an optional `filter:
OperationalEventOutboxEntryFilter` -- a single-method `fun interface`
(`matches(envelope): Boolean`), the same general-predicate shape `handler`
itself already has, evaluated against every currently-retained entry for the
scope *before* `maxEntries` is applied:

```kotlin
val result = processor.process(
    scope = scope,
    maxEntries = 100,
    filter = OperationalEventOutboxEntryFilter { it.category == OperationalEventCategory.AUDIT },
) { envelope -> /* only AUDIT entries reach here */ }
```

A rejected entry is never handed to `handler`, is left retained untouched for
a later pass, and -- deliberately -- does not count toward `maxEntries`, so a
filter for a rare `OperationalEventType`/`OperationalEventCategory` is never
starved by a batch full of common non-matching entries counting against the
bound. `DurableOperationalEventOutbox` itself is unmodified: it already loads
a scope's entire retained list as one persisted document per `entries` call,
so filtering earlier (inside that class) would save no read work, only move
the same in-memory `List.filter` into a class whose own documentation already
scopes filtering out as separate follow-up work. The default filter accepts
every entry, so an unconfigured `process` call reads and hands entries to
`handler` exactly as it did before this parameter existed;
`OperationalEventOutboxProcessingSummary.filteredOut` is a new counter,
always `0` in that unconfigured case. When every retained entry is rejected,
`process` still returns `Processed` with an all-zero summary
(`read` `0`, `filteredOut` equal to the retained count) rather than `NoWork`
-- `NoWork` stays reserved for "nothing retained at all," so a caller whose
filter is simply too narrow stays distinguishable from a caller whose scope
is genuinely empty. A general predicate was chosen over a narrower, structured
filter keyed on specific envelope fields (type/category/source) because no
such structured filter-criteria type exists anywhere else in this codebase,
and a predicate is strictly more expressive without requiring a guess, ahead
of any real caller, about which fields deserve dedicated criteria.

### What this does not do yet

- **No subscription/push delivery.** `process` is still a caller-invoked pull
  loop -- filtering (above) narrows what one `process` cycle acts on, it does
  not add a live subscription that calls a caller back as matching entries
  are appended.
- **No enumeration across scopes.** A caller must already know which
  `OperationalEventOutboxScope` to read.
- **Five real wired callers so far — synchronization events, retry/circuit
  administration commands, strategy-decision diagnostics, queue lifecycle,
  and conflict resolution.**
  `SynchronizationOperationalEventBridge`
  (`io.dataloom.runtime.observation.operational`) maps every
  `SynchronizationEvent` variant (`Started`, `PhaseChanged`,
  `ProgressUpdated`, `RetryScheduled`, `ConflictDetected`, `Completed`) to an
  `OperationalEventEnvelope` — classifying every field it places into
  `attributes` via `ClassifiedData`/`StrictDataLoomRedactor` as documented
  above, reusing the event's own `occurredAt` and a sanitized form of its own
  `SynchronizationEventId` rather than reading a clock or minting a new
  identifier, and reusing `SynchronizationRequest.context`'s
  `correlationId`/`traceId`/`tenantId` and `SynchronizationRequest.workflowId`
  unchanged for the envelope's own identity fields.
  `DispatchingSynchronizationLifecycleEventEmitter` — the sole constructor
  and dispatch point of every `SynchronizationEvent` — calls the bridge and
  durably appends the result after every dispatch, but only when the
  application opts in via `DataLoomBuilder.operationalEventOutboxConfiguration(DataLoomOperationalEventOutboxSpec)`;
  when it is not configured, no envelope is ever constructed or appended.

  `RetryCircuitAdministrationOperationalEventBridge`
  (`io.dataloom.runtime.observation.operational`) maps every terminal
  `RetryAdministrationResult` (from `RetryAdministrationCoordinator`) and
  `CircuitAdministrationResult` (from `CircuitAdministrationCoordinator`) —
  the richer, already-audited domain types both coordinators' own class docs
  describe as "authorized, idempotent, and audited" — to an
  `OperationalEventEnvelope`, deriving `occurredAt` from the durable command
  state's own already-computed `updatedAt` (or the original request's
  `requestedAt`/a `ClockRegression` result's own `observedAt` when no durable
  state exists yet) rather than reading a clock, and reusing each command's
  own `commandId` unchanged as `correlationId` and (domain-prefixed and
  sanitized, so the two independent `commandId` spaces can never collide in
  one shared outbox scope) as the envelope's own `id`. `DefaultDataLoomRetryAdministration`/
  `DefaultDataLoomCircuitAdministration` — the sole facade adapters over each
  coordinator — call the bridge and durably append the result after every
  `execute()` call, opt-in via
  `DataLoomBuilder.retryCircuitAdministrationOperationalEventOutboxConfiguration(DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec)`,
  a second, separate spec from the synchronization-events one (retry/circuit
  administration commands carry no correlation/trace identity of their own
  and are available independently of whether synchronization events are
  bridged at all — see that spec's own class doc for the full rationale).
  Configuring it alone does not enable either administration capability;
  `retryAdministrationConfiguration`/`circuitAdministrationConfiguration`
  must still be configured separately, and whichever of those two is
  configured has its results bridged into the one shared outbox scope this
  spec names.

  `StrategyDecisionOperationalEventBridge`
  (`io.dataloom.runtime.observation.operational`) maps the same
  `io.dataloom.api.strategy.StrategyDecisionEvent`
  `StrategySynchronizationExecutionCoordinator` already constructs and
  durably records via `DurableStrategyDecisionEventLog` (see "Adoption:
  strategy decision diagnostics" in
  [durable-state-contracts.md](./durable-state-contracts.md)) to an
  `OperationalEventEnvelope` — reusing the event's own already-computed
  `committedAt` rather than reading a clock, and reusing the decision's own
  `StrategyDecisionId` unchanged as `correlationId` and (sanitized and
  bounded) as the envelope's own `id`. This is genuinely additive, not a
  duplicate record: `DurableStrategyDecisionEventLog` is one mutable slot per
  `StrategyDecisionId` with no ordering or enumeration across decisions and a
  caller must already know the identifier to read it, while this bridge feeds
  the same ordered, cross-subsystem outbox stream the other two bridges
  above share — giving an operator one chronological timeline across
  subsystems no single per-domain durable log provides alone.
  `StrategySynchronizationExecutionCoordinator` calls the bridge and durably
  appends the result immediately after every `DurableStrategyDecisionEventLog.record`
  call, opt-in via a third, separate spec —
  `DataLoomBuilder.strategyDecisionOperationalEventOutboxConfiguration(DataLoomStrategyDecisionOperationalEventOutboxSpec)`
  — which has no effect unless `strategyDiagnosticsConfiguration` is also
  configured, since a `StrategyDecisionEvent` is only ever constructed at all
  when that separate capability is enabled (see that spec's own class doc for
  the full rationale, mirroring why the retry/circuit spec above has no
  effect without `retryAdministrationConfiguration`/
  `circuitAdministrationConfiguration`).

  `QueueLifecycleOperationalEventBridge`
  (`io.dataloom.runtime.observation.operational`) maps
  `io.dataloom.runtime.queue.QueueEntryExecutionOutcome` — the outcome
  `DurableQueueExecutionProcessor` already computes once per acquired queue
  entry and already uses to select which durable `QueueProvider` transition
  to persist — to an `OperationalEventEnvelope`, one per witnessed
  transition. `QueueEntryExecutionOutcome.Completed` already carries its own
  `completedAt`, reused unchanged; the other four variants (`Reschedule`,
  `Deferred`, `Failed`, `Cancelled`) carry no comparable timestamp, so the
  bridge falls back to a `witnessedAt` instant its caller reads once, the
  instant the transition was confirmed durably persisted. The envelope's `id`
  is derived from the already-unique `(QueueEntryId, QueueLeaseId)` pair —
  not `QueueEntryId` alone, since one entry legitimately passes through this
  bridge more than once over its lifetime (leased, rescheduled, leased again,
  completed) — and `correlationId`/`traceId`/`tenantId`/`workflowId` reuse
  `QueueEntry.synchronizationRequest.context`/`.workflowId` unchanged, the
  same reuse `SynchronizationOperationalEventBridge` already applies to the
  same `SynchronizationRequest` shape. `QueueEntryTransitionObserver`
  (`io.dataloom.runtime.queue`) is the new, optional hook
  `DurableQueueExecutionProcessor` calls once per entry, only immediately
  after that entry's transition has already been durably persisted — never
  speculatively before, and never for an entry whose transition failed.
  `QueueLifecycleOperationalEventRecorder` implements that hook, calls the
  bridge, and durably appends the result, opt-in via a fourth, separate spec —
  `DataLoomBuilder.queueLifecycleOperationalEventOutboxConfiguration(DataLoomQueueLifecycleOperationalEventOutboxSpec)`
  — which has no effect unless `queueWorkerConfiguration` or
  `circuitQueueWorkerConfiguration` is also configured, since a queue-entry
  transition is only ever witnessed at all when one of those two separate
  capabilities is enabled. This one spec bridges both queue-worker paths:
  `CircuitBreakerDurableQueueExecutionProcessor` (the circuit-aware processor
  backing `circuitQueueWorkerConfiguration`) additionally records its own
  separate, circuit-specific outcome evidence (`QueueCircuitOperationRecord`),
  but per acquired entry it also computes the exact same
  `QueueEntryExecutionOutcome` the non-circuit-aware processor does, and
  accepts and notifies the identical `QueueEntryTransitionObserver` —
  immediately once that entry's transition's underlying `QueueProvider`
  operation has already succeeded, independent of whether the entry's
  separate circuit-state recording is later confirmed, since the real queue
  transition is already durably persisted at that point either way.

  `ConflictResolutionOperationalEventBridge`
  (`io.dataloom.runtime.observation.operational`) maps the same
  `io.dataloom.api.conflict.UnresolvedConflictRecord`/
  `io.dataloom.api.conflict.ResolvedConflictDecisionRecord`
  `DurableConflictDetectionCoordinator` already constructs and durably
  records via `DurableUnresolvedConflictLog`/`DurableResolvedConflictDecisionLog`
  to an `OperationalEventEnvelope` — reusing each record's own already-computed
  `committedAt` rather than reading a clock, and reusing the conflict's own
  `ConflictId` unchanged as `correlationId` and (domain-prefixed
  `unresolved.`/`resolved.` and sanitized, so an unresolved outcome and a
  later resolved outcome for the same `ConflictId` can never collide) as the
  envelope's own `id`. This is genuinely additive, not a duplicate record, for
  the same reason `StrategyDecisionOperationalEventBridge` is: each durable
  conflict log is one mutable slot per `ConflictId` with no ordering or
  enumeration across conflicts and a caller must already know the identifier
  to read it, while this bridge feeds the same ordered, cross-subsystem outbox
  stream the other four bridges above share.
  `DurableConflictDetectionCoordinator` calls the bridge and durably appends
  the result immediately after every `DurableUnresolvedConflictLog.record`/
  `DurableResolvedConflictDecisionLog.record` call, using the exact record
  each just received rather than re-deriving one from
  `ConflictOrchestrationResult`, opt-in via a fifth, separate spec —
  `DataLoomBuilder.conflictResolutionOperationalEventOutboxConfiguration(DataLoomConflictResolutionOperationalEventOutboxSpec)`
  — which has no effect unless `conflictDetectionConfiguration` is also
  configured, since neither durable record is ever constructed at all
  otherwise (mirroring why the strategy-decision spec above has no effect
  without `strategyDiagnosticsConfiguration`).

  For all five bridges, bridging failures (envelope construction) and append
  outcomes other than success (`Conflict`, `PersistenceFailure`,
  `ContentionLimitReached`) are always swallowed — consistent with
  `StrategySynchronizationExecutionCoordinator`'s own durable-diagnostics
  posture, a durable side-record must never change or hide the real result of
  the operation it is describing. Every other subsystem's events (and so on)
  remain unbridged; wiring those is separate, larger follow-up work,
  consistent with `DurableConfigurationHistory` and `DurablePolicyDecisionLog`
  each originally shipping without a real caller.

## Remaining V1 boundary

This foundation does not yet provide:

- payload classification, minimization, encoding, encryption, or integrity;
- durable outbox replay or cross-scope enumeration (ordered append,
  single-scope read-back, opt-in count-based and age-based retention
  policies, operator-driven per-entry acknowledgement, and an opt-in
  read-then-consume processing loop all exist -- see "Durable outbox" above);
- subscription filtering or back-pressure delivery;
- subsystem adapters for every event and administrative action;
- policy-signature, residency, authorization, or tamper-evident audit storage;
- the operational read model or deployable reference dashboard.

Those remain owned by DL-039, DL-042, and the subsystem gates. Callers must not
treat this additive contract as durable delivery or complete V1 observability.

The frozen byte layout, untrusted-input rules, and transition constraints are
documented in the
[operational envelope wire format](./operational-envelope-wire-format.md).
