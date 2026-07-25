package io.dataloom.runtime.facade

import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.model.SynchronizationDirection

/**
 * Thrown by [DataLoomBuilder.build] when the supplied configuration is
 * structurally invalid.
 *
 * ## Purpose
 *
 * [DataLoomBuildException] signals a deterministic build failure caused by
 * missing or incompatible configuration. It is thrown — not returned as a
 * sealed result — because a build failure represents a programming error that
 * must be corrected before the application can proceed.
 *
 * ## Safe diagnostics
 *
 * The exception message identifies the structural problem using safe
 * diagnostic elements only:
 *
 * - Missing configuration field name
 * - [ProviderId] value
 * - [ProviderType] value
 * - [SynchronizationDirection] value
 * - Structural binding failure reason
 *
 * The exception must not expose:
 *
 * - Provider implementation state or `toString()` output
 * - Queue payloads
 * - Synchronization payloads
 * - Credentials, authorization headers, or access tokens
 * - Checkpoint tokens or encryption keys
 * - Personal data
 * - Observer implementation state
 * - Stack traces from provider or observer implementations
 *
 * ## No retry
 *
 * [DataLoomBuildException] must be corrected in code before calling
 * [DataLoomBuilder.build] again. The builder may be single-use; a corrected
 * build requires a new [DataLoomBuilder] instance.
 *
 * ## KMP compatibility
 *
 * Extends [IllegalStateException] for broad compatibility across platforms.
 *
 * @param message the safe diagnostic message describing the build failure.
 */
public class DataLoomBuildException(
    message: String,
) : IllegalStateException(message)
