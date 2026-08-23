package io.dataloom.runtime.observation.operational

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
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.security.StrictDataLoomRedactor
import io.dataloom.api.strategy.StrategyDecisionEvent
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDecisionOutcomeKind

/**
 * Stateless bridge from [StrategyDecisionEvent] -- the payload-free diagnostic
 * record [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]
 * already constructs and durably records via
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLog] -- to
 * [OperationalEventEnvelope], the canonical DL-042 envelope
 * [io.dataloom.api.operational.DurableOperationalEventOutbox] persists.
 *
 * ## Why bridging this domain is genuinely additive, not redundant
 *
 * [StrategyDecisionEvent] is already durably recorded by
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLog] (`#321`), and
 * that log's own class doc is explicit that it "is never read back by any
 * execution path" -- it exists purely for operator visibility and debugging.
 * This bridge does not change that: it never reads
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLog] back, and
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLog] gains no new
 * reader. What this bridge adds is a genuinely different capability the
 * per-[StrategyDecisionId] log cannot offer by itself: a caller must already
 * know a [StrategyDecisionId] to read that log's one mutable slot for it,
 * with no ordering or enumeration across decisions. The generic outbox this
 * bridge feeds is, by contrast, one ordered, cross-subsystem stream --
 * already shared by [SynchronizationOperationalEventBridge]'s synchronization
 * events and [RetryCircuitAdministrationOperationalEventBridge]'s retry/circuit
 * administration commands when an application configures more than one
 * bridge into the same [io.dataloom.api.operational.OperationalEventOutboxScope]
 * -- giving an operator one chronological timeline across subsystems that no
 * single per-domain durable log provides on its own. This is the same
 * "different capability, not a duplicate record" reasoning
 * [io.dataloom.runtime.facade.DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec]
 * already applies for retry/circuit administration's own separately-audited
 * state.
 *
 * ## No clock read, no identifier generation
 *
 * [toEnvelope] never reads a clock and never generates an identifier. It
 * reuses [StrategyDecisionEvent.committedAt] -- already computed by the
 * caller before this bridge is invoked -- for
 * [OperationalEventEnvelope.occurredAt], and derives
 * [OperationalEventEnvelope.id] from the already-unique [decisionId] (see
 * [operationalEventId]) rather than minting a second identifier for the same
 * logical decision.
 *
 * ## Correlation identity is reused, not invented
 *
 * [OperationalEventEnvelope.correlationId] reuses [decisionId] unchanged --
 * the natural identity a caller already has for one strategy admission
 * decision -- mirroring exactly how
 * [RetryCircuitAdministrationOperationalEventBridge] reuses each command's own
 * `commandId` rather than inventing a new correlation scheme.
 * [StrategyDecisionEvent] carries no
 * [io.dataloom.api.identifier.TraceId]/[io.dataloom.api.identifier.TenantId]/
 * [io.dataloom.api.identifier.WorkflowId] of its own, so those envelope fields
 * are left unset, the same posture
 * [RetryCircuitAdministrationOperationalEventBridge]'s own retry-administration
 * overload takes for the same reason.
 *
 * ## Classification
 *
 * Every field placed into [OperationalEventEnvelope.attributes] first enters
 * [ClassifiedData] with an explicit [DataClassification] and is redacted by
 * [StrictDataLoomRedactor] before it reaches the envelope, following
 * [SynchronizationOperationalEventBridge]'s own documented rules exactly.
 * Enum names ([StrategyDecisionEvent.requestedStrategy],
 * [StrategyDecisionEvent.effectiveStrategy], [StrategyDecisionEvent.direction],
 * [StrategyDecisionEvent.mode], [StrategyDecisionEvent.disposition],
 * [StrategyDecisionEvent.outcomeKind]) and the positive
 * [StrategyDecisionEvent.configurationVersion] counter are `PUBLIC`.
 * [StrategyDecisionEvent.reasonCodes] are `PUBLIC`:
 * [io.dataloom.api.strategy.StrategyEvaluationResult]'s own class doc already
 * documents them as "stable, non-sensitive identifiers intended for logs,
 * events, and durable decision records" that "must never contain arbitrary
 * exception messages, payload data, tenant IDs, or credentials" -- the exact
 * safety guarantee this bridge's `PUBLIC` classification for every other
 * enum/counter field already relies on. [StrategyDecisionEvent.outcomeDetail]
 * is also `PUBLIC`: [io.dataloom.api.strategy.DurableStrategyDecisionEventLog]'s
 * own class doc documents it as "a short, stable, non-sensitive code -- a
 * rejection-reason name or a [io.dataloom.api.error.DataLoomError.code] value,
 * never an exception message". [StrategyDecisionEvent.planId] and
 * [StrategyDecisionEvent.effectiveProfileId] are operational identifiers not
 * obviously safe to disclose, so they are `INTERNAL` -- the default policy
 * masks rather than removes them, still useful for correlation without
 * disclosing the raw value.
 *
 * ## Payload descriptor
 *
 * Content-free, following [SynchronizationOperationalEventBridge]'s own
 * convention: every field this bridge has to offer already goes through
 * [OperationalEventEnvelope.attributes] instead.
 */
public object StrategyDecisionOperationalEventBridge {

    private const val SOURCE_VALUE: String = "dataloom.runtime.strategy.decision"
    private const val PAYLOAD_TYPE_VALUE: String = "dataloom.strategy.decision.event"
    private const val PAYLOAD_ENCODING_VALUE: String = "none"
    private const val MAX_OPERATIONAL_TOKEN_LENGTH: Int = 128
    private const val MAX_REASON_CODES_JOINED_LENGTH: Int = 4_096
    private const val MAX_OUTCOME_DETAIL_LENGTH: Int = 4_096
    private const val REASON_CODES_SEPARATOR: String = "|"

    private val SOURCE: OperationalEventSource = OperationalEventSource(SOURCE_VALUE)
    private val ENVELOPE_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_TYPE: OperationalPayloadType = OperationalPayloadType(PAYLOAD_TYPE_VALUE)
    private val PAYLOAD_ENCODING: OperationalPayloadEncoding = OperationalPayloadEncoding(PAYLOAD_ENCODING_VALUE)

    private val redactor: StrictDataLoomRedactor = StrictDataLoomRedactor()

    /**
     * Maps one strategy admission/execution decision's [decisionId] and
     * already-constructed [event] to an [OperationalEventEnvelope].
     *
     * May throw [IllegalArgumentException] if a derived envelope field fails
     * its own validation (for example an unexpectedly long identifier). Every
     * caller in this codebase wraps this call so such a failure is swallowed
     * rather than allowed to affect the strategy execution result it is
     * describing -- see
     * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionCoordinator]'s
     * `recordOperationalEvent`.
     */
    public fun toEnvelope(
        decisionId: StrategyDecisionId,
        event: StrategyDecisionEvent,
    ): OperationalEventEnvelope {
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(event))).attributes
        return OperationalEventEnvelope(
            id = operationalEventId(decisionId),
            type = OperationalEventType(eventTypeValue(event)),
            source = SOURCE,
            category = OperationalEventCategory.DIAGNOSTIC,
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = event.committedAt,
            correlationId = CorrelationId(decisionId.value),
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

    /**
     * Derives a stable, unique [OperationalEventId] from [decisionId] rather
     * than generating a new identifier. [StrategyDecisionId.value] is only
     * guaranteed non-blank and bounded to 256 characters, not restricted to
     * [OperationalEventId]'s own bounded token charset/length, so this
     * derivation preserves uniqueness by replacing every disallowed character
     * with `_` and bounding the result to [OperationalEventId]'s own maximum
     * length, the same substitution
     * [SynchronizationOperationalEventBridge.toEnvelope] applies to
     * [io.dataloom.api.identifier.SynchronizationEventId]. Two different raw
     * values could theoretically collide after this substitution;
     * [io.dataloom.api.operational.DurableOperationalEventOutbox.append]
     * already handles that case safely by reporting
     * [io.dataloom.api.operational.DurableOperationalEventOutboxAppendOutcome.Conflict]
     * rather than overwriting the earlier entry.
     */
    private fun operationalEventId(decisionId: StrategyDecisionId): OperationalEventId {
        val sanitized = decisionId.value
            .map { character -> if (isAllowedOperationalTokenCharacter(character)) character else '_' }
            .joinToString(separator = "")
            .take(MAX_OPERATIONAL_TOKEN_LENGTH)
        return OperationalEventId(sanitized.ifEmpty { "unknown" })
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

    private fun eventTypeValue(event: StrategyDecisionEvent): String = when (event.outcomeKind) {
        StrategyDecisionOutcomeKind.EXECUTED -> "dataloom.strategy.decision.executed"
        StrategyDecisionOutcomeKind.SERVED_FROM_CACHE -> "dataloom.strategy.decision.served_from_cache"
        StrategyDecisionOutcomeKind.FAILED -> "dataloom.strategy.decision.failed"
        StrategyDecisionOutcomeKind.FALLBACK_ACTIVATED -> "dataloom.strategy.decision.fallback_activated"
        StrategyDecisionOutcomeKind.FALLBACK_UNAVAILABLE -> "dataloom.strategy.decision.fallback_unavailable"
        StrategyDecisionOutcomeKind.CANCELLED -> "dataloom.strategy.decision.cancelled"
        StrategyDecisionOutcomeKind.DEFERRED -> "dataloom.strategy.decision.deferred"
        StrategyDecisionOutcomeKind.DURABLY_ENQUEUED -> "dataloom.strategy.decision.durably_enqueued"
        StrategyDecisionOutcomeKind.ACCEPTED_LOCALLY -> "dataloom.strategy.decision.accepted_locally"
        StrategyDecisionOutcomeKind.REJECTED -> "dataloom.strategy.decision.rejected"
    }

    private fun classifiedAttributesFor(event: StrategyDecisionEvent): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        attributes["decision.planId"] = ClassifiedDataValue(event.planId.value, DataClassification.INTERNAL)
        attributes["decision.requestedStrategy"] =
            ClassifiedDataValue(event.requestedStrategy.name, DataClassification.PUBLIC)
        attributes["decision.effectiveStrategy"] =
            ClassifiedDataValue(event.effectiveStrategy.name, DataClassification.PUBLIC)
        attributes["decision.effectiveProfileId"] =
            ClassifiedDataValue(event.effectiveProfileId.value, DataClassification.INTERNAL)
        attributes["decision.configurationVersion"] =
            ClassifiedDataValue(event.configurationVersion.value.toString(), DataClassification.PUBLIC)
        attributes["decision.direction"] = ClassifiedDataValue(event.direction.name, DataClassification.PUBLIC)
        attributes["decision.mode"] = ClassifiedDataValue(event.mode.name, DataClassification.PUBLIC)
        attributes["decision.disposition"] = ClassifiedDataValue(event.disposition.name, DataClassification.PUBLIC)

        val reasonCodes = event.reasonCodesList
        attributes["decision.reasonCodeCount"] =
            ClassifiedDataValue(reasonCodes.size.toString(), DataClassification.PUBLIC)
        attributes["decision.reasonCodes"] = ClassifiedDataValue(
            reasonCodes.joinToString(separator = REASON_CODES_SEPARATOR).take(MAX_REASON_CODES_JOINED_LENGTH),
            DataClassification.PUBLIC,
        )

        attributes["decision.outcomeKind"] = ClassifiedDataValue(event.outcomeKind.name, DataClassification.PUBLIC)
        event.outcomeDetail?.let { detail ->
            attributes["decision.outcomeDetail"] =
                ClassifiedDataValue(detail.take(MAX_OUTCOME_DETAIL_LENGTH), DataClassification.PUBLIC)
        }
        return attributes
    }
}
