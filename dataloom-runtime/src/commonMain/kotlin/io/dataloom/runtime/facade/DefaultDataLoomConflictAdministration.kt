package io.dataloom.runtime.facade

import io.dataloom.api.conflict.ConflictAdministrationRequest
import io.dataloom.api.operational.DurableOperationalEventOutbox
import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.runtime.conflict.ConflictAdministrationCoordinator
import io.dataloom.runtime.conflict.ConflictAdministrationResult
import io.dataloom.runtime.observation.operational.ConflictResolutionOperationalEventBridge
import kotlin.coroutines.cancellation.CancellationException

/**
 * Immutable facade adapter over the qualified conflict-administration
 * coordinator.
 *
 * ## DL-042 operational-event outbox bridge (optional)
 *
 * When [operationalEventOutbox] and [operationalEventOutboxScope] are both
 * supplied (see [DataLoomConflictResolutionOperationalEventOutboxSpec] /
 * `DataLoomBuilder.conflictResolutionOperationalEventOutboxConfiguration`),
 * every terminal [ConflictAdministrationResult] this adapter returns is also
 * bridged into an [io.dataloom.api.operational.OperationalEventEnvelope] by
 * [ConflictResolutionOperationalEventBridge] and durably appended -- after
 * the coordinator's real result already exists, never blocking or altering
 * it. A durable-recording failure is swallowed and never surfaces as a
 * [ConflictAdministrationResult] change or a thrown exception; only
 * [CancellationException] still propagates. When either collaborator is
 * `null`, this class behaves byte-for-byte as it did before this bridge
 * existed. Mirrors [DefaultDataLoomRetryAdministration]/
 * [DefaultDataLoomCircuitAdministration] exactly -- this capability reuses
 * the existing conflict-resolution operational-event outbox opt-in point
 * rather than a new one, since bridged administration commands and bridged
 * detection outcomes both describe the same conflict-engine subsystem. See
 * [ConflictResolutionOperationalEventBridge]'s own class doc's
 * "Administration commands share this bridge, under a third domain prefix"
 * for why one shared bridge object, not a duplicate.
 */
internal class DefaultDataLoomConflictAdministration(
    private val coordinator: ConflictAdministrationCoordinator,
    private val operationalEventOutbox: DurableOperationalEventOutbox? = null,
    private val operationalEventOutboxScope: OperationalEventOutboxScope? = null,
) : DataLoomConflictAdministration {

    override suspend fun execute(
        request: ConflictAdministrationRequest,
    ): ConflictAdministrationResult {
        val result = coordinator.execute(request)
        recordOperationalEvent(request, result)
        return result
    }

    private suspend fun recordOperationalEvent(
        request: ConflictAdministrationRequest,
        result: ConflictAdministrationResult,
    ) {
        val outbox = operationalEventOutbox ?: return
        val scope = operationalEventOutboxScope ?: return
        try {
            val envelope = ConflictResolutionOperationalEventBridge.toEnvelope(request, result)
            outbox.append(scope, envelope)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ordinary: Exception) {
            // Intentionally swallowed -- see class doc above.
        }
    }
}
