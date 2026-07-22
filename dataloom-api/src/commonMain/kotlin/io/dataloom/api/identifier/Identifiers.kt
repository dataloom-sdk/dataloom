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

/**
 * Canonical identifier for a detected synchronization conflict.
 *
 * Wrap a non-blank application-supplied conflict identifier. DataLoom does not
 * generate conflict identifiers automatically; the caller is responsible for
 * supplying a meaningful value.
 *
 * Ownership: conflict producer (application or integration).
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - `toString()` returns the underlying value.
 *
 * Example placeholder values:
 * ```
 * conflict-001
 * inv-conflict-2026-07-01
 * order-conflict-batch-3
 * ```
 */
@JvmInline
public value class ConflictId(
    /** Underlying conflict identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConflictId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a [io.dataloom.api.conflict.ConflictDetector] implementation.
 *
 * Wrap a non-blank application-supplied detector identifier. DataLoom does not
 * generate detector identifiers automatically; the caller is responsible for
 * supplying a meaningful value.
 *
 * Ownership: conflict-detector implementor or host application.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - `toString()` returns the underlying value.
 *
 * Example placeholder values:
 * ```
 * entity-version-detector
 * application-order-detector
 * default-conflict-detector
 * ```
 */
@JvmInline
public value class ConflictDetectorId(
    /** Underlying detector identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConflictDetectorId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a [io.dataloom.api.conflict.ConflictResolver] implementation.
 *
 * Wrap a non-blank application-supplied resolver identifier. DataLoom does not
 * generate resolver identifiers automatically; the caller is responsible for
 * supplying a meaningful value.
 *
 * Ownership: conflict-resolver implementor or host application.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - `toString()` returns the underlying value.
 *
 * Example placeholder values:
 * ```
 * client-preferred-resolver
 * server-preferred-resolver
 * application-merge-resolver
 * ```
 */
@JvmInline
public value class ConflictResolverId(
    /** Underlying resolver identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConflictResolverId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a logical synchronization checkpoint stream.
 *
 * A [CheckpointKey] identifies the logical synchronization stream whose
 * progress is being stored, for example a specific entity type, tenant, or
 * integration channel. The key format is application or integration defined.
 * DataLoom does not generate checkpoint keys automatically; the caller is
 * responsible for supplying a meaningful value.
 *
 * Ownership: host application or integration.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - `toString()` returns the underlying value.
 *
 * Example placeholder values:
 * ```
 * customers-pull
 * orders-tenant-example
 * inventory-region-example
 * ```
 */
@JvmInline
public value class CheckpointKey(
    /** Underlying checkpoint key value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "CheckpointKey must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Opaque synchronization checkpoint token.
 *
 * A [CheckpointToken] may represent a delta token, a continuation cursor, a
 * remote sequence, an opaque revision, or an application-defined
 * synchronization marker. DataLoom treats the token as opaque: it does not
 * interpret its format, compare token ordering, or generate tokens
 * automatically.
 *
 * Ownership: remote system or application-defined synchronization contract.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization, interpretation, or ordering comparison is applied.
 * - `toString()` returns the underlying value.
 *
 * A transport provider may redact checkpoint tokens from diagnostics.
 * Checkpoint tokens must not be treated as credentials.
 */
@JvmInline
public value class CheckpointToken(
    /** Underlying opaque checkpoint token value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "CheckpointToken must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a scheduled synchronization request.
 *
 * A [ScheduleId] identifies a single scheduling entry managed by a
 * [io.dataloom.api.scheduling.SchedulerProvider]. It may be used by a
 * platform provider to implement unique scheduling and cancellation. DataLoom
 * does not generate schedule identifiers automatically; the caller is
 * responsible for supplying a meaningful, unique value.
 *
 * Ownership: DataLoom runtime or host integration.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - `toString()` returns the underlying value.
 *
 * Example placeholder values:
 * ```
 * sync-schedule-001
 * workflow-daily-customers
 * tenant-example-push
 * ```
 */
@JvmInline
public value class ScheduleId(
    /** Underlying schedule identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ScheduleId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a durable queue entry.
 *
 * A [QueueEntryId] uniquely identifies a single entry in the DataLoom durable
 * synchronization queue. DataLoom does not generate queue entry identifiers
 * automatically; the caller or host integration is responsible for supplying a
 * meaningful, unique value.
 *
 * Ownership: DataLoom runtime or host integration.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - `toString()` returns the underlying value.
 *
 * Example placeholder values:
 * ```
 * queue-entry-001
 * workflow-sync-entry-2026-07-22
 * entry-tenant-example-push
 * ```
 */
@JvmInline
public value class QueueEntryId(
    /** Underlying queue entry identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "QueueEntryId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for an exclusive queue entry lease.
 *
 * A [QueueLeaseId] identifies the exclusive lease that a consumer holds over a
 * queue entry while processing it. DataLoom does not generate lease identifiers
 * automatically; the DataLoom runtime is responsible for supplying a unique
 * value per acquisition operation.
 *
 * Ownership: DataLoom runtime.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - `toString()` returns the underlying value.
 *
 * Example placeholder values:
 * ```
 * lease-001
 * lease-batch-2026-07-22-001
 * acquisition-lease-xyz
 * ```
 */
@JvmInline
public value class QueueLeaseId(
    /** Underlying queue lease identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "QueueLeaseId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a queue consumer.
 *
 * A [QueueConsumerId] identifies the runtime worker or platform integration
 * that acquires and processes queue entries. DataLoom does not generate consumer
 * identifiers automatically; the runtime worker or platform integration is
 * responsible for supplying a meaningful, stable value.
 *
 * Ownership: Runtime worker or platform integration.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - `toString()` returns the underlying value.
 *
 * Example placeholder values:
 * ```
 * consumer-worker-001
 * workmanager-consumer-example
 * sync-consumer-tenant-example
 * ```
 */
@JvmInline
public value class QueueConsumerId(
    /** Underlying queue consumer identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "QueueConsumerId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a synchronization lifecycle event.
 *
 * A [SynchronizationEventId] uniquely identifies a single
 * [io.dataloom.api.synchronization.SynchronizationEvent] emitted by the
 * DataLoom runtime. DataLoom does not generate event identifiers
 * automatically; the future runtime or host integration is responsible for
 * supplying a meaningful, unique value per emitted event.
 *
 * Ownership: DataLoom runtime or host integration.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - `toString()` returns the underlying value.
 *
 * Example placeholder values:
 * ```
 * sync-event-001
 * event-workflow-daily-customers-started
 * observation-event-2026-07-22-001
 * ```
 */
@JvmInline
public value class SynchronizationEventId(
    /** Underlying synchronization event identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "SynchronizationEventId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a [io.dataloom.api.observation.SynchronizationObserver]
 * implementation.
 *
 * A [SynchronizationObserverId] uniquely identifies an observer registered
 * with the DataLoom runtime. DataLoom does not generate observer identifiers
 * automatically; the host application or integration is responsible for
 * supplying a meaningful, stable value.
 *
 * Ownership: host application or integration.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - `toString()` returns the underlying value.
 *
 * Example placeholder values:
 * ```
 * analytics-observer
 * debug-observer-example
 * ui-progress-observer
 * ```
 */
@JvmInline
public value class SynchronizationObserverId(
    /** Underlying observer identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "SynchronizationObserverId must not be blank." }
    }

    override fun toString(): String = value
}

/**
 * Canonical identifier for a [io.dataloom.api.retry.RetryPolicy] implementation.
 *
 * A [RetryPolicyId] uniquely identifies a retry policy configured or registered
 * by the host application or DataLoom runtime. DataLoom does not generate policy
 * identifiers automatically; the host application or runtime configuration is
 * responsible for supplying a meaningful, stable value.
 *
 * Ownership: host application or runtime configuration.
 *
 * Constraints:
 * - Value must not be blank or whitespace-only.
 * - Valid input is preserved exactly as supplied.
 * - No normalization or automatic generation is applied.
 * - `toString()` returns the underlying value.
 *
 * Example placeholder values:
 * ```
 * default-network-policy
 * critical-upload-policy
 * manual-only-policy
 * ```
 */
@JvmInline
public value class RetryPolicyId(
    /** Underlying retry-policy identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RetryPolicyId must not be blank." }
    }

    override fun toString(): String = value
}
