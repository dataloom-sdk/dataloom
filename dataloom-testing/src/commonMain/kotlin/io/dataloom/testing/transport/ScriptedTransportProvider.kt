package io.dataloom.testing.transport

import io.dataloom.api.provider.ProviderCapability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.testing.provider.TestProviderLifecycleController

/**
 * Script-driven [TransportProvider] for deterministic tests.
 *
 * Push and pull operations dequeue scripted results in call order and record
 * every received request. Script exhaustion fails fast with an
 * [IllegalStateException].
 *
 * @param descriptor provider descriptor exposed through [TransportProvider.descriptor].
 * @param lifecycleController shared lifecycle controller used by provider tests.
 */
public class ScriptedTransportProvider(
    override val descriptor: ProviderDescriptor = defaultDescriptor(),
    private val lifecycleController: TestProviderLifecycleController = TestProviderLifecycleController(),
) : TransportProvider {
    private val scriptedPushResults: MutableList<ProviderOperationResult<ChangeSetAcknowledgement>> = mutableListOf()
    private val scriptedPullResults: MutableList<ProviderOperationResult<PullChangesResult>> = mutableListOf()
    private val recordedPushRequests: MutableList<PushChangesRequest> = mutableListOf()
    private val recordedPullRequests: MutableList<PullChangesRequest> = mutableListOf()

    /** Recorded push requests in call order. */
    public val pushRequests: List<PushChangesRequest>
        get() = recordedPushRequests.toList()

    /** Recorded pull requests in call order. */
    public val pullRequests: List<PullChangesRequest>
        get() = recordedPullRequests.toList()

    /**
     * Queues the next result to return from [pushChanges].
     *
     * @param result provider result to dequeue on a future push call.
     */
    public fun enqueuePushResult(result: ProviderOperationResult<ChangeSetAcknowledgement>) {
        scriptedPushResults += result
    }

    /**
     * Queues the next result to return from [pullChanges].
     *
     * @param result provider result to dequeue on a future pull call.
     */
    public fun enqueuePullResult(result: ProviderOperationResult<PullChangesResult>) {
        scriptedPullResults += result
    }

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = lifecycleController.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> = lifecycleController.health()

    override suspend fun close(): ProviderOperationResult<Unit> = lifecycleController.close()

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> {
        recordedPushRequests += request
        return scriptedPushResults.removeFirstOrNull()
            ?: throw IllegalStateException(
                "ScriptedTransportProvider: push script exhausted. Enqueue a push result before calling pushChanges.",
            )
    }

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        recordedPullRequests += request
        return scriptedPullResults.removeFirstOrNull()
            ?: throw IllegalStateException(
                "ScriptedTransportProvider: pull script exhausted. Enqueue a pull result before calling pullChanges.",
            )
    }

    /** Clears recorded requests and lifecycle recordings without clearing scripts. */
    public fun clearRecordings() {
        recordedPushRequests.clear()
        recordedPullRequests.clear()
        lifecycleController.clearRecordings()
    }

    /** Clears scripts and recordings. */
    public fun resetState() {
        scriptedPushResults.clear()
        scriptedPullResults.clear()
        clearRecordings()
    }
}

private fun <T> MutableList<T>.removeFirstOrNull(): T? = if (isEmpty()) null else removeAt(0)

private fun defaultDescriptor(): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId("testing.transport.scripted"),
    name = ProviderName("ScriptedTransportProvider"),
    type = ProviderType.TRANSPORT,
    version = ProviderVersion("1.0.0"),
    capabilities = setOf(
        ProviderCapability("testing"),
        ProviderCapability("scripted"),
    ),
)
