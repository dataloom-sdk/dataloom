package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.runtime.retry.CircuitAdministrationCoordinator
import io.dataloom.runtime.retry.CircuitAdministrationResult

/** Immutable facade adapter over the circuit-administration coordinator. */
internal class DefaultDataLoomCircuitAdministration(
    private val coordinator: CircuitAdministrationCoordinator,
) : DataLoomCircuitAdministration {

    override suspend fun execute(
        request: CircuitAdministrationRequest,
    ): CircuitAdministrationResult = coordinator.execute(request)
}
