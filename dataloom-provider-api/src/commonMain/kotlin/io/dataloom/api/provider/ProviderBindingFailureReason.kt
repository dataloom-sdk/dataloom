package io.dataloom.api.provider

/**
 * Reason why a provider binding could not be resolved.
 *
 * Each value describes a distinct structural failure condition. Use this enum
 * to understand what went wrong when [SynchronizationProviderResolver] returns
 * a [ProviderResolutionResult.Failure].
 *
 * ## Usage
 *
 * [ProviderBindingFailureReason] is always accompanied by a
 * [ProviderBindingFailure] that carries the [io.dataloom.api.provider.ProviderId]
 * and additional context for each reason.
 *
 * ## Stability
 *
 * Enum ordinals are not a compatibility contract and must not be persisted or
 * compared by ordinal. Use named values only.
 *
 * ## No exception class names
 *
 * Reasons are defined independently of exception class names and must not be
 * derived from exception class names or stack traces.
 */
public enum class ProviderBindingFailureReason {

    /**
     * The configured [io.dataloom.api.provider.ProviderId] does not exist in
     * [ProviderRegistry].
     *
     * The registry was searched by exact [io.dataloom.api.provider.ProviderId]
     * and no matching provider was found.
     */
    PROVIDER_NOT_FOUND,

    /**
     * The provider exists in [ProviderRegistry] but its
     * [io.dataloom.api.provider.ProviderDescriptor.type] does not match the
     * expected [io.dataloom.api.provider.ProviderType] for the runtime role.
     *
     * For example, a [io.dataloom.api.provider.ProviderId] configured for the
     * storage role was found, but the matching provider's descriptor declares
     * [io.dataloom.api.provider.ProviderType.TRANSPORT].
     *
     * Type mismatch is evaluated before [PROVIDER_CONTRACT_MISMATCH].
     */
    PROVIDER_TYPE_MISMATCH,

    /**
     * The provider's descriptor declares the expected
     * [io.dataloom.api.provider.ProviderType], but the provider object does not
     * implement the required specialized provider interface for that role.
     *
     * For example, a provider's descriptor declares
     * [io.dataloom.api.provider.ProviderType.STORAGE] but the provider does not
     * implement [io.dataloom.api.storage.StorageProvider].
     */
    PROVIDER_CONTRACT_MISMATCH,
}
