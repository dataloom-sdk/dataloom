package io.dataloom.api.provider

import io.dataloom.api.error.DataLoomError

/**
 * Immutable provider-operation result.
 *
 * Implementations should return [Success] when an operation completes with a
 * value and [Failure] when it completes with a canonical [DataLoomError].
 *
 * This contract does not implement retries, callbacks, mutable completion
 * state, or logging behavior.
 */
public sealed interface ProviderOperationResult<out T> {
    /**
     * Successful provider-operation result that preserves the returned value.
     */
    public data class Success<T>(
        /** Returned operation value. */
        public val value: T,
    ) : ProviderOperationResult<T>

    /**
     * Failed provider-operation result represented by a canonical DataLoom
     * error contract.
     */
    public data class Failure(
        /** Canonical DataLoom error describing the failure. */
        public val error: DataLoomError,
    ) : ProviderOperationResult<Nothing>
}
