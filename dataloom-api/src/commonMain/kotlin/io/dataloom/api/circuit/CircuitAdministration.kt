package io.dataloom.api.circuit

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.DataLoomInstant
import kotlin.jvm.JvmInline

/** Stable idempotency key for one administrative circuit command. */
@JvmInline
public value class CircuitAdministrationCommandId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "CircuitAdministrationCommandId must not be blank." }
        require(value.length <= MAXIMUM_IDENTITY_LENGTH) {
            "CircuitAdministrationCommandId must not exceed $MAXIMUM_IDENTITY_LENGTH characters."
        }
    }

    override fun toString(): String = value
}

/** Stable identifier for the principal requesting a circuit operation. */
@JvmInline
public value class CircuitAdministrationPrincipalId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "CircuitAdministrationPrincipalId must not be blank." }
        require(value.length <= MAXIMUM_IDENTITY_LENGTH) {
            "CircuitAdministrationPrincipalId must not exceed $MAXIMUM_IDENTITY_LENGTH characters."
        }
    }

    override fun toString(): String = value
}

/** Stable authorization decision identifier supplied by the host authorizer. */
@JvmInline
public value class CircuitAdministrationAuthorizationId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "CircuitAdministrationAuthorizationId must not be blank." }
        require(value.length <= MAXIMUM_IDENTITY_LENGTH) {
            "CircuitAdministrationAuthorizationId must not exceed $MAXIMUM_IDENTITY_LENGTH characters."
        }
    }

    override fun toString(): String = value
}

/** Bounded sanitized reason for an administrative circuit operation. */
@JvmInline
public value class CircuitAdministrationReason(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "CircuitAdministrationReason must not be blank." }
        require(value.length <= MAXIMUM_LENGTH) {
            "CircuitAdministrationReason must not exceed $MAXIMUM_LENGTH characters."
        }
    }

    override fun toString(): String = value

    private companion object {
        const val MAXIMUM_LENGTH: Int = 512
    }
}

/** Privileged circuit-state operation. */
public enum class CircuitAdministrationAction {
    /** Opens the exact circuit scope until the request's bounded deadline. */
    OPEN,

    /** Closes the exact circuit scope while preserving its probe generation. */
    CLOSE,

    /** Resets the exact circuit scope to a fresh closed state. */
    RESET,
}

/** Immutable administrative circuit command. */
public data class CircuitAdministrationRequest(
    public val commandId: CircuitAdministrationCommandId,
    public val scope: CircuitBreakerScope,
    public val principalId: CircuitAdministrationPrincipalId,
    public val requestedAt: DataLoomInstant,
    public val action: CircuitAdministrationAction,
    public val reason: CircuitAdministrationReason,
    public val openUntil: DataLoomInstant? = null,
) {
    init {
        when (action) {
            CircuitAdministrationAction.OPEN -> {
                val deadline = requireNotNull(openUntil) {
                    "CircuitAdministrationRequest OPEN requires openUntil."
                }
                require(deadline.epochMilliseconds > requestedAt.epochMilliseconds) {
                    "CircuitAdministrationRequest openUntil must be later than requestedAt."
                }
            }
            CircuitAdministrationAction.CLOSE,
            CircuitAdministrationAction.RESET,
            -> require(openUntil == null) {
                "CircuitAdministrationRequest $action cannot define openUntil."
            }
        }
    }
}

/** Authorization result for a privileged circuit command. */
public sealed interface CircuitAdministrationAuthorizationDecision {
    public data class Authorized(
        public val authorizationId: CircuitAdministrationAuthorizationId,
    ) : CircuitAdministrationAuthorizationDecision

    public data class Denied(
        public val reasonCode: String,
    ) : CircuitAdministrationAuthorizationDecision {
        init {
            requireValidCircuitAdministrationReasonCode(
                reasonCode,
                "CircuitAdministrationAuthorizationDecision.Denied",
            )
        }
    }
}

/** Host-owned authorization boundary for privileged circuit commands. */
public interface CircuitAdministrationAuthorizer {
    /**
     * Returns an authorization decision for the complete immutable [request].
     *
     * Implementations must be side-effect free or idempotent by
     * [CircuitAdministrationRequest.commandId]. Cancellation must propagate.
     */
    public suspend fun authorize(
        request: CircuitAdministrationRequest,
    ): CircuitAdministrationAuthorizationDecision
}

/** Durable lifecycle status for one circuit-administration command. */
public enum class CircuitAdministrationCommandStatus {
    AUTHORIZED,
    SUCCEEDED,
    AUTHORIZATION_DENIED,
    POLICY_REJECTED,
    EXECUTION_REJECTED,
    EXECUTION_FAILED,
}

/** Redacted canonical execution-failure evidence retained in command history. */
public data class CircuitAdministrationFailureSnapshot(
    public val code: ErrorCode,
    public val category: ErrorCategory,
    public val severity: ErrorSeverity,
    public val recoverability: Recoverability,
)

/** Immutable durable audit state for one circuit-administration command. */
public data class CircuitAdministrationCommandState(
    public val request: CircuitAdministrationRequest,
    public val status: CircuitAdministrationCommandStatus,
    public val authorizationId: CircuitAdministrationAuthorizationId?,
    public val updatedAt: DataLoomInstant,
    public val rejectionReasonCode: String? = null,
    public val resultingRecord: CircuitBreakerStateRecord? = null,
    public val executionFailure: CircuitAdministrationFailureSnapshot? = null,
) {
    init {
        val authorizedStatus = status != CircuitAdministrationCommandStatus.AUTHORIZATION_DENIED
        require(authorizedStatus == (authorizationId != null)) {
            "CircuitAdministrationCommandState authorization evidence must match status $status."
        }

        val rejectedStatus = status == CircuitAdministrationCommandStatus.AUTHORIZATION_DENIED ||
            status == CircuitAdministrationCommandStatus.POLICY_REJECTED ||
            status == CircuitAdministrationCommandStatus.EXECUTION_REJECTED
        require(rejectedStatus == (rejectionReasonCode != null)) {
            "CircuitAdministrationCommandState rejection reason must match status $status."
        }
        rejectionReasonCode?.let { reasonCode ->
            requireValidCircuitAdministrationReasonCode(
                reasonCode,
                "CircuitAdministrationCommandState",
            )
        }

        require((status == CircuitAdministrationCommandStatus.SUCCEEDED) ==
            (resultingRecord != null)) {
            "CircuitAdministrationCommandState resulting record must match status $status."
        }
        resultingRecord?.let { record ->
            require(record.state.scope == request.scope) {
                "CircuitAdministrationCommandState resulting record scope must match request."
            }
        }

        require((status == CircuitAdministrationCommandStatus.EXECUTION_FAILED) ==
            (executionFailure != null)) {
            "CircuitAdministrationCommandState execution failure must match status $status."
        }
    }
}

/** Versioned durable circuit-administration command state. */
public data class CircuitAdministrationStateRecord(
    public val state: CircuitAdministrationCommandState,
    public val version: Long,
) {
    init {
        require(version >= 0L) { "CircuitAdministrationStateRecord version must be non-negative." }
    }
}

/** Result of loading one circuit-administration command. */
public sealed interface CircuitAdministrationLoadResult {
    public data object Missing : CircuitAdministrationLoadResult

    public data class Found(
        public val record: CircuitAdministrationStateRecord,
    ) : CircuitAdministrationLoadResult
}

/** Atomic compare-and-set request for circuit-administration command state. */
public data class CircuitAdministrationCompareAndSetRequest(
    public val commandId: CircuitAdministrationCommandId,
    public val expectedVersion: Long?,
    public val nextState: CircuitAdministrationCommandState,
) {
    init {
        require(expectedVersion == null || expectedVersion >= 0L) {
            "CircuitAdministrationCompareAndSetRequest expectedVersion must be null or non-negative."
        }
        require(nextState.request.commandId == commandId) {
            "CircuitAdministrationCompareAndSetRequest commandId must match nextState request."
        }
    }
}

/** Result of an atomic circuit-administration state update. */
public sealed interface CircuitAdministrationCompareAndSetResult {
    public data class Updated(
        public val record: CircuitAdministrationStateRecord,
    ) : CircuitAdministrationCompareAndSetResult

    public data class Conflict(
        public val current: CircuitAdministrationStateRecord?,
    ) : CircuitAdministrationCompareAndSetResult
}

/** Durable idempotency and immutable audit boundary for circuit administration. */
public interface CircuitAdministrationStateStore {
    /**
     * Loads the durable state for [commandId].
     *
     * Cancellation must propagate.
     */
    public suspend fun load(
        commandId: CircuitAdministrationCommandId,
    ): ProviderOperationResult<CircuitAdministrationLoadResult>

    /**
     * Applies [request] atomically when its expected version matches.
     *
     * A null expected version means that no command record may already exist.
     * Cancellation must propagate.
     */
    public suspend fun compareAndSet(
        request: CircuitAdministrationCompareAndSetRequest,
    ): ProviderOperationResult<CircuitAdministrationCompareAndSetResult>
}

/** Authorized command supplied to a platform circuit-state executor. */
public data class AuthorizedCircuitAdministrationCommand(
    public val request: CircuitAdministrationRequest,
    public val authorizationId: CircuitAdministrationAuthorizationId,
)

/** Result of applying an authorized circuit-state operation. */
public sealed interface CircuitAdministrationExecutionResult {
    /** The mutation was applied or an identical durable receipt was replayed. */
    public data class Applied(
        public val record: CircuitBreakerStateRecord,
    ) : CircuitAdministrationExecutionResult

    /** Current durable state rejected the command without mutation. */
    public data class Rejected(
        public val reasonCode: String,
    ) : CircuitAdministrationExecutionResult {
        init {
            requireValidCircuitAdministrationReasonCode(
                reasonCode,
                "CircuitAdministrationExecutionResult.Rejected",
            )
        }
    }

    /** Execution failed with redacted canonical evidence. */
    public data class Failed(
        public val failure: CircuitAdministrationFailureSnapshot,
    ) : CircuitAdministrationExecutionResult
}

/** Host/platform boundary for an authorized, idempotent circuit mutation. */
public interface CircuitAdministrationExecutor {
    /**
     * Applies [command] idempotently by command identifier.
     *
     * Implementations must validate the exact authorization and immutable
     * request before mutation. Mutation and a durable command receipt must be
     * committed atomically where the platform persistence boundary permits it.
     * Redelivery must not apply a second mutation. Cancellation must propagate.
     */
    public suspend fun execute(
        command: AuthorizedCircuitAdministrationCommand,
    ): CircuitAdministrationExecutionResult
}

private fun requireValidCircuitAdministrationReasonCode(reasonCode: String, owner: String) {
    require(reasonCode.isNotBlank()) { "$owner reasonCode must not be blank." }
    require(reasonCode.length <= 128) { "$owner reasonCode must not exceed 128 characters." }
}

private const val MAXIMUM_IDENTITY_LENGTH: Int = 128
