# Operational Envelope and Redaction

Status: **available foundation**. The shared contracts, strict redactor,
canonical V1 wire codec, and schema-upcast registry are implemented. A
bounded first slice of durable persistence exists (see "Durable outbox"
below); acknowledgement, replay, retention, filtering, and complete subsystem
adapters remain V1 work.

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

### What this does not do yet

- **No acknowledgement or per-item removal.** `entries` always returns the
  full retained list; there is no "mark consumed" operation.
- **No retention or eviction policy.** Entries accumulate without bound
  (bounded only by the codec's overall encoded-length safety limit, not by
  business retention policy).
- **No filtering or subscription delivery.**
- **No enumeration across scopes.** A caller must already know which
  `OperationalEventOutboxScope` to read.
- **Two real wired callers so far — synchronization events, and retry/circuit
  administration commands.** `SynchronizationOperationalEventBridge`
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

  For both bridges, bridging failures (envelope construction) and append
  outcomes other than success (`Conflict`, `PersistenceFailure`,
  `ContentionLimitReached`) are always swallowed — consistent with
  `StrategySynchronizationExecutionCoordinator`'s own durable-diagnostics
  posture, a durable side-record must never change or hide the real result of
  the operation it is describing. Every other subsystem's events (conflict
  resolution, strategy decisions, queue lifecycle, and so on) remain
  unbridged; wiring those is separate, larger follow-up work, consistent with
  `DurableConfigurationHistory` and `DurablePolicyDecisionLog` each
  originally shipping without a real caller.

## Remaining V1 boundary

This foundation does not yet provide:

- payload classification, minimization, encoding, encryption, or integrity;
- durable outbox acknowledgement, replay, retention, or cross-scope
  enumeration (ordered append and single-scope read-back exist -- see
  "Durable outbox" above);
- subscription filtering or back-pressure delivery;
- subsystem adapters for every event and administrative action;
- policy-signature, residency, authorization, or tamper-evident audit storage;
- the operational read model or deployable reference dashboard.

Those remain owned by DL-039, DL-042, and the subsystem gates. Callers must not
treat this additive contract as durable delivery or complete V1 observability.

The frozen byte layout, untrusted-input rules, and transition constraints are
documented in the
[operational envelope wire format](./operational-envelope-wire-format.md).
