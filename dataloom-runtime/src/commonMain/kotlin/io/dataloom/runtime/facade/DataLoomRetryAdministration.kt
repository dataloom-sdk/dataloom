package io.dataloom.runtime.facade

import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.runtime.retry.RetryAdministrationResult

/**
 * Public operations capability for authorized administrative retry commands.
 *
 * The capability is available only when
 * [DataLoomBuilder.retryAdministrationConfiguration] is supplied. It preserves
 * the coordinator's authorization, idempotency, durable audit, and fail-closed
 * result model without exposing the configured authorizer, state store, or
 * queue executor.
 *
 * Construction and property access perform no authorization, persistence,
 * queue operation, clock read, identifier generation, or coroutine launch.
 */
public interface DataLoomRetryAdministration {

    /**
     * Executes or resumes [request] by its stable command identifier.
     *
     * The exact [RetryAdministrationResult] from the configured coordinator is
     * returned. Caller cancellation propagates unchanged.
     */
    public suspend fun execute(
        request: RetryAdministrationRequest,
    ): RetryAdministrationResult
}
