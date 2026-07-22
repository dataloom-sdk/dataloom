package io.dataloom.core.provider

import io.dataloom.api.provider.ProviderId

/**
 * Immutable model that explicitly binds each synchronization runtime role to a
 * registered provider by [ProviderId].
 *
 * ## Purpose
 *
 * [SynchronizationProviderBindings] declares which provider instances a future
 * synchronization runtime must use for each role. Every binding uses an explicit
 * [ProviderId] so the caller — not the runtime — controls provider selection.
 *
 * ## Required roles
 *
 * [storageProviderId] and [transportProviderId] are required. A synchronization
 * runtime cannot function without a storage adapter and a transport adapter.
 *
 * ## Optional roles
 *
 * [schedulerProviderId], [connectivityProviderId], and [queueProviderId] are
 * optional. When `null`, the corresponding runtime capability is unavailable.
 *
 * ## Explicit selection
 *
 * Provider selection must always be based on [ProviderId]. The runtime must
 * never select a provider by type alone, by registration order, or by any
 * implicit convention.
 *
 * ## Construction restrictions
 *
 * Construction performs no registry lookup, no provider initialization, and no
 * provider operation. This model is a pure, immutable declaration.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * ## Value semantics
 *
 * [SynchronizationProviderBindings] is a `data class` and provides value-based
 * equality and `copy` semantics.
 *
 * ## Security restrictions
 *
 * Do not place credentials, tokens, encryption keys, or personal data in
 * provider identifiers or binding models.
 *
 * @param storageProviderId the [ProviderId] of the required storage provider.
 * @param transportProviderId the [ProviderId] of the required transport provider.
 * @param schedulerProviderId the optional [ProviderId] of the scheduler provider,
 *   or `null` when scheduling is not required.
 * @param connectivityProviderId the optional [ProviderId] of the connectivity
 *   provider, or `null` when connectivity checking is not required.
 * @param queueProviderId the optional [ProviderId] of the queue provider, or
 *   `null` when durable queue persistence is not required.
 */
public data class SynchronizationProviderBindings(
    /** Required [ProviderId] for the storage provider role. */
    public val storageProviderId: ProviderId,

    /** Required [ProviderId] for the transport provider role. */
    public val transportProviderId: ProviderId,

    /** Optional [ProviderId] for the scheduler provider role. `null` when not configured. */
    public val schedulerProviderId: ProviderId? = null,

    /** Optional [ProviderId] for the connectivity provider role. `null` when not configured. */
    public val connectivityProviderId: ProviderId? = null,

    /** Optional [ProviderId] for the queue provider role. `null` when not configured. */
    public val queueProviderId: ProviderId? = null,
)
