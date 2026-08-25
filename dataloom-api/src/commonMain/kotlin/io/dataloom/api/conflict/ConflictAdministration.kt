package io.dataloom.api.conflict

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.DataLoomInstant
import kotlin.jvm.JvmInline

/** Stable idempotency key for one administrative manual-conflict command. */
@JvmInline
public value class ConflictAdministrationCommandId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConflictAdministrationCommandId must not be blank." }
    }

    override fun toString(): String = value
}

/** Stable identifier for the principal requesting a manual conflict resolution. */
@JvmInline
public value class ConflictAdministrationPrincipalId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConflictAdministrationPrincipalId must not be blank." }
    }

    override fun toString(): String = value
}

/** Stable authorization decision identifier supplied by the host authorizer. */
@JvmInline
public value class ConflictAdministrationAuthorizationId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConflictAdministrationAuthorizationId must not be blank." }
    }

    override fun toString(): String = value
}

/** Bounded sanitized reason for a manual conflict-resolution request. */
@JvmInline
public value class ConflictAdministrationReason(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConflictAdministrationReason must not be blank." }
        require(value.length <= MAXIMUM_LENGTH) {
            "ConflictAdministrationReason must not exceed $MAXIMUM_LENGTH characters."
        }
    }

    override fun toString(): String = value

    private companion object {
        const val MAXIMUM_LENGTH: Int = 512
    }
}

/**
 * Immutable administrative manual-conflict-resolution command: apply
 * [decision] to the [io.dataloom.api.conflict.UnresolvedConflictRecord]
 * currently recorded for [conflictId].
 *
 * [decision] must not be [ConflictResolutionDecision.Defer] -- an
 * administrative command must reach a terminal outcome
 * ([ConflictResolutionDecision.UseLocal], [ConflictResolutionDecision.UseRemote],
 * [ConflictResolutionDecision.Merge], or [ConflictResolutionDecision.Fail]);
 * deferring is not an actionable manual decision.
 */
public data class ConflictAdministrationRequest(
    public val commandId: ConflictAdministrationCommandId,
    public val conflictId: ConflictId,
    public val principalId: ConflictAdministrationPrincipalId,
    public val requestedAt: DataLoomInstant,
    public val decision: ConflictResolutionDecision,
    public val reason: ConflictAdministrationReason,
) {
    init {
        require(decision !is ConflictResolutionDecision.Defer) {
            "ConflictAdministrationRequest decision must not be Defer -- an administrative command " +
                "must reach a terminal outcome (UseLocal, UseRemote, Merge, or Fail)."
        }
    }
}

/** Authorization result for an administrative manual-conflict-resolution command. */
public sealed interface ConflictAdministrationAuthorizationDecision {
    public data class Authorized(
        public val authorizationId: ConflictAdministrationAuthorizationId,
    ) : ConflictAdministrationAuthorizationDecision

    public data class Denied(
        public val reasonCode: String,
    ) : ConflictAdministrationAuthorizationDecision {
        init {
            requireValidReasonCode(reasonCode, "ConflictAdministrationAuthorizationDecision.Denied")
        }
    }
}

/**
 * Host-owned authorization boundary for administrative manual-conflict
 * commands. Mirrors [io.dataloom.api.retry.RetryAdministrationAuthorizer]'s
 * own deny-by-default, application-supplied posture: DataLoom does not
 * invent an identity or permission system, and there is no default
 * implementation.
 */
public interface ConflictAdministrationAuthorizer {
    /**
     * Returns an authorization decision for [request].
     *
     * Implementations must be side-effect free or idempotent by
     * [ConflictAdministrationRequest.commandId]. Cancellation must propagate.
     */
    public suspend fun authorize(
        request: ConflictAdministrationRequest,
    ): ConflictAdministrationAuthorizationDecision
}

/** Durable lifecycle status for one administrative manual-conflict command. */
public enum class ConflictAdministrationCommandStatus {
    AUTHORIZED,
    SUCCEEDED,
    AUTHORIZATION_DENIED,
    POLICY_REJECTED,
    EXECUTION_REJECTED,
    EXECUTION_FAILED,
}

/** Redacted immutable failure evidence retained in conflict-administration history. */
public data class ConflictAdministrationFailureSnapshot(
    public val code: ErrorCode,
    public val category: ErrorCategory,
    public val severity: ErrorSeverity,
    public val recoverability: Recoverability,
)

/** Immutable durable state for one administrative manual-conflict command. */
public data class ConflictAdministrationCommandState(
    public val request: ConflictAdministrationRequest,
    public val status: ConflictAdministrationCommandStatus,
    public val authorizationId: ConflictAdministrationAuthorizationId?,
    public val updatedAt: DataLoomInstant,
    public val rejectionReasonCode: String? = null,
    public val executionFailure: ConflictAdministrationFailureSnapshot? = null,
) {
    init {
        val authorizedStatus = status == ConflictAdministrationCommandStatus.AUTHORIZED ||
            status == ConflictAdministrationCommandStatus.SUCCEEDED ||
            status == ConflictAdministrationCommandStatus.POLICY_REJECTED ||
            status == ConflictAdministrationCommandStatus.EXECUTION_REJECTED ||
            status == ConflictAdministrationCommandStatus.EXECUTION_FAILED

        require(authorizedStatus == (authorizationId != null)) {
            "ConflictAdministrationCommandState authorization evidence must match status $status."
        }

        val rejectedStatus = status == ConflictAdministrationCommandStatus.AUTHORIZATION_DENIED ||
            status == ConflictAdministrationCommandStatus.POLICY_REJECTED ||
            status == ConflictAdministrationCommandStatus.EXECUTION_REJECTED
        require(rejectedStatus == (rejectionReasonCode != null)) {
            "ConflictAdministrationCommandState rejection reason must match status $status."
        }
        rejectionReasonCode?.let { reasonCode ->
            requireValidReasonCode(reasonCode, "ConflictAdministrationCommandState")
        }

        require(
            (status == ConflictAdministrationCommandStatus.EXECUTION_FAILED) == (executionFailure != null),
        ) {
            "ConflictAdministrationCommandState execution failure must match status $status."
        }
    }
}

/** Versioned durable administrative manual-conflict state. */
public data class ConflictAdministrationStateRecord(
    public val state: ConflictAdministrationCommandState,
    public val version: Long,
) {
    init {
        require(version >= 0L) { "ConflictAdministrationStateRecord version must be non-negative." }
    }
}

/** Result of loading one administrative manual-conflict command. */
public sealed interface ConflictAdministrationLoadResult {
    public data object Missing : ConflictAdministrationLoadResult

    public data class Found(
        public val record: ConflictAdministrationStateRecord,
    ) : ConflictAdministrationLoadResult
}

/** Atomic compare-and-set request for administrative manual-conflict state. */
public data class ConflictAdministrationCompareAndSetRequest(
    public val commandId: ConflictAdministrationCommandId,
    public val expectedVersion: Long?,
    public val nextState: ConflictAdministrationCommandState,
) {
    init {
        require(expectedVersion == null || expectedVersion >= 0L) {
            "ConflictAdministrationCompareAndSetRequest expectedVersion must be null or non-negative."
        }
        require(nextState.request.commandId == commandId) {
            "ConflictAdministrationCompareAndSetRequest commandId must match nextState request."
        }
    }
}

/** Result of atomic administrative manual-conflict state persistence. */
public sealed interface ConflictAdministrationCompareAndSetResult {
    public data class Updated(
        public val record: ConflictAdministrationStateRecord,
    ) : ConflictAdministrationCompareAndSetResult

    public data class Conflict(
        public val current: ConflictAdministrationStateRecord?,
    ) : ConflictAdministrationCompareAndSetResult
}

/** Durable idempotency and immutable audit boundary for conflict administration. */
public interface ConflictAdministrationStateStore {
    public suspend fun load(
        commandId: ConflictAdministrationCommandId,
    ): ProviderOperationResult<ConflictAdministrationLoadResult>

    public suspend fun compareAndSet(
        request: ConflictAdministrationCompareAndSetRequest,
    ): ProviderOperationResult<ConflictAdministrationCompareAndSetResult>
}

/**
 * Authorized command supplied to the host-owned manual-decision application
 * adapter, carrying the exact durably recorded
 * [UnresolvedConflictRecord] the decision targets -- the same payload-free
 * structural facts [io.dataloom.api.conflict.DurableUnresolvedConflictLog]
 * already durably holds, so an executor never needs to guess which entity,
 * conflict type, or change identifiers a command refers to.
 */
public data class AuthorizedConflictAdministrationCommand(
    public val request: ConflictAdministrationRequest,
    public val authorizationId: ConflictAdministrationAuthorizationId,
    public val unresolvedRecord: UnresolvedConflictRecord,
)

/** Result of applying an authorized administrative manual-conflict command. */
public sealed interface ConflictAdministrationExecutionResult {
    /** The requested decision was applied, or was already applied idempotently. */
    public data object Applied : ConflictAdministrationExecutionResult

    /** The target entity state rejected the command without applying a mutation. */
    public data class Rejected(
        public val reasonCode: String,
    ) : ConflictAdministrationExecutionResult {
        init {
            requireValidReasonCode(reasonCode, "ConflictAdministrationExecutionResult.Rejected")
        }
    }

    /** The command failed with a canonical sanitized error. */
    public data class Failed(
        public val error: DataLoomError,
    ) : ConflictAdministrationExecutionResult
}

/**
 * Host-owned application boundary for an authorized manual conflict
 * decision.
 *
 * Neither [io.dataloom.api.conflict.DurableUnresolvedConflictLog] nor
 * [io.dataloom.api.conflict.DurableResolvedConflictDecisionLog] durably
 * retains [io.dataloom.api.change.ChangeEvent] payload content -- by design,
 * documented on both. An implementation is responsible for retrieving
 * whatever real payload applying [AuthorizedConflictAdministrationCommand.request]`.decision`
 * requires from wherever the host application retains it (a local cache, a
 * fresh re-fetch from the remote provider, or its own storage), and for
 * deciding whether the target entity is still eligible given anything that
 * may have changed since the conflict was originally detected. DataLoom does
 * not perform freshness/staleness checking on the executor's behalf.
 *
 * Implementations must apply [AuthorizedConflictAdministrationCommand]
 * idempotently by [ConflictAdministrationRequest.commandId]. A repeated
 * command must not mutate the target entity twice. Cancellation must
 * propagate.
 */
public interface ConflictAdministrationExecutor {
    public suspend fun execute(
        command: AuthorizedConflictAdministrationCommand,
    ): ConflictAdministrationExecutionResult
}

private fun requireValidReasonCode(reasonCode: String, owner: String) {
    require(reasonCode.isNotBlank()) { "$owner reasonCode must not be blank." }
    require(reasonCode.length <= 128) { "$owner reasonCode must not exceed 128 characters." }
}
