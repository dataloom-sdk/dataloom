package io.dataloom.api.storage

import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType

/**
 * Platform-independent provider contract for exchanging synchronization
 * changes with application-controlled storage.
 *
 * [StorageProvider] is the adapter boundary between the DataLoom runtime and
 * the host application's storage architecture. It is a synchronization adapter,
 * not a replacement repository or DAO. The application repository remains the
 * API through which UI and business logic read and modify domain data.
 *
 * ```text
 * DataLoom Runtime
 *       ↓
 * StorageProvider
 *       ↓
 * Application-controlled storage adapter
 *       ↓
 * Room / SQLDelight / custom storage
 * ```
 *
 * Implementations adapt DataLoom requests and change sets to concrete storage
 * technologies while keeping technology-specific APIs outside the shared
 * DataLoom public surface.
 *
 * ## Application data boundary
 *
 * The application repository remains the API through which UI and business
 * logic read and modify domain data. [StorageProvider] is a synchronization
 * adapter used by DataLoom and is not a replacement repository or DAO. Do not
 * add general-purpose query methods such as `getCustomer`, `observeOrders`,
 * `readEntity`, `queryTable`, or `executeSql` to implementations.
 *
 * ## Thread safety
 *
 * Implementations are responsible for documenting and enforcing their own
 * thread-safety guarantees and additional concurrency constraints.
 *
 * ## Cancellation
 *
 * Implementations must preserve coroutine cancellation and must not swallow
 * cancellation signals.
 *
 * ## Constraints
 *
 * Implementations must:
 * - expose a [descriptor] whose [ProviderDescriptor.type] is [ProviderType.STORAGE]
 * - preserve coroutine cancellation and not swallow cancellation signals
 * - document and enforce their own thread-safety guarantees
 * - avoid automatically selecting threads or dispatchers
 * - avoid automatic initialization, registration, or retry behavior
 * - avoid logging payload content
 * - avoid exposing Room, SQLDelight, DataStore, SharedPreferences, SQLite,
 *   Realm, ObjectBox, DAO, cursor, transaction, or filesystem types
 * - avoid performing transport operations
 */
public interface StorageProvider : DataLoomProvider {

    /**
     * Immutable descriptor for this storage provider.
     *
     * [ProviderDescriptor.type] must be [ProviderType.STORAGE].
     */
    override public val descriptor: ProviderDescriptor

    /**
     * Reads outbound synchronization changes from application-controlled
     * storage.
     *
     * A successful result returns either [OutboundChangeReadResult.NoChanges]
     * when no changes are available, or [OutboundChangeReadResult.Changes]
     * containing a non-empty change set. The result does not automatically
     * acknowledge, delete, or mark events as synchronized.
     *
     * This contract does not define acknowledgement, cursors, checkpoints,
     * or retry behavior.
     *
     * @param request immutable read request containing the synchronization
     *   request, optional entity-type restrictions, and optional batch hint.
     * @return [ProviderOperationResult.Success] with an [OutboundChangeReadResult]
     *   when the read operation succeeds, or [ProviderOperationResult.Failure]
     *   with a canonical DataLoom error.
     */
    public suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult>

    /**
     * Applies inbound synchronization changes to application-controlled
     * storage.
     *
     * A successful result indicates that the configured storage operation
     * completed successfully. The provider implementation decides how change
     * events map to the application-controlled database.
     *
     * This contract does not define partial application, rollback, idempotency,
     * or conflict handling.
     *
     * @param request immutable apply request containing the synchronization
     *   request and inbound change set.
     * @return [ProviderOperationResult.Success] when the apply operation
     *   succeeds, or [ProviderOperationResult.Failure] with a canonical
     *   DataLoom error.
     */
    public suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit>
}
