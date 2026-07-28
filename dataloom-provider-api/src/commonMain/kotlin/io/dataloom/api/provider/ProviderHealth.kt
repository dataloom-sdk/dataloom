package io.dataloom.api.provider

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError

/**
 * Closed set of provider health status labels.
 *
 * Health status is related to, but distinct from, lifecycle state.
 */
public enum class ProviderHealthStatus {
    /** Health has not been evaluated or cannot be determined. */
    UNKNOWN,

    /** The provider is operating normally. */
    HEALTHY,

    /** The provider remains partially usable. */
    DEGRADED,

    /** The provider is not currently able to perform reliably. */
    UNHEALTHY,
}

/**
 * Immutable provider health snapshot.
 *
 * This model carries health data only. It does not execute health checks,
 * implement retries, or perform logging.
 *
 * Do not place credentials, tokens, encryption keys, personal data, or full
 * payloads in [details].
 *
 * `toString()` is for diagnostics only and is not a serialization format.
 */
public data class ProviderHealth(
    /** Required health status label. */
    public val status: ProviderHealthStatus,

    /** Optional canonical DataLoom error associated with the health status. */
    public val error: DataLoomError? = null,

    /** Optional immutable health details metadata. */
    public val details: DataLoomMetadata = DataLoomMetadata.Empty,
)
