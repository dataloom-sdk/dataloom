package io.dataloom.runtime.observation

import io.dataloom.api.event.DurableEventDelivery
import io.dataloom.api.event.DurableEventStore
import io.dataloom.api.event.EventAcknowledgeRequest
import io.dataloom.api.event.EventAcknowledgeResult
import io.dataloom.api.event.EventAcquireRequest
import io.dataloom.api.event.EventAcquireResult
import io.dataloom.api.event.EventBatchSize
import io.dataloom.api.event.EventConsumerId
import io.dataloom.api.event.EventFilter
import io.dataloom.api.event.EventLeaseId
import io.dataloom.api.event.EventReleaseReason
import io.dataloom.api.event.EventReleaseRequest
import io.dataloom.api.event.EventReleaseResult
import io.dataloom.api.event.EventStoreFailureStage
import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.observation.DurableEventExporter
import io.dataloom.api.operational.OperationalEventCategory
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Explicit deterministic overflow policy for a durable exporter buffer. */
public enum class DurableEventOverflowPolicy {
    /** Preserve accepted order and release only the newest acquired submission. */
    RELEASE_LATEST,
}

/** Bounded delivery configuration shared by all exporter workers. */
public data class DurableEventDeliveryConfiguration(
    public val bufferCapacityPerExporter: Int = 256,
    public val acquisitionBatchSize: EventBatchSize = EventBatchSize(100),
    public val exporterTimeout: SchedulingDelay = SchedulingDelay(5_000L),
    public val leaseDuration: SchedulingDelay = SchedulingDelay(1_500_000L),
    public val overflowPolicy: DurableEventOverflowPolicy =
        DurableEventOverflowPolicy.RELEASE_LATEST,
) {
    init {
        require(bufferCapacityPerExporter in 1..MAXIMUM_EXPORTER_BUFFER_CAPACITY) {
            "Durable exporter buffer capacity must be between 1 and " +
                "$MAXIMUM_EXPORTER_BUFFER_CAPACITY."
        }
        require(exporterTimeout.milliseconds > 0L) {
            "Durable exporter timeout must be greater than zero."
        }
        require(leaseDuration.milliseconds > 0L) {
            "Durable exporter lease duration must be greater than zero."
        }
        val minimumLeaseDuration: Long = multiplySaturated(
            exporterTimeout.milliseconds,
            bufferCapacityPerExporter.toLong() + 1L,
        )
        require(leaseDuration.milliseconds >= minimumLeaseDuration) {
            "Durable exporter lease duration must cover the bounded worst-case queue wait."
        }
    }
}

/** Closed delivery signal taxonomy used for bounded-cardinality diagnostics. */
public enum class DurableEventDeliverySignal {
    ACQUIRED,
    ACQUIRE_EMPTY,
    ACQUIRE_FAILED,
    BUFFER_OVERFLOW,
    EXPORTED,
    EXPORT_FAILED,
    EXPORT_TIMED_OUT,
    ACKNOWLEDGED,
    ACKNOWLEDGE_FAILED,
    RELEASED,
    RELEASE_FAILED,
}

/** Fixed-cardinality metric key. Dynamic event, consumer, workflow, and tenant IDs are absent. */
public data class DurableEventDeliveryMetricKey(
    public val signal: DurableEventDeliverySignal,
    public val category: OperationalEventCategory?,
    public val redelivery: Boolean,
)

/** Stable exporter failure category without exception text or provider identity. */
public enum class DurableEventExporterFailureReason {
    EXCEPTION,
    TIMEOUT,
    ACKNOWLEDGE_FAILED,
    RELEASE_FAILED,
    STORE_FAILED,
}

/** Closed exporter health state. */
public enum class DurableEventExporterHealth {
    HEALTHY,
    DEGRADED,
    STOPPED,
}

/** Redacted point-in-time state for one isolated exporter worker. */
public data class DurableEventExporterSnapshot(
    public val consumerId: EventConsumerId,
    public val health: DurableEventExporterHealth,
    public val bufferedCount: Int,
    public val acceptedCount: Long,
    public val overflowCount: Long,
    public val exportedCount: Long,
    public val acknowledgedCount: Long,
    public val releasedCount: Long,
    public val failureCount: Long,
    public val timeoutCount: Long,
    public val lastFailureReason: DurableEventExporterFailureReason?,
) {
    init {
        require(bufferedCount >= 0) { "Buffered exporter count must not be negative." }
        require(
            listOf(
                acceptedCount,
                overflowCount,
                exportedCount,
                acknowledgedCount,
                releasedCount,
                failureCount,
                timeoutCount,
            ).all { it >= 0L },
        ) { "Exporter counters must not be negative." }
    }
}

/** Local read model for bounded delivery diagnostics. */
public data class DurableEventDeliverySnapshot(
    public val metrics: Map<DurableEventDeliveryMetricKey, Long>,
    public val exporters: List<DurableEventExporterSnapshot>,
) {
    public val degraded: Boolean
        get() = exporters.any { it.health != DurableEventExporterHealth.HEALTHY }
}

/** One exporter submission result with deterministic overflow evidence. */
public data class DurableEventExporterSubmissionResult(
    public val acquiredCount: Int,
    public val acceptedCount: Int,
    public val overflowCount: Int,
) {
    init {
        require(acquiredCount >= 0 && acceptedCount >= 0 && overflowCount >= 0) {
            "Submission counters must not be negative."
        }
        require(acceptedCount + overflowCount == acquiredCount) {
            "Every acquired event must be accepted or overflowed exactly once."
        }
    }
}

/** Structured result of one bounded store-to-exporter acquisition attempt. */
public sealed interface DurableEventPumpResult {
    public data object NoEvents : DurableEventPumpResult

    public data class Submitted(
        public val result: DurableEventExporterSubmissionResult,
    ) : DurableEventPumpResult

    public data class StoreFailure(
        public val stage: EventStoreFailureStage,
    ) : DurableEventPumpResult

    public data object InfrastructureFailure : DurableEventPumpResult
}

/**
 * Bounded, isolated, at-least-once delivery coordinator.
 *
 * Each exporter owns one finite channel and one supervised worker. [pump] acquires
 * only for the requested exporter and never invokes that exporter on the caller's
 * coroutine. Slow or failing exporters cannot change synchronization results or
 * block another exporter worker.
 */
public class BoundedDurableEventDelivery(
    coroutineContext: CoroutineContext,
    private val store: DurableEventStore,
    private val clock: DataLoomClock,
    private val leaseIdGenerator: IdentifierGenerator<EventLeaseId>,
    private val configuration: DurableEventDeliveryConfiguration,
    exporters: List<DurableEventExporter>,
) {
    private val supervisorJob: CompletableJob = SupervisorJob(coroutineContext[Job])
    private val metrics = MutableStateFlow<Map<DurableEventDeliveryMetricKey, Long>>(emptyMap())
    private val pipelines: Map<EventConsumerId, DurableExporterPipeline>

    init {
        require(exporters.size <= MAXIMUM_EXPORTER_COUNT) {
            "Bounded durable event delivery supports at most $MAXIMUM_EXPORTER_COUNT exporters."
        }
        require(exporters.map { it.consumerId }.distinct().size == exporters.size) {
            "Durable event exporter consumer IDs must be unique."
        }
        val scope = CoroutineScope(coroutineContext + supervisorJob)
        pipelines = exporters.associate { exporter: DurableEventExporter ->
            exporter.consumerId to DurableExporterPipeline(
                scope = scope,
                store = store,
                clock = clock,
                exporter = exporter,
                configuration = configuration,
                recordMetric = ::recordMetric,
            )
        }
    }

    /**
     * Atomically acquires and submits work for one exporter without calling that exporter inline.
     *
     * A filter is stable consumer configuration and should not be changed between calls for the
     * same [consumerId]. Changing it can intentionally hide earlier unacknowledged events.
     */
    public suspend fun pump(
        consumerId: EventConsumerId,
        filter: EventFilter = EventFilter.All,
    ): DurableEventPumpResult {
        val pipeline: DurableExporterPipeline = pipelines[consumerId]
            ?: return DurableEventPumpResult.InfrastructureFailure
        val acquiredAt = try {
            clock.now()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordMetric(DurableEventDeliverySignal.ACQUIRE_FAILED, null, false)
            pipeline.markStoreFailure()
            return DurableEventPumpResult.InfrastructureFailure
        }
        val leaseId = try {
            leaseIdGenerator.generate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordMetric(DurableEventDeliverySignal.ACQUIRE_FAILED, null, false)
            pipeline.markStoreFailure()
            return DurableEventPumpResult.InfrastructureFailure
        }
        val acquireResult: EventAcquireResult = try {
            store.acquire(
                EventAcquireRequest(
                    consumerId = consumerId,
                    leaseId = leaseId,
                    acquiredAt = acquiredAt,
                    leaseDuration = configuration.leaseDuration,
                    batchSize = configuration.acquisitionBatchSize,
                    filter = filter,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordMetric(DurableEventDeliverySignal.ACQUIRE_FAILED, null, false)
            pipeline.markStoreFailure()
            return DurableEventPumpResult.InfrastructureFailure
        }

        return when (acquireResult) {
            EventAcquireResult.NoEvents -> {
                recordMetric(DurableEventDeliverySignal.ACQUIRE_EMPTY, null, false)
                DurableEventPumpResult.NoEvents
            }
            is EventAcquireResult.StoreFailure -> {
                recordMetric(DurableEventDeliverySignal.ACQUIRE_FAILED, null, false)
                pipeline.markStoreFailure()
                DurableEventPumpResult.StoreFailure(acquireResult.stage)
            }
            is EventAcquireResult.Events -> submitAcquired(pipeline, acquireResult.deliveries)
        }
    }

    public fun snapshot(): DurableEventDeliverySnapshot = DurableEventDeliverySnapshot(
        metrics = metrics.value,
        exporters = pipelines.values.map { it.snapshot.value },
    )

    /** Stops new submissions and drains already-buffered deliveries. */
    public fun close() {
        pipelines.values.forEach(DurableExporterPipeline::close)
    }

    /** Waits for every isolated exporter worker after [close]. */
    public suspend fun join() {
        pipelines.values.forEach { it.join() }
        supervisorJob.complete()
    }

    override fun toString(): String =
        "BoundedDurableEventDelivery(exporterCount=${pipelines.size})"

    private suspend fun submitAcquired(
        pipeline: DurableExporterPipeline,
        deliveries: List<DurableEventDelivery>,
    ): DurableEventPumpResult {
        var accepted: Int = 0
        val overflowed: MutableList<DurableEventDelivery> = mutableListOf()
        deliveries.forEach { delivery: DurableEventDelivery ->
            recordMetric(
                DurableEventDeliverySignal.ACQUIRED,
                delivery.record.envelope.category,
                delivery.isRedelivery,
            )
            if (pipeline.offer(delivery)) {
                accepted += 1
            } else {
                overflowed += delivery
                recordMetric(
                    DurableEventDeliverySignal.BUFFER_OVERFLOW,
                    delivery.record.envelope.category,
                    delivery.isRedelivery,
                )
            }
        }
        if (overflowed.isNotEmpty()) {
            releaseOverflow(pipeline, overflowed)
        }
        return DurableEventPumpResult.Submitted(
            DurableEventExporterSubmissionResult(
                acquiredCount = deliveries.size,
                acceptedCount = accepted,
                overflowCount = overflowed.size,
            ),
        )
    }

    private suspend fun releaseOverflow(
        pipeline: DurableExporterPipeline,
        deliveries: List<DurableEventDelivery>,
    ) {
        val releasedAt = try {
            clock.now()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pipeline.markReleaseFailure()
            deliveries.forEach { delivery: DurableEventDelivery ->
                recordMetric(
                    DurableEventDeliverySignal.RELEASE_FAILED,
                    delivery.record.envelope.category,
                    delivery.isRedelivery,
                )
            }
            return
        }
        val result: EventReleaseResult = try {
            store.release(
                EventReleaseRequest(
                    consumerId = pipeline.consumerId,
                    leaseId = deliveries.first().leaseId,
                    eventIds = deliveries.mapTo(linkedSetOf()) { it.record.envelope.id },
                    releasedAt = releasedAt,
                    reason = EventReleaseReason.BUFFER_OVERFLOW,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pipeline.markReleaseFailure()
            deliveries.forEach { delivery: DurableEventDelivery ->
                recordMetric(
                    DurableEventDeliverySignal.RELEASE_FAILED,
                    delivery.record.envelope.category,
                    delivery.isRedelivery,
                )
            }
            return
        }
        when (result) {
            is EventReleaseResult.Released -> {
                pipeline.markReleased(result.releasedCount)
                deliveries.forEach { delivery: DurableEventDelivery ->
                    recordMetric(
                        DurableEventDeliverySignal.RELEASED,
                        delivery.record.envelope.category,
                        delivery.isRedelivery,
                    )
                }
            }
            is EventReleaseResult.Rejected,
            is EventReleaseResult.StoreFailure,
            -> {
                pipeline.markReleaseFailure()
                deliveries.forEach { delivery: DurableEventDelivery ->
                    recordMetric(
                        DurableEventDeliverySignal.RELEASE_FAILED,
                        delivery.record.envelope.category,
                        delivery.isRedelivery,
                    )
                }
            }
        }
    }

    private fun recordMetric(
        signal: DurableEventDeliverySignal,
        category: OperationalEventCategory?,
        redelivery: Boolean,
    ) {
        val key = DurableEventDeliveryMetricKey(signal, category, redelivery)
        metrics.update { current: Map<DurableEventDeliveryMetricKey, Long> ->
            val count: Long = current[key] ?: 0L
            current + (key to incrementSaturated(count))
        }
    }
}

private class DurableExporterPipeline(
    scope: CoroutineScope,
    private val store: DurableEventStore,
    private val clock: DataLoomClock,
    private val exporter: DurableEventExporter,
    private val configuration: DurableEventDeliveryConfiguration,
    private val recordMetric: (DurableEventDeliverySignal, OperationalEventCategory?, Boolean) -> Unit,
) {
    private val channel = Channel<DurableEventDelivery>(configuration.bufferCapacityPerExporter)
    private val mutableSnapshot = MutableStateFlow(
        DurableEventExporterSnapshot(
            consumerId = exporter.consumerId,
            health = DurableEventExporterHealth.HEALTHY,
            bufferedCount = 0,
            acceptedCount = 0L,
            overflowCount = 0L,
            exportedCount = 0L,
            acknowledgedCount = 0L,
            releasedCount = 0L,
            failureCount = 0L,
            timeoutCount = 0L,
            lastFailureReason = null,
        ),
    )
    val snapshot: MutableStateFlow<DurableEventExporterSnapshot> = mutableSnapshot
    val consumerId: EventConsumerId
        get() = exporter.consumerId

    private val job: Job = scope.launch {
        for (delivery: DurableEventDelivery in channel) {
            mutableSnapshot.update { current: DurableEventExporterSnapshot ->
                current.copy(bufferedCount = (current.bufferedCount - 1).coerceAtLeast(0))
            }
            deliver(delivery)
        }
        mutableSnapshot.update { current: DurableEventExporterSnapshot ->
            current.copy(health = DurableEventExporterHealth.STOPPED)
        }
    }

    fun offer(delivery: DurableEventDelivery): Boolean {
        val accepted: Boolean = channel.trySend(delivery).isSuccess
        mutableSnapshot.update { current: DurableEventExporterSnapshot ->
            if (accepted) {
                current.copy(
                    bufferedCount = current.bufferedCount + 1,
                    acceptedCount = incrementSaturated(current.acceptedCount),
                )
            } else {
                current.copy(
                    health = DurableEventExporterHealth.DEGRADED,
                    overflowCount = incrementSaturated(current.overflowCount),
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

    fun markStoreFailure() {
        mutableSnapshot.update { current: DurableEventExporterSnapshot ->
            current.copy(
                health = DurableEventExporterHealth.DEGRADED,
                failureCount = incrementSaturated(current.failureCount),
                lastFailureReason = DurableEventExporterFailureReason.STORE_FAILED,
            )
        }
    }

    fun markReleaseFailure() {
        mutableSnapshot.update { current: DurableEventExporterSnapshot ->
            current.copy(
                health = DurableEventExporterHealth.DEGRADED,
                failureCount = incrementSaturated(current.failureCount),
                lastFailureReason = DurableEventExporterFailureReason.RELEASE_FAILED,
            )
        }
    }

    fun markReleased(count: Int) {
        mutableSnapshot.update { current: DurableEventExporterSnapshot ->
            current.copy(
                releasedCount = addSaturated(current.releasedCount, count.toLong()),
            )
        }
    }

    private suspend fun deliver(delivery: DurableEventDelivery) {
        val failureReason: EventReleaseReason? = try {
            val exported: Boolean = withTimeoutOrNull(configuration.exporterTimeout.milliseconds) {
                exporter.export(delivery.record.envelope)
                true
            } ?: false
            if (exported) {
                mutableSnapshot.update { current: DurableEventExporterSnapshot ->
                    current.copy(
                        health = DurableEventExporterHealth.HEALTHY,
                        exportedCount = incrementSaturated(current.exportedCount),
                        lastFailureReason = null,
                    )
                }
                recordMetric(
                    DurableEventDeliverySignal.EXPORTED,
                    delivery.record.envelope.category,
                    delivery.isRedelivery,
                )
                acknowledge(delivery)
                null
            } else {
                mutableSnapshot.update { current: DurableEventExporterSnapshot ->
                    current.copy(
                        health = DurableEventExporterHealth.DEGRADED,
                        timeoutCount = incrementSaturated(current.timeoutCount),
                        lastFailureReason = DurableEventExporterFailureReason.TIMEOUT,
                    )
                }
                recordMetric(
                    DurableEventDeliverySignal.EXPORT_TIMED_OUT,
                    delivery.record.envelope.category,
                    delivery.isRedelivery,
                )
                EventReleaseReason.EXPORTER_TIMED_OUT
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw cancelled
            }
            mutableSnapshot.update { current: DurableEventExporterSnapshot ->
                current.copy(
                    health = DurableEventExporterHealth.DEGRADED,
                    failureCount = incrementSaturated(current.failureCount),
                    lastFailureReason = DurableEventExporterFailureReason.EXCEPTION,
                )
            }
            recordMetric(
                DurableEventDeliverySignal.EXPORT_FAILED,
                delivery.record.envelope.category,
                delivery.isRedelivery,
            )
            EventReleaseReason.EXPORTER_FAILED
        } catch (_: Exception) {
            mutableSnapshot.update { current: DurableEventExporterSnapshot ->
                current.copy(
                    health = DurableEventExporterHealth.DEGRADED,
                    failureCount = incrementSaturated(current.failureCount),
                    lastFailureReason = DurableEventExporterFailureReason.EXCEPTION,
                )
            }
            recordMetric(
                DurableEventDeliverySignal.EXPORT_FAILED,
                delivery.record.envelope.category,
                delivery.isRedelivery,
            )
            EventReleaseReason.EXPORTER_FAILED
        }

        if (failureReason != null) {
            release(delivery, failureReason)
        }
    }

    private suspend fun acknowledge(delivery: DurableEventDelivery) {
        val acknowledgedAt = try {
            clock.now()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markAcknowledgeFailure()
            recordMetric(
                DurableEventDeliverySignal.ACKNOWLEDGE_FAILED,
                delivery.record.envelope.category,
                delivery.isRedelivery,
            )
            return
        }
        val result: EventAcknowledgeResult = try {
            store.acknowledge(
                EventAcknowledgeRequest(
                    consumerId = delivery.consumerId,
                    leaseId = delivery.leaseId,
                    eventIds = setOf(delivery.record.envelope.id),
                    acknowledgedAt = acknowledgedAt,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markAcknowledgeFailure()
            recordMetric(
                DurableEventDeliverySignal.ACKNOWLEDGE_FAILED,
                delivery.record.envelope.category,
                delivery.isRedelivery,
            )
            return
        }
        when (result) {
            is EventAcknowledgeResult.Acknowledged -> {
                mutableSnapshot.update { current: DurableEventExporterSnapshot ->
                    current.copy(
                        acknowledgedCount = addSaturated(
                            current.acknowledgedCount,
                            result.acknowledgedCount.toLong(),
                        ),
                    )
                }
                recordMetric(
                    DurableEventDeliverySignal.ACKNOWLEDGED,
                    delivery.record.envelope.category,
                    delivery.isRedelivery,
                )
            }
            is EventAcknowledgeResult.Rejected,
            is EventAcknowledgeResult.StoreFailure,
            -> {
                markAcknowledgeFailure()
                recordMetric(
                    DurableEventDeliverySignal.ACKNOWLEDGE_FAILED,
                    delivery.record.envelope.category,
                    delivery.isRedelivery,
                )
            }
        }
    }

    private suspend fun release(
        delivery: DurableEventDelivery,
        reason: EventReleaseReason,
    ) {
        val releasedAt = try {
            clock.now()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markReleaseFailure()
            recordMetric(
                DurableEventDeliverySignal.RELEASE_FAILED,
                delivery.record.envelope.category,
                delivery.isRedelivery,
            )
            return
        }
        val result: EventReleaseResult = try {
            store.release(
                EventReleaseRequest(
                    consumerId = delivery.consumerId,
                    leaseId = delivery.leaseId,
                    eventIds = setOf(delivery.record.envelope.id),
                    releasedAt = releasedAt,
                    reason = reason,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markReleaseFailure()
            recordMetric(
                DurableEventDeliverySignal.RELEASE_FAILED,
                delivery.record.envelope.category,
                delivery.isRedelivery,
            )
            return
        }
        when (result) {
            is EventReleaseResult.Released -> {
                markReleased(result.releasedCount)
                recordMetric(
                    DurableEventDeliverySignal.RELEASED,
                    delivery.record.envelope.category,
                    delivery.isRedelivery,
                )
            }
            is EventReleaseResult.Rejected,
            is EventReleaseResult.StoreFailure,
            -> {
                markReleaseFailure()
                recordMetric(
                    DurableEventDeliverySignal.RELEASE_FAILED,
                    delivery.record.envelope.category,
                    delivery.isRedelivery,
                )
            }
        }
    }

    private fun markAcknowledgeFailure() {
        mutableSnapshot.update { current: DurableEventExporterSnapshot ->
            current.copy(
                health = DurableEventExporterHealth.DEGRADED,
                failureCount = incrementSaturated(current.failureCount),
                lastFailureReason = DurableEventExporterFailureReason.ACKNOWLEDGE_FAILED,
            )
        }
    }
}

private fun incrementSaturated(value: Long): Long =
    if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L

private fun addSaturated(value: Long, addition: Long): Long =
    if (addition <= 0L || value == Long.MAX_VALUE) {
        value
    } else if (value > Long.MAX_VALUE - addition) {
        Long.MAX_VALUE
    } else {
        value + addition
    }

private fun multiplySaturated(left: Long, right: Long): Long =
    if (left == 0L || right == 0L) {
        0L
    } else if (left > Long.MAX_VALUE / right) {
        Long.MAX_VALUE
    } else {
        left * right
    }

private const val MAXIMUM_EXPORTER_BUFFER_CAPACITY: Int = 10_000
private const val MAXIMUM_EXPORTER_COUNT: Int = 32
