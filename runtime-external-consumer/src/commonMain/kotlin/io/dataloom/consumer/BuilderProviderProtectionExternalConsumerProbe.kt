package io.dataloom.consumer

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomProviderProtectionSpec
import io.dataloom.runtime.facade.ProviderProtectedSynchronizationExecutionResult

/** External JVM/iOS compile probe for DataLoomBuilder provider protection. */
public object BuilderProviderProtectionExternalConsumerProbe {

    public fun configure(
        builder: DataLoomBuilder,
        spec: DataLoomProviderProtectionSpec,
    ): DataLoomBuilder = builder.providerProtectionConfiguration(spec)

    public suspend fun synchronize(
        dataLoom: DataLoom,
        request: SynchronizationRequest,
    ): ProviderProtectedSynchronizationExecutionResult =
        requireNotNull(dataLoom.protectedSynchronization).synchronize(request)
}
