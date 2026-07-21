package io.dataloom.api.transport

import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType

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
     * A successful result indicates that the configured transport operation
     * completed successfully. It does not define durable local acknowledgement,
     * per-event acceptance, remote business completion, queue deletion, or
     * checkpoint advancement.
     *
     * @param request immutable push request containing the synchronization
     *   request and outbound change set.
     * @return [ProviderOperationResult.Success] when the transport operation
     *   succeeds, or [ProviderOperationResult.Failure] with a canonical
     *   DataLoom error.
     */
    public suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<Unit>

    /**
     * Pulls inbound synchronization changes using an application-controlled
     * transport operation.
     *
     * A successful result returns either [PullChangesResult.NoChanges] or
     * [PullChangesResult.Changes]. This contract does not automatically apply
     * inbound changes to storage and does not define continuation tokens,
     * cursors, checkpoints, or retry behavior.
     *
     * @param request immutable pull request containing the synchronization
     *   request, optional entity-type restrictions, and optional batch hint.
     * @return [ProviderOperationResult.Success] with a [PullChangesResult] when
     *   the transport operation succeeds, or [ProviderOperationResult.Failure]
     *   with a canonical DataLoom error.
     */
    public suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult>
}
