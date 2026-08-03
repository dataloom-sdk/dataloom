package io.dataloom.runtime.observation.retry

import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScopeKind
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.time.DataLoomInstant

/** Stable structured-log severity independent of a logging vendor. */
public enum class RetryCircuitLogSeverity {
    INFO,
    WARNING,
    ERROR,
}

/**
 * Vendor-neutral structured retry/circuit log record.
 *
 * The schema intentionally has no message, payload, exception, free-form tag,
 * or arbitrary metadata field. Applications map this fixed record to their
 * logger without parsing [RetryCircuitTelemetryEvent.toString].
 */
public class RetryCircuitStructuredLogRecord(
    public val schemaVersion: Int,
    public val severity: RetryCircuitLogSeverity,
    public val signal: RetryCircuitTelemetrySignal,
    public val occurredAt: DataLoomInstant,
    public val workflowId: WorkflowId?,
    public val tenantId: TenantId?,
    public val correlationId: CorrelationId?,
    public val traceId: TraceId?,
    public val scopeKind: CircuitBreakerScopeKind?,
    public val circuitPhase: CircuitBreakerPhase?,
    public val circuitOperationOutcome: RetryCircuitTelemetryOperationOutcome?,
    public val circuitDetail: RetryCircuitTelemetryCircuitDetail?,
    public val retryAttemptNumber: Int?,
    public val selectedDelayMillis: Long?,
    public val errorCode: ErrorCode?,
)

/** Application/vendor structured-log boundary. */
public interface RetryCircuitStructuredLogSink {
    public suspend fun write(record: RetryCircuitStructuredLogRecord)
}

/** Converts telemetry events to fixed-schema structured log records. */
public class RetryCircuitStructuredLogExporter(
    override val id: RetryCircuitTelemetryExporterId,
    private val sink: RetryCircuitStructuredLogSink,
) : RetryCircuitTelemetryExporter {
    override suspend fun export(event: RetryCircuitTelemetryEvent) {
        sink.write(event.toStructuredLogRecord())
    }
}

/** Vendor-neutral correlated trace signal for retry/circuit adapters. */
public class RetryCircuitTraceSignal(
    public val schemaVersion: Int,
    public val traceId: TraceId,
    public val correlationId: CorrelationId?,
    public val workflowId: WorkflowId?,
    public val signal: RetryCircuitTelemetrySignal,
    public val occurredAt: DataLoomInstant,
    public val errorCode: ErrorCode?,
)

/** Application/vendor trace boundary. */
public interface RetryCircuitTraceSink {
    public suspend fun record(signal: RetryCircuitTraceSignal)
}

/**
 * Exports only events carrying an existing trace id.
 *
 * This adapter does not invent trace identities or place high-cardinality
 * values into metrics. Missing trace context is ignored explicitly.
 */
public class RetryCircuitTraceExporter(
    override val id: RetryCircuitTelemetryExporterId,
    private val sink: RetryCircuitTraceSink,
) : RetryCircuitTelemetryExporter {
    override suspend fun export(event: RetryCircuitTelemetryEvent) {
        val traceId = event.context.traceId ?: return
        sink.record(
            RetryCircuitTraceSignal(
                schemaVersion = event.schemaVersion,
                traceId = traceId,
                correlationId = event.context.correlationId,
                workflowId = event.context.workflowId,
                signal = event.signal,
                occurredAt = event.occurredAt,
                errorCode = event.errorCode,
            ),
        )
    }
}

private fun RetryCircuitTelemetryEvent.toStructuredLogRecord(): RetryCircuitStructuredLogRecord =
    RetryCircuitStructuredLogRecord(
        schemaVersion = schemaVersion,
        severity = signal.logSeverity(),
        signal = signal,
        occurredAt = occurredAt,
        workflowId = context.workflowId,
        tenantId = context.tenantId,
        correlationId = context.correlationId,
        traceId = context.traceId,
        scopeKind = scopeKind,
        circuitPhase = circuitPhase,
        circuitOperationOutcome = circuitOperationOutcome,
        circuitDetail = circuitDetail,
        retryAttemptNumber = retryAttempt?.number,
        selectedDelayMillis = selectedDelay?.milliseconds,
        errorCode = errorCode,
    )

private fun RetryCircuitTelemetrySignal.logSeverity(): RetryCircuitLogSeverity = when (this) {
    RetryCircuitTelemetrySignal.RETRY_NOT_REQUIRED,
    RetryCircuitTelemetrySignal.RETRY_SCHEDULED,
    RetryCircuitTelemetrySignal.CIRCUIT_EXECUTED,
    RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_SUCCEEDED,
    RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_SUCCEEDED,
    -> RetryCircuitLogSeverity.INFO

    RetryCircuitTelemetrySignal.RETRY_STOPPED,
    RetryCircuitTelemetrySignal.RETRY_SCHEDULER_NOT_CONFIGURED,
    RetryCircuitTelemetrySignal.CIRCUIT_REJECTED,
    RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_AUTHORIZATION_DENIED,
    RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_POLICY_REJECTED,
    RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_EXECUTION_REJECTED,
    RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_COMMAND_CONFLICT,
    RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_CLOCK_REGRESSION,
    RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_CONTENTION,
    RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_AUTHORIZATION_DENIED,
    RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_POLICY_REJECTED,
    RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_EXECUTION_REJECTED,
    RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_COMMAND_CONFLICT,
    RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_CLOCK_REGRESSION,
    RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_CONTENTION,
    -> RetryCircuitLogSeverity.WARNING

    RetryCircuitTelemetrySignal.RETRY_SCHEDULER_FAILED,
    RetryCircuitTelemetrySignal.CIRCUIT_PERMISSION_PERSISTENCE_FAILED,
    RetryCircuitTelemetrySignal.CIRCUIT_PERMISSION_CONTENTION,
    RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_EXECUTION_FAILED,
    RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_PERSISTENCE_FAILED,
    RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_RECORDING_UNCONFIRMED,
    RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_EXECUTION_FAILED,
    RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_PERSISTENCE_FAILED,
    RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_RECORDING_UNCONFIRMED,
    -> RetryCircuitLogSeverity.ERROR
}
