package io.dataloom.runtime.facade

import io.dataloom.api.retry.RetryAdministrationAuthorizer
import io.dataloom.api.retry.RetryAdministrationExecutor
import io.dataloom.api.retry.RetryAdministrationStateStore

/**
 * Immutable configuration for retry-administration operations assembly.
 *
 * All collaborators are explicit application/platform dependencies. The
 * builder uses its existing runtime clock and does not invoke any collaborator
 * while constructing [DataLoomRetryAdministration].
 *
 * [maximumStateUpdateAttempts] bounds compare-and-set contention. It does not
 * retry authorization or queue mutation independently; command idempotency and
 * the durable state machine remain authoritative.
 */
public class DataLoomRetryAdministrationSpec(
    /** Host-owned authorization boundary for privileged retry commands. */
    public val authorizer: RetryAdministrationAuthorizer,

    /** Durable command-state and immutable audit boundary. */
    public val stateStore: RetryAdministrationStateStore,

    /** Platform queue mutation and durable receipt boundary. */
    public val executor: RetryAdministrationExecutor,

    /** Maximum bounded compare-and-set attempts for one command execution. */
    public val maximumStateUpdateAttempts: Int = DEFAULT_MAXIMUM_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "DataLoomRetryAdministrationSpec maximumStateUpdateAttempts must be at least one."
        }
    }

    /** Avoids rendering collaborator implementation state in diagnostics. */
    override fun toString(): String =
        "DataLoomRetryAdministrationSpec(" +
            "maximumStateUpdateAttempts=$maximumStateUpdateAttempts)"

    private companion object {
        const val DEFAULT_MAXIMUM_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
