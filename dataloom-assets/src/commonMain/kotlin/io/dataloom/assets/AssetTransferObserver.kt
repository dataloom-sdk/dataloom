package io.dataloom.assets

/**
 * Which public [AssetTransferEngine] operation produced an [AssetTransferOutcome].
 *
 * Distinguished separately from [AssetTransferSession.direction] because two
 * of [AssetTransferEngine]'s outcome variants --
 * [AssetTransferOutcome.NotStarted] and [AssetTransferOutcome.SessionStoreFailure]
 * -- carry no [AssetTransferSession] at all (no session was ever created, or
 * the store failed before one could be loaded), so there is no [direction][AssetTransferSession.direction]
 * to read. [AssetTransferObserver.onOutcome] is always told which operation
 * was called, regardless of whether a session exists in the outcome.
 */
public enum class AssetTransferOperation {
    UPLOAD,
    DOWNLOAD,
    CANCEL,
}

/**
 * Optional hook notified, once per [AssetTransferEngine.upload],
 * [AssetTransferEngine.download], or [AssetTransferEngine.cancel] call, with
 * the already-computed [AssetTransferOutcome] that call is about to return.
 *
 * ## Why one notification per call, not one per chunk
 *
 * [AssetTransferEngine] drives a resumable upload/download chunk by chunk
 * internally (see its own class doc), but nothing in this module exposes a
 * per-chunk "chunk committed" event to a caller today, and this interface
 * does not invent one: it mirrors the engine's own existing, already-computed
 * [AssetTransferOutcome] -- the exact value [upload]/[download]/[cancel]
 * already return to their caller -- the same way
 * `io.dataloom.runtime.queue.QueueEntryTransitionObserver` mirrors
 * `io.dataloom.runtime.queue.QueueEntryExecutionOutcome` rather than a new,
 * parallel domain type.
 *
 * ## Ordering
 *
 * [onOutcome] is notified only after [sessionId]'s underlying state is
 * already durably recorded (or, for [AssetTransferOutcome.NotStarted] and
 * [AssetTransferOutcome.SessionStoreFailure], after the engine has already
 * decided no session could be created or read) -- never before. A real
 * implementation must never change or hide the real [AssetTransferOutcome]
 * the calling operation is about to return; see
 * `io.dataloom.runtime.observation.operational.AssetTransferOperationalEventRecorder`
 * for the implementation [io.dataloom.runtime.facade.DataLoomBuilder] wires
 * in when configured, which fully isolates its own failures.
 *
 * @param sessionId the session id the caller originally passed to [operation].
 *   Always present, even for [AssetTransferOutcome.NotStarted] and
 *   [AssetTransferOutcome.SessionStoreFailure], which carry no session of
 *   their own to read it from.
 * @param operation which call produced [outcome].
 * @param outcome the outcome about to be returned to the caller.
 */
public interface AssetTransferObserver {
    public suspend fun onOutcome(
        sessionId: AssetTransferSessionId,
        operation: AssetTransferOperation,
        outcome: AssetTransferOutcome,
    )
}
