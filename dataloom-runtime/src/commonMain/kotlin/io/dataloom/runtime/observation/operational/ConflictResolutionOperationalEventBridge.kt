package io.dataloom.runtime.observation.operational

import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.identifier.ConflictId
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

/**
 * Stateless bridge from [UnresolvedConflictRecord]/[ResolvedConflictDecisionRecord]
 * -- the exact payload-free facts
 * [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator] already
 * constructs and durably records via
 * [io.dataloom.api.conflict.DurableUnresolvedConflictLog]/
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog] -- to
 * [OperationalEventEnvelope], the canonical DL-042 envelope
 * [io.dataloom.api.operational.DurableOperationalEventOutbox] persists.
 *
 * ## Why this bridges from the already-durable records, not `ConflictOrchestrationResult`
 *
 * [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator] already
 * derives [UnresolvedConflictRecord]/[ResolvedConflictDecisionRecord] from a
 * [io.dataloom.runtime.conflict.ConflictOrchestrationResult] before durably
 * recording either one (see that coordinator's `recordUnresolved`/`recordResolved`).
 * Both records are already exactly "structural identifiers only, never payload
 * content" by their own class docs. Mapping from the orchestration result a
 * second time would re-derive the same facts a second way; this bridge instead
 * reuses the identical, already-constructed record each durable log call
 * receives -- the same "reuse what a real caller already built rather than
 * re-deriving it" reasoning [StrategyDecisionOperationalEventBridge]'s own
 * class doc documents for reusing [io.dataloom.api.strategy.StrategyDecisionEvent]
 * rather than reconstructing one from
 * [io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult].
 *
 * ## Why bridging this domain is genuinely additive, not redundant
 *
 * [UnresolvedConflictRecord]/[ResolvedConflictDecisionRecord] are each a
 * single commit-once slot per [ConflictId] with no ordering or enumeration
 * across conflicts -- a caller must already know a [ConflictId] to read
 * either log. The generic outbox this bridge feeds is, by contrast, one
 * ordered, cross-subsystem stream -- already shared by
 * [SynchronizationOperationalEventBridge]'s synchronization events,
 * [RetryCircuitAdministrationOperationalEventBridge]'s retry/circuit
 * administration commands, [StrategyDecisionOperationalEventBridge]'s
 * strategy-decision diagnostics, and [QueueLifecycleOperationalEventBridge]'s
 * queue-entry transitions when an application configures more than one bridge
 * into the same [io.dataloom.api.operational.OperationalEventOutboxScope] --
 * giving an operator one chronological timeline across subsystems that no
 * single per-domain durable log provides on its own. Neither durable
 * conflict log gains a new reader; this bridge never reads either back.
 *
 * ## No clock read, no identifier generation
 *
 * [toEnvelope] never reads a clock and never generates an identifier. It
 * reuses [UnresolvedConflictRecord.committedAt]/[ResolvedConflictDecisionRecord.committedAt]
 * -- already computed by [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator]
 * before this bridge is invoked -- for [OperationalEventEnvelope.occurredAt],
 * and derives [OperationalEventEnvelope.id] from the already-unique
 * [conflictId] (see [operationalEventId]) rather than minting a second
 * identifier for the same logical conflict.
 *
 * ## Correlation identity is reused, not invented
 *
 * [OperationalEventEnvelope.correlationId] reuses [conflictId] unchanged --
 * the natural identity a caller already has for one detected conflict --
 * mirroring exactly how [StrategyDecisionOperationalEventBridge] reuses each
 * decision's own `StrategyDecisionId` rather than inventing a new correlation
 * scheme. Neither durable record carries a
 * [io.dataloom.api.identifier.TraceId]/[io.dataloom.api.identifier.TenantId]/
 * [io.dataloom.api.identifier.WorkflowId] of its own, so those envelope
 * fields are left unset, the same posture
 * [StrategyDecisionOperationalEventBridge] takes for the same reason.
 *
 * ## Unresolved and resolved outcomes never share an [OperationalEventId]
 *
 * [operationalEventId] prefixes the sanitized [ConflictId] with
 * `unresolved.`/`resolved.` so a bridged unresolved outcome and a later
 * bridged resolved outcome for the same [ConflictId] (for example: no
 * resolver configured initially, then a resolver is registered and the same
 * conflict is retried) can never collide inside a shared
 * [io.dataloom.api.operational.OperationalEventOutboxScope] -- the same
 * two-domain-prefix reasoning [RetryCircuitAdministrationOperationalEventBridge]
 * already documents for its own `retry.`/`circuit.` prefixes.
 * [OperationalEventEnvelope.correlationId] is left as the exact unprefixed
 * [ConflictId], since [CorrelationId] is a cross-system correlation field,
 * not a within-outbox uniqueness key.
 *
 * ## Classification
 *
 * Every field placed into [OperationalEventEnvelope.attributes] first enters
 * [ClassifiedData] with an explicit [DataClassification] and is redacted by
 * [StrictDataLoomRedactor], following [SynchronizationOperationalEventBridge]'s
 * own documented rules exactly. Enum names ([UnresolvedConflictRecord.conflictType]/
 * [ResolvedConflictDecisionRecord.conflictType], [UnresolvedConflictChangeSummary.operation],
 * [UnresolvedConflictReason], [ResolvedConflictDecisionKind]) are `PUBLIC`.
 * [ResolvedConflictDecisionRecord.failureErrorCode] is `PUBLIC`: that field's
 * own KDoc already documents it as "the bounded, non-sensitive
 * [io.dataloom.api.error.DataLoomError.code] value ... never the error
 * message". Operational identifiers not obviously safe to disclose (entity
 * type, entity ID, change-event IDs, [ResolvedConflictDecisionRecord.resolverId])
 * are `INTERNAL`, so the default policy masks rather than removes them --
 * still useful for correlation without disclosing the raw value. This is a
 * deliberately more conservative reading for `resolverId` than
 * [io.dataloom.runtime.conflict.ConflictOrchestrationResult]'s own safe
 * `toString()` (which already includes it unredacted as a "structural
 * identifier"), the same more-conservative choice
 * [RetryCircuitAdministrationOperationalEventBridge] already makes for
 * `rejectionReasonCode` rather than assuming a looser rule than the
 * precedent every other identifier in this bridge follows.
 * [UnresolvedConflictRecord.conflictMetadata]/[ResolvedConflictDecisionRecord.conflictMetadata]/
 * [ResolvedConflictDecisionRecord.decisionMetadata] are never included at
 * all: caller-supplied [io.dataloom.api.context.DataLoomMetadata] keys are,
 * by definition, not known ahead of time and cannot be given a trustworthy
 * per-field classification here, the same treatment
 * [SynchronizationOperationalEventBridge] already gives every caller-supplied
 * metadata map it encounters.
 *
 * ## Payload descriptor
 *
 * Content-free, following [SynchronizationOperationalEventBridge]'s own
 * convention: every field this bridge has to offer already goes through
 * [OperationalEventEnvelope.attributes] instead.
 */
public object ConflictResolutionOperationalEventBridge {

    private const val SOURCE_VALUE: String = "dataloom.runtime.conflict.resolution"
    private const val PAYLOAD_TYPE_VALUE: String = "dataloom.conflict.resolution.event"
    private const val PAYLOAD_ENCODING_VALUE: String = "none"
    private const val MAX_OPERATIONAL_TOKEN_LENGTH: Int = 128
    private const val UNRESOLVED_ID_PREFIX: String = "unresolved."
    private const val RESOLVED_ID_PREFIX: String = "resolved."

    private val SOURCE: OperationalEventSource = OperationalEventSource(SOURCE_VALUE)
    private val ENVELOPE_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_TYPE: OperationalPayloadType = OperationalPayloadType(PAYLOAD_TYPE_VALUE)
    private val PAYLOAD_ENCODING: OperationalPayloadEncoding = OperationalPayloadEncoding(PAYLOAD_ENCODING_VALUE)

    private val redactor: StrictDataLoomRedactor = StrictDataLoomRedactor()

    /**
     * Maps one already-durably-recorded [UnresolvedConflictRecord] for
     * [conflictId] to an [OperationalEventEnvelope].
     *
     * May throw [IllegalArgumentException] if a derived envelope field fails
     * its own validation (for example an unexpectedly long identifier). Every
     * caller in this codebase wraps this call so such a failure is swallowed
     * rather than allowed to affect the conflict-detection result it is
     * describing -- see
     * [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator]'s
     * `recordOperationalEvent`.
     */
    public fun toEnvelope(
        conflictId: ConflictId,
        record: UnresolvedConflictRecord,
    ): OperationalEventEnvelope {
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(record))).attributes
        return OperationalEventEnvelope(
            id = operationalEventId(UNRESOLVED_ID_PREFIX, conflictId),
            type = OperationalEventType(eventTypeValue(record.reason)),
            source = SOURCE,
            category = OperationalEventCategory.DIAGNOSTIC,
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = record.committedAt,
            correlationId = CorrelationId(conflictId.value),
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
     * Maps one already-durably-recorded [ResolvedConflictDecisionRecord] for
     * [conflictId] to an [OperationalEventEnvelope].
     *
     * May throw [IllegalArgumentException] if a derived envelope field fails
     * its own validation. Every caller in this codebase wraps this call so
     * such a failure is swallowed rather than allowed to affect the
     * conflict-detection result it is describing -- see
     * [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator]'s
     * `recordOperationalEvent`.
     */
    public fun toEnvelope(
        conflictId: ConflictId,
        record: ResolvedConflictDecisionRecord,
    ): OperationalEventEnvelope {
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(record))).attributes
        return OperationalEventEnvelope(
            id = operationalEventId(RESOLVED_ID_PREFIX, conflictId),
            type = OperationalEventType(eventTypeValue(record.decisionKind)),
            source = SOURCE,
            category = OperationalEventCategory.DIAGNOSTIC,
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = record.committedAt,
            correlationId = CorrelationId(conflictId.value),
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
     * Derives a stable, unique, domain-prefixed [OperationalEventId] from
     * [conflictId] rather than generating a new identifier. See this object's
     * class doc's "Unresolved and resolved outcomes never share an
     * [OperationalEventId]". [ConflictId.value] is only guaranteed
     * non-blank, not restricted to [OperationalEventId]'s own bounded token
     * charset/length, so this derivation preserves uniqueness by replacing
     * every disallowed character with `_` and bounding the combined result to
     * [OperationalEventId]'s own maximum length, the same substitution
     * [SynchronizationOperationalEventBridge.toEnvelope] applies. Two
     * different raw values could theoretically collide after this
     * substitution and truncation;
     * [io.dataloom.api.operational.DurableOperationalEventOutbox.append]
     * already handles that case safely by reporting
     * [io.dataloom.api.operational.DurableOperationalEventOutboxAppendOutcome.Conflict]
     * rather than overwriting the earlier entry.
     */
    private fun operationalEventId(prefix: String, conflictId: ConflictId): OperationalEventId {
        val sanitized = conflictId.value
            .map { character -> if (isAllowedOperationalTokenCharacter(character)) character else '_' }
            .joinToString(separator = "")
        val combined = "$prefix$sanitized".take(MAX_OPERATIONAL_TOKEN_LENGTH)
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

    private fun eventTypeValue(reason: UnresolvedConflictReason): String = when (reason) {
        UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED -> "dataloom.conflict.resolution.resolver_not_configured"
        UnresolvedConflictReason.RESOLVER_NOT_FOUND -> "dataloom.conflict.resolution.resolver_not_found"
    }

    private fun eventTypeValue(decisionKind: ResolvedConflictDecisionKind): String = when (decisionKind) {
        ResolvedConflictDecisionKind.USE_LOCAL -> "dataloom.conflict.resolution.resolved.use_local"
        ResolvedConflictDecisionKind.USE_REMOTE -> "dataloom.conflict.resolution.resolved.use_remote"
        ResolvedConflictDecisionKind.MERGE -> "dataloom.conflict.resolution.resolved.merge"
        ResolvedConflictDecisionKind.DEFER -> "dataloom.conflict.resolution.resolved.defer"
        ResolvedConflictDecisionKind.FAIL -> "dataloom.conflict.resolution.resolved.fail"
    }

    private fun classifiedAttributesFor(record: UnresolvedConflictRecord): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        attributes["conflict.type"] = ClassifiedDataValue(record.conflictType.name, DataClassification.PUBLIC)
        putEntityAttributes(attributes, record.entity)
        putChangeSummaryAttributes(attributes, "conflict.localChange", record.localChange)
        putChangeSummaryAttributes(attributes, "conflict.remoteChange", record.remoteChange)
        attributes["conflict.reason"] = ClassifiedDataValue(record.reason.name, DataClassification.PUBLIC)
        return attributes
    }

    private fun classifiedAttributesFor(record: ResolvedConflictDecisionRecord): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        attributes["conflict.type"] = ClassifiedDataValue(record.conflictType.name, DataClassification.PUBLIC)
        putEntityAttributes(attributes, record.entity)
        putChangeSummaryAttributes(attributes, "conflict.localChange", record.localChange)
        putChangeSummaryAttributes(attributes, "conflict.remoteChange", record.remoteChange)
        attributes["decision.resolverId"] =
            ClassifiedDataValue(record.resolverId.value, DataClassification.INTERNAL)
        attributes["decision.kind"] = ClassifiedDataValue(record.decisionKind.name, DataClassification.PUBLIC)
        record.mergedChange?.let { mergedChange ->
            putChangeSummaryAttributes(attributes, "decision.mergedChange", mergedChange)
        }
        record.failureErrorCode?.let { failureErrorCode ->
            attributes["decision.failureErrorCode"] = ClassifiedDataValue(failureErrorCode, DataClassification.PUBLIC)
        }
        return attributes
    }

    private fun putEntityAttributes(
        attributes: MutableMap<String, ClassifiedDataValue>,
        entity: io.dataloom.api.change.EntityReference,
    ) {
        attributes["conflict.entityType"] = ClassifiedDataValue(entity.type.value, DataClassification.INTERNAL)
        attributes["conflict.entityId"] = ClassifiedDataValue(entity.id.value, DataClassification.INTERNAL)
    }

    private fun putChangeSummaryAttributes(
        attributes: MutableMap<String, ClassifiedDataValue>,
        prefix: String,
        summary: UnresolvedConflictChangeSummary,
    ) {
        attributes["$prefix.changeEventId"] =
            ClassifiedDataValue(summary.changeEventId.value, DataClassification.INTERNAL)
        attributes["$prefix.operation"] = ClassifiedDataValue(summary.operation.name, DataClassification.PUBLIC)
    }
}
