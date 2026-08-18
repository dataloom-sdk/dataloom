package io.dataloom.runtime.observation.operational

import io.dataloom.api.error.DataLoomError
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
import io.dataloom.api.synchronization.SynchronizationEvent
import io.dataloom.api.synchronization.SynchronizationResult

/**
 * Stateless bridge from [SynchronizationEvent] -- the domain type the DataLoom
 * runtime already dispatches through [io.dataloom.runtime.observation.SynchronizationEventDispatcher]
 * -- to [OperationalEventEnvelope], the canonical DL-042 envelope
 * [io.dataloom.api.operational.DurableOperationalEventOutbox] persists.
 *
 * ## Why this exists
 *
 * `SynchronizationEventDispatcher` delivers [SynchronizationEvent] to
 * registered [io.dataloom.api.observation.SynchronizationObserver] instances,
 * but nothing constructed an [OperationalEventEnvelope] from one anywhere in
 * this codebase before this bridge existed -- see
 * [the operational envelope/redaction doc](https://github.com/dataloom-sdk/dataloom/blob/main/docs/api/operational-envelope-redaction.md)'s
 * former "No wired caller" note. [io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter]
 * calls [toEnvelope] for every event it constructs and dispatches when its
 * optional operational-event-outbox collaborators are configured (see that
 * class and [io.dataloom.runtime.facade.DataLoomOperationalEventOutboxSpec]);
 * this object owns only the mapping itself, not the durable append or the
 * opt-in wiring.
 *
 * ## No clock read, no identifier generation
 *
 * [toEnvelope] never reads a clock and never generates an identifier. It
 * reuses [SynchronizationEvent.occurredAt] -- already read exactly once by
 * the runtime component that constructed [event] -- for
 * [OperationalEventEnvelope.occurredAt], and derives
 * [OperationalEventEnvelope.id] from the already-unique
 * [SynchronizationEvent.id] (see [operationalEventId]) rather than minting a
 * second identifier for the same logical event.
 *
 * ## Correlation, trace, tenant, and workflow identity are reused, not invented
 *
 * [SynchronizationEvent.request] already carries
 * [io.dataloom.api.model.SynchronizationRequest.context], whose
 * `correlationId`/`traceId`/`tenantId` are the exact
 * [io.dataloom.api.identifier.CorrelationId]/[io.dataloom.api.identifier.TraceId]/[io.dataloom.api.identifier.TenantId]
 * types [OperationalEventEnvelope] itself declares, and
 * [io.dataloom.api.model.SynchronizationRequest.workflowId] is the exact
 * [io.dataloom.api.identifier.WorkflowId] type [OperationalEventEnvelope.workflowId]
 * declares. No new correlation scheme is introduced.
 *
 * ## Classification
 *
 * Every field placed into [OperationalEventEnvelope.attributes] first enters
 * [ClassifiedData] with an explicit [DataClassification] and is redacted by
 * [StrictDataLoomRedactor] before it reaches the envelope, exactly as
 * [the redaction doc](https://github.com/dataloom-sdk/dataloom/blob/main/docs/api/operational-envelope-redaction.md#central-redaction-boundary)
 * requires. Enum names and non-negative counters are `PUBLIC`. Operational
 * identifiers that are not obviously safe to disclose (session, entity type,
 * entity ID) are `INTERNAL`, so `StrictDataLoomRedactor`'s default policy
 * masks rather than removes them -- still useful for correlation without
 * disclosing the raw value. [DataLoomError.message] is unstructured free text
 * that this codebase's own convention (never credentials/tokens/PII) does not
 * enforce at the type level, so it is classified `CONFIDENTIAL` -- removed
 * outright by the default policy, never merely masked. [DataLoomError.cause]
 * and every caller-supplied [io.dataloom.api.context.DataLoomMetadata] map
 * (on the request, on [io.dataloom.api.synchronization.SynchronizationProgress],
 * on [io.dataloom.api.conflict.SynchronizationConflict], and on
 * [SynchronizationResult]) are never included at all: a [Throwable]'s message
 * is as unbounded and unvetted as [DataLoomError.message], and caller-supplied
 * metadata keys are, by definition, not known ahead of time and cannot be
 * given a trustworthy per-field classification here.
 *
 * ## Payload descriptor
 *
 * [OperationalEventEnvelope.payload] is a required, non-sensitive descriptor
 * for a *separately encoded* payload -- the envelope itself never carries
 * payload bytes. This bridge never encodes a separate payload; every field it
 * has to offer already goes through [attributes] instead. The descriptor this
 * bridge supplies is therefore intentionally content-free (`encodedSizeBytes`
 * is always `null`), following the same illustrative convention
 * [the redaction doc's own worked example](https://github.com/dataloom-sdk/dataloom/blob/main/docs/api/operational-envelope-redaction.md#central-redaction-boundary)
 * already uses for a signal with no real payload bytes.
 */
public object SynchronizationOperationalEventBridge {

    private const val SOURCE_VALUE: String = "dataloom.runtime.synchronization"
    private const val PAYLOAD_TYPE_VALUE: String = "dataloom.synchronization.event"
    private const val PAYLOAD_ENCODING_VALUE: String = "none"
    private const val MAX_OPERATIONAL_TOKEN_LENGTH: Int = 128
    private const val MAX_ERROR_MESSAGE_LENGTH: Int = 4_096

    private val SOURCE: OperationalEventSource = OperationalEventSource(SOURCE_VALUE)
    private val ENVELOPE_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_TYPE: OperationalPayloadType = OperationalPayloadType(PAYLOAD_TYPE_VALUE)
    private val PAYLOAD_ENCODING: OperationalPayloadEncoding = OperationalPayloadEncoding(PAYLOAD_ENCODING_VALUE)

    private val redactor: StrictDataLoomRedactor = StrictDataLoomRedactor()

    /**
     * Maps [event] to an [OperationalEventEnvelope].
     *
     * May throw [IllegalArgumentException] if a derived envelope field fails
     * its own validation (for example an unexpectedly long identifier). Every
     * caller in this codebase wraps this call so such a failure is swallowed
     * rather than allowed to affect the synchronization it is describing --
     * see [io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter]'s
     * `recordOperationalEvent`.
     */
    public fun toEnvelope(event: SynchronizationEvent): OperationalEventEnvelope {
        val request = event.request
        val context = request.context
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(event))).attributes

        return OperationalEventEnvelope(
            id = operationalEventId(event),
            type = OperationalEventType(eventTypeValue(event)),
            source = SOURCE,
            category = eventCategory(event),
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = event.occurredAt,
            correlationId = context.correlationId,
            traceId = context.traceId,
            tenantId = context.tenantId,
            workflowId = request.workflowId,
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
     * Derives a stable, unique [OperationalEventId] from
     * [SynchronizationEvent.id] rather than generating a new identifier.
     *
     * [io.dataloom.api.identifier.SynchronizationEventId] is guaranteed fresh
     * per emitted event (one [io.dataloom.api.identifier.IdentifierGenerator]
     * call per event -- see
     * [io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter]),
     * but its value is only guaranteed non-blank, not restricted to
     * [OperationalEventId]'s bounded token charset. This derivation preserves
     * the identifier's uniqueness by replacing every character outside that
     * charset with `_` and bounding the result to
     * [OperationalEventId]'s own maximum length, rather than inventing an
     * unrelated identifier. Two different raw values could theoretically
     * collide after this substitution; [io.dataloom.api.operational.DurableOperationalEventOutbox.append]
     * already handles that case safely by reporting
     * [io.dataloom.api.operational.DurableOperationalEventOutboxAppendOutcome.Conflict]
     * rather than overwriting the earlier entry.
     */
    private fun operationalEventId(event: SynchronizationEvent): OperationalEventId {
        val sanitized = event.id.value
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

    private fun eventTypeValue(event: SynchronizationEvent): String = when (event) {
        is SynchronizationEvent.Started -> "dataloom.synchronization.started"
        is SynchronizationEvent.PhaseChanged -> "dataloom.synchronization.phase_changed"
        is SynchronizationEvent.ProgressUpdated -> "dataloom.synchronization.progress_updated"
        is SynchronizationEvent.RetryScheduled -> "dataloom.synchronization.retry_scheduled"
        is SynchronizationEvent.ConflictDetected -> "dataloom.synchronization.conflict_detected"
        is SynchronizationEvent.Completed -> "dataloom.synchronization.completed"
    }

    /**
     * `Started`/`PhaseChanged`/`Completed` describe workflow lifecycle
     * transitions -- `LIFECYCLE`. `ProgressUpdated` is a periodic execution
     * measurement -- `TELEMETRY`. `RetryScheduled` and `ConflictDetected`
     * both report an operational condition worth an operator's attention --
     * `DIAGNOSTIC`.
     */
    private fun eventCategory(event: SynchronizationEvent): OperationalEventCategory = when (event) {
        is SynchronizationEvent.Started,
        is SynchronizationEvent.PhaseChanged,
        is SynchronizationEvent.Completed,
        -> OperationalEventCategory.LIFECYCLE
        is SynchronizationEvent.ProgressUpdated -> OperationalEventCategory.TELEMETRY
        is SynchronizationEvent.RetryScheduled,
        is SynchronizationEvent.ConflictDetected,
        -> OperationalEventCategory.DIAGNOSTIC
    }

    private fun classifiedAttributesFor(event: SynchronizationEvent): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        val request = event.request
        attributes["request.direction"] = ClassifiedDataValue(request.direction.name, DataClassification.PUBLIC)
        attributes["request.mode"] = ClassifiedDataValue(request.mode.name, DataClassification.PUBLIC)
        attributes["request.priority"] = ClassifiedDataValue(request.priority.name, DataClassification.PUBLIC)
        attributes["request.sessionId"] =
            ClassifiedDataValue(request.sessionId.value, DataClassification.INTERNAL)

        when (event) {
            is SynchronizationEvent.Started -> Unit

            is SynchronizationEvent.PhaseChanged -> {
                attributes["phase"] = ClassifiedDataValue(event.phase.name, DataClassification.PUBLIC)
            }

            is SynchronizationEvent.ProgressUpdated -> {
                val progress = event.progress
                attributes["progress.phase"] = ClassifiedDataValue(progress.phase.name, DataClassification.PUBLIC)
                attributes["progress.completed"] =
                    ClassifiedDataValue(progress.completed.toString(), DataClassification.PUBLIC)
                progress.total?.let { total ->
                    attributes["progress.total"] = ClassifiedDataValue(total.toString(), DataClassification.PUBLIC)
                }
                attributes["progress.unit"] = ClassifiedDataValue(progress.unit.name, DataClassification.PUBLIC)
            }

            is SynchronizationEvent.RetryScheduled -> {
                attributes["retry.attemptNumber"] =
                    ClassifiedDataValue(event.attempt.number.toString(), DataClassification.PUBLIC)
                attributes["retry.delayMilliseconds"] =
                    ClassifiedDataValue(event.delay.milliseconds.toString(), DataClassification.PUBLIC)
                putErrorAttributes(attributes, "retry.error", event.error)
            }

            is SynchronizationEvent.ConflictDetected -> {
                val conflict = event.conflict
                attributes["conflict.type"] = ClassifiedDataValue(conflict.type.name, DataClassification.PUBLIC)
                attributes["conflict.entityType"] =
                    ClassifiedDataValue(conflict.entity.type.value, DataClassification.INTERNAL)
                attributes["conflict.entityId"] =
                    ClassifiedDataValue(conflict.entity.id.value, DataClassification.INTERNAL)
                attributes["conflict.localOperation"] =
                    ClassifiedDataValue(conflict.localChange.operation.name, DataClassification.PUBLIC)
                attributes["conflict.remoteOperation"] =
                    ClassifiedDataValue(conflict.remoteChange.operation.name, DataClassification.PUBLIC)
            }

            is SynchronizationEvent.Completed -> {
                val result = event.result
                attributes["result.kind"] = ClassifiedDataValue(resultKind(result), DataClassification.PUBLIC)
                val summary = result.summary
                attributes["summary.outboundEventsRead"] =
                    ClassifiedDataValue(summary.outboundEventsRead.toString(), DataClassification.PUBLIC)
                attributes["summary.outboundEventsAccepted"] =
                    ClassifiedDataValue(summary.outboundEventsAccepted.toString(), DataClassification.PUBLIC)
                attributes["summary.outboundEventsMarkedForRetry"] =
                    ClassifiedDataValue(summary.outboundEventsMarkedForRetry.toString(), DataClassification.PUBLIC)
                attributes["summary.outboundEventsRejected"] =
                    ClassifiedDataValue(summary.outboundEventsRejected.toString(), DataClassification.PUBLIC)
                attributes["summary.inboundEventsReceived"] =
                    ClassifiedDataValue(summary.inboundEventsReceived.toString(), DataClassification.PUBLIC)
                attributes["summary.inboundEventsApplied"] =
                    ClassifiedDataValue(summary.inboundEventsApplied.toString(), DataClassification.PUBLIC)
                attributes["summary.conflictsDetected"] =
                    ClassifiedDataValue(summary.conflictsDetected.toString(), DataClassification.PUBLIC)
                attributes["summary.retryAttempts"] =
                    ClassifiedDataValue(summary.retryAttempts.toString(), DataClassification.PUBLIC)

                when (result) {
                    is SynchronizationResult.Failed -> putErrorAttributes(attributes, "result.error", result.error)
                    is SynchronizationResult.PartiallySucceeded ->
                        attributes["result.errorCount"] =
                            ClassifiedDataValue(result.errors.size.toString(), DataClassification.PUBLIC)
                    is SynchronizationResult.Skipped ->
                        attributes["result.skipReason"] =
                            ClassifiedDataValue(result.reason.name, DataClassification.PUBLIC)
                    is SynchronizationResult.Succeeded, is SynchronizationResult.Cancelled -> Unit
                }
            }
        }
        return attributes
    }

    /**
     * `code`/`category`/`severity`/`recoverability` are closed, stable
     * vocabularies -- `PUBLIC`. `message` is unstructured free text this
     * codebase's own convention (never credentials/tokens/PII) does not
     * enforce at the type level, so it is `CONFIDENTIAL` -- removed outright
     * by the default redaction policy. `cause` (a [Throwable]) is never
     * included: its message is exactly as unbounded and unvetted as
     * [DataLoomError.message], with no classification boundary of its own.
     */
    private fun putErrorAttributes(
        attributes: MutableMap<String, ClassifiedDataValue>,
        prefix: String,
        error: DataLoomError,
    ) {
        attributes["$prefix.code"] = ClassifiedDataValue(error.code.value, DataClassification.PUBLIC)
        attributes["$prefix.category"] = ClassifiedDataValue(error.category.name, DataClassification.PUBLIC)
        attributes["$prefix.severity"] = ClassifiedDataValue(error.severity.name, DataClassification.PUBLIC)
        attributes["$prefix.recoverability"] =
            ClassifiedDataValue(error.recoverability.name, DataClassification.PUBLIC)
        attributes["$prefix.message"] = ClassifiedDataValue(
            error.message.take(MAX_ERROR_MESSAGE_LENGTH),
            DataClassification.CONFIDENTIAL,
        )
    }

    private fun resultKind(result: SynchronizationResult): String = when (result) {
        is SynchronizationResult.Succeeded -> "SUCCEEDED"
        is SynchronizationResult.PartiallySucceeded -> "PARTIALLY_SUCCEEDED"
        is SynchronizationResult.Failed -> "FAILED"
        is SynchronizationResult.Cancelled -> "CANCELLED"
        is SynchronizationResult.Skipped -> "SKIPPED"
    }
}
