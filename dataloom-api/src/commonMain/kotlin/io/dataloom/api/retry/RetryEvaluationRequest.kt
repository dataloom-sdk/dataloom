package io.dataloom.api.retry

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.scheduling.SchedulingDelay

/**
 * Immutable model carrying all information needed for a [RetryPolicy] to
 * produce a [RetryDecision].
 *
 * Construction does not evaluate policy, schedule work, access storage,
 * query providers, mutate queues, read the system clock, inspect payload
 * content, or increment the attempt number.
 *
 * ## Equality
 *
 * Equality compares every property by value.
 *
 * ## Sensitive-data restrictions
 *
 * [metadata] must not contain credentials, authentication tokens, encryption
 * keys, personal data, or synchronization payload bytes.
 *
 * @param synchronizationRequest the synchronization request that failed and
 *   requires a retry decision. Required.
 * @param operation the logical operation being evaluated. Required.
 * @param error the canonical DataLoom error describing why the operation
 *   failed. Required. Must not expose credentials or sensitive internal state.
 * @param attempt the retry attempt number for this evaluation. Required.
 *   Attempt number 1 represents the first retry evaluation after the original
 *   operation failure.
 * @param previousDelay the scheduling delay used for the immediately preceding
 *   retry, or `null` when no prior retry delay is available. Allows future
 *   linear, exponential, or custom policies to consider the delay already
 *   applied. The model does not calculate a new delay itself.
 * @param provider the optional provider descriptor identifying which provider
 *   failed. Allows a policy to distinguish between storage, transport,
 *   scheduler, connectivity, or other provider failures. The policy must not
 *   initialize, close, or interact with the provider.
 * @param metadata optional contextual metadata. Defaults to
 *   [DataLoomMetadata.Empty]. Must not contain credentials, keys, payloads,
 *   or personal data.
 */
public data class RetryEvaluationRequest(
    /** The synchronization request that failed and requires a retry decision. */
    public val synchronizationRequest: SynchronizationRequest,

    /** The logical operation being evaluated. */
    public val operation: RetryOperation,

    /**
     * The canonical DataLoom error describing why the operation failed.
     *
     * Must not expose credentials, encryption keys, or sensitive internal
     * state. Provider-specific exceptions must already be mapped to
     * [DataLoomError] before constructing this request.
     */
    public val error: DataLoomError,

    /**
     * The retry attempt number for this evaluation.
     *
     * Attempt number 1 represents the first retry evaluation after the
     * original operation failure. The DataLoom runtime supplies and manages
     * this value.
     */
    public val attempt: RetryAttempt,

    /**
     * The scheduling delay used for the immediately preceding retry, or
     * `null` when no prior retry delay is available.
     *
     * This allows future linear, exponential, or custom policies to consider
     * the delay applied during the previous retry cycle. A `null` value means
     * this is the first retry evaluation and no earlier delay exists.
     *
     * The model does not calculate a new delay. The policy produces the next
     * delay through [RetryDecision.Retry.delay].
     */
    public val previousDelay: SchedulingDelay?,

    /**
     * Optional provider descriptor identifying which provider failed.
     *
     * Allows a [RetryPolicy] to distinguish between storage, transport,
     * scheduler, connectivity, or other provider failures. A `null` value
     * means the failure is not associated with a specific provider, or the
     * provider is not known to the caller.
     *
     * The policy must not initialize, close, or interact with the provider.
     * The descriptor must not expose internal credentials or provider secrets.
     */
    public val provider: ProviderDescriptor?,

    /**
     * Optional contextual metadata for this evaluation request.
     *
     * Defaults to [DataLoomMetadata.Empty]. Must not contain credentials,
     * authentication tokens, encryption keys, personal data, or
     * synchronization payload bytes.
     */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
