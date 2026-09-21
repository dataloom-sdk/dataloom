package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.AuthorizedConflictAdministrationCommand
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationDecision
import io.dataloom.api.conflict.ConflictAdministrationAuthorizationId
import io.dataloom.api.conflict.ConflictAdministrationAuthorizer
import io.dataloom.api.conflict.ConflictAdministrationCommandId
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetRequest
import io.dataloom.api.conflict.ConflictAdministrationCompareAndSetResult
import io.dataloom.api.conflict.ConflictAdministrationExecutionResult
import io.dataloom.api.conflict.ConflictAdministrationExecutor
import io.dataloom.api.conflict.ConflictAdministrationLoadResult
import io.dataloom.api.conflict.ConflictAdministrationPrincipalId
import io.dataloom.api.conflict.ConflictAdministrationReason
import io.dataloom.api.conflict.ConflictAdministrationStateStore
import io.dataloom.api.conflict.ConflictQuarantinePolicy
import io.dataloom.api.conflict.ConflictQuarantineRecord
import io.dataloom.api.conflict.ConflictQuarantineReleaseRequest
import io.dataloom.api.conflict.ConflictQuarantineScope
import io.dataloom.api.conflict.ConflictQuarantineStatus
import io.dataloom.api.conflict.DurableConflictQuarantineLog
import io.dataloom.api.conflict.DurableResolvedConflictDecisionLog
import io.dataloom.api.conflict.DurableUnresolvedConflictLog
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.UnresolvedConflictRecord
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
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
 * Proves the authorized quarantine-release command on
 * [ConflictAdministrationCoordinator]: deny-by-default authorization, durable
 * release evidence, idempotency by command ID, and the not-quarantined /
 * not-configured / failure outcomes.
 */
class ConflictQuarantineReleaseAdministrationTest {

    private val entityType = EntityType("invoice")
    private val entityId = EntityId("inv-1")
    private val scope = ConflictQuarantineScope(entityType, entityId)

    private val clock = object : DataLoomClock {
        override fun now() = DataLoomInstant(7_000L)
    }
    private val quarantineStore = InMemoryQuarantineStore()
    private val quarantineLog = DurableConflictQuarantineLog(quarantineStore)

    private fun coordinator(
        authorizer: ConflictAdministrationAuthorizer,
        withQuarantine: Boolean = true,
        store: DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord> = quarantineStore,
    ) = ConflictAdministrationCoordinator(
        clock = clock,
        authorizer = authorizer,
        stateStore = UnusedCommandStore,
        executor = UnusedExecutor,
        unresolvedConflictLog = DurableUnresolvedConflictLog(EmptyStore<ConflictId, UnresolvedConflictRecord>()),
        resolvedConflictDecisionLog = DurableResolvedConflictDecisionLog(
            EmptyStore<ConflictId, ResolvedConflictDecisionRecord>(),
        ),
        quarantineLog = if (withQuarantine) DurableConflictQuarantineLog(store) else null,
    )

    private fun request(command: String = "release-1") = ConflictQuarantineReleaseRequest(
        commandId = ConflictAdministrationCommandId(command),
        entityType = entityType,
        entityId = entityId,
        principalId = ConflictAdministrationPrincipalId("operator-1"),
        requestedAt = DataLoomInstant(6_000L),
        reason = ConflictAdministrationReason("upstream schema fixed"),
    )

    private suspend fun quarantine() {
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 2)
        repeat(2) {
            quarantineLog.recordOccurrence(scope, ConflictId("c-$it"), null, DataLoomInstant(1_000L + it), policy)
        }
    }

    private fun current(): ConflictQuarantineRecord? = quarantineStore.records[scope]?.state

    private class ReleaseAuthorizer(private val decision: ConflictAdministrationAuthorizationDecision) :
        ConflictAdministrationAuthorizer {
        var releaseCalls = 0
            private set

        override suspend fun authorize(
            request: io.dataloom.api.conflict.ConflictAdministrationRequest,
        ): ConflictAdministrationAuthorizationDecision = error("manual-resolution authorization is not used here")

        override suspend fun authorizeQuarantineRelease(
            request: ConflictQuarantineReleaseRequest,
        ): ConflictAdministrationAuthorizationDecision {
            releaseCalls++
            return decision
        }
    }

    /** Authorizes every manual resolution but, like any pre-quarantine authorizer, never overrides release. */
    private class ManualOnlyAuthorizer : ConflictAdministrationAuthorizer {
        override suspend fun authorize(
            request: io.dataloom.api.conflict.ConflictAdministrationRequest,
        ): ConflictAdministrationAuthorizationDecision =
            ConflictAdministrationAuthorizationDecision.Authorized(ConflictAdministrationAuthorizationId("manual-auth"))
    }

    private fun allow() = ReleaseAuthorizer(
        ConflictAdministrationAuthorizationDecision.Authorized(ConflictAdministrationAuthorizationId("auth-77")),
    )

    // -------------------------------------------------------------------------
    // Authorization: deny-by-default
    // -------------------------------------------------------------------------

    @Test
    fun anAuthorizerThatNeverOptedIn_deniesByDefault_andNothingIsReadOrChanged() = runTest {
        quarantine()
        val readsBefore = quarantineStore.loadCalls
        val before = current()

        val result = coordinator(ManualOnlyAuthorizer()).releaseQuarantine(request())

        val denied = assertIs<ConflictQuarantineReleaseResult.AuthorizationDenied>(result)
        assertEquals("QUARANTINE_RELEASE_NOT_AUTHORIZED", denied.reasonCode)
        assertEquals(readsBefore, quarantineStore.loadCalls, "a denied caller must not cause the quarantine state to be read")
        assertEquals(before, current())
        assertEquals(ConflictQuarantineStatus.QUARANTINED, current()?.status)
    }

    @Test
    fun anExplicitDenialIsReturnedWithTheHostReasonCode() = runTest {
        quarantine()
        val authorizer = ReleaseAuthorizer(ConflictAdministrationAuthorizationDecision.Denied("ROLE_REQUIRED"))

        val result = coordinator(authorizer).releaseQuarantine(request())

        assertEquals("ROLE_REQUIRED", assertIs<ConflictQuarantineReleaseResult.AuthorizationDenied>(result).reasonCode)
        assertEquals(1, authorizer.releaseCalls)
        assertEquals(ConflictQuarantineStatus.QUARANTINED, current()?.status)
    }

    @Test
    fun aDeniedCommandLeavesTheEntityQuarantinedAndAnAuthorizedRetryThenSucceeds() = runTest {
        quarantine()
        assertIs<ConflictQuarantineReleaseResult.AuthorizationDenied>(
            coordinator(ManualOnlyAuthorizer()).releaseQuarantine(request("cmd-a")),
        )
        assertIs<ConflictQuarantineReleaseResult.Released>(coordinator(allow()).releaseQuarantine(request("cmd-b")))
    }

    // -------------------------------------------------------------------------
    // Authorized release
    // -------------------------------------------------------------------------

    @Test
    fun anAuthorizedReleaseClearsTheQuarantineAndRecordsDurableEvidence() = runTest {
        quarantine()

        val result = coordinator(allow()).releaseQuarantine(request())

        val released = assertIs<ConflictQuarantineReleaseResult.Released>(result)
        assertEquals(ConflictQuarantineStatus.COUNTING, released.record.status)
        assertEquals(0, released.record.occurrenceCount)
        val evidence = checkNotNull(current()?.lastRelease)
        assertEquals(ConflictAdministrationCommandId("release-1"), evidence.commandId)
        assertEquals(ConflictAdministrationPrincipalId("operator-1"), evidence.principalId)
        assertEquals(ConflictAdministrationAuthorizationId("auth-77"), evidence.authorizationId)
        assertEquals(ConflictAdministrationReason("upstream schema fixed"), evidence.reason)
        assertEquals(DataLoomInstant(7_000L), evidence.releasedAt)
        assertEquals(1, current()?.releaseCount)
    }

    @Test
    fun replayingTheSameCommandIsIdempotent() = runTest {
        quarantine()
        val authorizer = allow()
        val coordinator = coordinator(authorizer)
        assertIs<ConflictQuarantineReleaseResult.Released>(coordinator.releaseQuarantine(request()))
        val writes = quarantineStore.compareAndSetCalls

        val replay = coordinator.releaseQuarantine(request())

        assertIs<ConflictQuarantineReleaseResult.AlreadyReleased>(replay)
        assertEquals(writes, quarantineStore.compareAndSetCalls)
        assertEquals(1, current()?.releaseCount)
    }

    @Test
    fun releasingAnEntityThatIsNotQuarantinedIsRejectedWithoutChange() = runTest {
        val neverSeen = coordinator(allow()).releaseQuarantine(request())
        assertNull(assertIs<ConflictQuarantineReleaseResult.NotQuarantined>(neverSeen).record)

        quarantineLog.recordOccurrence(scope, ConflictId("c"), null, DataLoomInstant(1L), ConflictQuarantinePolicy())
        val counting = assertIs<ConflictQuarantineReleaseResult.NotQuarantined>(
            coordinator(allow()).releaseQuarantine(request("release-2")),
        )
        assertEquals(1, counting.record?.occurrenceCount)
        assertNull(current()?.lastRelease)
    }

    // -------------------------------------------------------------------------
    // Not configured / failures
    // -------------------------------------------------------------------------

    @Test
    fun withoutQuarantineConfiguredTheCommandIsNotConfiguredAndTheAuthorizerIsNotConsulted() = runTest {
        val authorizer = allow()
        val result = coordinator(authorizer, withQuarantine = false).releaseQuarantine(request())
        assertEquals(ConflictQuarantineReleaseResult.NotConfigured, result)
        assertEquals(0, authorizer.releaseCalls)
    }

    @Test
    fun aQuarantineStoreFailureIsReportedAsPersistenceFailure() = runTest {
        val failing = InMemoryQuarantineStore().apply {
            failure = object : DataLoomError {
                override val code = ErrorCode("STORE-DOWN")
                override val category = ErrorCategory.STORAGE
                override val severity = ErrorSeverity.ERROR
                override val recoverability = Recoverability.RECOVERABLE
                override val message = "down"
                override val cause: Throwable? = null
            }
        }
        val result = coordinator(allow(), store = failing).releaseQuarantine(request())
        assertIs<ConflictQuarantineReleaseResult.PersistenceFailure>(result)
    }

    @Test
    fun theRequestExposesItsQuarantineScope() {
        assertEquals(scope, request().scope)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private class InMemoryQuarantineStore : DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord> {
        val records = mutableMapOf<ConflictQuarantineScope, DurableStateRecord<ConflictQuarantineRecord>>()
        var loadCalls = 0
            private set
        var compareAndSetCalls = 0
            private set
        var failure: DataLoomError? = null

        override suspend fun load(
            scope: ConflictQuarantineScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConflictQuarantineRecord>> {
            loadCalls++
            failure?.let { return ProviderOperationResult.Failure(it) }
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictQuarantineScope, ConflictQuarantineRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConflictQuarantineRecord>> {
            compareAndSetCalls++
            failure?.let { return ProviderOperationResult.Failure(it) }
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(request.nextState, (current?.version ?: -1L) + 1L, request.nextSchemaVersion)
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    private class EmptyStore<S : Any, R : Any> : DurableStateStore<S, R> {
        override suspend fun load(scope: S): ProviderOperationResult<DurableStateLoadResult<R>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<S, R>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<R>> = error("not used by release")
    }

    private object UnusedCommandStore : ConflictAdministrationStateStore {
        override suspend fun load(
            commandId: ConflictAdministrationCommandId,
        ): ProviderOperationResult<ConflictAdministrationLoadResult> = error("not used by release")

        override suspend fun compareAndSet(
            request: ConflictAdministrationCompareAndSetRequest,
        ): ProviderOperationResult<ConflictAdministrationCompareAndSetResult> = error("not used by release")
    }

    private object UnusedExecutor : ConflictAdministrationExecutor {
        override suspend fun execute(
            command: AuthorizedConflictAdministrationCommand,
        ): ConflictAdministrationExecutionResult = error("not used by release")
    }
}
