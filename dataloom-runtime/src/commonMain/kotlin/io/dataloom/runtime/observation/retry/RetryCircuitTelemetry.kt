package io.dataloom.runtime.observation.retry

import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScopeKind
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmInline
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Stable identifier for one retry/circuit telemetry exporter. */
@JvmInline
public value class RetryCircuitTelemetryExporterId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RetryCircuitTelemetryExporterId must not be blank." }
        require(value.length <= MAXIMUM_EXPORTER_ID_LENGTH) {
            "RetryCircuitTelemetryExporterId must not exceed $MAXIMUM_EXPORTER_ID_LENGTH characters."
        }
    }

    override fun toString(): String = value
}

/** Closed retry/circuit signal taxonomy used by logs, metrics, and exporters. */
public enum class RetryCircuitTelemetrySignal {
    RETRY_NOT_REQUIRED,
    RETRY_STOPPED,
    RETRY_SCHEDULED,
    RETRY_SCHEDULER_NOT_CONFIGURED,
    RETRY_SCHEDULER_FAILED,
    CIRCUIT_EXECUTED,
    CIRCUIT_REJECTED,
    CIRCUIT_PERMISSION_PERSISTENCE_FAILED,
    CIRCUIT_PERMISSION_CONTENTION,
    RETRY_ADMINISTRATION_SUCCEEDED,
    RETRY_ADMINISTRATION_AUTHORIZATION_DENIED,
    RETRY_ADMINISTRATION_POLICY_REJECTED,
    RETRY_ADMINISTRATION_EXECUTION_REJECTED,
    RETRY_ADMINISTRATION_EXECUTION_FAILED,
    RETRY_ADMINISTRATION_COMMAND_CONFLICT,
    RETRY_ADMINISTRATION_PERSISTENCE_FAILED,
    RETRY_ADMINISTRATION_RECORDING_UNCONFIRMED,
    RETRY_ADMINISTRATION_CLOCK_REGRESSION,
    RETRY_ADMINISTRATION_CONTENTION,
    CIRCUIT_ADMINISTRATION_SUCCEEDED,
    CIRCUIT_ADMINISTRATION_AUTHORIZATION_DENIED,
    CIRCUIT_ADMINISTRATION_POLICY_REJECTED,
    CIRCUIT_ADMINISTRATION_EXECUTION_REJECTED,
    CIRCUIT_ADMINISTRATION_EXECUTION_FAILED,
    CIRCUIT_ADMINISTRATION_COMMAND_CONFLICT,
    CIRCUIT_ADMINISTRATION_PERSISTENCE_FAILED,
    CIRCUIT_ADMINISTRATION_RECORDING_UNCONFIRMED,
    CIRCUIT_ADMINISTRATION_CLOCK_REGRESSION,
    CIRCUIT_ADMINISTRATION_CONTENTION,
}

/** Stable, bounded circuit detail taxonomy. */
public enum class RetryCircuitTelemetryCircuitDetail {
    STATE_RECORDED,
    STATE_IGNORED,
    STALE_PROBE,
    PROBE_LEASE_EXPIRED,
    STATE_CLOCK_REGRESSION,
    STATE_PERSISTENCE_FAILED,
    STATE_CONTENTION,
    OPEN,
    PROBE_IN_FLIGHT,
    PERMISSION_CLOCK_REGRESSION,
    PROBE_GENERATION_EXHAUSTED,
    PROBE_LEASE_DEADLINE_EXHAUSTED,
}

/** Closed operation-outcome dimension for circuit execution metrics. */
public enum class RetryCircuitTelemetryOperationOutcome {
    SUCCEEDED,
    CIRCUIT_FAILURE,
    NON_CIRCUIT_FAILURE,
}

/** Correlation carried to logs/traces but never used as a metric dimension. */
public class RetryCircuitTelemetryContext(
    public val workflowId: WorkflowId? = null,
    public val tenantId: TenantId? = null,
    public val correlationId: CorrelationId? = null,
    public val traceId: TraceId? = null,
)

/**
 * Immutable, redacted retry/circuit telemetry fact.
 *
 * The model contains no payload, exception, credential, free-form metadata, or
 * reason text. [errorCode] is the already-sanitized canonical DataLoom code.
 */
public class RetryCircuitTelemetryEvent(
    public val signal: RetryCircuitTelemetrySignal,
    public val occurredAt: DataLoomInstant,
    public val context: RetryCircuitTelemetryContext = RetryCircuitTelemetryContext(),
    public val scopeKind: CircuitBreakerScopeKind? = null,
    public val circuitPhase: CircuitBreakerPhase? = null,
    public val circuitOperationOutcome: RetryCircuitTelemetryOperationOutcome? = null,
    public val circuitDetail: RetryCircuitTelemetryCircuitDetail? = null,
    public val retryAttempt: RetryAttempt? = null,
    public val selectedDelay: SchedulingDelay? = null,
    public val errorCode: ErrorCode? = null,
) {
    /** Stable schema version for exporter compatibility. */
    public val schemaVersion: Int
        get() = 1

    override fun toString(): String =
        "RetryCircuitTelemetryEvent(" +
            "schemaVersion=$schemaVersion, " +
            "signal=$signal, " +
            "occurredAt=$occurredAt, " +
            "workflowId=${context.workflowId?.value}, " +
            "tenantScoped=${context.tenantId != null}, " +
            "correlationId=${context.correlationId?.value}, " +
            "traceId=${context.traceId?.value}, " +
            "scopeKind=$scopeKind, " +
            "circuitPhase=$circuitPhase, " +
            "circuitOperationOutcome=$circuitOperationOutcome, " +
            "circuitDetail=$circuitDetail, " +
            "retryAttempt=${retryAttempt?.number}, " +
            "selectedDelayMillis=${selectedDelay?.milliseconds}, " +
            "errorCode=${errorCode?.value}" +
            ")"

}

/** Host/vendor adapter invoked only from its dedicated bounded worker. */
public interface RetryCircuitTelemetryExporter {
    public val id: RetryCircuitTelemetryExporterId

    public suspend fun export(event: RetryCircuitTelemetryEvent)
}

/** Explicit bounded-delivery policy for retry/circuit telemetry. */
public enum class RetryCircuitTelemetryOverflowPolicy {
    /** Preserve already-buffered order and drop the newest submission. */
    DROP_LATEST,
}

/** Explicit bounded-delivery policy for retry/circuit telemetry. */
public class RetryCircuitTelemetryConfiguration(
    public val bufferCapacityPerExporter: Int = 256,
    public val exporterTimeout: SchedulingDelay = SchedulingDelay(5_000L),
    public val overflowPolicy: RetryCircuitTelemetryOverflowPolicy =
        RetryCircuitTelemetryOverflowPolicy.DROP_LATEST,
) {
    init {
        require(bufferCapacityPerExporter in 1..MAXIMUM_BUFFER_CAPACITY) {
            "RetryCircuitTelemetryConfiguration bufferCapacityPerExporter must be between 1 and $MAXIMUM_BUFFER_CAPACITY."
        }
        require(exporterTimeout.milliseconds > 0L) {
            "RetryCircuitTelemetryConfiguration exporterTimeout must be greater than zero."
        }
    }
}

/** Result of one non-blocking telemetry submission. */
public class RetryCircuitTelemetryRecordResult(
    public val configuredExporterCount: Int,
    public val acceptedExporterCount: Int,
    public val droppedExporterCount: Int,
) {
    init {
        require(configuredExporterCount >= 0)
        require(acceptedExporterCount >= 0)
        require(droppedExporterCount >= 0)
        require(acceptedExporterCount + droppedExporterCount == configuredExporterCount)
    }
}

/** Fixed-dimension metric key. Dynamic IDs and error codes cannot become labels. */
public data class RetryCircuitMetricKey(
    public val signal: RetryCircuitTelemetrySignal,
    public val scopeKind: CircuitBreakerScopeKind?,
    public val circuitOperationOutcome: RetryCircuitTelemetryOperationOutcome?,
    public val circuitDetail: RetryCircuitTelemetryCircuitDetail?,
)

/** Exporter health visible through the SDK-owned operational read model. */
public enum class RetryCircuitExporterHealth {
    HEALTHY,
    DEGRADED,
    STOPPED,
}

/** Stable exporter failure category without exception text. */
public enum class RetryCircuitExporterFailureReason {
    EXCEPTION,
    TIMEOUT,
}

/** Point-in-time redacted state for one isolated exporter worker. */
public data class RetryCircuitExporterSnapshot(
    public val exporterId: RetryCircuitTelemetryExporterId,
    public val health: RetryCircuitExporterHealth,
    public val acceptedCount: Long,
    public val droppedCount: Long,
    public val exportedCount: Long,
    public val failureCount: Long,
    public val timeoutCount: Long,
    public val lastFailureReason: RetryCircuitExporterFailureReason?,
)

/** Redacted support/read-model snapshot with bounded metric dimensions. */
public class RetryCircuitTelemetrySnapshot(
    public val metricCounts: Map<RetryCircuitMetricKey, Long>,
    public val exporters: List<RetryCircuitExporterSnapshot>,
) {
    public val degraded: Boolean
        get() = exporters.any { it.health != RetryCircuitExporterHealth.HEALTHY }
}

/**
 * Non-blocking, bounded and exporter-isolated retry/circuit telemetry pipeline.
 *
 * [record] never calls an exporter and never suspends. Each exporter owns a
 * dedicated bounded channel and worker. When its buffer is full the newest
 * event is dropped for that exporter and counted; other exporters continue.
 */
public class BoundedRetryCircuitTelemetry(
    coroutineContext: CoroutineContext,
    configuration: RetryCircuitTelemetryConfiguration,
    exporters: List<RetryCircuitTelemetryExporter>,
) {
    private val metricState = MutableStateFlow<Map<RetryCircuitMetricKey, Long>>(emptyMap())
    private val pipelines: List<ExporterPipeline>
    private val supervisorJob: CompletableJob = SupervisorJob(coroutineContext[Job])

    init {
        require(exporters.size <= MAXIMUM_EXPORTER_COUNT) {
            "BoundedRetryCircuitTelemetry supports at most $MAXIMUM_EXPORTER_COUNT exporters."
        }
        require(exporters.map { it.id }.distinct().size == exporters.size) {
            "BoundedRetryCircuitTelemetry exporter ids must be unique."
        }
        val scope = CoroutineScope(coroutineContext + supervisorJob)
        pipelines = exporters.map { exporter ->
            ExporterPipeline(scope, configuration, exporter)
        }
    }

    /** Submits [event] without suspension, exporter invocation, or unbounded allocation. */
    public fun record(event: RetryCircuitTelemetryEvent): RetryCircuitTelemetryRecordResult {
        val key = RetryCircuitMetricKey(
            signal = event.signal,
            scopeKind = event.scopeKind,
            circuitOperationOutcome = event.circuitOperationOutcome,
            circuitDetail = event.circuitDetail,
        )
        metricState.update { counts ->
            val current = counts[key] ?: 0L
            counts + (key to incrementSaturated(current))
        }

        var accepted = 0
        pipelines.forEach { pipeline ->
            if (pipeline.offer(event)) accepted++
        }
        return RetryCircuitTelemetryRecordResult(
            configuredExporterCount = pipelines.size,
            acceptedExporterCount = accepted,
            droppedExporterCount = pipelines.size - accepted,
        )
    }

    /** Returns a redacted point-in-time read model without contacting exporters. */
    public fun snapshot(): RetryCircuitTelemetrySnapshot = RetryCircuitTelemetrySnapshot(
        metricCounts = metricState.value,
        exporters = pipelines.map { it.snapshot.value },
    )

    /** Stops accepting new events and lets already-buffered exporter work finish. */
    public fun close() {
        pipelines.forEach(ExporterPipeline::close)
    }

    /** Waits for all exporter workers after [close]. */
    public suspend fun join() {
        pipelines.forEach { it.join() }
        supervisorJob.complete()
    }
}

private class ExporterPipeline(
    scope: CoroutineScope,
    configuration: RetryCircuitTelemetryConfiguration,
    private val exporter: RetryCircuitTelemetryExporter,
) {
    private val channel = Channel<RetryCircuitTelemetryEvent>(configuration.bufferCapacityPerExporter)
    private val mutableSnapshot = MutableStateFlow(
        RetryCircuitExporterSnapshot(
            exporterId = exporter.id,
            health = RetryCircuitExporterHealth.HEALTHY,
            acceptedCount = 0L,
            droppedCount = 0L,
            exportedCount = 0L,
            failureCount = 0L,
            timeoutCount = 0L,
            lastFailureReason = null,
        ),
    )
    val snapshot: StateFlow<RetryCircuitExporterSnapshot> = mutableSnapshot.asStateFlow()
    private val job: Job = scope.launch {
        for (event in channel) {
            deliver(event, configuration.exporterTimeout)
        }
        mutableSnapshot.update { current ->
            current.copy(health = RetryCircuitExporterHealth.STOPPED)
        }
    }

    fun offer(event: RetryCircuitTelemetryEvent): Boolean {
        val accepted = channel.trySend(event).isSuccess
        mutableSnapshot.update { current ->
            if (accepted) {
                current.copy(acceptedCount = incrementSaturated(current.acceptedCount))
            } else {
                current.copy(
                    health = RetryCircuitExporterHealth.DEGRADED,
                    droppedCount = incrementSaturated(current.droppedCount),
                )
            }
        }
        return accepted
    }

    fun close() {
        channel.close()
    }

    suspend fun join() {
        job.join()
    }

    private suspend fun deliver(
        event: RetryCircuitTelemetryEvent,
        timeout: SchedulingDelay,
    ) {
        try {
            val completed = withTimeoutOrNull(timeout.milliseconds) {
                exporter.export(event)
                true
            } ?: false
            mutableSnapshot.update { current ->
                if (completed) {
                    current.copy(
                        health = RetryCircuitExporterHealth.HEALTHY,
                        exportedCount = incrementSaturated(current.exportedCount),
                        lastFailureReason = null,
                    )
                } else {
                    current.copy(
                        health = RetryCircuitExporterHealth.DEGRADED,
                        timeoutCount = incrementSaturated(current.timeoutCount),
                        lastFailureReason = RetryCircuitExporterFailureReason.TIMEOUT,
                    )
                }
            }
        } catch (exception: Exception) {
            if (!currentCoroutineContext().isActive) throw exception
            mutableSnapshot.update { current ->
                current.copy(
                    health = RetryCircuitExporterHealth.DEGRADED,
                    failureCount = incrementSaturated(current.failureCount),
                    lastFailureReason = RetryCircuitExporterFailureReason.EXCEPTION,
                )
            }
        }
    }
}

private fun incrementSaturated(value: Long): Long =
    if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L

private const val MAXIMUM_EXPORTER_ID_LENGTH: Int = 96
private const val MAXIMUM_BUFFER_CAPACITY: Int = 10_000
private const val MAXIMUM_EXPORTER_COUNT: Int = 32
