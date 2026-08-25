package io.dataloom.runtime.facade

import io.dataloom.api.conflict.ConflictAdministrationRequest
import io.dataloom.runtime.conflict.ConflictAdministrationCoordinator
import io.dataloom.runtime.conflict.ConflictAdministrationResult

/**
 * Immutable facade adapter over the qualified conflict-administration
 * coordinator.
 *
 * No operational-event outbox bridge exists yet for this capability, unlike
 * [DefaultDataLoomRetryAdministration]/[DefaultDataLoomCircuitAdministration]
 * -- bridging is left as a genuinely separate, later-scoped follow-up rather
 * than bundled into this first slice.
 */
internal class DefaultDataLoomConflictAdministration(
    private val coordinator: ConflictAdministrationCoordinator,
) : DataLoomConflictAdministration {

    override suspend fun execute(
        request: ConflictAdministrationRequest,
    ): ConflictAdministrationResult = coordinator.execute(request)
}
