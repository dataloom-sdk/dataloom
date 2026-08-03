# Operational Envelope and Redaction

Status: **available foundation**. The shared contracts and strict redactor are
implemented. Durable delivery, wire codecs, schema upcasting, persistence, and
complete subsystem adapters remain V1 work.

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

## Remaining V1 boundary

This foundation does not yet provide:

- a canonical byte-level codec or compatibility/upcast registry;
- payload classification, minimization, encoding, encryption, or integrity;
- durable outbox/acknowledgement/replay/retention/ordering;
- subscription filtering or back-pressure delivery;
- subsystem adapters for every event and administrative action;
- policy-signature, residency, authorization, or tamper-evident audit storage;
- the operational read model or deployable reference dashboard.

Those remain owned by DL-039, DL-042, and the subsystem gates. Callers must not
treat this additive contract as durable delivery or complete V1 observability.
