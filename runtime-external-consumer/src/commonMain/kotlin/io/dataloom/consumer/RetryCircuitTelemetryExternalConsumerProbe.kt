package io.dataloom.consumer

import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.observation.retry.BoundedRetryCircuitTelemetry
import io.dataloom.runtime.observation.retry.RetryCircuitStructuredLogExporter
import io.dataloom.runtime.observation.retry.RetryCircuitStructuredLogRecord
import io.dataloom.runtime.observation.retry.RetryCircuitStructuredLogSink
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetryConfiguration
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetryContext
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetryEvent
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetryExporterId
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetryRecordResult
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetrySignal
import io.dataloom.runtime.observation.retry.RetryCircuitTelemetrySnapshot
import kotlin.coroutines.CoroutineContext

/** Compiles public retry/circuit telemetry from a separate common consumer. */
public class RetryCircuitTelemetryExternalConsumerProbe(
    coroutineContext: CoroutineContext,
    sink: RetryCircuitStructuredLogSink,
) {
    private val telemetry = BoundedRetryCircuitTelemetry(
        coroutineContext = coroutineContext,
        configuration = RetryCircuitTelemetryConfiguration(
            bufferCapacityPerExporter = 64,
            exporterTimeout = SchedulingDelay(1_000L),
        ),
        exporters = listOf(
            RetryCircuitStructuredLogExporter(
                id = RetryCircuitTelemetryExporterId("consumer-log"),
                sink = sink,
            ),
        ),
    )

    public fun record(
        occurredAt: DataLoomInstant,
    ): RetryCircuitTelemetryRecordResult = telemetry.record(
        RetryCircuitTelemetryEvent(
            signal = RetryCircuitTelemetrySignal.RETRY_SCHEDULED,
            occurredAt = occurredAt,
            context = RetryCircuitTelemetryContext(
                workflowId = WorkflowId("consumer-workflow"),
                correlationId = CorrelationId("consumer-correlation"),
                traceId = TraceId("consumer-trace"),
            ),
            retryAttempt = RetryAttempt(1),
            selectedDelay = SchedulingDelay(100L),
        ),
    )

    public fun snapshot(): RetryCircuitTelemetrySnapshot = telemetry.snapshot()

    public fun close() {
        telemetry.close()
    }
}

/** Verifies the structured sink contract remains implementable externally. */
public class RecordingRetryCircuitStructuredLogSink : RetryCircuitStructuredLogSink {
    public var latest: RetryCircuitStructuredLogRecord? = null
        private set

    override suspend fun write(record: RetryCircuitStructuredLogRecord) {
        latest = record
    }
}
