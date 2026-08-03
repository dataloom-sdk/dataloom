package io.dataloom.api.operational

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.time.DataLoomInstant
import kotlin.jvm.JvmInline

/** Stable identifier for a canonical operational or audit event. */
@JvmInline
public value class OperationalEventId(public val value: String) {
    init {
        require(value.isOperationalToken()) { "OperationalEventId must be a bounded token." }
    }

    override fun toString(): String = value
}

/** Stable schema-qualified event type, for example `dataloom.retry.scheduled`. */
@JvmInline
public value class OperationalEventType(public val value: String) {
    init {
        require(value.isOperationalToken()) { "OperationalEventType must be a bounded token." }
    }

    override fun toString(): String = value
}

/** Stable producer identity, for example `dataloom.runtime.retry`. */
@JvmInline
public value class OperationalEventSource(public val value: String) {
    init {
        require(value.isOperationalToken()) { "OperationalEventSource must be a bounded token." }
    }

    override fun toString(): String = value
}

/** Stable payload type without carrying payload bytes or application objects. */
@JvmInline
public value class OperationalPayloadType(public val value: String) {
    init {
        require(value.isOperationalToken()) { "OperationalPayloadType must be a bounded token." }
    }

    override fun toString(): String = value
}

/** Stable encoding label such as `application/json` or `application/cbor`. */
@JvmInline
public value class OperationalPayloadEncoding(public val value: String) {
    init {
        require(value.isOperationalToken()) { "OperationalPayloadEncoding must be a bounded token." }
    }

    override fun toString(): String = value
}

/** Positive schema version used by both envelopes and payload descriptors. */
@JvmInline
public value class OperationalSchemaVersion(public val value: Int) {
    init {
        require(value > 0) { "OperationalSchemaVersion must be greater than zero." }
    }

    override fun toString(): String = value.toString()
}

/** Closed top-level event category used for retention, authorization, and routing policy. */
public enum class OperationalEventCategory {
    DOMAIN,
    LIFECYCLE,
    SYSTEM,
    AUDIT,
    TELEMETRY,
    DIAGNOSTIC,
}

/**
 * Non-sensitive metadata for a separately encoded payload.
 *
 * The canonical envelope intentionally does not accept payload bytes, arbitrary maps, secrets,
 * credentials, or application objects. Content crosses an operational boundary only through a
 * subsystem codec and the centralized classification/redaction policy.
 */
public data class OperationalPayloadDescriptor(
    public val type: OperationalPayloadType,
    public val schemaVersion: OperationalSchemaVersion,
    public val encoding: OperationalPayloadEncoding,
    public val classification: DataClassification,
    public val encodedSizeBytes: Long? = null,
) {
    init {
        require(encodedSizeBytes == null || encodedSizeBytes >= 0L) {
            "Operational payload size must not be negative."
        }
    }

    /** Excludes dynamic type/encoding values from ordinary diagnostics. */
    override fun toString(): String =
        "OperationalPayloadDescriptor(" +
            "schemaVersion=$schemaVersion, " +
            "classification=$classification, " +
            "hasEncodedSize=${encodedSizeBytes != null}" +
            ")"
}

/**
 * Canonical versioned envelope shared by operational, audit, event, and telemetry pipelines.
 *
 * All identity and time values are supplied explicitly. The envelope never reads a global clock,
 * generates identifiers, accepts raw metadata, or serializes payload content. [attributes] can
 * only be created by the central redaction boundary.
 */
public data class OperationalEventEnvelope(
    public val id: OperationalEventId,
    public val type: OperationalEventType,
    public val source: OperationalEventSource,
    public val category: OperationalEventCategory,
    public val schemaVersion: OperationalSchemaVersion,
    public val occurredAt: DataLoomInstant,
    public val correlationId: CorrelationId,
    public val causationId: OperationalEventId? = null,
    public val traceId: TraceId? = null,
    public val tenantId: TenantId? = null,
    public val workflowId: WorkflowId? = null,
    public val payload: OperationalPayloadDescriptor,
    public val attributes: RedactedAttributes = RedactedAttributes.Empty,
) {
    init {
        require(causationId != id) {
            "Operational event cannot identify itself as its cause."
        }
    }

    /** Bounded diagnostics that exclude every dynamic identity and attribute value. */
    override fun toString(): String =
        "OperationalEventEnvelope(" +
            "category=$category, " +
            "schemaVersion=$schemaVersion, " +
            "hasTenant=${tenantId != null}, " +
            "hasWorkflow=${workflowId != null}, " +
            "attributeCount=${attributes.size}" +
            ")"
}

private const val MAX_OPERATIONAL_TOKEN_LENGTH: Int = 128

private fun String.isOperationalToken(): Boolean =
    length in 1..MAX_OPERATIONAL_TOKEN_LENGTH && all { character: Char ->
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '.' ||
            character == '_' ||
            character == '-' ||
            character == ':' ||
            character == '/'
    }
