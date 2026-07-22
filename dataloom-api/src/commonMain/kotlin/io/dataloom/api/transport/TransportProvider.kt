package io.dataloom.api.transport

import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.synchronization.ChangeSetAcknowledgement

/**
 * Platform-independent provider contract for transporting synchronization
 * changes between DataLoom and an application-controlled remote integration.
 *
 * Implementations adapt DataLoom requests and change sets to concrete transport
 * technologies while keeping protocol-specific APIs outside the shared
 * DataLoom public surface.
 *
 * Implementations must:
 * - expose a [descriptor] whose [ProviderDescriptor.type] is
 *   [ProviderType.TRANSPORT]
 * - preserve coroutine cancellation and not swallow cancellation signals
 * - document and enforce their own thread-safety guarantees
 * - avoid automatically selecting threads or dispatchers
 * - avoid automatic retries, registration, logging of payload content, and
 *   direct application-database access
 */
public interface TransportProvider : DataLoomProvider {
    /**
     * Immutable descriptor for this transport provider.
     *
     * [ProviderDescriptor.type] must be [ProviderType.TRANSPORT].
     */
    override public val descriptor: ProviderDescriptor

    /**
     * Pushes outbound synchronization changes using an application-controlled
     * transport operation.
     *
     * A successful result returns a [ChangeSetAcknowledgement] describing how
     * the remote participant responded to each pushed event. Transport
     * implementations map protocol-specific remote responses to canonical
     * acknowledgement statuses; protocol-specific response types must not
     * escape this provider. A successful provider operation may still
     * contain event-level
     * [io.dataloom.api.synchronization.ChangeAcknowledgementStatus.RETRY] or
     * [io.dataloom.api.synchronization.ChangeAcknowledgementStatus.REJECTED]
     * statuses.
     *
     * This operation does not acknowledge local storage directly, does not
     * implement queue deletion, and does not define checkpoint advancement.
     * Recording the acknowledgement in application-controlled storage is the
     * responsibility of
     * [io.dataloom.api.storage.StorageProvider.acknowledgeOutboundChanges].
     *
     * @param request immutable push request containing the synchronization
     *   request and outbound change set.
     * @return [ProviderOperationResult.Success] with a
     *   [ChangeSetAcknowledgement] when the transport operation succeeds, or
     *   [ProviderOperationResult.Failure] with a canonical DataLoom error.
     */
    public suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement>

    /**
     * Pulls inbound synchronization changes using an application-controlled
     * transport operation.
     *
     * A successful result returns either [PullChangesResult.NoChanges] or
     * [PullChangesResult.Changes], each of which may carry an optional next
     * [io.dataloom.api.synchronization.SynchronizationCheckpoint]. This
     * contract does not automatically apply inbound changes to storage and
     * does not persist or activate the returned checkpoint. The transport
     * provider treats [PullChangesRequest.checkpoint] as opaque unless it
     * owns the token format.
     *
     * @param request immutable pull request containing the synchronization
     *   request, optional entity-type restrictions, optional batch hint, and
     *   optional prior checkpoint.
     * @return [ProviderOperationResult.Success] with a [PullChangesResult] when
     *   the transport operation succeeds, or [ProviderOperationResult.Failure]
     *   with a canonical DataLoom error.
     */
    public suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult>
}
