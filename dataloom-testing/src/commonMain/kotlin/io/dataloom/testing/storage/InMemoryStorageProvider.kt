package io.dataloom.testing.storage

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.provider.ProviderCapability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.testing.provider.TestProviderLifecycleController

/**
 * In-memory [StorageProvider] for deterministic common tests.
 *
 * The provider records every request, supports scripted results for change
 * operations, and stores checkpoints in memory. The implementation is mutable
 * and not thread-safe.
 *
 * @param descriptor provider descriptor exposed through [StorageProvider.descriptor].
 * @param lifecycleController shared lifecycle controller used by provider tests.
 */
public class InMemoryStorageProvider(
    override val descriptor: ProviderDescriptor = defaultDescriptor(),
    private val lifecycleController: TestProviderLifecycleController = TestProviderLifecycleController(),
) : StorageProvider {
    private val scriptedReadOutboundResults: MutableList<ProviderOperationResult<OutboundChangeReadResult>> =
        mutableListOf()
    private val scriptedAcknowledgeResults: MutableList<ProviderOperationResult<Unit>> = mutableListOf()
    private val scriptedApplyInboundResults: MutableList<ProviderOperationResult<Unit>> = mutableListOf()
    private val storedCheckpoints: LinkedHashMap<CheckpointKey, SynchronizationCheckpoint> = LinkedHashMap()
    private val recordedReadOutboundRequests: MutableList<OutboundChangeReadRequest> = mutableListOf()
    private val recordedAcknowledgeRequests: MutableList<OutboundChangeAcknowledgementRequest> = mutableListOf()
    private val recordedApplyInboundRequests: MutableList<InboundChangeApplyRequest> = mutableListOf()
    private val recordedCheckpointReadRequests: MutableList<CheckpointReadRequest> = mutableListOf()
    private val recordedCheckpointWriteRequests: MutableList<CheckpointWriteRequest> = mutableListOf()
    private var checkpointReadFailure: DataLoomError? = null
    private var checkpointWriteFailure: DataLoomError? = null

    /** Recorded outbound-read requests in call order. */
    public val readOutboundRequests: List<OutboundChangeReadRequest>
        get() = recordedReadOutboundRequests.toList()

    /** Recorded acknowledgement requests in call order. */
    public val acknowledgeRequests: List<OutboundChangeAcknowledgementRequest>
        get() = recordedAcknowledgeRequests.toList()

    /** Recorded inbound-apply requests in call order. */
    public val applyInboundRequests: List<InboundChangeApplyRequest>
        get() = recordedApplyInboundRequests.toList()

    /** Recorded checkpoint-read requests in call order. */
    public val checkpointReadRequests: List<CheckpointReadRequest>
        get() = recordedCheckpointReadRequests.toList()

    /** Recorded checkpoint-write requests in call order. */
    public val checkpointWriteRequests: List<CheckpointWriteRequest>
        get() = recordedCheckpointWriteRequests.toList()

    /**
     * Queues a scripted result for the next [readOutboundChanges] call.
     *
     * @param result provider result to dequeue on a future call.
     */
    public fun enqueueReadOutboundResult(result: ProviderOperationResult<OutboundChangeReadResult>) {
        scriptedReadOutboundResults += result
    }

    /**
     * Queues a scripted result for the next [acknowledgeOutboundChanges] call.
     *
     * @param result provider result to dequeue on a future call.
     */
    public fun enqueueAcknowledgeResult(result: ProviderOperationResult<Unit>) {
        scriptedAcknowledgeResults += result
    }

    /**
     * Queues a scripted result for the next [applyInboundChanges] call.
     *
     * @param result provider result to dequeue on a future call.
     */
    public fun enqueueApplyInboundResult(result: ProviderOperationResult<Unit>) {
        scriptedApplyInboundResults += result
    }

    /**
     * Configures a checkpoint read failure.
     *
     * Pass `null` to restore normal in-memory checkpoint reads.
     *
     * @param error canonical error to return from [readCheckpoint], or `null`.
     */
    public fun setCheckpointReadFailure(error: DataLoomError?) {
        checkpointReadFailure = error
    }

    /**
     * Configures a checkpoint write failure.
     *
     * Pass `null` to restore normal in-memory checkpoint writes.
     *
     * @param error canonical error to return from [writeCheckpoint], or `null`.
     */
    public fun setCheckpointWriteFailure(error: DataLoomError?) {
        checkpointWriteFailure = error
    }

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = lifecycleController.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> = lifecycleController.health()

    override suspend fun close(): ProviderOperationResult<Unit> = lifecycleController.close()

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> {
        recordedReadOutboundRequests += request
        return scriptedReadOutboundResults.removeFirstOrNull()
            ?: ProviderOperationResult.Success(OutboundChangeReadResult.NoChanges)
    }

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> {
        recordedApplyInboundRequests += request
        return scriptedApplyInboundResults.removeFirstOrNull() ?: ProviderOperationResult.Success(Unit)
    }

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> {
        recordedAcknowledgeRequests += request
        return scriptedAcknowledgeResults.removeFirstOrNull() ?: ProviderOperationResult.Success(Unit)
    }

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> {
        recordedCheckpointReadRequests += request
        checkpointReadFailure?.let { return ProviderOperationResult.Failure(it) }
        return ProviderOperationResult.Success(storedCheckpoints[request.key])
    }

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> {
        recordedCheckpointWriteRequests += request
        checkpointWriteFailure?.let { return ProviderOperationResult.Failure(it) }
        storedCheckpoints[request.checkpoint.key] = request.checkpoint
        return ProviderOperationResult.Success(Unit)
    }

    /**
     * Clears recorded requests and lifecycle recordings without resetting state.
     */
    public fun clearRecordings() {
        recordedReadOutboundRequests.clear()
        recordedAcknowledgeRequests.clear()
        recordedApplyInboundRequests.clear()
        recordedCheckpointReadRequests.clear()
        recordedCheckpointWriteRequests.clear()
        lifecycleController.clearRecordings()
    }

    /**
     * Clears checkpoints, scripts, failure overrides, and all recordings.
     */
    public fun resetState() {
        storedCheckpoints.clear()
        scriptedReadOutboundResults.clear()
        scriptedAcknowledgeResults.clear()
        scriptedApplyInboundResults.clear()
        checkpointReadFailure = null
        checkpointWriteFailure = null
        clearRecordings()
    }
}

private fun <T> MutableList<T>.removeFirstOrNull(): T? = if (isEmpty()) null else removeAt(0)

private fun defaultDescriptor(): ProviderDescriptor = ProviderDescriptor(
    id = ProviderId("testing.storage.in-memory"),
    name = ProviderName("InMemoryStorageProvider"),
    type = ProviderType.STORAGE,
    version = ProviderVersion("1.0.0"),
    capabilities = setOf(
        ProviderCapability("testing"),
        ProviderCapability("in-memory"),
    ),
)
