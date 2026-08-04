package io.dataloom.queue.room.internal

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConfigurationVersion
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.LocaleTag
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.identifier.RequestId
import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.UserId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.WorkflowPriority
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueEntryState
import io.dataloom.api.queue.QueueLease
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryBudgetState
import io.dataloom.api.retry.WorkflowTimeoutState
import io.dataloom.api.strategy.BuiltInSynchronizationStrategy
import io.dataloom.api.strategy.PersistedStrategyDecision
import io.dataloom.api.strategy.StrategyConfigurationVersion
import io.dataloom.api.strategy.StrategyDecisionId
import io.dataloom.api.strategy.StrategyDisposition
import io.dataloom.api.strategy.StrategyPlanId
import io.dataloom.api.strategy.StrategyProfileId
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomInstant
import org.json.JSONObject

/**
 * Converts a [QueueEntry] domain model to a [QueueEntryEntity] for storage.
 *
 * Enum values are stored as their constant names. The `ExecutionContext.metadata`
 * map is encoded as a compact JSON object using `org.json.JSONObject` (Android
 * SDK bundled). The provider does not interpret the logical content of the
 * synchronization request.
 */
internal fun QueueEntry.toEntity(): QueueEntryEntity {
    val request = synchronizationRequest
    val ctx = request.context
    return QueueEntryEntity(
        entryId = id.value,
        workflowId = request.workflowId.value,
        sessionId = request.sessionId.value,
        direction = request.direction.name,
        mode = request.mode.name,
        priority = request.priority.name,
        execExecutionId = ctx.executionId.value,
        execCorrelationId = ctx.correlationId.value,
        execTraceId = ctx.traceId?.value,
        execRequestId = ctx.requestId?.value,
        execTenantId = ctx.tenantId?.value,
        execUserId = ctx.userId?.value,
        execLocaleTag = ctx.localeTag?.value,
        execRuntimeVersion = ctx.runtimeVersion?.value,
        execConfigVersion = ctx.configurationVersion?.value,
        execMetadataJson = ctx.metadata.toJsonOrNull(),
        state = state.name,
        enqueuedAtMs = enqueuedAt.epochMilliseconds,
        availableAtMs = availableAt.epochMilliseconds,
        retryAttemptNumber = retryAttempt?.number,
        retryWindowStartedAtMs = retryBudgetState?.windowStartedAt?.epochMilliseconds,
        retryLastEvaluatedAtMs = retryBudgetState?.lastEvaluatedAt?.epochMilliseconds,
        retryCumulativeDelayMs = retryBudgetState?.cumulativeDelay?.milliseconds,
        workflowStartedAtMs = workflowTimeoutState?.startedAt?.epochMilliseconds,
        workflowDeadlineAtMs = workflowTimeoutState?.deadline?.epochMilliseconds,
        leaseId = lease?.id?.value,
        leaseConsumerId = lease?.consumerId?.value,
        leaseAcquiredAtMs = lease?.acquiredAt?.epochMilliseconds,
        leaseExpiresAtMs = lease?.expiresAt?.epochMilliseconds,
        lastErrorCode = lastError?.code?.value,
        lastErrorCategory = lastError?.category?.name,
        lastErrorSeverity = lastError?.severity?.name,
        lastErrorRecoverability = lastError?.recoverability?.name,
        lastErrorMessage = lastError?.message,
        entryMetadataJson = metadata.toJsonOrNull(),
        strategyDecisionId = strategyDecision?.decisionId?.value,
        strategyPlanId = strategyDecision?.planId?.value,
        strategyRequestedStrategy = strategyDecision?.requestedStrategy?.name,
        strategyEffectiveProfileId = strategyDecision?.effectiveProfileId?.value,
        strategyEffectiveStrategy = strategyDecision?.effectiveStrategy?.name,
        strategyConfigurationVersion = strategyDecision?.configurationVersion?.value,
        strategyDisposition = strategyDecision?.disposition?.name,
    )
}

/**
 * Reconstructs a [QueueEntry] domain model from a [QueueEntryEntity].
 *
 * Enum constants are looked up by name. Unknown state or enum names result in
 * an [IllegalStateException] — these indicate schema corruption and must not
 * be silently ignored.
 */
internal fun QueueEntryEntity.toDomain(): QueueEntry {
    val state = QueueEntryState.valueOf(state)
    val direction = SynchronizationDirection.valueOf(direction)
    val mode = SynchronizationMode.valueOf(mode)
    val priority = WorkflowPriority.valueOf(priority)
    val execMetadata = execMetadataJson?.toMetadata() ?: DataLoomMetadata.Empty
    val entryMeta = entryMetadataJson?.toMetadata() ?: DataLoomMetadata.Empty
    val lastError = toLastErrorOrNull()

    val executionContext = ExecutionContext(
        executionId = ExecutionId(execExecutionId),
        correlationId = CorrelationId(execCorrelationId),
        traceId = execTraceId?.let { TraceId(it) },
        requestId = execRequestId?.let { RequestId(it) },
        tenantId = execTenantId?.let { TenantId(it) },
        userId = execUserId?.let { UserId(it) },
        localeTag = execLocaleTag?.let { LocaleTag(it) },
        runtimeVersion = execRuntimeVersion?.let { RuntimeVersion(it) },
        configurationVersion = execConfigVersion?.let { ConfigurationVersion(it) },
        metadata = execMetadata,
    )

    val syncRequest = SynchronizationRequest(
        workflowId = WorkflowId(workflowId),
        sessionId = SynchronizationSessionId(sessionId),
        direction = direction,
        mode = mode,
        priority = priority,
        context = executionContext,
    )

    val lease: QueueLease? = if (leaseId != null && leaseConsumerId != null &&
        leaseAcquiredAtMs != null && leaseExpiresAtMs != null
    ) {
        QueueLease(
            id = QueueLeaseId(leaseId),
            consumerId = QueueConsumerId(leaseConsumerId),
            acquiredAt = DataLoomInstant(leaseAcquiredAtMs),
            expiresAt = DataLoomInstant(leaseExpiresAtMs),
        )
    } else {
        null
    }

    val retryAttempt: RetryAttempt? = retryAttemptNumber?.let { RetryAttempt(it) }
    val budgetColumns = listOf(
        retryWindowStartedAtMs,
        retryLastEvaluatedAtMs,
        retryCumulativeDelayMs,
    )
    check(budgetColumns.all { it == null } || budgetColumns.all { it != null }) {
        "Persisted retry-budget columns must be either all null or all non-null."
    }
    val retryBudgetState = if (budgetColumns.all { it == null }) {
        null
    } else {
        RetryBudgetState(
            windowStartedAt = DataLoomInstant(checkNotNull(retryWindowStartedAtMs)),
            lastEvaluatedAt = DataLoomInstant(checkNotNull(retryLastEvaluatedAtMs)),
            cumulativeDelay = SchedulingDelay(checkNotNull(retryCumulativeDelayMs)),
        )
    }

    val workflowColumns = listOf(workflowStartedAtMs, workflowDeadlineAtMs)
    check(workflowColumns.all { it == null } || workflowColumns.all { it != null }) {
        "Persisted workflow-timeout columns must be either all null or all non-null."
    }
    val workflowTimeoutState = if (workflowColumns.all { it == null }) {
        null
    } else {
        WorkflowTimeoutState(
            startedAt = DataLoomInstant(checkNotNull(workflowStartedAtMs)),
            deadline = DataLoomInstant(checkNotNull(workflowDeadlineAtMs)),
        )
    }

    val strategyColumns: List<Any?> = listOf(
        strategyDecisionId,
        strategyPlanId,
        strategyRequestedStrategy,
        strategyEffectiveProfileId,
        strategyEffectiveStrategy,
        strategyConfigurationVersion,
        strategyDisposition,
    )
    check(strategyColumns.all { it == null } || strategyColumns.all { it != null }) {
        "Persisted strategy-decision columns must be either all null or all non-null."
    }
    val strategyDecision = if (strategyColumns.all { it == null }) {
        null
    } else {
        PersistedStrategyDecision(
            decisionId = StrategyDecisionId(checkNotNull(strategyDecisionId)),
            planId = StrategyPlanId(checkNotNull(strategyPlanId)),
            requestedStrategy = BuiltInSynchronizationStrategy.valueOf(
                checkNotNull(strategyRequestedStrategy),
            ),
            effectiveProfileId = StrategyProfileId(
                checkNotNull(strategyEffectiveProfileId),
            ),
            effectiveStrategy = BuiltInSynchronizationStrategy.valueOf(
                checkNotNull(strategyEffectiveStrategy),
            ),
            configurationVersion = StrategyConfigurationVersion(
                checkNotNull(strategyConfigurationVersion),
            ),
            disposition = StrategyDisposition.valueOf(checkNotNull(strategyDisposition)),
        )
    }

    return QueueEntry(
        id = QueueEntryId(entryId),
        synchronizationRequest = syncRequest,
        state = state,
        enqueuedAt = DataLoomInstant(enqueuedAtMs),
        availableAt = DataLoomInstant(availableAtMs),
        retryAttempt = retryAttempt,
        lease = lease,
        lastError = lastError,
        metadata = entryMeta,
        retryBudgetState = retryBudgetState,
        workflowTimeoutState = workflowTimeoutState,
        strategyDecision = strategyDecision,
    )
}

/**
 * Reconstructs the complete canonical error contract from its flat columns.
 *
 * A partially populated error is corrupt durable state. Failing closed keeps
 * the caller from receiving a misleading error with fabricated defaults.
 */
private fun QueueEntryEntity.toLastErrorOrNull(): DataLoomError? {
    val columns = listOf(
        lastErrorCode,
        lastErrorCategory,
        lastErrorSeverity,
        lastErrorRecoverability,
        lastErrorMessage,
    )
    if (columns.all { it == null }) return null

    check(columns.all { it != null }) {
        "Persisted queue error columns must be either all null or all non-null."
    }

    return PersistedQueueError(
        code = ErrorCode(checkNotNull(lastErrorCode)),
        category = enumValueOf<ErrorCategory>(checkNotNull(lastErrorCategory)),
        severity = enumValueOf<ErrorSeverity>(checkNotNull(lastErrorSeverity)),
        recoverability = enumValueOf<Recoverability>(checkNotNull(lastErrorRecoverability)),
        message = checkNotNull(lastErrorMessage),
    )
}

/** Canonical error reconstructed from durable, sanitized queue columns. */
private data class PersistedQueueError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
) : DataLoomError {
    override val cause: Throwable? = null
}

// ── DataLoomMetadata ↔ JSON ──────────────────────────────────────────────────

/**
 * Serializes a [DataLoomMetadata] map to a compact JSON string, or returns
 * `null` when the map is empty.
 *
 * Uses `org.json.JSONObject` (Android SDK bundled). No external library is
 * required.
 */
private fun DataLoomMetadata.toJsonOrNull(): String? {
    val entries = this.entries
    if (entries.isEmpty()) return null
    val json = JSONObject()
    entries.forEach { (k, v) -> json.put(k, v) }
    return json.toString()
}

/**
 * Deserializes a compact JSON string back to a [DataLoomMetadata] instance.
 *
 * Invalid JSON is treated as corrupt durable state and propagates to the
 * provider's sanitized database-failure mapping.
 */
private fun String.toMetadata(): DataLoomMetadata {
    val json = JSONObject(this)
    val map = mutableMapOf<String, String>()
    json.keys().forEach { key -> map[key] = json.getString(key) }
    return DataLoomMetadata.of(map)
}
