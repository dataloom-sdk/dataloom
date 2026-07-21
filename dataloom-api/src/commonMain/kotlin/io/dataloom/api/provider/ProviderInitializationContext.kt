package io.dataloom.api.provider

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ConfigurationVersion
import io.dataloom.api.identifier.RuntimeVersion

/**
 * Immutable initialization context for provider runtime setup.
 *
 * This context is initialization-specific and separate from synchronization
 * request execution context.
 *
 * Construction does not read environment variables or platform APIs, generate
 * versions, or perform logging.
 *
 * Do not place authentication tokens, credentials, encryption keys, personal
 * data, or full payloads in [metadata].
 *
 * `toString()` is for diagnostics only and is not a serialization format.
 */
public data class ProviderInitializationContext(
    /** Optional DataLoom runtime version label. */
    public val runtimeVersion: RuntimeVersion? = null,

    /** Optional host configuration version label. */
    public val configurationVersion: ConfigurationVersion? = null,

    /** Optional immutable initialization metadata. */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
