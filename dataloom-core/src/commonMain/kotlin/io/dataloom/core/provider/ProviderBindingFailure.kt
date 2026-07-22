package io.dataloom.core.provider

import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderType

/**
 * Immutable record of a single provider binding failure.
 *
 * ## Purpose
 *
 * [ProviderBindingFailure] is produced by [SynchronizationProviderResolver]
 * for each configured [io.dataloom.api.provider.ProviderId] that cannot be
 * resolved to a valid provider for its runtime role. A
 * [ProviderResolutionResult.Failure] contains an ordered, non-empty list of
 * these records.
 *
 * ## Structural information only
 *
 * [ProviderBindingFailure] exposes structural binding diagnostics:
 *
 * - [requestedId]: the [ProviderId] that was explicitly configured
 * - [expectedType]: the [ProviderType] required for the runtime role
 * - [actualType]: the [ProviderType] declared in the provider's descriptor,
 *   or `null` when the provider was not found
 * - [reason]: the [ProviderBindingFailureReason] classifying the failure
 *
 * ## Security restrictions
 *
 * [ProviderBindingFailure] must not expose:
 * - provider object references or provider internal state
 * - credentials, tokens, or authorization headers
 * - payload bytes, checkpoint tokens, or encryption keys
 * - [Throwable] instances or stack traces
 * - personal data
 *
 * Provider IDs and types are structural identifiers and may appear in
 * diagnostic output.
 *
 * ## Diagnostic representation
 *
 * [toString] is overridden to produce a safe diagnostic string that does not
 * invoke any provider implementation's `toString()` method.
 *
 * ## Value semantics
 *
 * [ProviderBindingFailure] is a `data class` and provides value-based equality
 * and `copy` semantics.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param requestedId the [ProviderId] that was configured in
 *   [SynchronizationProviderBindings] for this role.
 * @param expectedType the [ProviderType] required for the runtime role being
 *   resolved.
 * @param actualType the [ProviderType] declared in the matching provider's
 *   descriptor, or `null` when no provider with [requestedId] exists in the
 *   registry.
 * @param reason the [ProviderBindingFailureReason] classifying why the binding
 *   failed.
 */
public data class ProviderBindingFailure(
    /** The [ProviderId] that was explicitly configured for this runtime role. */
    public val requestedId: ProviderId,

    /** The [ProviderType] required for the runtime role being resolved. */
    public val expectedType: ProviderType,

    /**
     * The [ProviderType] declared in the matching provider's descriptor, or
     * `null` when [reason] is [ProviderBindingFailureReason.PROVIDER_NOT_FOUND].
     */
    public val actualType: ProviderType?,

    /** The [ProviderBindingFailureReason] classifying why the binding failed. */
    public val reason: ProviderBindingFailureReason,
) {
    /**
     * Returns a safe diagnostic representation containing provider IDs, types,
     * and the failure reason.
     *
     * This implementation does not invoke any provider's `toString()` method
     * and does not expose provider internal state.
     */
    override fun toString(): String =
        "ProviderBindingFailure(" +
            "requestedId=${requestedId.value}, " +
            "expectedType=$expectedType, " +
            "actualType=$actualType, " +
            "reason=$reason" +
            ")"
}
