package io.dataloom.consumer

import io.dataloom.api.retry.RetryAdministrationAuthorizer
import io.dataloom.api.retry.RetryAdministrationExecutor
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryAdministrationStateStore
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomRetryAdministrationSpec
import io.dataloom.runtime.retry.RetryAdministrationResult

/** External JVM and Apple consumer probe for operations-facade assembly. */
public object RetryAdministrationFacadeExternalConsumerProbe {

    public fun configure(
        builder: DataLoomBuilder,
        authorizer: RetryAdministrationAuthorizer,
        stateStore: RetryAdministrationStateStore,
        executor: RetryAdministrationExecutor,
    ): DataLoomBuilder = builder.retryAdministrationConfiguration(
        DataLoomRetryAdministrationSpec(
            authorizer = authorizer,
            stateStore = stateStore,
            executor = executor,
        ),
    )

    public suspend fun execute(
        dataLoom: DataLoom,
        request: RetryAdministrationRequest,
    ): RetryAdministrationResult? = dataLoom.retryAdministration?.execute(request)
}
