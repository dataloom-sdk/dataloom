package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitAdministrationAuthorizer
import io.dataloom.api.circuit.CircuitAdministrationExecutor
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitAdministrationStateStore
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomCircuitAdministrationSpec
import io.dataloom.runtime.retry.CircuitAdministrationResult

/** External JVM and Apple consumer probe for circuit operations assembly. */
public object CircuitAdministrationFacadeExternalConsumerProbe {

    public fun configure(
        builder: DataLoomBuilder,
        authorizer: CircuitAdministrationAuthorizer,
        stateStore: CircuitAdministrationStateStore,
        executor: CircuitAdministrationExecutor,
    ): DataLoomBuilder = builder.circuitAdministrationConfiguration(
        DataLoomCircuitAdministrationSpec(authorizer, stateStore, executor),
    )

    public suspend fun execute(
        dataLoom: DataLoom,
        request: CircuitAdministrationRequest,
    ): CircuitAdministrationResult? = dataLoom.circuitAdministration?.execute(request)
}
