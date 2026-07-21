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

/**
 * Canonical identifier for a synchronization execution context instance.
 *
 * Ownership: DataLoom runtime or host integration.
 */
@JvmInline
public value class ExecutionId(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ExecutionId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a synchronization request.
 *
 * Ownership: request initiator or host integration.
 */
@JvmInline
public value class RequestId(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RequestId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for tenant scope.
 *
 * Ownership: host application or enterprise integration.
 */
@JvmInline
public value class TenantId(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "TenantId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for user scope.
 *
 * Ownership: host authentication or domain layer.
 */
@JvmInline
public value class UserId(
    /** Underlying canonical identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "UserId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical version label for the executing DataLoom runtime.
 *
 * Ownership: DataLoom runtime.
 */
@JvmInline
public value class RuntimeVersion(
    /** Underlying runtime version value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RuntimeVersion must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical version label for host-provided synchronization configuration.
 *
 * Ownership: configuration source or host integration.
 */
@JvmInline
public value class ConfigurationVersion(
    /** Underlying configuration version value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConfigurationVersion must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical locale tag associated with a synchronization request context.
 *
 * Ownership: host application or request initiator.
 */
@JvmInline
public value class LocaleTag(
    /** Underlying locale tag value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "LocaleTag must not be blank." }
    }

    override fun toString(): String = value
}
