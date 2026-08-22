package io.dataloom.runtime.facade

import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.runtime.observation.operational.RetryCircuitAdministrationOperationalEventBridge
import io.dataloom.runtime.retry.RetryAdministrationCoordinator
import io.dataloom.runtime.retry.RetryAdministrationResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * Immutable facade adapter over the qualified retry-administration coordinator.
 *
 * ## DL-042 operational-event outbox bridge (optional)
 *
 * When [operationalEventOutbox] and [operationalEventOutboxScope] are both
 * supplied (see [DataLoomRetryCircuitAdministrationOperationalEventOutboxSpec]
 * / `DataLoomBuilder.retryCircuitAdministrationOperationalEventOutboxConfiguration`),
 * every terminal [RetryAdministrationResult] this adapter returns is also
 * bridged into an [io.dataloom.api.operational.OperationalEventEnvelope] by
 * [RetryCircuitAdministrationOperationalEventBridge] and durably appended --
 * after the coordinator's real result already exists, never blocking or
 * altering it. A durable-recording failure is swallowed and never surfaces as
 * a [RetryAdministrationResult] change or a thrown exception; only
 * [CancellationException] still propagates. When either collaborator is
 * `null`, this class behaves byte-for-byte as it did before this bridge
 * existed.
 */
internal class DefaultDataLoomRetryAdministration(
    private val coordinator: RetryAdministrationCoordinator,
    private val operationalEventOutbox: DurableOperationalEventOutbox? = null,
    private val operationalEventOutboxScope: OperationalEventOutboxScope? = null,
) : DataLoomRetryAdministration {

    override suspend fun execute(
        request: RetryAdministrationRequest,
    ): RetryAdministrationResult {
        val result = coordinator.execute(request)
        recordOperationalEvent(request, result)
        return result
    }

    private suspend fun recordOperationalEvent(
        request: RetryAdministrationRequest,
        result: RetryAdministrationResult,
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
