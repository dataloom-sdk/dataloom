package io.dataloom.api.event

import io.dataloom.api.error.ErrorCode
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant

/** Append request whose canonical event identity must remain stable across retry and restart. */
public data class EventAppendRequest(
    public val envelope: OperationalEventEnvelope,
    public val orderingScope: EventOrderingScope,
    public val retention: EventRetentionWindow,
) {
    init {
        if (orderingScope is EventOrderingScope.Workflow) {
            require(envelope.workflowId == orderingScope.workflowId) {
                "Workflow append scope must equal envelope.workflowId."
            }
        }
    }
}

/** Stable rejection reason for an append that completed without persistence. */
public enum class EventAppendRejectionReason {
    EVENT_IDENTITY_CONFLICT,
    RETENTION_ALREADY_EXPIRED,
    CAPACITY_EXCEEDED,
    INVALID_RECORD,
}

/** Structured result of one atomic durable append. */
public sealed interface EventAppendResult {
    /** First append of this event identity. */
    public data class Appended(
        public val record: DurableEventRecord,
    ) : EventAppendResult

    /** Exact idempotent replay of an already-stored append request. */
    public data class AlreadyPresent(
        public val record: DurableEventRecord,
    ) : EventAppendResult

    /** Request rejected without mutation. */
    public data class Rejected(
        public val reason: EventAppendRejectionReason,
    ) : EventAppendResult

    /** Store operation failed without exposing provider or exception details. */
    public data class StoreFailure(
        public val errorCode: ErrorCode,
    ) : EventAppendResult
}

/**
 * Atomic acquisition request for one independent at-least-once consumer.
 *
 * A store may return at most one event per ordering scope in one result. This
 * prevents a slow exporter from observing later events in a workflow before an
 * earlier event has been acknowledged.
 */
public data class EventAcquireRequest(
    public val consumerId: EventConsumerId,
    public val leaseId: EventLeaseId,
    public val acquiredAt: DataLoomInstant,
    public val leaseDuration: SchedulingDelay,
    public val batchSize: EventBatchSize = EventBatchSize(100),
    public val filter: EventFilter = EventFilter.All,
) {
    init {
        require(leaseDuration.milliseconds > 0L) {
            "Event lease duration must be greater than zero."
        }
        require(acquiredAt.epochMilliseconds <= Long.MAX_VALUE - leaseDuration.milliseconds) {
            "Event lease expiration exceeds the supported instant range."
        }
    }

    public val leaseExpiresAtExclusive: DataLoomInstant
        get() = DataLoomInstant(acquiredAt.epochMilliseconds + leaseDuration.milliseconds)
}

/** Stable store failure stage for acquisition diagnostics. */
public enum class EventStoreFailureStage {
    APPEND,
    ACQUIRE,
    ACKNOWLEDGE,
    RELEASE,
    PURGE,
    QUERY,
}

/** Structured result of one atomic acquisition. */
public sealed interface EventAcquireResult {
    /** No matching unexpired event was available. */
    public data object NoEvents : EventAcquireResult

    /** Ordered, lease-protected deliveries. At most one delivery exists per scope. */
    public data class Events(
        public val deliveries: List<DurableEventDelivery>,
    ) : EventAcquireResult {
        init {
            val deliveryValues: List<DurableEventDelivery> = deliveries.toList()
            require(deliveryValues.isNotEmpty()) { "Acquired event deliveries must not be empty." }
            require(deliveryValues.size <= MAXIMUM_EVENT_BATCH_SIZE) {
                "Acquired event deliveries exceed the supported batch size."
            }
            require(deliveryValues.map { it.consumerId }.distinct().size == 1) {
                "Every acquired event must belong to the same consumer."
            }
            require(deliveryValues.map { it.leaseId }.distinct().size == 1) {
                "Every acquired event must use the same lease identity."
            }
            require(deliveryValues.map { it.orderingScope() }.distinct().size == deliveryValues.size) {
                "One atomic acquisition may contain at most one event per ordering scope."
            }
            require(deliveriesAreSequenceOrderedPerScope(deliveryValues)) {
                "Acquired event deliveries must be ordered by sequence inside each scope."
            }
        }
    }

    /** Store operation failed without leaking provider or exception details. */
    public data class StoreFailure(
        public val stage: EventStoreFailureStage,
        public val errorCode: ErrorCode,
    ) : EventAcquireResult
}

/** Lease-guarded acknowledgement request. */
public data class EventAcknowledgeRequest(
    public val consumerId: EventConsumerId,
    public val leaseId: EventLeaseId,
    public val eventIds: Set<io.dataloom.api.operational.OperationalEventId>,
    public val acknowledgedAt: DataLoomInstant,
) {
    init {
        require(eventIds.isNotEmpty()) { "Event acknowledgement must contain at least one event." }
        require(eventIds.size <= MAXIMUM_EVENT_BATCH_SIZE) {
            "Event acknowledgement exceeds the supported batch size."
        }
    }
}

/** Stable rejection reason for acknowledgement without mutation. */
public enum class EventAcknowledgeRejectionReason {
    EVENT_NOT_FOUND,
    LEASE_NOT_FOUND,
    LEASE_MISMATCH,
    LEASE_EXPIRED,
    ALREADY_ACKNOWLEDGED,
}

/** Structured result of one atomic acknowledgement. */
public sealed interface EventAcknowledgeResult {
    public data class Acknowledged(
        public val acknowledgedCount: Int,
    ) : EventAcknowledgeResult {
        init {
            require(acknowledgedCount > 0) { "Acknowledged count must be greater than zero." }
        }
    }

    public data class Rejected(
        public val reason: EventAcknowledgeRejectionReason,
    ) : EventAcknowledgeResult

    public data class StoreFailure(
        public val errorCode: ErrorCode,
    ) : EventAcknowledgeResult
}

/** Reason why an accepted delivery is made available for replay without acknowledgement. */
public enum class EventReleaseReason {
    EXPORTER_FAILED,
    EXPORTER_TIMED_OUT,
    BUFFER_OVERFLOW,
    WORKER_STOPPED,
}

/** Lease-guarded release request. */
public data class EventReleaseRequest(
    public val consumerId: EventConsumerId,
    public val leaseId: EventLeaseId,
    public val eventIds: Set<io.dataloom.api.operational.OperationalEventId>,
    public val releasedAt: DataLoomInstant,
    public val reason: EventReleaseReason,
) {
    init {
        require(eventIds.isNotEmpty()) { "Event release must contain at least one event." }
        require(eventIds.size <= MAXIMUM_EVENT_BATCH_SIZE) {
            "Event release exceeds the supported batch size."
        }
    }
}

/** Stable rejection reason for release without mutation. */
public enum class EventReleaseRejectionReason {
    EVENT_NOT_FOUND,
    LEASE_NOT_FOUND,
    LEASE_MISMATCH,
    LEASE_EXPIRED,
    ALREADY_ACKNOWLEDGED,
}

/** Structured result of one atomic release. */
public sealed interface EventReleaseResult {
    public data class Released(
        public val releasedCount: Int,
    ) : EventReleaseResult {
        init {
            require(releasedCount > 0) { "Released count must be greater than zero." }
        }
    }

    public data class Rejected(
        public val reason: EventReleaseRejectionReason,
    ) : EventReleaseResult

    public data class StoreFailure(
        public val errorCode: ErrorCode,
    ) : EventReleaseResult
}

/** Exact-boundary retention purge request. */
public data class EventPurgeRequest(
    public val now: DataLoomInstant,
    public val maximumRecords: EventPageSize = EventPageSize(1_000),
)

/** Structured purge result. */
public sealed interface EventPurgeResult {
    public data class Purged(
        public val purgedCount: Int,
    ) : EventPurgeResult {
        init {
            require(purgedCount >= 0) { "Purged count must not be negative." }
        }
    }

    public data class StoreFailure(
        public val errorCode: ErrorCode,
    ) : EventPurgeResult
}

/** Payload-free bounded query over durable operational state. */
public data class EventQueryRequest(
    public val now: DataLoomInstant,
    public val filter: EventFilter = EventFilter.All,
    public val pageSize: EventPageSize = EventPageSize(100),
    public val includeAcknowledged: Boolean = false,
)

/** Read-only summary of one durable event; payload and attributes are intentionally absent. */
public data class EventRecordSummary(
    public val type: io.dataloom.api.operational.OperationalEventType,
    public val source: io.dataloom.api.operational.OperationalEventSource,
    public val category: io.dataloom.api.operational.OperationalEventCategory,
    public val schemaVersion: io.dataloom.api.operational.OperationalSchemaVersion,
    public val occurredAt: DataLoomInstant,
    public val orderingScopeKind: EventOrderingScopeKind,
    public val sequence: EventSequence,
    public val expiresAtExclusive: DataLoomInstant,
    public val acknowledgedConsumerCount: Int,
    public val outstandingLeaseCount: Int,
) {
    init {
        require(acknowledgedConsumerCount >= 0) {
            "Acknowledged consumer count must not be negative."
        }
        require(outstandingLeaseCount >= 0) {
            "Outstanding lease count must not be negative."
        }
    }

    override fun toString(): String =
        "EventRecordSummary(" +
            "category=$category, " +
            "schemaVersion=$schemaVersion, " +
            "orderingScope=$orderingScopeKind, " +
            "sequence=$sequence, " +
            "acknowledgedConsumerCount=$acknowledgedConsumerCount, " +
            "outstandingLeaseCount=$outstandingLeaseCount" +
            ")"
}

/** Bounded payload-free query result. */
public sealed interface EventQueryResult {
    public data class Page(
        public val records: List<EventRecordSummary>,
        public val hasMore: Boolean,
    ) : EventQueryResult {
        init {
            require(records.size <= MAXIMUM_EVENT_PAGE_SIZE) {
                "Event query page exceeds the supported maximum size."
            }
        }
    }

    public data class StoreFailure(
        public val errorCode: ErrorCode,
    ) : EventQueryResult
}

/**
 * Provider-neutral durable event/outbox persistence boundary.
 *
 * Implementations own atomic sequence allocation, idempotent append, per-consumer
 * acknowledgement, replay leases, exact expiration, and bounded query behavior.
 * They must not export events, call synchronization providers, log payloads, or
 * change synchronization outcomes.
 */
public interface DurableEventStore {
    public suspend fun append(request: EventAppendRequest): EventAppendResult

    public suspend fun acquire(request: EventAcquireRequest): EventAcquireResult

    public suspend fun acknowledge(request: EventAcknowledgeRequest): EventAcknowledgeResult

    public suspend fun release(request: EventReleaseRequest): EventReleaseResult

    public suspend fun purgeExpired(request: EventPurgeRequest): EventPurgeResult

    public suspend fun query(request: EventQueryRequest): EventQueryResult
}

private fun DurableEventDelivery.orderingScope(): EventOrderingScope = record.orderingScope

private fun deliveriesAreSequenceOrderedPerScope(
    deliveries: List<DurableEventDelivery>,
): Boolean {
    val lastSequenceByScope: MutableMap<EventOrderingScope, Long> = mutableMapOf()
    deliveries.forEach { delivery: DurableEventDelivery ->
        val scope: EventOrderingScope = delivery.record.orderingScope
        val sequence: Long = delivery.record.sequence.value
        val previous: Long? = lastSequenceByScope[scope]
        if (previous != null && sequence <= previous) {
            return false
        }
        lastSequenceByScope[scope] = sequence
    }
    return true
}
