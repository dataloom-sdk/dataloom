package io.dataloom.runtime.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.AuthorizedConflictAdministrationCommand
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationDecision
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationId
import io.dataloom.api.conflict.ConflictAdministrationAuthorizer
import io.dataloom.api.conflict.ConflictAdministrationCommandId
import io.dataloom.api.conflict.ConflictAdministrationCommandStatus
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetRequest
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetResult
import io.dataloom.api.conflict.ConflictAdministrationExecutionResult
import io.dataloom.api.conflict.ConflictAdministrationExecutor
import io.dataloom.api.conflict.ConflictAdministrationLoadResult
import io.dataloom.api.conflict.ConflictAdministrationPrincipalId
import io.dataloom.api.conflict.ConflictAdministrationReason
import io.dataloom.api.conflict.ConflictAdministrationRequest
import io.dataloom.api.conflict.ConflictAdministrationStateRecord
import io.dataloom.api.conflict.ConflictAdministrationStateStore
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.ResolvedConflictDecisionKind
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictChangeSummary
import io.dataloom.api.conflict.UnresolvedConflictReason
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Verifies [ConflictAdministrationCoordinator]: authorized manual resolution
 * applies for real and is durably recorded (reusing
 * [ResolvedConflictDecisionRecord] with a sentinel manual resolver id);
 * unauthorized commands are rejected without ever invoking the executor;
 * nonexistent and already-resolved conflict ids are well-defined
 * [ConflictAdministrationCommandStatus.POLICY_REJECTED] outcomes; and replay
 * by the same command id is idempotent.
 */
class ConflictAdministrationCoordinatorTest {

    private val entity = EntityReference(EntityType("invoice"), EntityId("entity-001"))
    private val conflictId = ConflictId("conflict-001")
    private val localSummary = UnresolvedConflictChangeSummary(
        changeEventId = ChangeEventId("event-local"),
        operation = ChangeOperation.UPDATE,
        metadata = DataLoomMetadata.Empty,
    )
    private val remoteSummary = UnresolvedConflictChangeSummary(
        changeEventId = ChangeEventId("event-remote"),
        operation = ChangeOperation.UPDATE,
        metadata = DataLoomMetadata.Empty,
    )

    private val clock = MutableClock(1_000L)
    private val authorizer = RecordingAuthorizer()
    private val commandStore = InMemoryConflictAdministrationStore()
    private val executor = RecordingExecutor()
    private val unresolvedStore = InMemoryUnresolvedConflictStore()
    private val resolvedStore = InMemoryResolvedConflictDecisionStore()
    private val unresolvedLog = DurableUnresolvedConflictLog(unresolvedStore)
    private val resolvedLog = DurableResolvedConflictDecisionLog(resolvedStore)

    private val coordinator = ConflictAdministrationCoordinator(
        clock = clock,
        authorizer = authorizer,
        stateStore = commandStore,
        executor = executor,
        unresolvedConflictLog = unresolvedLog,
        resolvedConflictDecisionLog = resolvedLog,
    )

    @Test
    fun authorizedManualResolutionAppliesAndIsDurablyRecorded() = runTest {
        seedUnresolved()
        val request = request(decision = ConflictResolutionDecision.UseLocal())

        val result = assertIs<ConflictAdministrationResult.Succeeded>(coordinator.execute(request))

        assertEquals(ConflictAdministrationCommandStatus.SUCCEEDED, result.record.state.status)
        assertEquals(1, executor.invocations)
        assertEquals(entity, executor.lastCommand?.unresolvedRecord?.entity)

        val recorded = assertIs<ProviderOperationResult.Success<ResolvedConflictDecisionRecord?>>(
            resolvedLog.current(conflictId),
        ).value
        val decisionRecord = checkNotNull(recorded)
        assertEquals(ConflictResolverId("manual:operator-1"), decisionRecord.resolverId)
        assertEquals(ResolvedConflictDecisionKind.USE_LOCAL, decisionRecord.decisionKind)

        // Replay by the same command id is idempotent and does not re-invoke the executor.
        val replay = assertIs<ConflictAdministrationResult.Succeeded>(coordinator.execute(request))
        assertEquals(result.record, replay.record)
        assertEquals(1, executor.invocations)
    }

    @Test
    fun unauthorizedCommandIsRejectedWithoutInvokingExecutor() = runTest {
        seedUnresolved()
        authorizer.decision = ConflictAdministrationAuthorizationDecision.Denied("NOT_A_CONFLICT_ADMIN")
        val request = request()

        val result = assertIs<ConflictAdministrationResult.AuthorizationDenied>(coordinator.execute(request))

        assertEquals("NOT_A_CONFLICT_ADMIN", result.record.state.rejectionReasonCode)
        assertNull(result.record.state.authorizationId)
        assertEquals(0, executor.invocations)
    }

    @Test
    fun nonexistentConflictIdIsPolicyRejected() = runTest {
        // No seedUnresolved() call -- the conflict id was never recorded as unresolved.
        val request = request()

        val result = assertIs<ConflictAdministrationResult.PolicyRejected>(coordinator.execute(request))

        assertEquals("CONFLICT_NOT_UNRESOLVED", result.record.state.rejectionReasonCode)
        assertEquals(0, executor.invocations)
    }

    @Test
    fun alreadyResolvedConflictIdIsPolicyRejected() = runTest {
        seedUnresolved()
        seedResolved()
        val request = request()

        val result = assertIs<ConflictAdministrationResult.PolicyRejected>(coordinator.execute(request))

        assertEquals("CONFLICT_ALREADY_RESOLVED", result.record.state.rejectionReasonCode)
        assertEquals(0, executor.invocations)
    }

    private suspend fun seedUnresolved() {
        unresolvedLog.record(
            conflictId,
            UnresolvedConflictRecord(
                conflictType = ConflictType.CONCURRENT_CHANGE,
                entity = entity,
                localChange = localSummary,
                remoteChange = remoteSummary,
                conflictMetadata = DataLoomMetadata.Empty,
                reason = UnresolvedConflictReason.RESOLVER_NOT_CONFIGURED,
                committedAt = DataLoomInstant(500L),
            ),
        )
    }

    private suspend fun seedResolved() {
        resolvedLog.record(
            conflictId,
            ResolvedConflictDecisionRecord(
                conflictType = ConflictType.CONCURRENT_CHANGE,
                entity = entity,
                localChange = localSummary,
                remoteChange = remoteSummary,
                conflictMetadata = DataLoomMetadata.Empty,
                resolverId = ConflictResolverId("resolver-1"),
                decisionKind = ResolvedConflictDecisionKind.USE_REMOTE,
                decisionMetadata = DataLoomMetadata.Empty,
                committedAt = DataLoomInstant(600L),
            ),
        )
    }

    private fun request(
        decision: ConflictResolutionDecision = ConflictResolutionDecision.UseLocal(),
        commandId: String = "command-1",
    ): ConflictAdministrationRequest = ConflictAdministrationRequest(
        commandId = ConflictAdministrationCommandId(commandId),
        conflictId = conflictId,
        principalId = ConflictAdministrationPrincipalId("operator-1"),
        requestedAt = DataLoomInstant(900L),
        decision = decision,
        reason = ConflictAdministrationReason("operator investigated and chose local"),
    )

    private class MutableClock(var nowMillis: Long) : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
    }

    private class RecordingAuthorizer : ConflictAdministrationAuthorizer {
        var decision: ConflictAdministrationAuthorizationDecision =
            ConflictAdministrationAuthorizationDecision.Authorized(
                ConflictAdministrationAuthorizationId("authorization-1"),
            )

        override suspend fun authorize(
            request: ConflictAdministrationRequest,
        ): ConflictAdministrationAuthorizationDecision = decision
    }

    private class RecordingExecutor : ConflictAdministrationExecutor {
        var invocations: Int = 0
        var lastCommand: AuthorizedConflictAdministrationCommand? = null

        override suspend fun execute(
            command: AuthorizedConflictAdministrationCommand,
        ): ConflictAdministrationExecutionResult {
            invocations += 1
            lastCommand = command
            return ConflictAdministrationExecutionResult.Applied
        }
    }

    private class InMemoryConflictAdministrationStore : ConflictAdministrationStateStore {
        private val records = mutableMapOf<ConflictAdministrationCommandId, ConflictAdministrationStateRecord>()

        override suspend fun load(
            commandId: ConflictAdministrationCommandId,
        ): ProviderOperationResult<ConflictAdministrationLoadResult> = ProviderOperationResult.Success(
            records[commandId]?.let(ConflictAdministrationLoadResult::Found)
                ?: ConflictAdministrationLoadResult.Missing,
        )

        override suspend fun compareAndSet(
            request: ConflictAdministrationCompareAndSetRequest,
        ): ProviderOperationResult<ConflictAdministrationCompareAndSetResult> {
            val current = records[request.commandId]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    ConflictAdministrationCompareAndSetResult.Conflict(current),
                )
            }
            val updated = ConflictAdministrationStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.commandId] = updated
            return ProviderOperationResult.Success(
                ConflictAdministrationCompareAndSetResult.Updated(updated),
            )
        }
    }

    private class InMemoryUnresolvedConflictStore : DurableStateStore<ConflictId, UnresolvedConflictRecord> {
        private val records = mutableMapOf<ConflictId, DurableStateRecord<UnresolvedConflictRecord>>()

        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<UnresolvedConflictRecord>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, UnresolvedConflictRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<UnresolvedConflictRecord>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    private class InMemoryResolvedConflictDecisionStore : DurableStateStore<ConflictId, ResolvedConflictDecisionRecord> {
        private val records = mutableMapOf<ConflictId, DurableStateRecord<ResolvedConflictDecisionRecord>>()

        override suspend fun load(
            scope: ConflictId,
        ): ProviderOperationResult<DurableStateLoadResult<ResolvedConflictDecisionRecord>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictId, ResolvedConflictDecisionRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ResolvedConflictDecisionRecord>> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }
}
