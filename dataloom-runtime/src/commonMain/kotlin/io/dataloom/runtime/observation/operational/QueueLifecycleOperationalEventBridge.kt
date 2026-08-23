package io.dataloom.runtime.observation.operational

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalEventId
import io.dataloom.api.operational.OperationalEventSource
import io.dataloom.api.operational.OperationalEventType
import io.dataloom.api.operational.OperationalPayloadDescriptor
import io.dataloom.api.operational.OperationalPayloadEncoding
import io.dataloom.api.operational.OperationalPayloadType
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.security.StrictDataLoomRedactor
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.queue.QueueEntryExecutionOutcome

/**
 * Stateless bridge from [QueueEntryExecutionOutcome] -- the outcome
 * [io.dataloom.runtime.queue.DurableQueueExecutionProcessor] already computes
 * for every acquired [QueueEntry] and already uses to select which durable
 * [io.dataloom.api.queue.QueueProvider] transition to persist -- to
 * [OperationalEventEnvelope], the canonical DL-042 envelope
 * [io.dataloom.api.operational.DurableOperationalEventOutbox] persists.
 *
 * ## Why bridging from [QueueEntryExecutionOutcome], not a new domain type
 *
 * Nothing in this codebase already exposes a per-queue-entry "lifecycle
 * event" or "state transition" domain type outside
 * [io.dataloom.runtime.queue.DurableQueueExecutionProcessor]'s own private
 * loop -- [io.dataloom.runtime.queue.QueueProcessingResult] only ever returns
 * aggregate counters for the whole processing cycle (see
 * [io.dataloom.runtime.queue.QueueProcessingResult.Processed.summary]), never
 * a per-entry record. [QueueEntryExecutionOutcome] itself, however, already is
 * exactly "one real queue-entry outcome": it is already computed once per
 * entry, already payload-free by its own class doc ("Outcome fields must
 * remain payload-free and redaction-safe"), and already the sole input the
 * processor uses to decide which [io.dataloom.api.queue.QueueProvider]
 * transition to persist. Rather than inventing a second, parallel type to
 * describe the same fact, this bridge maps [QueueEntryExecutionOutcome]
 * directly -- reached through the new, optional
 * [io.dataloom.runtime.queue.QueueEntryTransitionObserver] hook that reports
 * it only once persistence has already succeeded (see that type's own class
 * doc for why no speculative or unpersisted outcome is ever bridged).
 *
 * ## No clock read, no identifier generation for [QueueEntry.id]
 *
 * [toEnvelope] never reads a clock and never generates an identifier for the
 * entry itself. It derives [OperationalEventEnvelope.id] from the already-
 * unique pair `(entry.id, leaseId)` (see [operationalEventId]) rather than
 * minting a new identifier. A single [io.dataloom.api.identifier.QueueEntryId]
 * legitimately passes through this bridge more than once over its lifetime
 * (leased, rescheduled, leased again, completed, for example); `leaseId` is
 * guaranteed fresh per acquisition by
 * [io.dataloom.api.identifier.QueueLeaseId]'s own class doc, so the pair
 * uniquely identifies this one witnessed transition, never the entry as a
 * whole.
 *
 * ## [witnessedAt] is a fallback, not the default
 *
 * [QueueEntryExecutionOutcome.Completed] already carries its own
 * [QueueEntryExecutionOutcome.Completed.completedAt], computed by the
 * execution handler before this bridge ever runs, so [toEnvelope] reuses it
 * for [OperationalEventEnvelope.occurredAt] unchanged -- no second read. The
 * other four variants carry no comparable "when this was decided" timestamp:
 * [QueueEntryExecutionOutcome.Reschedule.availableAt] and
 * [QueueEntryExecutionOutcome.Deferred.availableAt] are future availability
 * instants, not occurrence instants, and
 * [QueueEntryExecutionOutcome.Failed]/[QueueEntryExecutionOutcome.Cancelled]
 * carry no timestamp at all. For those four variants only, [toEnvelope] uses
 * the caller-supplied [witnessedAt] -- the instant the transition was
 * witnessed as durably persisted, read once by the caller (see
 * [io.dataloom.runtime.queue.QueueEntryTransitionObserver]'s own class doc),
 * mirroring exactly how
 * [io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter]
 * reads its own clock once per constructed event and this bridge's sibling
 * bridges reuse that single read rather than reading a second time.
 *
 * ## Correlation, trace, and tenant identity are reused, not invented
 *
 * [QueueEntry.synchronizationRequest] already carries
 * [io.dataloom.api.model.SynchronizationRequest.context], whose
 * `correlationId`/`traceId`/`tenantId` are the exact
 * [io.dataloom.api.identifier.CorrelationId]/[io.dataloom.api.identifier.TraceId]/[io.dataloom.api.identifier.TenantId]
 * types [OperationalEventEnvelope] itself declares, and
 * [io.dataloom.api.model.SynchronizationRequest.workflowId] is the exact
 * [io.dataloom.api.identifier.WorkflowId] type [OperationalEventEnvelope.workflowId]
 * declares -- the identical reuse [SynchronizationOperationalEventBridge]
 * already applies to the same [io.dataloom.api.model.SynchronizationRequest]
 * shape. Used uniformly for every outcome variant, including
 * [QueueEntryExecutionOutcome.Cancelled]: its own
 * [io.dataloom.api.context.ExecutionContext] is never separately read into
 * the envelope's correlation fields or into attributes, keeping one
 * consistent correlation identity per bridged entry regardless of outcome.
 *
 * ## Classification
 *
 * Every field placed into [OperationalEventEnvelope.attributes] first enters
 * [ClassifiedData] with an explicit [DataClassification] and is redacted by
 * [StrictDataLoomRedactor], following [SynchronizationOperationalEventBridge]'s
 * own documented rules exactly. Enum names and non-negative counters/epoch
 * timestamps are `PUBLIC`. [io.dataloom.api.model.SynchronizationRequest.sessionId]
 * and [leaseId] are operational identifiers not obviously safe to disclose, so
 * they are `INTERNAL` -- the default policy masks rather than removes them.
 * [DataLoomError.message] (present on
 * [QueueEntryExecutionOutcome.Reschedule.error]/[QueueEntryExecutionOutcome.Failed.error])
 * is `CONFIDENTIAL` -- removed outright -- exactly as
 * [SynchronizationOperationalEventBridge] classifies it. [DataLoomError.cause]
 * is never included, for the same reason as that precedent. The caller-
 * supplied [io.dataloom.api.context.DataLoomMetadata] on
 * [io.dataloom.api.model.SynchronizationRequest.context] is never included,
 * the same posture the precedent already takes for every caller-supplied
 * metadata map it encounters.
 *
 * ## Payload descriptor
 *
 * Content-free, following [SynchronizationOperationalEventBridge]'s own
 * convention: every field this bridge has to offer already goes through
 * [OperationalEventEnvelope.attributes] instead.
 */
public object QueueLifecycleOperationalEventBridge {

    private const val SOURCE_VALUE: String = "dataloom.runtime.queue.lifecycle"
    private const val PAYLOAD_TYPE_VALUE: String = "dataloom.queue.entry.transition"
    private const val PAYLOAD_ENCODING_VALUE: String = "none"
    private const val MAX_OPERATIONAL_TOKEN_LENGTH: Int = 128
    private const val MAX_ERROR_MESSAGE_LENGTH: Int = 4_096
    private const val ID_SEPARATOR: String = "."

    private val SOURCE: OperationalEventSource = OperationalEventSource(SOURCE_VALUE)
    private val ENVELOPE_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_TYPE: OperationalPayloadType = OperationalPayloadType(PAYLOAD_TYPE_VALUE)
    private val PAYLOAD_ENCODING: OperationalPayloadEncoding = OperationalPayloadEncoding(PAYLOAD_ENCODING_VALUE)

    private val redactor: StrictDataLoomRedactor = StrictDataLoomRedactor()

    /**
     * Maps one already-persisted queue-entry [outcome] for [entry], witnessed
     * under [leaseId], to an [OperationalEventEnvelope].
     *
     * [witnessedAt] is used only as a fallback for outcome variants that carry
     * no timestamp of their own -- see this object's class doc.
     *
     * May throw [IllegalArgumentException] if a derived envelope field fails
     * its own validation (for example an unexpectedly long identifier). Every
     * caller in this codebase wraps this call so such a failure is swallowed
     * rather than allowed to affect the queue-processing cycle it is
     * describing -- see the recorder configured by
     * [io.dataloom.runtime.facade.DataLoomBuilder.queueLifecycleOperationalEventOutboxConfiguration].
     */
    public fun toEnvelope(
        entry: QueueEntry,
        leaseId: QueueLeaseId,
        outcome: QueueEntryExecutionOutcome,
        witnessedAt: DataLoomInstant,
    ): OperationalEventEnvelope {
        val request = entry.synchronizationRequest
        val context = request.context
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(entry, leaseId, outcome))).attributes

        return OperationalEventEnvelope(
            id = operationalEventId(entry, leaseId),
            type = OperationalEventType(eventTypeValue(outcome)),
            source = SOURCE,
            category = OperationalEventCategory.LIFECYCLE,
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = occurredAt(outcome, witnessedAt),
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
     * Derives a stable, unique [OperationalEventId] from the already-unique
     * `(entry.id, leaseId)` pair rather than generating a new identifier. See
     * this object's class doc for why the pair, not [QueueEntry.id] alone, is
     * the correct uniqueness key. Neither
     * [io.dataloom.api.identifier.QueueEntryId] nor
     * [io.dataloom.api.identifier.QueueLeaseId] is restricted to
     * [OperationalEventId]'s own bounded token charset/length, so this
     * derivation preserves uniqueness by replacing every disallowed character
     * with `_` and bounding the combined result to [OperationalEventId]'s own
     * maximum length, the same substitution
     * [SynchronizationOperationalEventBridge.toEnvelope] applies. Two
     * different raw values could theoretically collide after this
     * substitution and truncation;
     * [io.dataloom.api.operational.DurableOperationalEventOutbox.append]
     * already handles that case safely by reporting
     * [io.dataloom.api.operational.DurableOperationalEventOutboxAppendOutcome.Conflict]
     * rather than overwriting the earlier entry.
     */
    private fun operationalEventId(entry: QueueEntry, leaseId: QueueLeaseId): OperationalEventId {
        val sanitizedEntryId = sanitize(entry.id.value)
        val sanitizedLeaseId = sanitize(leaseId.value)
        val combined = "$sanitizedEntryId$ID_SEPARATOR$sanitizedLeaseId".take(MAX_OPERATIONAL_TOKEN_LENGTH)
        return OperationalEventId(combined.ifEmpty { "unknown" })
    }

    private fun sanitize(value: String): String =
        value
            .map { character -> if (isAllowedOperationalTokenCharacter(character)) character else '_' }
            .joinToString(separator = "")

    private fun isAllowedOperationalTokenCharacter(character: Char): Boolean =
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '.' ||
            character == '_' ||
            character == '-' ||
            character == ':' ||
            character == '/'

    private fun occurredAt(outcome: QueueEntryExecutionOutcome, witnessedAt: DataLoomInstant): DataLoomInstant =
        when (outcome) {
            is QueueEntryExecutionOutcome.Completed -> outcome.completedAt
            is QueueEntryExecutionOutcome.Reschedule,
            is QueueEntryExecutionOutcome.Deferred,
            is QueueEntryExecutionOutcome.Failed,
            is QueueEntryExecutionOutcome.Cancelled,
            -> witnessedAt
        }

    private fun eventTypeValue(outcome: QueueEntryExecutionOutcome): String = when (outcome) {
        is QueueEntryExecutionOutcome.Completed -> "dataloom.queue.entry.completed"
        is QueueEntryExecutionOutcome.Reschedule -> "dataloom.queue.entry.rescheduled"
        is QueueEntryExecutionOutcome.Deferred -> "dataloom.queue.entry.deferred"
        is QueueEntryExecutionOutcome.Failed -> "dataloom.queue.entry.failed"
        is QueueEntryExecutionOutcome.Cancelled -> "dataloom.queue.entry.cancelled"
    }

    /**
     * Derives the resulting [QueueEntryState] purely from [outcome] (and, for
     * [QueueEntryExecutionOutcome.Deferred], whether [entry] already carried a
     * retry attempt) rather than reading it back from the provider. This
     * mirrors exactly the transition table
     * [io.dataloom.runtime.queue.DurableQueueExecutionProcessor]'s own class
     * doc and [io.dataloom.api.queue.QueueProvider.defer]'s own contract
     * already document: [io.dataloom.api.queue.QueueEntryState.PENDING] when
     * no retry attempt exists, [io.dataloom.api.queue.QueueEntryState.RETRY_WAITING]
     * when one does.
     */
    private fun resultingState(entry: QueueEntry, outcome: QueueEntryExecutionOutcome): QueueEntryState =
        when (outcome) {
            is QueueEntryExecutionOutcome.Completed -> QueueEntryState.COMPLETED
            is QueueEntryExecutionOutcome.Reschedule -> QueueEntryState.RETRY_WAITING
            is QueueEntryExecutionOutcome.Deferred ->
                if (entry.retryAttempt != null) QueueEntryState.RETRY_WAITING else QueueEntryState.PENDING
            is QueueEntryExecutionOutcome.Failed -> when (outcome.disposition) {
                QueueFailureDisposition.FAILED -> QueueEntryState.FAILED
                QueueFailureDisposition.DEAD_LETTER -> QueueEntryState.DEAD_LETTER
            }
            is QueueEntryExecutionOutcome.Cancelled -> QueueEntryState.CANCELLED
        }

    private fun classifiedAttributesFor(
        entry: QueueEntry,
        leaseId: QueueLeaseId,
        outcome: QueueEntryExecutionOutcome,
    ): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        val request = entry.synchronizationRequest
        attributes["entry.direction"] = ClassifiedDataValue(request.direction.name, DataClassification.PUBLIC)
        attributes["entry.mode"] = ClassifiedDataValue(request.mode.name, DataClassification.PUBLIC)
        attributes["entry.priority"] = ClassifiedDataValue(request.priority.name, DataClassification.PUBLIC)
        attributes["entry.sessionId"] =
            ClassifiedDataValue(request.sessionId.value, DataClassification.INTERNAL)
        attributes["entry.leaseId"] = ClassifiedDataValue(leaseId.value, DataClassification.INTERNAL)
        entry.retryAttempt?.let { retryAttempt ->
            attributes["entry.retryAttemptNumber"] =
                ClassifiedDataValue(retryAttempt.number.toString(), DataClassification.PUBLIC)
        }

        attributes["transition.kind"] = ClassifiedDataValue(outcomeKindName(outcome), DataClassification.PUBLIC)
        attributes["transition.resultingState"] =
            ClassifiedDataValue(resultingState(entry, outcome).name, DataClassification.PUBLIC)

        when (outcome) {
            is QueueEntryExecutionOutcome.Completed -> Unit

            is QueueEntryExecutionOutcome.Reschedule -> {
                attributes["transition.retryAttemptNumber"] =
                    ClassifiedDataValue(outcome.retryAttempt.number.toString(), DataClassification.PUBLIC)
                attributes["transition.availableAtEpochMillis"] = ClassifiedDataValue(
                    outcome.availableAt.epochMilliseconds.toString(),
                    DataClassification.PUBLIC,
                )
                putErrorAttributes(attributes, "transition.error", outcome.error)
                outcome.retryBudgetState?.let { budget ->
                    attributes["transition.retryBudget.windowStartedAtEpochMillis"] = ClassifiedDataValue(
                        budget.windowStartedAt.epochMilliseconds.toString(),
                        DataClassification.PUBLIC,
                    )
                    attributes["transition.retryBudget.lastEvaluatedAtEpochMillis"] = ClassifiedDataValue(
                        budget.lastEvaluatedAt.epochMilliseconds.toString(),
                        DataClassification.PUBLIC,
                    )
                    attributes["transition.retryBudget.cumulativeDelayMilliseconds"] = ClassifiedDataValue(
                        budget.cumulativeDelay.milliseconds.toString(),
                        DataClassification.PUBLIC,
                    )
                }
            }

            is QueueEntryExecutionOutcome.Deferred -> {
                attributes["transition.reason"] = ClassifiedDataValue(outcome.reason.name, DataClassification.PUBLIC)
                attributes["transition.availableAtEpochMillis"] = ClassifiedDataValue(
                    outcome.availableAt.epochMilliseconds.toString(),
                    DataClassification.PUBLIC,
                )
            }

            is QueueEntryExecutionOutcome.Failed -> {
                attributes["transition.disposition"] =
                    ClassifiedDataValue(outcome.disposition.name, DataClassification.PUBLIC)
                putErrorAttributes(attributes, "transition.error", outcome.error)
            }

            is QueueEntryExecutionOutcome.Cancelled -> Unit
        }
        return attributes
    }

    private fun outcomeKindName(outcome: QueueEntryExecutionOutcome): String = when (outcome) {
        is QueueEntryExecutionOutcome.Completed -> "COMPLETED"
        is QueueEntryExecutionOutcome.Reschedule -> "RESCHEDULE"
        is QueueEntryExecutionOutcome.Deferred -> "DEFERRED"
        is QueueEntryExecutionOutcome.Failed -> "FAILED"
        is QueueEntryExecutionOutcome.Cancelled -> "CANCELLED"
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
}
