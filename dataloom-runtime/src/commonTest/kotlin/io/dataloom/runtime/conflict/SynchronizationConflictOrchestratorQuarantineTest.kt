package io.dataloom.runtime.conflict

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictQuarantineObservation
import io.dataloom.api.conflict.ConflictQuarantinePolicy
import io.dataloom.api.conflict.ConflictQuarantineRecord
import io.dataloom.api.conflict.ConflictQuarantineScope
import io.dataloom.api.conflict.ConflictQuarantineStatus
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableConflictQuarantineLog
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.identifier.EntityType
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.SynchronizationSessionId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.model.SynchronizationMode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.conflict.ConflictResolverSelectionRule.ForEntityType
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the orchestrator's opt-in quarantine step: counting per entity, the
 * threshold/window, restart survival, fail-closed behaviour, and that an
 * orchestrator without a tracker is unchanged.
 */
class SynchronizationConflictOrchestratorQuarantineTest {

    private val detectorId = ConflictDetectorId("detector")
    private val invoice = EntityType("invoice")
    private val document = EntityType("document")
    private val serverWins = ConflictResolverId("dataloom.builtin.server-wins")
    private val clientWins = ConflictResolverId("dataloom.builtin.client-wins")

    private class RecordingResolver(override val id: ConflictResolverId) : ConflictResolver {
        var invocations = 0
            private set

        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
            invocations++
            return ConflictResolutionDecision.UseRemote()
        }
    }

    private class AlwaysConflictDetector(override val id: ConflictDetectorId) : ConflictDetector {
        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult =
            ConflictDetectionResult.ConflictDetected(
                SynchronizationConflict(
                    id = ConflictId("conflict-${request.localChange.entity.id.value}"),
                    type = ConflictType.CONCURRENT_CHANGE,
                    entity = request.localChange.entity,
                    localChange = request.localChange,
                    remoteChange = request.remoteChange,
                ),
            )
    }

    private class MutableClock(var nowMillis: Long = 1_000L) : DataLoomClock {
        override fun now() = DataLoomInstant(nowMillis)
    }

    private class InMemoryStore : DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord> {
        val records = mutableMapOf<ConflictQuarantineScope, DurableStateRecord<ConflictQuarantineRecord>>()
        var failure: DataLoomError? = null
        var alwaysLose = false

        override suspend fun load(
            scope: ConflictQuarantineScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConflictQuarantineRecord>> {
            failure?.let { return ProviderOperationResult.Failure(it) }
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictQuarantineScope, ConflictQuarantineRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConflictQuarantineRecord>> {
            failure?.let { return ProviderOperationResult.Failure(it) }
            val current = records[request.scope]
            if (alwaysLose || current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(request.nextState, (current?.version ?: -1L) + 1L, request.nextSchemaVersion)
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    private fun tracker(
        store: DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord>,
        policy: ConflictQuarantinePolicy = ConflictQuarantinePolicy(),
        clock: DataLoomClock = MutableClock(),
    ) = ConflictQuarantineTracker(
        DurableConflictQuarantineLog(store, maximumStateUpdateAttempts = 2),
        clock,
        policy,
    )

    private fun orchestrator(
        resolvers: List<ConflictResolver>,
        tracker: ConflictQuarantineTracker?,
    ) = SynchronizationConflictOrchestrator(
        detectorRegistry = ConflictDetectorRegistry(listOf(AlwaysConflictDetector(detectorId))),
        resolverRegistry = ConflictResolverRegistry(resolvers),
        quarantineTracker = tracker,
    )

    private fun request(
        entityType: EntityType = invoice,
        entityId: String = "entity-1",
        bindings: ConflictOrchestrationBindings = ConflictOrchestrationBindings(detectorId, serverWins),
    ): ConflictOrchestrationRequest {
        val entity = EntityReference(entityType, EntityId(entityId))
        return ConflictOrchestrationRequest(
            detectionRequest = ConflictDetectionRequest(
                synchronizationRequest = SynchronizationRequest(
                    workflowId = WorkflowId("workflow"),
                    sessionId = SynchronizationSessionId("session"),
                    direction = SynchronizationDirection.PULL,
                    mode = SynchronizationMode.DELTA,
                    context = ExecutionContext(ExecutionId("execution"), CorrelationId("correlation")),
                ),
                localChange = ChangeEvent(ChangeEventId("local"), entity, ChangeOperation.UPDATE),
                remoteChange = ChangeEvent(ChangeEventId("remote"), entity, ChangeOperation.UPDATE),
            ),
            bindings = bindings,
        )
    }

    private fun scopeOf(entityType: EntityType = invoice, entityId: String = "entity-1") =
        ConflictQuarantineScope(entityType, EntityId(entityId))

    // -------------------------------------------------------------------------

    @Test
    fun resolverRunsBelowTheThresholdAndThresholdOccurrenceIsQuarantined_forEachThreshold() {
        for (threshold in listOf(2, 3, 5)) {
            val resolver = RecordingResolver(serverWins)
            val orchestrator = orchestrator(
                listOf(resolver),
                tracker(InMemoryStore(), ConflictQuarantinePolicy(occurrenceThreshold = threshold)),
            )
            val results = List(threshold + 2) { runSuspend { orchestrator.detectAndResolve(request()) } }

            for (index in 0 until threshold - 1) {
                assertIs<ConflictOrchestrationResult.Resolved>(results[index], "threshold=$threshold #$index")
            }
            val first = assertIs<ConflictOrchestrationResult.Quarantined>(results[threshold - 1], "threshold=$threshold")
            assertTrue(first.newlyQuarantined)
            assertEquals(threshold, first.record.occurrenceCount)
            val later = assertIs<ConflictOrchestrationResult.Quarantined>(results[threshold], "threshold=$threshold")
            assertFalse(later.newlyQuarantined)
            assertEquals(threshold - 1, resolver.invocations, "threshold=$threshold: resolver must not run once quarantined")
        }
    }

    @Test
    fun quarantinedResultPreservesTheExactConflictAndDetector() {
        val orchestrator = orchestrator(
            listOf(RecordingResolver(serverWins)),
            tracker(InMemoryStore(), ConflictQuarantinePolicy(occurrenceThreshold = 2)),
        )
        runSuspend { orchestrator.detectAndResolve(request()) }
        val quarantined = assertIs<ConflictOrchestrationResult.Quarantined>(
            runSuspend { orchestrator.detectAndResolve(request()) },
        )
        assertEquals(ConflictId("conflict-entity-1"), quarantined.conflict.id)
        assertEquals(detectorId, quarantined.detectorId)
        assertTrue(quarantined.toString().contains("Quarantined"))
        assertFalse(quarantined.toString().contains("remote"), "diagnostics must not expose change payload identifiers")
    }

    @Test
    fun conflictsThatAreNeverResolvedStillCount() {
        // No resolver configured: every occurrence would be ResolverNotConfigured forever.
        val orchestrator = orchestrator(
            emptyList(),
            tracker(InMemoryStore(), ConflictQuarantinePolicy(occurrenceThreshold = 3)),
        )
        val unresolved = ConflictOrchestrationBindings(detectorId, resolverId = null)
        val results = List(4) { runSuspend { orchestrator.detectAndResolve(request(bindings = unresolved)) } }
        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(results[0])
        assertIs<ConflictOrchestrationResult.ResolverNotConfigured>(results[1])
        assertIs<ConflictOrchestrationResult.Quarantined>(results[2])
        assertIs<ConflictOrchestrationResult.Quarantined>(results[3])
    }

    @Test
    fun unknownResolverOccurrencesAlsoCount() {
        val orchestrator = orchestrator(
            emptyList(),
            tracker(InMemoryStore(), ConflictQuarantinePolicy(occurrenceThreshold = 2)),
        )
        val unknown = ConflictOrchestrationBindings(detectorId, ConflictResolverId("does.not.exist"))
        assertIs<ConflictOrchestrationResult.ResolverNotFound>(
            runSuspend { orchestrator.detectAndResolve(request(bindings = unknown)) },
        )
        assertIs<ConflictOrchestrationResult.Quarantined>(
            runSuspend { orchestrator.detectAndResolve(request(bindings = unknown)) },
        )
    }

    @Test
    fun entitiesAreCountedIndependently() {
        val resolver = RecordingResolver(serverWins)
        val orchestrator = orchestrator(
            listOf(resolver),
            tracker(InMemoryStore(), ConflictQuarantinePolicy(occurrenceThreshold = 2)),
        )
        assertIs<ConflictOrchestrationResult.Resolved>(runSuspend { orchestrator.detectAndResolve(request(entityId = "a")) })
        assertIs<ConflictOrchestrationResult.Resolved>(runSuspend { orchestrator.detectAndResolve(request(entityId = "b")) })
        assertIs<ConflictOrchestrationResult.Quarantined>(runSuspend { orchestrator.detectAndResolve(request(entityId = "a")) })
        assertIs<ConflictOrchestrationResult.Quarantined>(runSuspend { orchestrator.detectAndResolve(request(entityId = "b")) })
        assertIs<ConflictOrchestrationResult.Resolved>(runSuspend { orchestrator.detectAndResolve(request(entityId = "c")) })
    }

    @Test
    fun theSelectionPolicyStillRoutesPerEntityType_andTheSelectedIdIsRecorded() {
        val store = InMemoryStore()
        val server = RecordingResolver(serverWins)
        val client = RecordingResolver(clientWins)
        val bindings = ConflictOrchestrationBindings(
            detectorId = detectorId,
            resolverId = null,
            resolverSelectionPolicy = ConflictResolverSelectionPolicy(
                listOf(ForEntityType(invoice, serverWins), ForEntityType(document, clientWins)),
            ),
        )
        val orchestrator = orchestrator(
            listOf(server, client),
            tracker(store, ConflictQuarantinePolicy(occurrenceThreshold = 3)),
        )

        assertEquals(serverWins, assertIs<ConflictOrchestrationResult.Resolved>(
            runSuspend { orchestrator.detectAndResolve(request(invoice, bindings = bindings)) },
        ).resolverId)
        assertEquals(clientWins, assertIs<ConflictOrchestrationResult.Resolved>(
            runSuspend { orchestrator.detectAndResolve(request(document, bindings = bindings)) },
        ).resolverId)

        assertEquals(serverWins, store.records.getValue(scopeOf(invoice)).state.lastResolverId)
        assertEquals(clientWins, store.records.getValue(scopeOf(document)).state.lastResolverId)
    }

    @Test
    fun anUnroutedConflictIsRecordedWithNoResolver() {
        val store = InMemoryStore()
        val orchestrator = orchestrator(emptyList(), tracker(store))
        runSuspend {
            orchestrator.detectAndResolve(
                request(bindings = ConflictOrchestrationBindings(detectorId, resolverId = null)),
            )
        }
        assertNull(store.records.getValue(scopeOf()).state.lastResolverId)
    }

    // -------------------------------------------------------------------------
    // Window
    // -------------------------------------------------------------------------

    private class WindowCase(val name: String, val windowMillis: Long?, val times: List<Long>, val quarantinedAtIndex: Int?)

    @Test
    fun windowGovernsWhetherOccurrencesAccumulate() {
        val cases = listOf(
            WindowCase("no window: slow occurrences still quarantine", null, listOf(0L, 100_000L, 900_000L), 2),
            WindowCase("burst inside window quarantines", 1_000L, listOf(0L, 300L, 600L), 2),
            WindowCase("slow occurrences never accumulate", 1_000L, listOf(0L, 2_000L, 4_000L, 6_000L), null),
            WindowCase("window restarts after expiry", 1_000L, listOf(0L, 2_000L, 2_300L, 2_600L), 3),
        )
        for (case in cases) {
            val clock = MutableClock()
            val orchestrator = orchestrator(
                listOf(RecordingResolver(serverWins)),
                tracker(InMemoryStore(), ConflictQuarantinePolicy(occurrenceThreshold = 3, windowMillis = case.windowMillis), clock),
            )
            val quarantinedAt = case.times.indexOfFirst { time ->
                clock.nowMillis = time
                runSuspend { orchestrator.detectAndResolve(request()) } is ConflictOrchestrationResult.Quarantined
            }.takeIf { it >= 0 }
            assertEquals(case.quarantinedAtIndex, quarantinedAt, case.name)
        }
    }

    // -------------------------------------------------------------------------
    // Restart survival
    // -------------------------------------------------------------------------

    @Test
    fun quarantineSurvivesFreshTrackerOrchestratorAndLogOverTheSameStore() {
        val store = InMemoryStore()
        val policy = ConflictQuarantinePolicy(occurrenceThreshold = 3)
        val before = orchestrator(listOf(RecordingResolver(serverWins)), tracker(store, policy))
        runSuspend { before.detectAndResolve(request()) }
        runSuspend { before.detectAndResolve(request()) }

        val resolverAfterRestart = RecordingResolver(serverWins)
        val after = orchestrator(listOf(resolverAfterRestart), tracker(store, policy))
        val third = assertIs<ConflictOrchestrationResult.Quarantined>(runSuspend { after.detectAndResolve(request()) })
        assertEquals(3, third.record.occurrenceCount)
        assertEquals(0, resolverAfterRestart.invocations)

        val again = orchestrator(listOf(RecordingResolver(serverWins)), tracker(store, policy))
        assertFalse(assertIs<ConflictOrchestrationResult.Quarantined>(runSuspend { again.detectAndResolve(request()) }).newlyQuarantined)
    }

    @Test
    fun afterReleaseTheEntityIsResolvedAgainAndRequarantinesOnTheNextLoop() {
        val store = InMemoryStore()
        val resolver = RecordingResolver(serverWins)
        val orchestrator = orchestrator(listOf(resolver), tracker(store, ConflictQuarantinePolicy(occurrenceThreshold = 2)))
        runSuspend { orchestrator.detectAndResolve(request()) }
        assertIs<ConflictOrchestrationResult.Quarantined>(runSuspend { orchestrator.detectAndResolve(request()) })

        runSuspend {
            DurableConflictQuarantineLog(store).release(scopeOf(), releaseEvidence())
        }

        assertIs<ConflictOrchestrationResult.Resolved>(runSuspend { orchestrator.detectAndResolve(request()) })
        assertIs<ConflictOrchestrationResult.Quarantined>(runSuspend { orchestrator.detectAndResolve(request()) })
        assertEquals(2, resolver.invocations)
        assertEquals(ConflictQuarantineStatus.QUARANTINED, store.records.getValue(scopeOf()).state.status)
    }

    // -------------------------------------------------------------------------
    // Fail closed
    // -------------------------------------------------------------------------

    @Test
    fun aCounterThatCannotBeUpdatedFailsClosedWithoutInvokingTheResolver() {
        val store = InMemoryStore().apply {
            failure = object : DataLoomError {
                override val code = ErrorCode("STORE-DOWN")
                override val category = ErrorCategory.STORAGE
                override val severity = ErrorSeverity.ERROR
                override val recoverability = Recoverability.RECOVERABLE
                override val message = "down"
                override val cause: Throwable? = null
            }
        }
        val resolver = RecordingResolver(serverWins)
        val result = runSuspend { orchestrator(listOf(resolver), tracker(store)).detectAndResolve(request()) }
        val unavailable = assertIs<ConflictOrchestrationResult.QuarantineUnavailable>(result)
        assertIs<ConflictQuarantineObservation.PersistenceFailure>(unavailable.observation)
        assertEquals(0, resolver.invocations)
    }

    @Test
    fun contentionOnTheCounterFailsClosedWithoutInvokingTheResolver() {
        val store = InMemoryStore().apply { alwaysLose = true }
        val resolver = RecordingResolver(serverWins)
        val result = runSuspend { orchestrator(listOf(resolver), tracker(store)).detectAndResolve(request()) }
        val unavailable = assertIs<ConflictOrchestrationResult.QuarantineUnavailable>(result)
        assertEquals(ConflictQuarantineObservation.ContentionLimitReached, unavailable.observation)
        assertEquals(0, resolver.invocations)
    }

    // -------------------------------------------------------------------------
    // Absent tracker == legacy behaviour
    // -------------------------------------------------------------------------

    @Test
    fun withoutATracker_repeatedConflictsAreResolvedEveryTime() {
        val resolver = RecordingResolver(serverWins)
        val orchestrator = orchestrator(listOf(resolver), tracker = null)
        repeat(50) {
            assertIs<ConflictOrchestrationResult.Resolved>(runSuspend { orchestrator.detectAndResolve(request()) })
        }
        assertEquals(50, resolver.invocations)
    }

    // -------------------------------------------------------------------------

    private fun releaseEvidence() = io.dataloom.api.conflict.ConflictQuarantineRelease(
        commandId = io.dataloom.api.conflict.ConflictAdministrationCommandId("cmd"),
        principalId = io.dataloom.api.conflict.ConflictAdministrationPrincipalId("operator"),
        authorizationId = io.dataloom.api.conflict.ConflictAdministrationAuthorizationId("auth"),
        reason = io.dataloom.api.conflict.ConflictAdministrationReason("fixed"),
        releasedAt = DataLoomInstant(5_000L),
    )

    private object Pending

    private fun <T> runSuspend(block: suspend () -> T): T {
        var rawResult: Any? = Pending
        var thrown: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    if (result.isSuccess) rawResult = result.getOrNull() else thrown = result.exceptionOrNull()
                }
            },
        )
        thrown?.let { throw it }
        check(rawResult !== Pending) { "Suspend block did not complete synchronously in test." }
        @Suppress("UNCHECKED_CAST")
        return rawResult as T
    }
}
