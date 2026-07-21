package io.dataloom.api.identifier

/**
 * Canonical identifier for a workflow.
 *
 * Ownership: DataLoom runtime or host integration.
 */
@JvmInline
public value class WorkflowId(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "WorkflowId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a synchronization session.
 *
 * Ownership: DataLoom runtime or host integration.
 */
@JvmInline
public value class SynchronizationSessionId(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "SynchronizationSessionId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a single change event.
 *
 * Ownership: change producer.
 */
@JvmInline
public value class ChangeEventId(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ChangeEventId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a change set.
 *
 * Ownership: change-set producer.
 */
@JvmInline
public value class ChangeSetId(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ChangeSetId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a domain entity.
 *
 * Ownership: host application or domain model.
 */
@JvmInline
public value class EntityId(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "EntityId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical type identifier for a domain entity.
 *
 * Ownership: host application or domain model.
 */
@JvmInline
public value class EntityType(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "EntityType must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical correlation identifier across an integration boundary.
 *
 * Ownership: request initiator or integration boundary.
 */
@JvmInline
public value class CorrelationId(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "CorrelationId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical trace identifier for observability integration.
 *
 * Ownership: observability integration.
 */
@JvmInline
public value class TraceId(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "TraceId must not be blank." }
    }

    override fun toString(): String = value
}
