package io.dataloom.runtime.observation.operational

import io.dataloom.api.circuit.CircuitAdministrationRequest
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
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.security.StrictDataLoomRedactor
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.CircuitAdministrationResult
import io.dataloom.runtime.retry.RetryAdministrationResult

/**
 * Stateless bridge from [RetryAdministrationResult]/[CircuitAdministrationResult]
 * -- the exact terminal outcome types [io.dataloom.runtime.retry.RetryAdministrationCoordinator]
 * and [io.dataloom.runtime.retry.CircuitAdministrationCoordinator] already produce
 * for every authorized, idempotent, and audited administration command -- to
 * [OperationalEventEnvelope], the canonical DL-042 envelope
 * [io.dataloom.api.operational.DurableOperationalEventOutbox] persists.
 *
 * ## Why this bridges from the coordinator result, not `RetryCircuitTelemetryEvent`
 *
 * [io.dataloom.runtime.observation.retry.RetryCircuitTelemetryEvent] already
 * exists and already covers retry/circuit administration outcomes (see
 * [io.dataloom.runtime.observation.retry.RetryCircuitTelemetrySignal]'s
 * `RETRY_ADMINISTRATION_*`/`CIRCUIT_ADMINISTRATION_*` variants), but it is a
 * deliberately lossy, already-redacted metrics/log/trace signal -- it carries
 * no command identifier at all, so it cannot supply a stable, idempotent
 * [OperationalEventEnvelope.id] or [OperationalEventEnvelope.correlationId]
 * the way [SynchronizationOperationalEventBridge] derives both from
 * [io.dataloom.api.synchronization.SynchronizationEvent.id]. The coordinator
 * results this bridge maps from -- [RetryAdministrationResult] and
 * [CircuitAdministrationResult] -- are the richer, already-existing domain
 * types: both coordinators' own class docs already call their durable state
 * "audited". This bridge reuses that existing audit identity rather than
 * inventing a second one.
 *
 * ## No clock read, no identifier generation
 *
 * [toEnvelope] never reads a clock and never generates an identifier.
 * [OperationalEventEnvelope.occurredAt] is always an already-computed
 * timestamp -- the durable command state's own `updatedAt` when a terminal
 * record exists, [RetryAdministrationRequest.requestedAt]/[CircuitAdministrationRequest.requestedAt]
 * when it does not (a command that never reached durable state, or a
 * conflict/persistence-failure result carrying its own `existing`/`observedAt`
 * timestamp instead) -- never a fresh read. [OperationalEventEnvelope.id] is
 * derived from the already-unique [RetryAdministrationRequest.commandId]/
 * [CircuitAdministrationRequest.commandId] (see [operationalEventId]), and
 * [OperationalEventEnvelope.correlationId] reuses the exact same command
 * identifier unchanged -- the natural idempotency/correlation key an
 * application already uses to submit and replay one administration command,
 * not a new one.
 *
 * ## Two commandId spaces share one bridge, never one identity
 *
 * [RetryAdministrationRequest.commandId] and [CircuitAdministrationRequest.commandId]
 * are independent identifier spaces -- an application could reuse the same
 * raw value in both. [operationalEventId] prefixes the sanitized token with
 * `retry.`/`circuit.` so a bridged retry command and a bridged circuit
 * command can never collide inside a shared
 * [io.dataloom.api.operational.OperationalEventOutboxScope].
 * [OperationalEventEnvelope.correlationId] is left as the exact unprefixed
 * command identifier, since [io.dataloom.api.identifier.CorrelationId] is a
 * cross-system correlation field, not a within-outbox uniqueness key.
 *
 * ## Classification
 *
 * Every field placed into [OperationalEventEnvelope.attributes] first enters
 * [ClassifiedData] with an explicit [DataClassification] and is redacted by
 * [StrictDataLoomRedactor], following [SynchronizationOperationalEventBridge]'s
 * own documented rules exactly: enum names and non-negative counters/epoch
 * timestamps are `PUBLIC`. Operational identifiers not obviously safe to
 * disclose (principal ID, queue entry ID, authorization ID, provider ID,
 * retry-operation label) are `INTERNAL`, so the default policy masks rather
 * than removes them. `rejectionReasonCode` fields are host-authorizer/executor
 * supplied bounded strings (128 characters), not a codebase-closed enum the
 * way [DataLoomError.code]/`category`/`severity`/`recoverability` are, so they
 * are classified `INTERNAL` rather than `PUBLIC` -- the more conservative
 * reading, consistent with never inventing a looser rule than the precedent.
 * [DataLoomError.message] is `CONFIDENTIAL` -- removed outright -- exactly as
 * [SynchronizationOperationalEventBridge] classifies it, whenever a bridged
 * result carries a full [DataLoomError] ([RetryAdministrationResult.PersistenceFailure]/
 * [CircuitAdministrationResult.PersistenceFailure], and the `persistenceError`
 * on each `ExecutionRecordingUnconfirmed` variant). [DataLoomError.cause] is
 * never included, for the same reason as the precedent. The already-redacted
 * `RetryFailureSnapshot`/`CircuitAdministrationFailureSnapshot` types the
 * coordinators themselves persist (`code`/`category`/`severity`/`recoverability`,
 * deliberately no `message` field) are entirely `PUBLIC` -- the coordinators
 * already stripped free text before this bridge ever sees them.
 * [io.dataloom.api.circuit.CircuitAdministrationReason]/
 * [io.dataloom.api.retry.RetryAdministrationReason] (caller-supplied free-text
 * justification) are never included at all, the same treatment the precedent
 * gives caller-supplied metadata.
 *
 * ## Payload descriptor
 *
 * Content-free, following [SynchronizationOperationalEventBridge]'s own
 * convention: every field this bridge has to offer already goes through
 * [OperationalEventEnvelope.attributes] instead.
 */
public object RetryCircuitAdministrationOperationalEventBridge {

    private const val RETRY_SOURCE_VALUE: String = "dataloom.runtime.retry.administration"
    private const val CIRCUIT_SOURCE_VALUE: String = "dataloom.runtime.circuit.administration"
    private const val RETRY_PAYLOAD_TYPE_VALUE: String = "dataloom.retry.administration.event"
    private const val CIRCUIT_PAYLOAD_TYPE_VALUE: String = "dataloom.circuit.administration.event"
    private const val PAYLOAD_ENCODING_VALUE: String = "none"
    private const val MAX_OPERATIONAL_TOKEN_LENGTH: Int = 128
    private const val MAX_ERROR_MESSAGE_LENGTH: Int = 4_096

    private val RETRY_SOURCE: OperationalEventSource = OperationalEventSource(RETRY_SOURCE_VALUE)
    private val CIRCUIT_SOURCE: OperationalEventSource = OperationalEventSource(CIRCUIT_SOURCE_VALUE)
    private val ENVELOPE_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val PAYLOAD_SCHEMA_VERSION: OperationalSchemaVersion = OperationalSchemaVersion(1)
    private val RETRY_PAYLOAD_TYPE: OperationalPayloadType = OperationalPayloadType(RETRY_PAYLOAD_TYPE_VALUE)
    private val CIRCUIT_PAYLOAD_TYPE: OperationalPayloadType = OperationalPayloadType(CIRCUIT_PAYLOAD_TYPE_VALUE)
    private val PAYLOAD_ENCODING: OperationalPayloadEncoding = OperationalPayloadEncoding(PAYLOAD_ENCODING_VALUE)

    private val redactor: StrictDataLoomRedactor = StrictDataLoomRedactor()

    /**
     * Maps one authorized retry-administration command's [request] and terminal
     * [result] to an [OperationalEventEnvelope].
     *
     * May throw [IllegalArgumentException] if a derived envelope field fails its
     * own validation. Every caller in this codebase wraps this call so such a
     * failure is swallowed rather than allowed to affect the administration
     * result it is describing -- see [io.dataloom.runtime.facade.DefaultDataLoomRetryAdministration].
     */
    public fun toEnvelope(
        request: RetryAdministrationRequest,
        result: RetryAdministrationResult,
    ): OperationalEventEnvelope {
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(request, result))).attributes
        return OperationalEventEnvelope(
            id = operationalEventId("retry", request.commandId.value),
            type = OperationalEventType(retryEventTypeValue(result)),
            source = RETRY_SOURCE,
            category = OperationalEventCategory.AUDIT,
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = retryOccurredAt(request, result),
            correlationId = CorrelationId(request.commandId.value),
            payload = OperationalPayloadDescriptor(
                type = RETRY_PAYLOAD_TYPE,
                schemaVersion = PAYLOAD_SCHEMA_VERSION,
                encoding = PAYLOAD_ENCODING,
                classification = DataClassification.INTERNAL,
                encodedSizeBytes = null,
            ),
            attributes = attributes,
        )
    }

    /**
     * Maps one authorized circuit-administration command's [request] and
     * terminal [result] to an [OperationalEventEnvelope].
     *
     * May throw [IllegalArgumentException] if a derived envelope field fails its
     * own validation. Every caller in this codebase wraps this call so such a
     * failure is swallowed rather than allowed to affect the administration
     * result it is describing -- see [io.dataloom.runtime.facade.DefaultDataLoomCircuitAdministration].
     */
    public fun toEnvelope(
        request: CircuitAdministrationRequest,
        result: CircuitAdministrationResult,
    ): OperationalEventEnvelope {
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(request, result))).attributes
        return OperationalEventEnvelope(
            id = operationalEventId("circuit", request.commandId.value),
            type = OperationalEventType(circuitEventTypeValue(result)),
            source = CIRCUIT_SOURCE,
            category = OperationalEventCategory.AUDIT,
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = circuitOccurredAt(request, result),
            correlationId = CorrelationId(request.commandId.value),
            tenantId = request.scope.tenantId,
            workflowId = request.scope.workflowId,
            payload = OperationalPayloadDescriptor(
                type = CIRCUIT_PAYLOAD_TYPE,
                schemaVersion = PAYLOAD_SCHEMA_VERSION,
                encoding = PAYLOAD_ENCODING,
                classification = DataClassification.INTERNAL,
                encodedSizeBytes = null,
            ),
            attributes = attributes,
        )
    }

    /**
     * Derives a stable, unique, domain-prefixed [OperationalEventId] from a raw
     * command identifier rather than generating a new identifier. See the class
     * doc's "Two commandId spaces share one bridge, never one identity".
     */
    private fun operationalEventId(domainPrefix: String, rawCommandId: String): OperationalEventId {
        val sanitized = rawCommandId
            .map { character -> if (isAllowedOperationalTokenCharacter(character)) character else '_' }
            .joinToString(separator = "")
        val combined = "$domainPrefix.$sanitized".take(MAX_OPERATIONAL_TOKEN_LENGTH)
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

    private fun retryOccurredAt(
        request: RetryAdministrationRequest,
        result: RetryAdministrationResult,
    ): DataLoomInstant = when (result) {
        is RetryAdministrationResult.Succeeded -> result.record.state.updatedAt
        is RetryAdministrationResult.AuthorizationDenied -> result.record.state.updatedAt
        is RetryAdministrationResult.PolicyRejected -> result.record.state.updatedAt
        is RetryAdministrationResult.ExecutionRejected -> result.record.state.updatedAt
        is RetryAdministrationResult.ExecutionFailed -> result.record.state.updatedAt
        is RetryAdministrationResult.CommandConflict -> result.existing.state.updatedAt
        is RetryAdministrationResult.PersistenceFailure -> request.requestedAt
        is RetryAdministrationResult.ExecutionRecordingUnconfirmed -> request.requestedAt
        is RetryAdministrationResult.ClockRegression -> result.observedAt
        RetryAdministrationResult.ContentionLimitReached -> request.requestedAt
    }

    private fun circuitOccurredAt(
        request: CircuitAdministrationRequest,
        result: CircuitAdministrationResult,
    ): DataLoomInstant = when (result) {
        is CircuitAdministrationResult.Succeeded -> result.record.state.updatedAt
        is CircuitAdministrationResult.AuthorizationDenied -> result.record.state.updatedAt
        is CircuitAdministrationResult.PolicyRejected -> result.record.state.updatedAt
        is CircuitAdministrationResult.ExecutionRejected -> result.record.state.updatedAt
        is CircuitAdministrationResult.ExecutionFailed -> result.record.state.updatedAt
        is CircuitAdministrationResult.CommandConflict -> result.existing.state.updatedAt
        is CircuitAdministrationResult.PersistenceFailure -> request.requestedAt
        is CircuitAdministrationResult.ExecutionRecordingUnconfirmed -> request.requestedAt
        is CircuitAdministrationResult.ClockRegression -> result.observedAt
        CircuitAdministrationResult.ContentionLimitReached -> request.requestedAt
    }

    private fun retryEventTypeValue(result: RetryAdministrationResult): String = when (result) {
        is RetryAdministrationResult.Succeeded -> "dataloom.retry.administration.succeeded"
        is RetryAdministrationResult.AuthorizationDenied -> "dataloom.retry.administration.authorization_denied"
        is RetryAdministrationResult.PolicyRejected -> "dataloom.retry.administration.policy_rejected"
        is RetryAdministrationResult.ExecutionRejected -> "dataloom.retry.administration.execution_rejected"
        is RetryAdministrationResult.ExecutionFailed -> "dataloom.retry.administration.execution_failed"
        is RetryAdministrationResult.CommandConflict -> "dataloom.retry.administration.command_conflict"
        is RetryAdministrationResult.PersistenceFailure -> "dataloom.retry.administration.persistence_failure"
        is RetryAdministrationResult.ExecutionRecordingUnconfirmed ->
            "dataloom.retry.administration.execution_recording_unconfirmed"
        is RetryAdministrationResult.ClockRegression -> "dataloom.retry.administration.clock_regression"
        RetryAdministrationResult.ContentionLimitReached -> "dataloom.retry.administration.contention_limit_reached"
    }

    private fun circuitEventTypeValue(result: CircuitAdministrationResult): String = when (result) {
        is CircuitAdministrationResult.Succeeded -> "dataloom.circuit.administration.succeeded"
        is CircuitAdministrationResult.AuthorizationDenied -> "dataloom.circuit.administration.authorization_denied"
        is CircuitAdministrationResult.PolicyRejected -> "dataloom.circuit.administration.policy_rejected"
        is CircuitAdministrationResult.ExecutionRejected -> "dataloom.circuit.administration.execution_rejected"
        is CircuitAdministrationResult.ExecutionFailed -> "dataloom.circuit.administration.execution_failed"
        is CircuitAdministrationResult.CommandConflict -> "dataloom.circuit.administration.command_conflict"
        is CircuitAdministrationResult.PersistenceFailure -> "dataloom.circuit.administration.persistence_failure"
        is CircuitAdministrationResult.ExecutionRecordingUnconfirmed ->
            "dataloom.circuit.administration.execution_recording_unconfirmed"
        is CircuitAdministrationResult.ClockRegression -> "dataloom.circuit.administration.clock_regression"
        CircuitAdministrationResult.ContentionLimitReached ->
            "dataloom.circuit.administration.contention_limit_reached"
    }

    private fun classifiedAttributesFor(
        request: RetryAdministrationRequest,
        result: RetryAdministrationResult,
    ): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        attributes["request.action"] = ClassifiedDataValue(request.action.name, DataClassification.PUBLIC)
        attributes["request.principalId"] =
            ClassifiedDataValue(request.principalId.value, DataClassification.INTERNAL)
        attributes["request.queueEntryId"] =
            ClassifiedDataValue(request.queueEntryId.value, DataClassification.INTERNAL)
        attributes["request.originalFailure.code"] =
            ClassifiedDataValue(request.originalFailure.code.value, DataClassification.PUBLIC)
        attributes["request.originalFailure.category"] =
            ClassifiedDataValue(request.originalFailure.category.name, DataClassification.PUBLIC)
        attributes["request.originalFailure.severity"] =
            ClassifiedDataValue(request.originalFailure.severity.name, DataClassification.PUBLIC)
        attributes["request.originalFailure.recoverability"] =
            ClassifiedDataValue(request.originalFailure.recoverability.name, DataClassification.PUBLIC)

        when (result) {
            is RetryAdministrationResult.Succeeded -> {
                attributes["result.authorizationId"] = maskedId(result.record.state.authorizationId?.value)
                attributes["result.effectiveRecoverability"] =
                    ClassifiedDataValue(
                        checkNotNull(result.record.state.effectiveRecoverability).name,
                        DataClassification.PUBLIC,
                    )
            }
            is RetryAdministrationResult.AuthorizationDenied -> {
                attributes["result.rejectionReasonCode"] =
                    maskedId(result.record.state.rejectionReasonCode)
            }
            is RetryAdministrationResult.PolicyRejected -> {
                attributes["result.authorizationId"] = maskedId(result.record.state.authorizationId?.value)
                attributes["result.rejectionReasonCode"] = maskedId(result.record.state.rejectionReasonCode)
            }
            is RetryAdministrationResult.ExecutionRejected -> {
                attributes["result.authorizationId"] = maskedId(result.record.state.authorizationId?.value)
                attributes["result.rejectionReasonCode"] = maskedId(result.record.state.rejectionReasonCode)
            }
            is RetryAdministrationResult.ExecutionFailed -> {
                attributes["result.authorizationId"] = maskedId(result.record.state.authorizationId?.value)
                val failure = checkNotNull(result.record.state.executionFailure)
                attributes["result.executionFailure.code"] =
                    ClassifiedDataValue(failure.code.value, DataClassification.PUBLIC)
                attributes["result.executionFailure.category"] =
                    ClassifiedDataValue(failure.category.name, DataClassification.PUBLIC)
                attributes["result.executionFailure.severity"] =
                    ClassifiedDataValue(failure.severity.name, DataClassification.PUBLIC)
                attributes["result.executionFailure.recoverability"] =
                    ClassifiedDataValue(failure.recoverability.name, DataClassification.PUBLIC)
            }
            is RetryAdministrationResult.CommandConflict -> {
                attributes["result.existingStatus"] =
                    ClassifiedDataValue(result.existing.state.status.name, DataClassification.PUBLIC)
            }
            is RetryAdministrationResult.PersistenceFailure -> {
                putErrorAttributes(attributes, "result.error", result.error)
            }
            is RetryAdministrationResult.ExecutionRecordingUnconfirmed -> {
                putErrorAttributes(attributes, "result.persistenceError", result.persistenceError)
            }
            is RetryAdministrationResult.ClockRegression -> {
                attributes["result.persistedAtEpochMillis"] =
                    ClassifiedDataValue(result.persistedAt.epochMilliseconds.toString(), DataClassification.PUBLIC)
            }
            RetryAdministrationResult.ContentionLimitReached -> Unit
        }
        return attributes
    }

    private fun classifiedAttributesFor(
        request: CircuitAdministrationRequest,
        result: CircuitAdministrationResult,
    ): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        attributes["request.action"] = ClassifiedDataValue(request.action.name, DataClassification.PUBLIC)
        attributes["request.principalId"] =
            ClassifiedDataValue(request.principalId.value, DataClassification.INTERNAL)
        attributes["request.scope.kind"] = ClassifiedDataValue(request.scope.kind.name, DataClassification.PUBLIC)
        request.scope.providerId?.let { providerId ->
            attributes["request.scope.providerId"] = ClassifiedDataValue(providerId.value, DataClassification.INTERNAL)
        }
        request.scope.operation?.let { operation ->
            attributes["request.scope.operation"] = ClassifiedDataValue(operation.value, DataClassification.INTERNAL)
        }
        request.openUntil?.let { openUntil ->
            attributes["request.openUntilEpochMillis"] =
                ClassifiedDataValue(openUntil.epochMilliseconds.toString(), DataClassification.PUBLIC)
        }

        when (result) {
            is CircuitAdministrationResult.Succeeded -> {
                attributes["result.authorizationId"] = maskedId(result.record.state.authorizationId?.value)
                result.record.state.resultingRecord?.let { resultingRecord ->
                    attributes["result.phase"] =
                        ClassifiedDataValue(resultingRecord.state.phase.name, DataClassification.PUBLIC)
                }
            }
            is CircuitAdministrationResult.AuthorizationDenied -> {
                attributes["result.rejectionReasonCode"] = maskedId(result.record.state.rejectionReasonCode)
            }
            is CircuitAdministrationResult.PolicyRejected -> {
                attributes["result.authorizationId"] = maskedId(result.record.state.authorizationId?.value)
                attributes["result.rejectionReasonCode"] = maskedId(result.record.state.rejectionReasonCode)
            }
            is CircuitAdministrationResult.ExecutionRejected -> {
                attributes["result.authorizationId"] = maskedId(result.record.state.authorizationId?.value)
                attributes["result.rejectionReasonCode"] = maskedId(result.record.state.rejectionReasonCode)
            }
            is CircuitAdministrationResult.ExecutionFailed -> {
                attributes["result.authorizationId"] = maskedId(result.record.state.authorizationId?.value)
                val failure = checkNotNull(result.record.state.executionFailure)
                attributes["result.executionFailure.code"] =
                    ClassifiedDataValue(failure.code.value, DataClassification.PUBLIC)
                attributes["result.executionFailure.category"] =
                    ClassifiedDataValue(failure.category.name, DataClassification.PUBLIC)
                attributes["result.executionFailure.severity"] =
                    ClassifiedDataValue(failure.severity.name, DataClassification.PUBLIC)
                attributes["result.executionFailure.recoverability"] =
                    ClassifiedDataValue(failure.recoverability.name, DataClassification.PUBLIC)
            }
            is CircuitAdministrationResult.CommandConflict -> {
                attributes["result.existingStatus"] =
                    ClassifiedDataValue(result.existing.state.status.name, DataClassification.PUBLIC)
            }
            is CircuitAdministrationResult.PersistenceFailure -> {
                putErrorAttributes(attributes, "result.error", result.error)
            }
            is CircuitAdministrationResult.ExecutionRecordingUnconfirmed -> {
                putErrorAttributes(attributes, "result.persistenceError", result.persistenceError)
            }
            is CircuitAdministrationResult.ClockRegression -> {
                attributes["result.persistedAtEpochMillis"] =
                    ClassifiedDataValue(result.persistedAt.epochMilliseconds.toString(), DataClassification.PUBLIC)
            }
            CircuitAdministrationResult.ContentionLimitReached -> Unit
        }
        return attributes
    }

    /**
     * `code`/`category`/`severity`/`recoverability` are closed, stable
     * vocabularies -- `PUBLIC`. `message` is unstructured free text this
     * codebase's own convention (never credentials/tokens/PII) does not
     * enforce at the type level, so it is `CONFIDENTIAL` -- removed outright by
     * the default redaction policy. `cause` (a [Throwable]) is never included:
     * its message is exactly as unbounded and unvetted as
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

    /**
     * Wraps a nullable host-supplied identifier/reason-code string as an
     * `INTERNAL`-masked attribute, or a bounded empty-string placeholder when
     * absent -- these fields are structurally required by their invariant for
     * every branch this helper is called from and are never actually null in
     * practice; the null-safety is defensive only.
     */
    private fun maskedId(value: String?): ClassifiedDataValue =
        ClassifiedDataValue(value.orEmpty(), DataClassification.INTERNAL)
}
