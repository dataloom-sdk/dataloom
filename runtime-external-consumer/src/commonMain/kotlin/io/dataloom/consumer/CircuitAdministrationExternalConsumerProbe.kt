package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitAdministrationAction
import io.dataloom.api.circuit.CircuitAdministrationAuthorizer
import io.dataloom.api.circuit.CircuitAdministrationCommandId
import io.dataloom.api.circuit.CircuitAdministrationExecutor
import io.dataloom.api.circuit.CircuitAdministrationPrincipalId
import io.dataloom.api.circuit.CircuitAdministrationReason
import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitAdministrationStateStore
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.CircuitAdministrationCoordinator
import io.dataloom.runtime.retry.CircuitAdministrationResult

public fun circuitAdministrationRequestExternalProbe(): CircuitAdministrationRequest =
    CircuitAdministrationRequest(
        commandId = CircuitAdministrationCommandId("external-circuit-command"),
        scope = CircuitBreakerScope.provider(ProviderId("external-provider")),
        principalId = CircuitAdministrationPrincipalId("external-operator"),
        requestedAt = DataLoomInstant(1L),
        action = CircuitAdministrationAction.OPEN,
        reason = CircuitAdministrationReason("external authorized circuit isolation"),
        openUntil = DataLoomInstant(2L),
    )

public suspend fun circuitAdministrationCoordinatorExternalProbe(
    clock: DataLoomClock,
    authorizer: CircuitAdministrationAuthorizer,
    stateStore: CircuitAdministrationStateStore,
    executor: CircuitAdministrationExecutor,
    request: CircuitAdministrationRequest,
): CircuitAdministrationResult = CircuitAdministrationCoordinator(
    clock = clock,
    authorizer = authorizer,
    stateStore = stateStore,
    executor = executor,
).execute(request)
