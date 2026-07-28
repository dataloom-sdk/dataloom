package io.dataloom.runtime.submission

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.queue.QueueEnqueueRequest

/**
 * Sealed result produced by [QueuedSynchronizationWorkEncoder.encode] for a
 * single [QueuedSynchronizationSubmission].
 *
 * ## Variants
 *
 * | Variant    | Meaning                                                            |
 * |------------|--------------------------------------------------------------------|
 * | [Encoded]  | Encoding succeeded; carries the ready-to-submit enqueue request.  |
 * | [Rejected] | Encoding failed due to a known condition; carries a canonical error. |
 *
 * ## Unexpected exceptions
 *
 * Unexpected programming exceptions from the encoder are not converted into
 * [Rejected] automatically. They propagate normally from
 * [DataLoomQueueSubmission.submit].
 *
 * ## Sensitive-data restrictions
 *
 * [Rejected.error] must not expose credentials, tokens, encryption keys,
 * personal data, stack traces, or encoded payload bytes.
 *
 * ## Construction restrictions
 *
 * Construction performs no queue operation, no clock read, no identifier
 * generation, and no encoding.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface QueuedSynchronizationWorkEncodingResult {

    /**
     * Encoding succeeded.
     *
     * [request] is the exact [QueueEnqueueRequest] produced by the encoder,
     * ready for structural validation and persistence by the
     * [io.dataloom.api.queue.QueueProvider].
     *
     * ## Construction restrictions
     *
     * Construction does not enqueue the request, read the clock, or generate
     * identifiers.
     *
     * @param request the exact [QueueEnqueueRequest] produced by the encoder.
     *   Required.
     */
    public data class Encoded(
        /** The exact [QueueEnqueueRequest] produced by the encoder. */
        public val request: QueueEnqueueRequest,
    ) : QueuedSynchronizationWorkEncodingResult

    /**
     * Encoding was rejected due to a known structural or policy condition.
     *
     * [error] is the canonical [DataLoomError] describing the rejection reason.
     * No [io.dataloom.api.queue.QueueProvider] operation is performed when
     * encoding is rejected.
     *
     * ## Security restrictions
     *
     * [error] must not expose credentials, tokens, encryption keys, personal
     * data, stack traces, or encoded payload bytes.
     *
     * ## Construction restrictions
     *
     * Construction does not enqueue any entry, read the clock, or generate
     * identifiers.
     *
     * @param error canonical [DataLoomError] classifying the rejection reason.
     *   Required.
     */
    public data class Rejected(
        /** Canonical [DataLoomError] classifying the rejection reason. */
        public val error: DataLoomError,
    ) : QueuedSynchronizationWorkEncodingResult
}
