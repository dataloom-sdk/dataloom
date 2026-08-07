package io.dataloom.api.event

import kotlin.jvm.JvmInline

/** Stable identifier for an ordered operational event stream without a workflow. */
@JvmInline
public value class EventStreamId(public val value: String) {
    init {
        require(value.isEventToken()) { "EventStreamId must be a bounded ASCII token." }
    }

    override fun toString(): String = value
}

/** Stable identity for one independent durable event consumer. */
@JvmInline
public value class EventConsumerId(public val value: String) {
    init {
        require(value.isEventToken()) { "EventConsumerId must be a bounded ASCII token." }
    }

    override fun toString(): String = value
}

/** Stable identity for one atomic acquisition lease. */
@JvmInline
public value class EventLeaseId(public val value: String) {
    init {
        require(value.isEventToken()) { "EventLeaseId must be a bounded ASCII token." }
    }

    override fun toString(): String = value
}

/** Positive authoritative sequence inside exactly one [EventOrderingScope]. */
@JvmInline
public value class EventSequence(public val value: Long) {
    init {
        require(value > 0L) { "EventSequence must be greater than zero." }
    }

    override fun toString(): String = value.toString()
}

/** Positive persistent delivery attempt for one event and one consumer. */
@JvmInline
public value class EventDeliveryAttempt(public val value: Int) {
    init {
        require(value > 0) { "EventDeliveryAttempt must be greater than zero." }
    }

    override fun toString(): String = value.toString()
}

/** Bounded number of events requested from one atomic acquisition. */
@JvmInline
public value class EventBatchSize(public val value: Int) {
    init {
        require(value in 1..MAXIMUM_EVENT_BATCH_SIZE) {
            "EventBatchSize must be between 1 and $MAXIMUM_EVENT_BATCH_SIZE."
        }
    }

    override fun toString(): String = value.toString()
}

/** Bounded number of records requested through the read-only operations boundary. */
@JvmInline
public value class EventPageSize(public val value: Int) {
    init {
        require(value in 1..MAXIMUM_EVENT_PAGE_SIZE) {
            "EventPageSize must be between 1 and $MAXIMUM_EVENT_PAGE_SIZE."
        }
    }

    override fun toString(): String = value.toString()
}

internal const val MAXIMUM_EVENT_BATCH_SIZE: Int = 1_000
internal const val MAXIMUM_EVENT_PAGE_SIZE: Int = 1_000
private const val MAXIMUM_EVENT_TOKEN_LENGTH: Int = 128

private fun String.isEventToken(): Boolean =
    length in 1..MAXIMUM_EVENT_TOKEN_LENGTH && all { character: Char ->
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '.' ||
            character == '_' ||
            character == '-' ||
            character == ':' ||
            character == '/'
    }
