package io.dataloom.plugin

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.plugin.PluginId
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.security.StrictDataLoomRedactor
import io.dataloom.api.time.DataLoomInstant
import kotlin.jvm.JvmInline

/**
 * Caller-supplied identifier of one bounded plugin invocation, unique across
 * every invocation whose outcome is bridged into the same outbox scope.
 *
 * [PluginExecutionBoundsOperationalEventBridge] derives the envelope id and
 * correlation id from it, exactly as
 * [PluginLifecycleAdministrationOperationalEventBridge] derives them from a
 * [PluginLifecycleAdministrationCommandId]. Unlike a lifecycle command, a
 * bounded invocation has no naturally unique identifier of its own, so the
 * caller must mint one.
 */
@JvmInline
public value class PluginExecutionInvocationId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "PluginExecutionInvocationId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Stateless bridge from a [PluginExecutionBoundsResult] -- the outcome of one
 * [PluginExecutionBoundsEnforcer.execute] call -- to an
 * [OperationalEventEnvelope], the canonical DL-042 envelope
 * [io.dataloom.api.operational.DurableOperationalEventOutbox] persists. The
 * execution-bounds counterpart of
 * [PluginLifecycleAdministrationOperationalEventBridge], following its shape
 * and rules.
 *
 * ## Every outcome is bridged
 *
 * [PluginExecutionBoundsResult.Completed], [PluginExecutionBoundsResult.TimedOut],
 * [PluginExecutionBoundsResult.ConcurrencyLimitExceeded], and
 * [PluginExecutionBoundsResult.NotActive] each map to their own event type, all
 * in category [OperationalEventCategory.AUDIT]. Refusals and timeouts are the
 * outcomes an operator most needs, but "which plugin ran and when" is
 * equally auditable, so completions are not dropped.
 *
 * ## Never the plugin's output or failures
 *
 * The invocation's returned value (`Completed.value`) is never read: it is
 * plugin-produced content of unknown type and sensitivity. An exception thrown
 * by the operation is not a [PluginExecutionBoundsResult] at all, so it is
 * never bridged either, and no exception message can reach an envelope. Only
 * stable identifiers and closed values are recorded:
 *
 * - `request.pluginId`: `INTERNAL`, the same treatment as the lifecycle bridge.
 * - `result.state` (the observed [io.dataloom.api.plugin.PluginLifecycleState]
 *   on `NotActive`): a closed enum, `PUBLIC`.
 * - `result.maximumExecutionMillis` and `result.maximumConcurrentInvocations`:
 *   the numeric bounds the plugin was held to, `PUBLIC`. Numbers only, so no
 *   free text.
 *
 * Every attribute passes through [ClassifiedData] and [StrictDataLoomRedactor]
 * before reaching [OperationalEventEnvelope.attributes].
 *
 * ## No clock read, no identifier generation
 *
 * [toEnvelope] reads no clock and generates no identifier.
 * [OperationalEventEnvelope.occurredAt] is the caller-supplied `occurredAt`.
 * [OperationalEventEnvelope.id] is derived from [PluginExecutionInvocationId]
 * (see [operationalEventId]) and the correlation id reuses it unchanged. The
 * payload descriptor is content-free.
 */
public object PluginExecutionBoundsOperationalEventBridge {

    private const val SOURCE_VALUE: String = "dataloom.plugin.execution.bounds"
    private const val PAYLOAD_TYPE_VALUE: String = "dataloom.plugin.execution.bounds.event"
    private const val PAYLOAD_ENCODING_VALUE: String = "none"
    private const val MAX_OPERATIONAL_TOKEN_LENGTH: Int = 128

    private val SOURCE: OperationalEventSource = OperationalEventSource(SOURCE_VALUE)
    private val ENVELOPE_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_TYPE: OperationalPayloadType = OperationalPayloadType(PAYLOAD_TYPE_VALUE)
    private val PAYLOAD_ENCODING: OperationalPayloadEncoding = OperationalPayloadEncoding(PAYLOAD_ENCODING_VALUE)

    private val redactor: StrictDataLoomRedactor = StrictDataLoomRedactor()

    /**
     * Maps one bounded-invocation [result] for [pluginId] to an
     * [OperationalEventEnvelope].
     *
     * May throw [IllegalArgumentException] if a derived envelope field fails
     * its own validation; a real caller must swallow that rather than let it
     * affect the result it describes.
     */
    public fun toEnvelope(
        pluginId: PluginId,
        invocationId: PluginExecutionInvocationId,
        result: PluginExecutionBoundsResult<*>,
        occurredAt: DataLoomInstant,
    ): OperationalEventEnvelope {
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(pluginId, result))).attributes
        return OperationalEventEnvelope(
            id = operationalEventId(invocationId.value),
            type = OperationalEventType(eventTypeValue(result)),
            source = SOURCE,
            category = OperationalEventCategory.AUDIT,
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = occurredAt,
            correlationId = CorrelationId(invocationId.value),
            payload = OperationalPayloadDescriptor(
                type = PAYLOAD_TYPE,
                schemaVersion = PAYLOAD_SCHEMA_VERSION,
                encoding = PAYLOAD_ENCODING,
                classification = DataClassification.INTERNAL,
                encodedSizeBytes = null,
            ),
            attributes = attributes,
        )
    }

    private fun operationalEventId(rawInvocationId: String): OperationalEventId {
        val sanitized = rawInvocationId
            .map { character -> if (isAllowedOperationalTokenCharacter(character)) character else '_' }
            .joinToString(separator = "")
        val combined = "plugin.execution.$sanitized".take(MAX_OPERATIONAL_TOKEN_LENGTH)
        return OperationalEventId(combined.ifEmpty { "unknown" })
    }

    private fun isAllowedOperationalTokenCharacter(character: Char): Boolean =
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '.' ||
            character == '_' ||
            character == '-' ||
            character == ':' ||
            character == '/'

    private fun eventTypeValue(result: PluginExecutionBoundsResult<*>): String = when (result) {
        is PluginExecutionBoundsResult.Completed<*> -> "dataloom.plugin.execution.bounds.completed"
        is PluginExecutionBoundsResult.TimedOut -> "dataloom.plugin.execution.bounds.timed_out"
        is PluginExecutionBoundsResult.ConcurrencyLimitExceeded ->
            "dataloom.plugin.execution.bounds.concurrency_limit_exceeded"
        is PluginExecutionBoundsResult.NotActive -> "dataloom.plugin.execution.bounds.not_active"
    }

    private fun classifiedAttributesFor(
        pluginId: PluginId,
        result: PluginExecutionBoundsResult<*>,
    ): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        attributes["request.pluginId"] = ClassifiedDataValue(pluginId.value, DataClassification.INTERNAL)
        when (result) {
            // Completed.value is deliberately never read: plugin-produced content.
            is PluginExecutionBoundsResult.Completed<*> -> Unit
            is PluginExecutionBoundsResult.TimedOut -> {
                attributes["result.maximumExecutionMillis"] =
                    ClassifiedDataValue(result.maximumExecutionMillis.toString(), DataClassification.PUBLIC)
            }
            is PluginExecutionBoundsResult.ConcurrencyLimitExceeded -> {
                attributes["result.maximumConcurrentInvocations"] =
                    ClassifiedDataValue(result.maximumConcurrentInvocations.toString(), DataClassification.PUBLIC)
            }
            is PluginExecutionBoundsResult.NotActive -> {
                attributes["result.state"] = ClassifiedDataValue(result.state.name, DataClassification.PUBLIC)
            }
        }
        return attributes
    }
}
