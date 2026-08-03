package io.dataloom.runtime.facade

import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.runtime.retry.RetryAdministrationCoordinator
import io.dataloom.runtime.retry.RetryAdministrationResult

/** Immutable facade adapter over the qualified retry-administration coordinator. */
internal class DefaultDataLoomRetryAdministration(
    private val coordinator: RetryAdministrationCoordinator,
) : DataLoomRetryAdministration {

    override suspend fun execute(
        request: RetryAdministrationRequest,
    ): RetryAdministrationResult = coordinator.execute(request)
}
