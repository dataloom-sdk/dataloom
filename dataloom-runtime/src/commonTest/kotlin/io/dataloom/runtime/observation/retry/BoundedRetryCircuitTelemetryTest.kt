package io.dataloom.runtime.observation.retry

import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.scheduling.ExistingSchedulePolicy
import io.dataloom.api.scheduling.ScheduleConstraints
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.synchronization.SynchronizationSummary
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.RetryOrchestrationStatus
import io.dataloom.runtime.retry.RetrySchedulingConfiguration
import io.dataloom.runtime.retry.SynchronizationRetryOrchestrator
import io.dataloom.runtime.retry.SynchronizationRetryRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class BoundedRetryCircuitTelemetryTest {
    @Test
    fun fullExporterBufferDropsNewestWithoutBlockingProducer() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val exporter = object : RetryCircuitTelemetryExporter {
            override val id = RetryCircuitTelemetryExporterId("slow")

            override suspend fun export(event: RetryCircuitTelemetryEvent) {
                started.complete(Unit)
                release.await()
            }
        }
        val telemetry = BoundedRetryCircuitTelemetry(
            coroutineContext = coroutineContext,
            configuration = RetryCircuitTelemetryConfiguration(
                bufferCapacityPerExporter = 1,
                exporterTimeout = SchedulingDelay(10_000L),
            ),
            exporters = listOf(exporter),
        )

        assertEquals(1, telemetry.record(event(1L)).acceptedExporterCount)
        runCurrent()
        started.await()
        assertEquals(1, telemetry.record(event(2L)).acceptedExporterCount)
        assertEquals(1, telemetry.record(event(3L)).droppedExporterCount)

        val saturated = telemetry.snapshot().exporters.single()
        assertEquals(2L, saturated.acceptedCount)
        assertEquals(1L, saturated.droppedCount)
        assertEquals(RetryCircuitExporterHealth.DEGRADED, saturated.health)

        release.complete(Unit)
        telemetry.close()
        advanceUntilIdle()
        telemetry.join()
        assertEquals(2L, telemetry.snapshot().exporters.single().exportedCount)
    }

    @Test
    fun failingExporterIsIsolatedFromHealthyExporter() = runTest {
        val delivered = mutableListOf<RetryCircuitTelemetryEvent>()
        val telemetry = BoundedRetryCircuitTelemetry(
            coroutineContext = coroutineContext,
            configuration = RetryCircuitTelemetryConfiguration(),
            exporters = listOf(
                object : RetryCircuitTelemetryExporter {
                    override val id = RetryCircuitTelemetryExporterId("failing")

                    override suspend fun export(event: RetryCircuitTelemetryEvent) {
                        throw IllegalStateException("Exporter test failure with sensitive-looking text.")
                    }
                },
                object : RetryCircuitTelemetryExporter {
                    override val id = RetryCircuitTelemetryExporterId("healthy")

                    override suspend fun export(event: RetryCircuitTelemetryEvent) {
                        delivered += event
                    }
                },
            ),
        )

        val event = event(4L)
        assertEquals(2, telemetry.record(event).acceptedExporterCount)
        runCurrent()

        assertEquals(listOf(event), delivered)
        val snapshots = telemetry.snapshot().exporters.associateBy { it.exporterId.value }
        assertEquals(1L, snapshots.getValue("failing").failureCount)
        assertEquals(RetryCircuitExporterFailureReason.EXCEPTION, snapshots.getValue("failing").lastFailureReason)
        assertEquals(1L, snapshots.getValue("healthy").exportedCount)
        assertEquals(RetryCircuitExporterHealth.HEALTHY, snapshots.getValue("healthy").health)
        telemetry.close()
        runCurrent()
        telemetry.join()
    }

    @Test
    fun exporterTimeoutIsBoundedAndReportedWithoutExceptionText() = runTest {
        val telemetry = BoundedRetryCircuitTelemetry(
            coroutineContext = coroutineContext,
            configuration = RetryCircuitTelemetryConfiguration(
                exporterTimeout = SchedulingDelay(100L),
            ),
            exporters = listOf(
                object : RetryCircuitTelemetryExporter {
                    override val id = RetryCircuitTelemetryExporterId("timeout")

                    override suspend fun export(event: RetryCircuitTelemetryEvent) {
                        awaitCancellation()
                    }
                },
            ),
        )

        telemetry.record(event(5L))
        runCurrent()
        advanceTimeBy(100L)
        runCurrent()

        val snapshot = telemetry.snapshot().exporters.single()
        assertEquals(1L, snapshot.timeoutCount)
        assertEquals(RetryCircuitExporterFailureReason.TIMEOUT, snapshot.lastFailureReason)
        assertTrue(telemetry.snapshot().degraded)
        telemetry.close()
        runCurrent()
        telemetry.join()
    }

    @Test
    fun metricDimensionsRemainBoundedAcrossDynamicCorrelationValues() = runTest {
        val telemetry = BoundedRetryCircuitTelemetry(
            coroutineContext = coroutineContext,
            configuration = RetryCircuitTelemetryConfiguration(),
            exporters = emptyList(),
        )

        repeat(1_000) { index ->
            telemetry.record(
                event(
                    time = index.toLong(),
                    context = RetryCircuitTelemetryContext(
                        workflowId = WorkflowId("workflow-$index"),
                        tenantId = TenantId("tenant-$index"),
                        correlationId = CorrelationId("correlation-$index"),
                        traceId = TraceId("trace-$index"),
                    ),
                ),
            )
        }

        val counts = telemetry.snapshot().metricCounts
        assertEquals(1, counts.size)
        assertEquals(1_000L, counts.values.single())
        telemetry.close()
        telemetry.join()
    }

    @Test
    fun structuredLogAndTraceAdaptersPreserveFixedRedactedCorrelationSchema() = runTest {
        val logs = mutableListOf<RetryCircuitStructuredLogRecord>()
        val traces = mutableListOf<RetryCircuitTraceSignal>()
        val telemetry = BoundedRetryCircuitTelemetry(
            coroutineContext = coroutineContext,
            configuration = RetryCircuitTelemetryConfiguration(),
            exporters = listOf(
                RetryCircuitStructuredLogExporter(
                    id = RetryCircuitTelemetryExporterId("log"),
                    sink = object : RetryCircuitStructuredLogSink {
                        override suspend fun write(record: RetryCircuitStructuredLogRecord) {
                            logs += record
                        }
                    },
                ),
                RetryCircuitTraceExporter(
                    id = RetryCircuitTelemetryExporterId("trace"),
                    sink = object : RetryCircuitTraceSink {
                        override suspend fun record(signal: RetryCircuitTraceSignal) {
                            traces += signal
                        }
                    },
                ),
            ),
        )
        val event = event(
            time = 7L,
            context = RetryCircuitTelemetryContext(
                workflowId = WorkflowId("workflow"),
                tenantId = TenantId("tenant"),
                correlationId = CorrelationId("correlation"),
                traceId = TraceId("trace"),
            ),
        )

        telemetry.record(event)
        runCurrent()

        val log = logs.single()
        assertEquals(1, log.schemaVersion)
        assertEquals(RetryCircuitLogSeverity.INFO, log.severity)
        assertEquals(event.signal, log.signal)
        assertEquals(event.context.traceId, log.traceId)
        assertEquals(event.retryAttempt?.number, log.retryAttemptNumber)
        val trace = traces.single()
        assertEquals(event.context.traceId, trace.traceId)
        assertEquals(event.context.correlationId, trace.correlationId)
        assertEquals(event.signal, trace.signal)
        telemetry.close()
        runCurrent()
        telemetry.join()
    }

    @Test
    fun observedRetryPreservesExactResultAndExportsCorrelation() = runTest {
        val events = mutableListOf<RetryCircuitTelemetryEvent>()
        val telemetry = BoundedRetryCircuitTelemetry(
            coroutineContext = coroutineContext,
            configuration = RetryCircuitTelemetryConfiguration(),
            exporters = listOf(recordingExporter(events)),
        )
        val clock = object : DataLoomClock {
            override fun now(): DataLoomInstant = DataLoomInstant(9_000L)
        }
        val request = synchronizationRequest()
        val orchestrator = SynchronizationRetryOrchestrator(
            retryPolicy = object : RetryPolicy {
                override val id = RetryPolicyId("unused")

                override fun evaluate(request: RetryEvaluationRequest): RetryDecision =
                    error("Successful results must not evaluate retry policy.")
            },
            schedulerProvider = null,
            configuration = RetrySchedulingConfiguration(
                constraints = ScheduleConstraints(),
                existingSchedulePolicy = ExistingSchedulePolicy.KEEP,
            ),
        )
        val observed = ObservedSynchronizationRetryOrchestrator(orchestrator, clock, telemetry)
        val retryRequest = SynchronizationRetryRequest(
            synchronizationRequest = request,
            synchronizationResult = SynchronizationResult.Succeeded(
                request = request,
                completedAt = DataLoomInstant(8_000L),
                summary = SynchronizationSummary(),
            ),
            retryOperation = RetryOperation("transport.push"),
            retryAttempt = RetryAttempt(1),
            scheduleId = ScheduleId("schedule-1"),
        )

        val result = observed.evaluateAndSchedule(retryRequest)
        runCurrent()

        assertEquals(RetryOrchestrationStatus.NOT_REQUIRED, result.status)
        val event = events.single()
        assertEquals(RetryCircuitTelemetrySignal.RETRY_NOT_REQUIRED, event.signal)
        assertEquals(DataLoomInstant(9_000L), event.occurredAt)
        assertEquals(request.workflowId, event.context.workflowId)
        assertEquals(request.context.tenantId, event.context.tenantId)
        assertEquals(request.context.correlationId, event.context.correlationId)
        assertEquals(request.context.traceId, event.context.traceId)
        assertSame(retryRequest.retryAttempt, event.retryAttempt)
        assertFalse(telemetry.snapshot().degraded)
        telemetry.close()
        runCurrent()
        telemetry.join()
    }

    private fun event(
        time: Long,
        context: RetryCircuitTelemetryContext = RetryCircuitTelemetryContext(),
    ): RetryCircuitTelemetryEvent = RetryCircuitTelemetryEvent(
        signal = RetryCircuitTelemetrySignal.RETRY_SCHEDULED,
        occurredAt = DataLoomInstant(time),
        context = context,
        retryAttempt = RetryAttempt(1),
        selectedDelay = SchedulingDelay(10L),
    )

    private fun synchronizationRequest(): SynchronizationRequest = SynchronizationRequest(
        workflowId = WorkflowId("workflow"),
        sessionId = SynchronizationSessionId("session"),
        direction = SynchronizationDirection.PUSH,
        mode = SynchronizationMode.DELTA,
        context = ExecutionContext(
            executionId = ExecutionId("execution"),
            correlationId = CorrelationId("correlation"),
            traceId = TraceId("trace"),
            tenantId = TenantId("tenant"),
        ),
    )

    private fun recordingExporter(
        events: MutableList<RetryCircuitTelemetryEvent>,
    ): RetryCircuitTelemetryExporter = object : RetryCircuitTelemetryExporter {
        override val id = RetryCircuitTelemetryExporterId("recording")

        override suspend fun export(event: RetryCircuitTelemetryEvent) {
            events += event
        }
    }
}
