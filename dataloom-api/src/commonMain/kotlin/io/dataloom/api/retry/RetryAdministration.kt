package io.dataloom.api.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.DataLoomInstant
import kotlin.jvm.JvmInline

/** Stable idempotency key for one administrative retry command. */
@JvmInline
public value class RetryAdministrationCommandId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RetryAdministrationCommandId must not be blank." }
    }

    override fun toString(): String = value
}

/** Stable identifier for the principal requesting an administrative retry. */
@JvmInline
public value class RetryAdministrationPrincipalId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RetryAdministrationPrincipalId must not be blank." }
    }

    override fun toString(): String = value
}

/** Stable authorization decision identifier supplied by the host authorizer. */
@JvmInline
public value class RetryAdministrationAuthorizationId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RetryAdministrationAuthorizationId must not be blank." }
    }

    override fun toString(): String = value
}

/** Bounded sanitized reason for an administrative retry request. */
@JvmInline
public value class RetryAdministrationReason(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "RetryAdministrationReason must not be blank." }
        require(value.length <= MAXIMUM_LENGTH) {
            "RetryAdministrationReason must not exceed $MAXIMUM_LENGTH characters."
        }
    }

    override fun toString(): String = value

    private companion object {
        const val MAXIMUM_LENGTH: Int = 512
    }
}

/** Administrative action requested for one terminal queue failure. */
public enum class RetryAdministrationAction {
    /** Requeue an already automatically retry-safe failure without changing classification. */
    REQUEUE,

    /** Explicitly override retry protection and requeue with recoverable execution evidence. */
    RECLASSIFY_AND_REQUEUE,
}

/** Redacted immutable failure evidence retained in retry administration history. */
public data class RetryFailureSnapshot(
    public val code: ErrorCode,
    public val category: ErrorCategory,
    public val severity: ErrorSeverity,
    public val recoverability: Recoverability,
)

/** Immutable administrative retry command. */
public data class RetryAdministrationRequest(
    public val commandId: RetryAdministrationCommandId,
    public val queueEntryId: QueueEntryId,
    public val principalId: RetryAdministrationPrincipalId,
    public val requestedAt: DataLoomInstant,
    public val action: RetryAdministrationAction,
    public val reason: RetryAdministrationReason,
    public val originalFailure: RetryFailureSnapshot,
)

/** Authorization result for an administrative retry command. */
public sealed interface RetryAdministrationAuthorizationDecision {
    public data class Authorized(
        public val authorizationId: RetryAdministrationAuthorizationId,
    ) : RetryAdministrationAuthorizationDecision

    public data class Denied(
        public val reasonCode: String,
    ) : RetryAdministrationAuthorizationDecision {
        init {
            requireValidReasonCode(reasonCode, "RetryAdministrationAuthorizationDecision.Denied")
        }
    }
}

/** Host-owned authorization boundary for administrative retry commands. */
public interface RetryAdministrationAuthorizer {
    /**
     * Returns an authorization decision for [request].
     *
     * Implementations must be side-effect free or idempotent by
     * [RetryAdministrationRequest.commandId]. Cancellation must propagate.
     */
    public suspend fun authorize(
        request: RetryAdministrationRequest,
    ): RetryAdministrationAuthorizationDecision
}

/** Durable lifecycle status for one administrative retry command. */
public enum class RetryAdministrationCommandStatus {
    AUTHORIZED,
    SUCCEEDED,
    AUTHORIZATION_DENIED,
    POLICY_REJECTED,
    EXECUTION_REJECTED,
    EXECUTION_FAILED,
}

/** Immutable durable state for one administrative retry command. */
public data class RetryAdministrationCommandState(
    public val request: RetryAdministrationRequest,
    public val status: RetryAdministrationCommandStatus,
    public val authorizationId: RetryAdministrationAuthorizationId?,
    public val effectiveRecoverability: Recoverability?,
    public val updatedAt: DataLoomInstant,
    public val rejectionReasonCode: String? = null,
    public val executionFailure: RetryFailureSnapshot? = null,
) {
    init {
        val authorizedStatus = status == RetryAdministrationCommandStatus.AUTHORIZED ||
            status == RetryAdministrationCommandStatus.SUCCEEDED ||
            status == RetryAdministrationCommandStatus.POLICY_REJECTED ||
            status == RetryAdministrationCommandStatus.EXECUTION_REJECTED ||
            status == RetryAdministrationCommandStatus.EXECUTION_FAILED

        require(authorizedStatus == (authorizationId != null)) {
            "RetryAdministrationCommandState authorization evidence must match status $status."
        }
        require(authorizedStatus == (effectiveRecoverability != null)) {
            "RetryAdministrationCommandState effective recoverability must match status $status."
        }

        val rejectedStatus = status == RetryAdministrationCommandStatus.AUTHORIZATION_DENIED ||
            status == RetryAdministrationCommandStatus.POLICY_REJECTED ||
            status == RetryAdministrationCommandStatus.EXECUTION_REJECTED
        require(rejectedStatus == (rejectionReasonCode != null)) {
            "RetryAdministrationCommandState rejection reason must match status $status."
        }
        rejectionReasonCode?.let { reasonCode ->
            requireValidReasonCode(reasonCode, "RetryAdministrationCommandState")
        }

        require((status == RetryAdministrationCommandStatus.EXECUTION_FAILED) ==
            (executionFailure != null)) {
            "RetryAdministrationCommandState execution failure must match status $status."
        }

        if (request.action == RetryAdministrationAction.RECLASSIFY_AND_REQUEUE && authorizedStatus) {
            require(effectiveRecoverability == Recoverability.RECOVERABLE) {
                "Reclassified retry administration commands must be effectively recoverable."
            }
        }
    }
}

/** Versioned durable administrative retry state. */
public data class RetryAdministrationStateRecord(
    public val state: RetryAdministrationCommandState,
    public val version: Long,
) {
    init {
        require(version >= 0L) { "RetryAdministrationStateRecord version must be non-negative." }
    }
}

/** Result of loading one administrative retry command. */
public sealed interface RetryAdministrationLoadResult {
    public data object Missing : RetryAdministrationLoadResult

    public data class Found(
        public val record: RetryAdministrationStateRecord,
    ) : RetryAdministrationLoadResult
}

/** Atomic compare-and-set request for administrative retry state. */
public data class RetryAdministrationCompareAndSetRequest(
    public val commandId: RetryAdministrationCommandId,
    public val expectedVersion: Long?,
    public val nextState: RetryAdministrationCommandState,
) {
    init {
        require(expectedVersion == null || expectedVersion >= 0L) {
            "RetryAdministrationCompareAndSetRequest expectedVersion must be null or non-negative."
        }
        require(nextState.request.commandId == commandId) {
            "RetryAdministrationCompareAndSetRequest commandId must match nextState request."
        }
    }
}

/** Result of atomic administrative retry state persistence. */
public sealed interface RetryAdministrationCompareAndSetResult {
    public data class Updated(
        public val record: RetryAdministrationStateRecord,
    ) : RetryAdministrationCompareAndSetResult

    public data class Conflict(
        public val current: RetryAdministrationStateRecord?,
    ) : RetryAdministrationCompareAndSetResult
}

/** Durable idempotency and immutable audit boundary for retry administration. */
public interface RetryAdministrationStateStore {
    public suspend fun load(
        commandId: RetryAdministrationCommandId,
    ): ProviderOperationResult<RetryAdministrationLoadResult>

    public suspend fun compareAndSet(
        request: RetryAdministrationCompareAndSetRequest,
    ): ProviderOperationResult<RetryAdministrationCompareAndSetResult>
}

/** Authorized command supplied to the host-owned queue mutation adapter. */
public data class AuthorizedRetryAdministrationCommand(
    public val request: RetryAdministrationRequest,
    public val authorizationId: RetryAdministrationAuthorizationId,
    public val effectiveRecoverability: Recoverability,
)

/** Result of applying an authorized administrative retry command. */
public sealed interface RetryAdministrationExecutionResult {
    /** The requested queue mutation was applied or was already applied idempotently. */
    public data object Applied : RetryAdministrationExecutionResult

    /** The target queue state rejected the command without applying a mutation. */
    public data class Rejected(
        public val reasonCode: String,
    ) : RetryAdministrationExecutionResult {
        init {
            requireValidReasonCode(reasonCode, "RetryAdministrationExecutionResult.Rejected")
        }
    }

    /** The command failed with a canonical sanitized error. */
    public data class Failed(
        public val error: DataLoomError,
    ) : RetryAdministrationExecutionResult
}

/** Host-owned queue mutation boundary for authorized administrative retry. */
public interface RetryAdministrationExecutor {
    /**
     * Applies [command] idempotently by [RetryAdministrationRequest.commandId].
     *
     * The same command may be delivered again after contention, process loss,
     * or an unconfirmed audit write. A repeated command must not create another
     * queue entry or consume retry history twice. Cancellation must propagate.
     */
    public suspend fun execute(
        command: AuthorizedRetryAdministrationCommand,
    ): RetryAdministrationExecutionResult
}

private fun requireValidReasonCode(reasonCode: String, owner: String) {
    require(reasonCode.isNotBlank()) { "$owner reasonCode must not be blank." }
    require(reasonCode.length <= 128) { "$owner reasonCode must not exceed 128 characters." }
}
