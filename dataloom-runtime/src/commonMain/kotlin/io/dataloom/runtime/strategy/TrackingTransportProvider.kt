package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider

/**
 * Per-execution transport decorator that records operation boundaries without
 * catching exceptions or changing provider results.
 */
internal class TrackingTransportProvider(
    private val delegate: TransportProvider,
) : TransportProvider {
    override val descriptor = delegate.descriptor

    public var attempted: Boolean = false
        private set

    public var lastOperation: StrategyOperation? = null
        private set

    public var lastFailure: DataLoomError? = null
        private set

    private val completed: MutableList<StrategyOperation> = mutableListOf()

    public val completedOperations: List<StrategyOperation>
        get() = completed.toList()

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = delegate.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        delegate.health()

    override suspend fun close(): ProviderOperationResult<Unit> =
        delegate.close()

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> {
        attempted = true
        lastOperation = StrategyOperation.PUSH_REMOTE
        lastFailure = null
        return when (val result = delegate.pushChanges(request)) {
            is ProviderOperationResult.Success -> {
                completed += StrategyOperation.PUSH_REMOTE
                result
            }
            is ProviderOperationResult.Failure -> {
                lastFailure = result.error
                result
            }
        }
    }

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        attempted = true
        lastOperation = StrategyOperation.PULL_REMOTE
        lastFailure = null
        return when (val result = delegate.pullChanges(request)) {
            is ProviderOperationResult.Success -> {
                completed += StrategyOperation.PULL_REMOTE
                result
            }
            is ProviderOperationResult.Failure -> {
                lastFailure = result.error
                result
            }
        }
    }
}
