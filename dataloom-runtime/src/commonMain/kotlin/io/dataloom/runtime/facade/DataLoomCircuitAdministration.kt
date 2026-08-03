package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.runtime.retry.CircuitAdministrationResult

/** Public operations capability for authorized administrative circuit commands. */
public interface DataLoomCircuitAdministration {

    /**
     * Executes or resumes [request] by its stable command identifier.
     *
     * The configured coordinator's exact result is returned and caller
     * cancellation propagates unchanged.
     */
    public suspend fun execute(
        request: CircuitAdministrationRequest,
    ): CircuitAdministrationResult
}
