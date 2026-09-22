package io.dataloom.runtime.observation.operational

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.CorrelationId
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
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.security.ClassifiedData
import io.dataloom.api.security.ClassifiedDataValue
import io.dataloom.api.security.DataClassification
import io.dataloom.api.security.RedactedAttributes
import io.dataloom.api.security.StrictDataLoomRedactor
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.runtime.worker.QueueWorkerSchedulingResult
import io.dataloom.runtime.worker.QueueWorkerWakeUpPlan

/**
 * Pure mapping from the outcome of a queue worker's wake-up scheduling
 * attempt to an [OperationalEventEnvelope], for
 * [io.dataloom.api.operational.DurableOperationalEventOutbox].
 *
 * ## What "scheduler events" are here
 *
 * A queue worker run that leaves work behind asks the [io.dataloom.api.scheduling.SchedulerProvider]
 * for a future wake-up, and reports the result as a [QueueWorkerSchedulingResult].
 * That result -- scheduled, scheduled through a circuit gate, rejected or
 * failed, or impossible because no scheduler is configured -- is the one
 * scheduler-side fact this runtime really produces and that no existing
 * bridge covers. (A synchronization retry's scheduling is already bridged as
 * the `RetryScheduled` synchronization event, so it is deliberately not
 * duplicated here.) [QueueWorkerSchedulingResult.NotRequired] means no
 * scheduling was attempted, so it maps to no envelope: [toEnvelope] returns
 * `null`.
 *
 * ## Envelope shape
 * - `id`: `scheduler.wakeup.<leaseId>` (characters outside `[A-Za-z0-9._:/-]`
 *   replaced by `_`, truncated to 128) -- a run's [QueueLeaseId] is unique per
 *   run, so each run yields at most one envelope and re-reporting the same run
 *   is an idempotent `AlreadyAppended`. (The wake-up's own `ScheduleId` is
 *   deliberately not the identity: the configured schedule id is reused run
 *   after run.)
 * - `type`: `dataloom.scheduler.wakeup.scheduled`, `.not_configured`,
 *   `.failed` or `.circuit_rejected`.
 * - `category`: [OperationalEventCategory.SYSTEM]; `source`:
 *   `dataloom.runtime.scheduler.wakeup`.
 * - `occurredAt`: the caller-supplied witness time; `correlationId`: the run's
 *   lease id (a queue worker run has no execution context of its own).
 *
 * ## Attributes
 * Closed vocabularies and numbers are `PUBLIC`; schedule and lease ids are
 * `INTERNAL`; an error's `code`/`category`/`severity`/`recoverability` are
 * `PUBLIC` and its free-text `message` is `CONFIDENTIAL` (removed by the
 * default policy), exactly as the other bridges do. Receipt metadata is never
 * read.
 */
public object QueueWorkerSchedulingOperationalEventBridge {

    private const val SOURCE_VALUE: String = "dataloom.runtime.scheduler.wakeup"
    private const val PAYLOAD_TYPE_VALUE: String = "dataloom.scheduler.wakeup"
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
     * Maps [result], produced by the worker run that held [leaseId] and
     * witnessed at [witnessedAt], to its envelope, or `null` when
     * [result] is [QueueWorkerSchedulingResult.NotRequired]. Pure: no clock,
     * no I/O.
     */
    public fun toEnvelope(
        leaseId: QueueLeaseId,
        result: QueueWorkerSchedulingResult,
        witnessedAt: DataLoomInstant,
    ): OperationalEventEnvelope? {
        val plan = planOf(result) ?: return null
        val attributes: RedactedAttributes =
            redactor.redact(ClassifiedData.of(classifiedAttributesFor(leaseId, result, plan))).attributes
        return OperationalEventEnvelope(
            id = operationalEventId(leaseId),
            type = OperationalEventType(eventTypeValue(result)),
            source = SOURCE,
            category = OperationalEventCategory.SYSTEM,
            schemaVersion = ENVELOPE_SCHEMA_VERSION,
            occurredAt = witnessedAt,
            correlationId = CorrelationId(leaseId.value),
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

    private fun planOf(result: QueueWorkerSchedulingResult): QueueWorkerWakeUpPlan.Schedule? = when (result) {
        is QueueWorkerSchedulingResult.NotRequired -> null
        is QueueWorkerSchedulingResult.Scheduled -> result.plan
        is QueueWorkerSchedulingResult.CircuitProtected -> result.plan
        is QueueWorkerSchedulingResult.SchedulerNotConfigured -> result.plan
        is QueueWorkerSchedulingResult.SchedulerFailed -> result.plan
    }

    private fun operationalEventId(leaseId: QueueLeaseId): OperationalEventId {
        val sanitized = leaseId.value
            .map { character -> if (isAllowedOperationalTokenCharacter(character)) character else '_' }
            .joinToString(separator = "")
        return OperationalEventId("scheduler.wakeup.$sanitized".take(MAX_OPERATIONAL_TOKEN_LENGTH))
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

    private fun eventTypeValue(result: QueueWorkerSchedulingResult): String = when (result) {
        is QueueWorkerSchedulingResult.Scheduled -> "dataloom.scheduler.wakeup.scheduled"
        is QueueWorkerSchedulingResult.SchedulerNotConfigured -> "dataloom.scheduler.wakeup.not_configured"
        is QueueWorkerSchedulingResult.SchedulerFailed -> "dataloom.scheduler.wakeup.failed"
        is QueueWorkerSchedulingResult.CircuitProtected -> when (val execution = result.executionResult) {
            is CircuitBreakerExecutionResult.Executed -> when (execution.operationResult) {
                is CircuitProtectedOperationResult.Success -> "dataloom.scheduler.wakeup.scheduled"
                is CircuitProtectedOperationResult.Failure,
                is CircuitProtectedOperationResult.NonCircuitFailure,
                -> "dataloom.scheduler.wakeup.failed"
            }
            is CircuitBreakerExecutionResult.Rejected -> "dataloom.scheduler.wakeup.circuit_rejected"
            is CircuitBreakerExecutionResult.PermissionPersistenceFailure,
            is CircuitBreakerExecutionResult.PermissionContentionLimitReached,
            -> "dataloom.scheduler.wakeup.failed"
        }
        is QueueWorkerSchedulingResult.NotRequired -> error("NotRequired maps to no envelope.")
    }

    private fun classifiedAttributesFor(
        leaseId: QueueLeaseId,
        result: QueueWorkerSchedulingResult,
        plan: QueueWorkerWakeUpPlan.Schedule,
    ): Map<String, ClassifiedDataValue> {
        val attributes = linkedMapOf<String, ClassifiedDataValue>()
        attributes["wakeup.leaseId"] = ClassifiedDataValue(leaseId.value, DataClassification.INTERNAL)
        attributes["wakeup.reason"] = ClassifiedDataValue(plan.reason.name, DataClassification.PUBLIC)
        attributes["wakeup.delayMilliseconds"] =
            ClassifiedDataValue(plan.delay.milliseconds.toString(), DataClassification.PUBLIC)
        attributes["wakeup.scheduleId"] = ClassifiedDataValue(plan.scheduleId.value, DataClassification.INTERNAL)
        attributes["wakeup.existingSchedulePolicy"] =
            ClassifiedDataValue(plan.existingSchedulePolicy.name, DataClassification.PUBLIC)
        when (result) {
            is QueueWorkerSchedulingResult.NotRequired -> Unit
            is QueueWorkerSchedulingResult.Scheduled -> {
                attributes["wakeup.result"] = ClassifiedDataValue("SCHEDULED", DataClassification.PUBLIC)
                attributes["wakeup.receiptScheduleId"] =
                    ClassifiedDataValue(result.receipt.id.value, DataClassification.INTERNAL)
            }
            is QueueWorkerSchedulingResult.SchedulerNotConfigured ->
                attributes["wakeup.result"] = ClassifiedDataValue("SCHEDULER_NOT_CONFIGURED", DataClassification.PUBLIC)
            is QueueWorkerSchedulingResult.SchedulerFailed -> {
                attributes["wakeup.result"] = ClassifiedDataValue("SCHEDULER_FAILED", DataClassification.PUBLIC)
                putErrorAttributes(attributes, "wakeup.error", result.error)
            }
            is QueueWorkerSchedulingResult.CircuitProtected -> {
                attributes["wakeup.result"] = ClassifiedDataValue("CIRCUIT_PROTECTED", DataClassification.PUBLIC)
                putCircuitAttributes(attributes, result.executionResult)
            }
        }
        return attributes
    }

    private fun putCircuitAttributes(
        attributes: MutableMap<String, ClassifiedDataValue>,
        execution: CircuitBreakerExecutionResult<*>,
    ) {
        when (execution) {
            is CircuitBreakerExecutionResult.Executed -> when (val operation = execution.operationResult) {
                is CircuitProtectedOperationResult.Success -> {
                    attributes["wakeup.circuit.outcome"] =
                        ClassifiedDataValue("EXECUTED_SUCCEEDED", DataClassification.PUBLIC)
                    (operation.value as? ScheduleReceipt)?.let { receipt ->
                        attributes["wakeup.receiptScheduleId"] =
                            ClassifiedDataValue(receipt.id.value, DataClassification.INTERNAL)
                    }
                }
                is CircuitProtectedOperationResult.Failure -> {
                    attributes["wakeup.circuit.outcome"] =
                        ClassifiedDataValue("EXECUTED_FAILED", DataClassification.PUBLIC)
                    putErrorAttributes(attributes, "wakeup.error", operation.error)
                }
                is CircuitProtectedOperationResult.NonCircuitFailure -> {
                    attributes["wakeup.circuit.outcome"] =
                        ClassifiedDataValue("EXECUTED_NON_CIRCUIT_FAILURE", DataClassification.PUBLIC)
                    putErrorAttributes(attributes, "wakeup.error", operation.error)
                }
            }
            is CircuitBreakerExecutionResult.Rejected -> {
                attributes["wakeup.circuit.outcome"] = ClassifiedDataValue("REJECTED", DataClassification.PUBLIC)
                attributes["wakeup.circuit.rejectionReason"] =
                    ClassifiedDataValue(execution.reason.name, DataClassification.PUBLIC)
                execution.retryAt?.let { retryAt ->
                    attributes["wakeup.circuit.retryAtEpochMillis"] =
                        ClassifiedDataValue(retryAt.epochMilliseconds.toString(), DataClassification.PUBLIC)
                }
            }
            is CircuitBreakerExecutionResult.PermissionPersistenceFailure -> {
                attributes["wakeup.circuit.outcome"] =
                    ClassifiedDataValue("PERMISSION_PERSISTENCE_FAILURE", DataClassification.PUBLIC)
                putErrorAttributes(attributes, "wakeup.error", execution.error)
            }
            is CircuitBreakerExecutionResult.PermissionContentionLimitReached ->
                attributes["wakeup.circuit.outcome"] =
                    ClassifiedDataValue("PERMISSION_CONTENTION_LIMIT", DataClassification.PUBLIC)
        }
    }

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
