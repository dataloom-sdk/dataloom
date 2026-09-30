package io.dataloom.governance.audit

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateStore

/**
 * Thrown by [DurableAuditStore] when the underlying [DurableStateStore] itself
 * fails (a provider/transport/storage error, reported as
 * [ProviderOperationResult.Failure]).
 *
 * [AuditStore]'s contract predates a durable backing and its methods return
 * plain values rather than [ProviderOperationResult], the same way
 * [InMemoryAuditStore] never fails for a reason other than the two
 * [AuditAppendRejectedException] cases it already models. A durable
 * implementation can fail for a third reason -- the store itself is
 * unavailable -- that [AuditAppendRejection] does not represent, so this
 * dedicated exception carries [error] through instead of being silently
 * swallowed or misreported as a chain-integrity problem.
 */
public class AuditStorePersistenceException(
    public val error: DataLoomError,
) : RuntimeException(error.message, error.cause)

/**
 * Durable [AuditStore] backed by a [DurableStateStore], persisting the
 * hash-chained [AuditRecord]s for one [AuditStoreScope].
 *
 * ## Why one [DurableStateStore] scope holds the whole chain
 *
 * See [AuditChainState]'s documentation: keeping every record for a scope
 * under one known key means [head] and [readAll] are answered directly by one
 * [DurableStateStore.load], with no per-record probing to find the current
 * head -- the concern ADR-0010 raised against freezing a per-record layout
 * before retention/overflow/delivery were decided. This slice resolves that by
 * not needing a per-record layout at all.
 *
 * ## Compare-and-set append, not commit-once
 *
 * Unlike this codebase's other commit-once durable logs
 * ([io.dataloom.api.conflict.DurableUnresolvedConflictLog],
 * [io.dataloom.api.strategy.DurableStrategyDecisionEventLog]), [append] does
 * not retry with a recomputed next state on losing a race. An [AuditRecord] is
 * an immutable, fully-formed value handed in by [AuditLog.append] -- its
 * [AuditRecord.sequence], [AuditRecord.previousMac], and [AuditRecord.mac] are
 * already fixed by whatever head [AuditLog] observed. If [store]'s current
 * state has since advanced past that head (a concurrent writer, in this
 * process or another, committed first), [record] can never legitimately land:
 * recomputing and retrying the *same* record would either duplicate a chain
 * position or contradict the new head. So [append] makes exactly one
 * [DurableStateStore.compareAndSet] attempt: if it is lost, this call throws
 * [AuditAppendRejectedException] with [AuditAppendRejection.HEAD_CONFLICT],
 * mirroring [InMemoryAuditStore.append]'s existing behavior for the same
 * situation. The caller -- [AuditLog.append] -- is where a legitimate retry
 * belongs: reading the new head and building a fresh record from it. That
 * retry is naturally correct because it always derives the next record from
 * whatever the head actually is; it never resubmits stale bytes.
 *
 * ## Idempotency: deliberately none at this layer
 *
 * [AuditRecord] has no caller-supplied identifier the way
 * [io.dataloom.api.conflict.UnresolvedConflictRecord] has [io.dataloom.api.identifier.ConflictId]:
 * its identity is purely positional (sequence plus the previous record's MAC).
 * Treating a byte-identical replay of an old, already-committed record as a
 * silent no-op would require distinguishing "the same append retried" from "an
 * old record replayed" with no evidence beyond equality -- a distinction this
 * slice does not have grounds to make safely, and getting it wrong would let a
 * stale or replayed record appear to succeed without ever being re-verified
 * against the *current* chain. [append] therefore always requires [record] to
 * extend the exact current head, full stop; there is no "already applied"
 * outcome. A caller whose append failed for any reason (lost race, or a
 * [AuditStorePersistenceException] of unknown outcome) recovers by calling
 * [AuditLog.append] again, which reads the current head fresh and produces a
 * record that -- if that append truly never landed -- extends it correctly.
 *
 * ## Retention
 *
 * No pruning. See [AuditChainState]'s "Retention" documentation for why, and
 * for the bounded safety limit ([AuditChainState.MAX_RECORD_COUNT], enforced
 * here as [AuditAppendRejection.CAPACITY_EXCEEDED]) that stands in for it.
 *
 * ## Verification
 *
 * [readAll] returns exactly the durably-persisted records in order; a caller
 * verifies them the same way as any other [AuditStore]-backed chain, via
 * [AuditChainVerifier] (see [AuditLog.verify]). Nothing here re-derives or
 * checks a MAC.
 *
 * @param store durable persistence for this scope's [AuditChainState].
 * @param scope which durable audit chain this instance reads and appends to.
 *   See [AuditStoreScope] for how a host chooses one (or several).
 * @param schemaVersion the [io.dataloom.api.state.DurableStateRecord.schemaVersion]
 *   this instance writes.
 */
public class DurableAuditStore(
    private val store: DurableStateStore<AuditStoreScope, AuditChainState>,
    private val scope: AuditStoreScope,
    private val schemaVersion: Int = DEFAULT_SCHEMA_VERSION,
) : AuditStore {

    override suspend fun head(): AuditRecord? = loadState().head

    override suspend fun readAll(): List<AuditRecord> = loadState().records

    /**
     * Appends [record] if it extends the durably-persisted head exactly. See
     * this class's "Compare-and-set append" and "Idempotency" documentation
     * for exactly what "extends" requires and why a mismatch is never treated
     * as an idempotent retry.
     *
     * @throws AuditAppendRejectedException with [AuditAppendRejection.HEAD_CONFLICT]
     *   if [record] does not extend the currently persisted head, or a
     *   concurrent writer commits first between this call's load and its
     *   compare-and-set. With [AuditAppendRejection.CAPACITY_EXCEEDED] if the
     *   scope already holds [AuditChainState.MAX_RECORD_COUNT] records.
     * @throws AuditStorePersistenceException if the underlying [store] fails.
     */
    override suspend fun append(record: AuditRecord) {
        val loaded = load()
        val current = loaded.state
        val expectedSequence = current.records.size.toLong()
        val expectedPreviousMac = current.head?.mac
        if (record.sequence != expectedSequence || record.previousMac != expectedPreviousMac) {
            throw AuditAppendRejectedException(
                AuditAppendRejection.HEAD_CONFLICT,
                "Audit record does not extend the durable store head (expected sequence $expectedSequence).",
            )
        }
        if (current.records.size >= AuditChainState.MAX_RECORD_COUNT) {
            throw AuditAppendRejectedException(
                AuditAppendRejection.CAPACITY_EXCEEDED,
                "Durable audit store scope '${scope.value}' is at its bounded capacity of " +
                    "${AuditChainState.MAX_RECORD_COUNT} records.",
            )
        }
        val next = AuditChainState(current.records + record)
        when (
            val result = store.compareAndSet(
                DurableStateCompareAndSetRequest(
                    scope = scope,
                    expectedVersion = loaded.version,
                    nextState = next,
                    nextSchemaVersion = schemaVersion,
                ),
            )
        ) {
            is ProviderOperationResult.Failure -> throw AuditStorePersistenceException(result.error)
            is ProviderOperationResult.Success -> when (result.value) {
                is DurableStateCompareAndSetResult.Updated -> Unit
                is DurableStateCompareAndSetResult.Conflict ->
                    throw AuditAppendRejectedException(
                        AuditAppendRejection.HEAD_CONFLICT,
                        "A concurrent writer advanced the durable audit chain before this append landed.",
                    )
            }
        }
    }

    private suspend fun loadState(): AuditChainState = load().state

    private class Loaded(val state: AuditChainState, val version: Long?)

    private suspend fun load(): Loaded =
        when (val result = store.load(scope)) {
            is ProviderOperationResult.Failure -> throw AuditStorePersistenceException(result.error)
            is ProviderOperationResult.Success -> when (val loaded = result.value) {
                is DurableStateLoadResult.Missing -> Loaded(AuditChainState.Empty, null)
                is DurableStateLoadResult.Found -> Loaded(loaded.record.state, loaded.record.version)
            }
        }

    public companion object {
        private const val DEFAULT_SCHEMA_VERSION: Int = 1
    }
}
