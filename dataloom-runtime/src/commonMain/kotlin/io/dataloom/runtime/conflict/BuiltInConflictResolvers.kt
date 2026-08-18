package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.identifier.ConflictResolverId

/**
 * Resolves one of DataLoom's deterministic built-in conflict policies by its
 * exact, documented identifier.
 *
 * This lookup is deliberately internal. Applications select a policy through
 * the existing public [ConflictResolverId] binding contract; no second public
 * registry or strategy abstraction is introduced. Application-supplied
 * resolvers are checked before this function, so explicitly registering the
 * same identifier safely overrides the reference built-in implementation.
 */
internal fun builtInConflictResolver(id: ConflictResolverId): ConflictResolver? =
    BuiltInConflictResolverCatalog.lookup(id)

private object BuiltInConflictResolverCatalog {
    private val clientWins: ConflictResolver = FixedDecisionConflictResolver(
        id = ConflictResolverId("dataloom.builtin.client-wins"),
        decision = ConflictResolutionDecision.UseLocal(),
    )
    private val serverWins: ConflictResolver = FixedDecisionConflictResolver(
        id = ConflictResolverId("dataloom.builtin.server-wins"),
        decision = ConflictResolutionDecision.UseRemote(),
    )
    private val lastWriteWins: ConflictResolver = LastWriteWinsConflictResolver()
    private val timestamp: ConflictResolver = TimestampEvidenceConflictResolver
    private val reject: ConflictResolver = RejectConflictResolver
    private val manual: ConflictResolver = FixedDecisionConflictResolver(
        id = ConflictResolverId("dataloom.builtin.manual"),
        decision = ConflictResolutionDecision.Defer(),
    )

    private val resolversById: Map<ConflictResolverId, ConflictResolver> = listOf(
        clientWins,
        serverWins,
        lastWriteWins,
        timestamp,
        reject,
        manual,
    ).associateBy(ConflictResolver::id)

    fun lookup(id: ConflictResolverId): ConflictResolver? = resolversById[id]
}

/** A deterministic resolver that always returns one immutable decision. */
private class FixedDecisionConflictResolver(
    override val id: ConflictResolverId,
    private val decision: ConflictResolutionDecision,
) : ConflictResolver {
    override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision = decision

    override fun toString(): String = "FixedDecisionConflictResolver(id=${id.value})"
}

/**
 * Chooses the change with the newest explicit epoch-millisecond evidence.
 *
 * Evidence is read from resolution-request metadata first and conflict
 * metadata second, using these exact keys:
 *
 * - `dataloom.conflict.local.updated-at-epoch-millis`
 * - `dataloom.conflict.remote.updated-at-epoch-millis`
 *
 * Both values must parse as [Long]. Missing or malformed evidence returns
 * [ConflictResolutionDecision.Defer] rather than inventing an ordering. Equal
 * timestamps deterministically choose the remote change so duplicate delivery
 * and replay converge on one stable outcome.
 */
private object TimestampEvidenceConflictResolver : ConflictResolver {
    override val id: ConflictResolverId = ConflictResolverId("dataloom.builtin.timestamp")

    override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
        val localTimestamp = timestamp(
            request = request,
            key = "dataloom.conflict.local.updated-at-epoch-millis",
        ) ?: return ConflictResolutionDecision.Defer()
        val remoteTimestamp = timestamp(
            request = request,
            key = "dataloom.conflict.remote.updated-at-epoch-millis",
        ) ?: return ConflictResolutionDecision.Defer()

        return if (localTimestamp > remoteTimestamp) {
            ConflictResolutionDecision.UseLocal()
        } else {
            ConflictResolutionDecision.UseRemote()
        }
    }

    private fun timestamp(request: ConflictResolutionRequest, key: String): Long? {
        val rawValue = request.metadata[key] ?: request.conflict.metadata[key] ?: return null
        return rawValue.toLongOrNull()
    }

    override fun toString(): String = "TimestampEvidenceConflictResolver(id=${id.value})"
}

/** Rejects the conflict with one stable, redaction-safe policy error. */
private object RejectConflictResolver : ConflictResolver {
    override val id: ConflictResolverId = ConflictResolverId("dataloom.builtin.reject")

    override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision =
        ConflictResolutionDecision.Fail(BuiltInConflictRejectedError)

    override fun toString(): String = "RejectConflictResolver(id=${id.value})"
}

private object BuiltInConflictRejectedError : DataLoomError {
    override val code: ErrorCode = ErrorCode("DL-CONFLICT-REJECTED-BY-POLICY")
    override val category: ErrorCategory = ErrorCategory.CONFLICT
    override val severity: ErrorSeverity = ErrorSeverity.ERROR
    override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
    override val message: String = "Conflict resolution was rejected by the selected policy."
    override val cause: Throwable? = null

    override fun toString(): String = safeDiagnosticString()
}
