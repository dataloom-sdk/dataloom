# Operational Envelope and Redaction

Status: **available foundation**. The shared contracts, strict redactor,
canonical V1 wire codec, and schema-upcast registry are implemented. Durable
delivery, persistence, and complete subsystem adapters remain V1 work.

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

## Remaining V1 boundary

This foundation does not yet provide:

- payload classification, minimization, encoding, encryption, or integrity;
- durable outbox/acknowledgement/replay/retention/ordering;
- subscription filtering or back-pressure delivery;
- subsystem adapters for every event and administrative action;
- policy-signature, residency, authorization, or tamper-evident audit storage;
- the operational read model or deployable reference dashboard.

Those remain owned by DL-039, DL-042, and the subsystem gates. Callers must not
treat this additive contract as durable delivery or complete V1 observability.

The frozen byte layout, untrusted-input rules, and transition constraints are
documented in the
[operational envelope wire format](./operational-envelope-wire-format.md).
