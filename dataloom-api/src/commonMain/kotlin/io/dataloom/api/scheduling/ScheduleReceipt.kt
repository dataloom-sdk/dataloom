package io.dataloom.api.scheduling

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ScheduleId

/**
 * Immutable confirmation that a [SchedulerProvider] accepted a
 * [ScheduleRequest].
 *
 * A [ScheduleReceipt] is returned by [SchedulerProvider.schedule] to confirm
 * that the provider registered the scheduling intent. It does not guarantee
 * that synchronization has started, that execution will succeed, or that the
 * platform will trigger the workflow under every operating-system condition.
 *
 * ## Platform identifiers
 *
 * Platform-specific scheduler identifiers (such as WorkManager work IDs) must
 * remain internal to the provider implementation. [ScheduleReceipt] exposes
 * only the canonical DataLoom [id].
 *
 * ## Sensitive-data restrictions
 *
 * [metadata] must not contain credentials, authentication tokens, encryption
 * keys, personal data, or synchronization payloads.
 *
 * ## Equality
 *
 * Equality compares [id] and [metadata] by value.
 *
 * @param id required identifier corresponding to the accepted
 *   [ScheduleRequest.id].
 * @param metadata optional contextual attributes for this receipt. Defaults
 *   to [DataLoomMetadata.Empty].
 */
public data class ScheduleReceipt(
    /** Required identifier corresponding to the accepted [ScheduleRequest.id]. */
    public val id: ScheduleId,

    /**
     * Optional contextual attributes for this receipt.
     *
     * Defaults to [DataLoomMetadata.Empty] when not supplied. Must not contain
     * credentials, tokens, encryption keys, or personal data.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
