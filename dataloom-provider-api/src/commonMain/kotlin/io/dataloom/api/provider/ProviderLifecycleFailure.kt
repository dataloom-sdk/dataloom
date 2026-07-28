package io.dataloom.api.provider

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderId

/**
 * Immutable record of a canonical failure that occurred during a provider
 * lifecycle operation.
 *
 * ## Immutability and value equality
 *
 * [ProviderLifecycleFailure] is a value type. Equality is based on
 * [providerId], [operation], and [error]. Construction performs no provider
 * action.
 *
 * ## Security restrictions
 *
 * This type must not expose stack traces, credentials, tokens, encryption
 * keys, or personal data. [DataLoomError.message] must contain only sanitized
 * diagnostic information.
 *
 * @param providerId the identifier of the provider that produced the failure.
 * @param operation the lifecycle operation that failed.
 * @param error the canonical [DataLoomError] describing the failure.
 */
public data class ProviderLifecycleFailure(
    /** Identifier of the provider that produced this failure. */
    public val providerId: ProviderId,

    /** Lifecycle operation that produced this failure. */
    public val operation: ProviderLifecycleOperation,

    /** Canonical error describing the failure. */
    public val error: DataLoomError,
)
