package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.runtime.observation.operational.RetryCircuitAdministrationOperationalEventBridge
import io.dataloom.runtime.retry.CircuitAdministrationCoordinator
import io.dataloom.runtime.retry.CircuitAdministrationResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * Immutable facade adapter over the circuit-administration coordinator.
 *
 * ## DL-042 operational-event outbox bridge (optional)
 *
 * When [operationalEventOutbox] and [operationalEventOutboxScope] are both
 * supplied (see [DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec]
 * / `DataLoomBuilder.retryCircuitAdministrationOperationalEventOutboxConfiguration`),
 * every terminal [CircuitAdministrationResult] this adapter returns is also
 * bridged into an [io.dataloom.api.operational.OperationalEventEnvelope] by
 * [RetryCircuitAdministrationOperationalEventBridge] and durably appended --
 * after the coordinator's real result already exists, never blocking or
 * altering it. A durable-recording failure is swallowed and never surfaces as
 * a [CircuitAdministrationResult] change or a thrown exception; only
 * [CancellationException] still propagates. When either collaborator is
 * `null`, this class behaves byte-for-byte as it did before this bridge
 * existed.
 */
internal class DefaultDataLoomCircuitAdministration(
    private val coordinator: CircuitAdministrationCoordinator,
    private val operationalEventOutbox: DurableOperationalEventOutbox? = null,
    private val operationalEventOutboxScope: OperationalEventOutboxScope? = null,
) : DataLoomCircuitAdministration {

    override suspend fun execute(
        request: CircuitAdministrationRequest,
    ): CircuitAdministrationResult {
        val result = coordinator.execute(request)
        recordOperationalEvent(request, result)
        return result
    }

    private suspend fun recordOperationalEvent(
        request: CircuitAdministrationRequest,
        result: CircuitAdministrationResult,
    ) {
        val outbox = operationalEventOutbox ?: return
        val scope = operationalEventOutboxScope ?: return
        try {
            val envelope = RetryCircuitAdministrationOperationalEventBridge.toEnvelope(request, result)
            outbox.append(scope, envelope)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ordinary: Exception) {
            // Intentionally swallowed -- see class doc above.
        }
    }
}
