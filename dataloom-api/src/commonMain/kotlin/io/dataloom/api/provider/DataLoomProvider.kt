package io.dataloom.api.provider

/**
 * Common platform-independent provider contract for DataLoom integrations.
 *
 * Implementations are responsible for their own thread-safety guarantees and
 * must document any additional concurrency constraints they impose.
 *
 * Implementations must preserve coroutine cancellation and must not swallow
 * cancellation signals.
 */
public interface DataLoomProvider {
    /** Immutable descriptor for this provider implementation. */
    public val descriptor: ProviderDescriptor

    /**
     * Initializes the provider using [context].
     *
     * This contract does not define automatic registration, automatic
     * initialization, retry behavior, threading policy, or dispatcher
     * selection.
     *
     * @param context immutable provider initialization context.
     * @return [ProviderOperationResult.Success] when initialization succeeds, or
     * [ProviderOperationResult.Failure] with a canonical DataLoom error.
     */
    public suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit>

    /**
     * Returns the current provider health snapshot.
     *
     * @return [ProviderOperationResult.Success] with [ProviderHealth] when
     * health can be evaluated, or [ProviderOperationResult.Failure] when health
     * evaluation fails.
     */
    public suspend fun health(): ProviderOperationResult<ProviderHealth>

    /**
     * Closes the provider and releases its resources.
     *
     * @return [ProviderOperationResult.Success] when close succeeds, or
     * [ProviderOperationResult.Failure] with a canonical DataLoom error.
     */
    public suspend fun close(): ProviderOperationResult<Unit>
}
