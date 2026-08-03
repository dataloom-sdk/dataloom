package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitAdministrationAuthorizer
import io.dataloom.api.circuit.CircuitAdministrationExecutor
import io.dataloom.api.circuit.CircuitAdministrationStateStore

/** Immutable configuration for circuit-administration operations assembly. */
public class DataLoomCircuitAdministrationSpec(
    /** Host-owned authorization boundary for privileged circuit commands. */
    public val authorizer: CircuitAdministrationAuthorizer,

    /** Durable command-state and immutable audit boundary. */
    public val stateStore: CircuitAdministrationStateStore,

    /** Platform circuit mutation and durable receipt boundary. */
    public val executor: CircuitAdministrationExecutor,

    /** Maximum bounded compare-and-set attempts for one command execution. */
    public val maximumStateUpdateAttempts: Int = DEFAULT_MAXIMUM_STATE_UPDATE_ATTEMPTS,
) {
    init {
        require(maximumStateUpdateAttempts >= 1) {
            "DataLoomCircuitAdministrationSpec maximumStateUpdateAttempts must be at least one."
        }
    }

    /** Avoids rendering collaborator implementation state in diagnostics. */
    override fun toString(): String =
        "DataLoomCircuitAdministrationSpec(" +
            "maximumStateUpdateAttempts=$maximumStateUpdateAttempts)"

    private companion object {
        const val DEFAULT_MAXIMUM_STATE_UPDATE_ATTEMPTS: Int = 8
    }
}
