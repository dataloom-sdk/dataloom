package io.dataloom.runtime.observation.operational

import io.dataloom.api.error.DataLoomError
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
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetTransferOperation
import io.dataloom.assets.AssetTransferOutcome
import io.dataloom.assets.AssetTransferSession
import io.dataloom.assets.AssetTransferSessionId

/**
 * Pure mapping from one already-computed [AssetTransferOutcome] -- the exact
 * value [io.dataloom.assets.AssetTransferEngine.upload]/`.download`/`.cancel`
 * already return to their own caller -- to [OperationalEventEnvelope], the
 * same shape and conventions [QueueLifecycleOperationalEventBridge] and
 * [PolicyDecisionOperationalEventBridge] already establish, applied to the
 * first `dataloom-assets` producer DL-042's outbox bridges this gate.
 *
 * ## Why bridge the operation's outcome, not a per-chunk event
 *
 * `dataloom-assets` exposes no per-chunk "chunk committed" event to a caller
 * today -- [io.dataloom.assets.AssetTransferSession.reduce] is a private
 * state-machine transition the engine drives internally, not a type any
 * caller observes. [AssetTransferOutcome] is, by contrast, already the
 * engine's own public contract: the one thing every caller of [upload]/
 * [download]/[cancel] already receives. This bridge reuses it rather than
 * inventing a second, parallel event shape, mirroring exactly how
 * [QueueLifecycleOperationalEventBridge] reuses
 * [io.dataloom.runtime.queue.QueueEntryExecutionOutcome] instead of inventing
 * a queue-entry-lifecycle event type of its own.
 *
 * ## Correlation identity
 *
 * Unlike every sibling bridge, this domain has no
 * [io.dataloom.api.context.ExecutionContext] to read a
 * [CorrelationId]/[io.dataloom.api.identifier.TraceId]/[io.dataloom.api.identifier.TenantId]
 * from -- `dataloom-assets` is not driven through the synchronization
 * pipeline. [AssetTransferSessionId] is, however, already this domain's own
 * stable cross-call correlation identity (the same id a caller reuses to
 * resume, retry, or cancel a transfer), so [toEnvelope] reuses it as
 * [OperationalEventEnvelope.correlationId] rather than inventing a new
 * identifier -- the same "reuse, never invent" posture every sibling bridge
 * already follows, applied to the one identity this domain actually has.
 * [OperationalEventEnvelope.traceId]/`.tenantId`/`.workflowId` are left
 * `null`: nothing in this domain carries them.
 *
 * ## Envelope shape
 * - `id`: `asset.transfer.<operation>.<sessionId>.<discriminator>`, sanitized
 *   and bounded to 128 characters. For the four outcomes that carry a session
 *   ([AssetTransferOutcome.Completed], `.Interrupted`, `.Failed`,
 *   `.Cancelled`) the discriminator is the session's own
 *   [AssetTransferSession.revision] -- a stable, idempotent key: reporting
 *   the same outcome for the same session revision twice produces the same
 *   id (an idempotent `AlreadyAppended`), while a later call that actually
 *   advanced the session (a higher revision) produces a genuinely new
 *   envelope. The two outcomes with no session
 *   ([AssetTransferOutcome.NotStarted], `.SessionStoreFailure]) have no
 *   revision to key on, so the discriminator falls back to [witnessedAt]'s
 *   epoch milliseconds; two such failures for the same session id in the
 *   same millisecond could theoretically collide, which
 *   [io.dataloom.api.operational.DurableOperationalEventOutbox.append]
 *   already handles safely by reporting
 *   [io.dataloom.api.operational.DurableOperationalEventOutboxAppendOutcome.Conflict]
 *   rather than overwriting the earlier entry -- the same accepted tradeoff
 *   [QueueLifecycleOperationalEventBridge]'s own class doc documents for its
 *   own sanitized-identifier collision case.
 * - `type`: `dataloom.asset.transfer.<operation>.<outcome>`, for example
 *   `dataloom.asset.transfer.upload.completed` or
 *   `dataloom.asset.transfer.download.interrupted`.
 * - `category`: [OperationalEventCategory.LIFECYCLE] -- this bridges a
 *   transfer session's own lifecycle outcome, the same category
 *   [QueueLifecycleOperationalEventBridge] uses for the analogous queue-entry
 *   case.
 * - `occurredAt`: [witnessedAt], read once by the caller
 *   ([AssetTransferOperationalEventRecorder]) -- [AssetTransferOutcome]
 *   itself carries no timestamp of its own, unlike
 *   [io.dataloom.runtime.queue.QueueEntryExecutionOutcome.Completed].
 *
 * ## Attributes
 * Every field goes through [ClassifiedData]/[StrictDataLoomRedactor]. Closed
 * vocabularies (operation, outcome kind, direction, phase, counts) are
 * `PUBLIC`; [io.dataloom.api.identifier.AssetId] is `INTERNAL`. A
 * [DataLoomError.message] (present on [AssetTransferOutcome.Interrupted],
 * `.Failed`, `.NotStarted`, `.SessionStoreFailure]) is `CONFIDENTIAL`, removed
 * outright by the default policy -- the same classification every sibling
 * bridge already applies to a `DataLoomError.message`. `cause` is never
 * included, for the same reason as every sibling bridge.
 *
 * ## Payload descriptor
 * Content-free, following every sibling bridge's own convention.
 */
public object AssetTransferOperationalEventBridge {

    private const val SOURCE_VALUE: String = "dataloom.assets.transfer"
    private const val PAYLOAD_TYPE_VALUE: String = "dataloom.asset.transfer.outcome"
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
     * Maps [outcome] -- produced by [operation] for [sessionId] -- to its
     * envelope. Pure: no clock, no I/O, no identifier generation beyond the
     * deterministic, sanitized derivation documented on this object.
     */
    public fun toEnvelope(
        sessionId: AssetTransferSessionId,
        operation: AssetTransferOperation,
        outcome: AssetTransferOutcome,
        witnessedAt: DataLoomInstant,
    ): OperationalEventEnvelope {
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(operation, outcome))).attributes
        return OperationalEventEnvelope(
            id = operationalEventId(sessionId, operation, outcome, witnessedAt),
            type = OperationalEventType(eventTypeValue(operation, outcome)),
            source = SOURCE,
            category = OperationalEventCategory.LIFECYCLE,
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = witnessedAt,
            correlationId = CorrelationId(sessionId.value),
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

    private fun operationalEventId(
        sessionId: AssetTransferSessionId,
        operation: AssetTransferOperation,
        outcome: AssetTransferOutcome,
        witnessedAt: DataLoomInstant,
    ): OperationalEventId {
        val combined = "asset.transfer$ID_SEPARATOR${operation.name.lowercase()}" +
            "$ID_SEPARATOR${sanitize(sessionId.value)}$ID_SEPARATOR${discriminator(outcome, witnessedAt)}"
        return OperationalEventId(combined.take(MAX_OPERATIONAL_TOKEN_LENGTH))
    }

    private fun discriminator(outcome: AssetTransferOutcome, witnessedAt: DataLoomInstant): String =
        when (val session = sessionOf(outcome)) {
            null -> "at${witnessedAt.epochMilliseconds}"
            else -> "rev${session.revision}"
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

    private fun eventTypeValue(operation: AssetTransferOperation, outcome: AssetTransferOutcome): String =
        "dataloom.asset.transfer.${operation.name.lowercase()}.${outcomeTypeSuffix(outcome)}"

    private fun outcomeTypeSuffix(outcome: AssetTransferOutcome): String = when (outcome) {
        is AssetTransferOutcome.Completed -> "completed"
        is AssetTransferOutcome.Interrupted -> "interrupted"
        is AssetTransferOutcome.Failed -> "failed"
        is AssetTransferOutcome.Cancelled -> "cancelled"
        is AssetTransferOutcome.NotStarted -> "not_started"
        is AssetTransferOutcome.SessionStoreFailure -> "session_store_failure"
    }

    private fun outcomeKindName(outcome: AssetTransferOutcome): String = when (outcome) {
        is AssetTransferOutcome.Completed -> "COMPLETED"
        is AssetTransferOutcome.Interrupted -> "INTERRUPTED"
        is AssetTransferOutcome.Failed -> "FAILED"
        is AssetTransferOutcome.Cancelled -> "CANCELLED"
        is AssetTransferOutcome.NotStarted -> "NOT_STARTED"
        is AssetTransferOutcome.SessionStoreFailure -> "SESSION_STORE_FAILURE"
    }

    private fun sessionOf(outcome: AssetTransferOutcome): AssetTransferSession? = when (outcome) {
        is AssetTransferOutcome.Completed -> outcome.session
        is AssetTransferOutcome.Interrupted -> outcome.session
        is AssetTransferOutcome.Failed -> outcome.session
        is AssetTransferOutcome.Cancelled -> outcome.session
        is AssetTransferOutcome.NotStarted -> null
        is AssetTransferOutcome.SessionStoreFailure -> null
    }

    private fun errorOf(outcome: AssetTransferOutcome): DataLoomError? = when (outcome) {
        is AssetTransferOutcome.Interrupted -> outcome.error
        is AssetTransferOutcome.Failed -> outcome.error
        is AssetTransferOutcome.NotStarted -> outcome.error
        is AssetTransferOutcome.SessionStoreFailure -> outcome.error
        is AssetTransferOutcome.Completed, is AssetTransferOutcome.Cancelled -> null
    }

    private fun classifiedAttributesFor(
        operation: AssetTransferOperation,
        outcome: AssetTransferOutcome,
    ): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        attributes["transfer.operation"] = ClassifiedDataValue(operation.name, DataClassification.PUBLIC)
        attributes["transfer.outcome"] = ClassifiedDataValue(outcomeKindName(outcome), DataClassification.PUBLIC)

        sessionOf(outcome)?.let { session ->
            attributes["transfer.direction"] = ClassifiedDataValue(session.direction.name, DataClassification.PUBLIC)
            attributes["transfer.phase"] = ClassifiedDataValue(session.phase.name, DataClassification.PUBLIC)
            attributes["transfer.revision"] = ClassifiedDataValue(session.revision.toString(), DataClassification.PUBLIC)
            attributes["transfer.assetId"] =
                ClassifiedDataValue(session.manifest.assetId.value, DataClassification.INTERNAL)
            attributes["transfer.assetVersion"] =
                ClassifiedDataValue(session.manifest.version.toString(), DataClassification.PUBLIC)
            attributes["transfer.mediaType"] =
                ClassifiedDataValue(session.manifest.mediaType.value, DataClassification.PUBLIC)
            attributes["transfer.sizeBytes"] =
                ClassifiedDataValue(session.manifest.sizeBytes.toString(), DataClassification.PUBLIC)
            attributes["transfer.chunkCount"] =
                ClassifiedDataValue(session.manifest.chunkLayout.chunkCount.toString(), DataClassification.PUBLIC)
            attributes["transfer.committedChunkCount"] =
                ClassifiedDataValue(session.committedChunks.size.toString(), DataClassification.PUBLIC)
        }

        errorOf(outcome)?.let { error -> putErrorAttributes(attributes, "transfer.error", error) }
        return attributes
    }

    /**
     * `code`/`category`/`severity`/`recoverability` are closed, stable
     * vocabularies -- `PUBLIC`. `message` is unstructured free text, so it is
     * `CONFIDENTIAL` -- removed outright by the default redaction policy,
     * mirroring every sibling bridge's own `DataLoomError` classification.
     * `cause` is never included.
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
