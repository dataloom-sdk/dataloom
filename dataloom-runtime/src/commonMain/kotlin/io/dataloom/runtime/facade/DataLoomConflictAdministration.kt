package io.dataloom.runtime.facade

import io.dataloom.api.conflict.ConflictAdministrationRequest
import io.dataloom.runtime.conflict.ConflictAdministrationResult

/**
 * Public operations capability for authorized manual conflict-resolution
 * commands -- applying a decision to a
 * [io.dataloom.api.conflict.UnresolvedConflictRecord] already durably
 * recorded by [io.dataloom.runtime.conflict.DurableConflictDetectionCoordinator],
 * after the live inbound pull that detected it has already failed closed.
 *
 * The capability is available only when
 * [DataLoomBuilder.conflictAdministrationConfiguration] is supplied. It
 * preserves the coordinator's authorization, eligibility, idempotency,
 * durable audit, and fail-closed result model without exposing the
 * configured authorizer, durable logs, or executor.
 *
 * Construction and property access perform no authorization, persistence,
 * execution, clock read, or coroutine launch.
 */
public interface DataLoomConflictAdministration {

    /**
     * Executes or resumes [request] by its stable command identifier.
     *
     * The exact [ConflictAdministrationResult] from the configured
     * coordinator is returned. Caller cancellation propagates unchanged.
     */
    public suspend fun execute(
        request: ConflictAdministrationRequest,
    ): ConflictAdministrationResult
}
