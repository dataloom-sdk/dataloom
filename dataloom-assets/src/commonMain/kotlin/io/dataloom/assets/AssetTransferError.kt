package io.dataloom.assets

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString

/**
 * The closed set of asset-transfer failure classes. Each carries its
 * canonical [ErrorCategory] and [Recoverability], which the transfer engine
 * uses to decide between leaving a session resumable
 * ([Recoverability.RECOVERABLE]) and failing it terminally.
 *
 * Recorded on a failed [AssetTransferSession] as its terminal failure kind.
 */
public enum class AssetErrorKind(
    public val category: ErrorCategory,
    public val recoverability: Recoverability,
) {
    /** The upload or download would exceed a storage quota. Terminal. */
    QUOTA_EXCEEDED(ErrorCategory.STORAGE, Recoverability.NON_RECOVERABLE),

    /** A chunk's bytes did not match its manifest digest; nothing was committed. Retransmit. */
    CHUNK_DIGEST_MISMATCH(ErrorCategory.SECURITY, Recoverability.RECOVERABLE),

    /** The assembled asset did not match the manifest's whole-object digest. Terminal. */
    OBJECT_DIGEST_MISMATCH(ErrorCategory.SECURITY, Recoverability.NON_RECOVERABLE),

    /** A chunk's length differs from the manifest's descriptor. Terminal (caller bug). */
    CHUNK_LENGTH_MISMATCH(ErrorCategory.VALIDATION, Recoverability.NON_RECOVERABLE),

    /** A chunk index is outside the manifest's layout. Terminal (caller bug). */
    CHUNK_OUT_OF_RANGE(ErrorCategory.VALIDATION, Recoverability.NON_RECOVERABLE),

    /** The provider has no such session (expired or never opened). Terminal; start a new session. */
    SESSION_NOT_FOUND(ErrorCategory.STATE, Recoverability.NON_RECOVERABLE),

    /** The session id was already opened with a different manifest. Terminal. */
    SESSION_CONFLICT(ErrorCategory.STATE, Recoverability.NON_RECOVERABLE),

    /** The provider already holds a different committed asset for this id and version. Terminal. */
    ASSET_VERSION_CONFLICT(ErrorCategory.STATE, Recoverability.NON_RECOVERABLE),

    /** The requested asset (or version) is not committed on the provider. Terminal. */
    ASSET_NOT_FOUND(ErrorCategory.PROVIDER, Recoverability.NON_RECOVERABLE),

    /** Completion was requested before every chunk was committed. Terminal. */
    INCOMPLETE_UPLOAD(ErrorCategory.STATE, Recoverability.NON_RECOVERABLE),

    /** The asset has zero bytes; an empty asset has no chunks and cannot be described by a manifest. */
    EMPTY_ASSET(ErrorCategory.VALIDATION, Recoverability.NON_RECOVERABLE),

    /** The provider rejected the operation for a reason with no more specific kind. Terminal. */
    PROVIDER_REJECTED(ErrorCategory.PROVIDER, Recoverability.NON_RECOVERABLE),

    /** The provider could not be reached or failed transiently. Resumable. */
    PROVIDER_UNAVAILABLE(ErrorCategory.NETWORK, Recoverability.RECOVERABLE),

    /** The upload source's content no longer matches the manifest. Terminal. */
    SOURCE_CONTENT_CHANGED(ErrorCategory.SECURITY, Recoverability.NON_RECOVERABLE),

    /** Reading the upload source failed transiently. Resumable. */
    SOURCE_FAILURE(ErrorCategory.STORAGE, Recoverability.RECOVERABLE),

    /** Writing the download sink failed transiently. Resumable. */
    SINK_FAILURE(ErrorCategory.STORAGE, Recoverability.RECOVERABLE),

    /** The durable transfer-session store failed or was contended. The stored session is unchanged; retry. */
    SESSION_STORE_FAILURE(ErrorCategory.STORAGE, Recoverability.RECOVERABLE),

    /**
     * A persisted transfer session is unusable: it failed decoding or an
     * integrity check, or belongs to a different session id. Terminal for that
     * session id; start a new session.
     */
    SESSION_STATE_CORRUPT(ErrorCategory.STORAGE, Recoverability.NON_RECOVERABLE),
    ;

    /** Stable machine-readable code, `dataloom.assets.<lowercase kind>`. */
    public val code: ErrorCode
        get() = ErrorCode("dataloom.assets.${name.lowercase()}")
}

/**
 * Canonical [DataLoomError] for asset-transfer failures.
 *
 * [message] must be sanitised — it must never contain asset content, key
 * material, credentials, or exception text — so callers pass short fixed
 * descriptions; diagnostic detail belongs in [cause].
 *
 * @param kind the failure class; supplies code, category and recoverability.
 */
public class AssetTransferError(
    public val kind: AssetErrorKind,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError {
    override val code: ErrorCode get() = kind.code
    override val category: ErrorCategory get() = kind.category
    override val severity: ErrorSeverity get() = ErrorSeverity.ERROR
    override val recoverability: Recoverability get() = kind.recoverability

    override fun equals(other: Any?): Boolean =
        other is AssetTransferError && kind == other.kind && message == other.message

    override fun hashCode(): Int = 31 * kind.hashCode() + message.hashCode()

    override fun toString(): String = safeDiagnosticString()
}
