package io.dataloom.runtime.execution.inbound

import io.dataloom.api.change.ChangeEvent
import io.dataloom.api.change.ChangeSet
import io.dataloom.api.change.EntityReference
import io.dataloom.api.conflict.ConflictDetectionRequest
import io.dataloom.api.conflict.ConflictDetectionResult
import io.dataloom.api.conflict.ConflictDetector
import io.dataloom.api.conflict.ConflictQuarantinePolicy
import io.dataloom.api.conflict.ConflictQuarantineRecord
import io.dataloom.api.conflict.ConflictQuarantineScope
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.conflict.ConflictResolutionRequest
import io.dataloom.api.conflict.ConflictResolver
import io.dataloom.api.conflict.ConflictType
import io.dataloom.api.conflict.DurableConflictQuarantineLog
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.ChangeEventId
import io.dataloom.api.identifier.ChangeSetId
import io.dataloom.api.identifier.ConflictDetectorId
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.identifier.EntityId
import io.dataloom.api.model.ChangeOperation
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.storage.LocalConflictCandidateReadResult
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.conflict.ConflictQuarantineTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D21: a replay that follows a retry-eligible infrastructure failure is not a
 * conflict loop, so it must not consume the quarantine budget. Drives the real
 * [InboundPullSynchronizationPipeline] with failing storage and stores.
 */
class InboundPullConflictQuarantineTransientFailureTest : ConflictDecisionApplicationFixture() {

    private val scope = ConflictQuarantineScope.of(entity)

    private class SwitchableResolver(
        override val id: ConflictResolverId,
        var decision: ConflictResolutionDecision,
    ) : ConflictResolver {
        var invocations = 0
            private set

        override fun resolve(request: ConflictResolutionRequest): ConflictResolutionDecision {
            invocations++
            return decision
        }
    }

    /** Mints a fresh conflict ID per detection so a changed decision is not a non-convergence error. */
    private class MintingDetector(override val id: ConflictDetectorId) : ConflictDetector {
        private var detections = 0

        override fun detect(request: ConflictDetectionRequest): ConflictDetectionResult =
            ConflictDetectionResult.ConflictDetected(
                SynchronizationConflict(
                    id = ConflictId("minted-${++detections}"),
                    type = ConflictType.CONCURRENT_CHANGE,
                    entity = request.localChange.entity,
                    localChange = request.localChange,
                    remoteChange = request.remoteChange,
                ),
            )
    }

    private fun tracker(
        store: DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord>,
        threshold: Int,
    ) = ConflictQuarantineTracker(
        log = DurableConflictQuarantineLog(store, maximumStateUpdateAttempts = 2),
        clock = FixedClock,
        policy = ConflictQuarantinePolicy(occurrenceThreshold = threshold),
    )

    private fun testError(code: String, category: ErrorCategory, recoverability: Recoverability) =
        TestError(ErrorCode(code), category, recoverability)

    private fun storageFailingApply(vararg failures: TestError): FakeStorageProvider =
        storageWithCandidate(
            localEvent,
            MutableList<ProviderOperationResult<Unit>>(failures.size) { ProviderOperationResult.Failure(failures[it]) },
        )

    private fun storageFailingCheckpoint(vararg failures: TestError): FakeStorageProvider =
        FakeStorageProvider(
            candidateResults = mutableMapOf(
                entity.id.value to ProviderOperationResult.Success(LocalConflictCandidateReadResult.Found(localEvent)),
            ),
            writeResults = MutableList<ProviderOperationResult<Unit>>(failures.size) {
                ProviderOperationResult.Failure(failures[it])
            },
        )

    private fun attempt(
        pipeline: InboundPullSynchronizationPipeline,
        storage: FakeStorageProvider,
    ): SynchronizationResult =
        runSuspend {
            pipeline.execute(context(storage, transport(changeSet(remoteEvent), nextCheckpoint = checkpoint)))
        }

    private fun count(store: InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>): Int? =
        store.state(scope)?.occurrenceCount

    // -------------------------------------------------------------------------
    // A transient failure streak at or above the threshold does NOT quarantine
    // -------------------------------------------------------------------------

    private class TransientCase(
        val name: String,
        val failure: TestError,
        val failingCheckpoint: Boolean,
    )

    @Test
    fun aStreakOfRetryEligibleFailuresAboveTheThresholdNeverQuarantines() {
        val cases = listOf(
            TransientCase(
                "apply: recoverable storage",
                testError("APPLY-STORAGE-DOWN", ErrorCategory.STORAGE, Recoverability.RECOVERABLE),
                failingCheckpoint = false,
            ),
            TransientCase(
                "apply: recoverable provider",
                testError("APPLY-PROVIDER-DOWN", ErrorCategory.PROVIDER, Recoverability.RECOVERABLE),
                failingCheckpoint = false,
            ),
            TransientCase(
                "checkpoint write: recoverable storage",
                testError("CHECKPOINT-STORAGE-DOWN", ErrorCategory.STORAGE, Recoverability.RECOVERABLE),
                failingCheckpoint = true,
            ),
        )
        val threshold = 3
        val streak = 8
        for (case in cases) {
            val quarantineStore = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
            val resolver = SwitchableResolver(resolverId, ConflictResolutionDecision.UseRemote())
            val pipeline = pipeline(
                resolver = resolver,
                resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
                quarantineTracker = tracker(quarantineStore, threshold),
            )
            val failures = Array(streak) { case.failure }
            val storage = if (case.failingCheckpoint) storageFailingCheckpoint(*failures) else storageFailingApply(*failures)

            repeat(streak) {
                val failed = assertIs<SynchronizationResult.Failed>(attempt(pipeline, storage), case.name)
                assertEquals(case.failure.code, failed.error.code, case.name)
            }
            val afterStreak = assertNotNull(quarantineStore.state(scope), case.name)
            assertEquals(0, afterStreak.occurrenceCount, case.name)
            assertEquals(false, afterStreak.isQuarantined, case.name)
            assertEquals(streak, resolver.invocations, case.name)

            // The store recovers: the entity is healthy and resolves normally.
            assertIs<SynchronizationResult.Succeeded>(attempt(pipeline, storage), case.name)
            assertEquals(1, count(quarantineStore), case.name)
            assertEquals(false, quarantineStore.state(scope)?.isQuarantined, case.name)
            assertEquals(streak + 1, resolver.invocations, case.name)
        }
    }

    @Test
    fun aTransientDecisionStoreFailureIsCreditedToo() {
        val quarantineStore = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val resolver = SwitchableResolver(resolverId, ConflictResolutionDecision.UseRemote())
        val decisionStoreDown = testError("DECISION-STORE-DOWN", ErrorCategory.STORAGE, Recoverability.RECOVERABLE)
        val pipeline = pipeline(
            resolver = resolver,
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(
                compareAndSetFailure = decisionStoreDown,
            ),
            quarantineTracker = tracker(quarantineStore, threshold = 2),
        )
        val storage = storageWithCandidate(localEvent)

        repeat(6) {
            val failed = assertIs<SynchronizationResult.Failed>(attempt(pipeline, storage))
            assertEquals(decisionStoreDown.code, failed.error.code)
        }

        assertEquals(0, count(quarantineStore))
        assertEquals(0, storage.applyCallCount)
    }

    @Test
    fun aTransientFailureLaterInTheSameBatchCreditsTheEarlierConflicts() {
        val quarantineStore = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val resolver = SwitchableResolver(resolverId, ConflictResolutionDecision.UseRemote())
        val pipeline = pipeline(
            resolver = resolver,
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(quarantineStore, threshold = 2),
        )
        val other = EntityReference(entity.type, EntityId("document-2"))
        val otherRemote = ChangeEvent(ChangeEventId("remote-2"), other, ChangeOperation.UPDATE)
        val readDown = testError("READ-DOWN", ErrorCategory.STORAGE, Recoverability.RECOVERABLE)
        val storage = FakeStorageProvider(
            candidateResults = mutableMapOf(
                entity.id.value to ProviderOperationResult.Success(LocalConflictCandidateReadResult.Found(localEvent)),
                other.id.value to ProviderOperationResult.Failure(readDown),
            ),
        )

        repeat(5) {
            val failed = assertIs<SynchronizationResult.Failed>(
                runSuspend {
                    pipeline.execute(context(storage, transport(changeSet(remoteEvent, otherRemote))))
                },
            )
            assertEquals(readDown.code, failed.error.code)
        }

        assertEquals(0, count(quarantineStore))
        assertEquals(5, resolver.invocations)
    }

    @Test
    fun observationalModeCreditsTransientFailuresToo() {
        val quarantineStore = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val storageDown = testError("OBSERVE-APPLY-DOWN", ErrorCategory.STORAGE, Recoverability.RECOVERABLE)
        val pipeline = pipeline(
            resolver = SwitchableResolver(resolverId, ConflictResolutionDecision.UseRemote()),
            resolvedStore = null,
            quarantineTracker = tracker(quarantineStore, threshold = 2),
        )
        val storage = storageFailingApply(*Array(4) { storageDown })

        repeat(4) { assertIs<SynchronizationResult.Failed>(attempt(pipeline, storage)) }

        assertEquals(0, count(quarantineStore))
        assertEquals(false, quarantineStore.state(scope)?.isQuarantined)
    }

    // -------------------------------------------------------------------------
    // Everything else still counts exactly as before
    // -------------------------------------------------------------------------

    private class CountsCase(val name: String, val failure: TestError)

    @Test
    fun failuresThatAreNotRetryEligibleStillCountAndQuarantineAtTheThreshold() {
        val cases = listOf(
            CountsCase("non-recoverable storage", testError("APPLY-REJECTED", ErrorCategory.STORAGE, Recoverability.NON_RECOVERABLE)),
            CountsCase("unknown recoverability", testError("APPLY-UNKNOWN", ErrorCategory.STORAGE, Recoverability.UNKNOWN)),
            CountsCase(
                "recoverable but protected category",
                testError("APPLY-MISCONFIGURED", ErrorCategory.CONFIGURATION, Recoverability.RECOVERABLE),
            ),
            CountsCase("recoverable conflict category", testError("APPLY-CONFLICT", ErrorCategory.CONFLICT, Recoverability.RECOVERABLE)),
        )
        for (case in cases) {
            val quarantineStore = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
            val pipeline = pipeline(
                resolver = SwitchableResolver(resolverId, ConflictResolutionDecision.UseRemote()),
                resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
                quarantineTracker = tracker(quarantineStore, threshold = 3),
            )
            val storage = storageFailingApply(case.failure, case.failure, case.failure)

            val codes = List(4) {
                assertIs<SynchronizationResult.Failed>(attempt(pipeline, storage), case.name).error.code.value
            }

            assertEquals(
                listOf(case.failure.code.value, case.failure.code.value, "DL-CONFLICT-QUARANTINED", "DL-CONFLICT-QUARANTINED"),
                codes,
                case.name,
            )
            assertEquals(3, count(quarantineStore), case.name)
            assertEquals(true, quarantineStore.state(scope)?.isQuarantined, case.name)
        }
    }

    @Test
    fun aMixedSequenceCountsOnlyTheGenuineRepeats() {
        val quarantineStore = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val resolver = SwitchableResolver(resolverId, ConflictResolutionDecision.Defer())
        val transient = testError("APPLY-STORAGE-DOWN", ErrorCategory.STORAGE, Recoverability.RECOVERABLE)
        val pipeline = pipeline(
            detector = MintingDetector(detectorId),
            resolver = resolver,
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(quarantineStore, threshold = 4),
        )
        val storage = storageFailingApply(transient, transient)

        // genuine (Defer), transient, genuine, transient, genuine, then the fourth genuine repeat quarantines.
        resolver.decision = ConflictResolutionDecision.Defer()
        assertEquals("DL-CONFLICT-DECISION-DEFERRED", assertIs<SynchronizationResult.Failed>(attempt(pipeline, storage)).error.code.value)
        assertEquals(1, count(quarantineStore))

        resolver.decision = ConflictResolutionDecision.UseRemote()
        assertEquals(transient.code, assertIs<SynchronizationResult.Failed>(attempt(pipeline, storage)).error.code)
        assertEquals(1, count(quarantineStore), "the transient attempt was credited back")

        resolver.decision = ConflictResolutionDecision.Defer()
        assertEquals("DL-CONFLICT-DECISION-DEFERRED", assertIs<SynchronizationResult.Failed>(attempt(pipeline, storage)).error.code.value)
        assertEquals(2, count(quarantineStore))

        resolver.decision = ConflictResolutionDecision.UseRemote()
        assertEquals(transient.code, assertIs<SynchronizationResult.Failed>(attempt(pipeline, storage)).error.code)
        assertEquals(2, count(quarantineStore), "the second transient attempt was credited back")

        resolver.decision = ConflictResolutionDecision.Defer()
        assertEquals("DL-CONFLICT-DECISION-DEFERRED", assertIs<SynchronizationResult.Failed>(attempt(pipeline, storage)).error.code.value)
        assertEquals(3, count(quarantineStore))

        resolver.decision = ConflictResolutionDecision.Defer()
        assertEquals("DL-CONFLICT-QUARANTINED", assertIs<SynchronizationResult.Failed>(attempt(pipeline, storage)).error.code.value)
        assertEquals(4, count(quarantineStore))
        assertTrue(quarantineStore.state(scope)!!.isQuarantined)
        // Quarantine still blocks application and the checkpoint (fail closed).
        assertEquals(2, storage.applyCallCount)
        assertTrue(storage.writtenCheckpoints.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Robustness of the credit itself
    // -------------------------------------------------------------------------

    @Test
    fun creditsSurviveARestartOfTheTrackerAndLogOverTheSamePersistedState() {
        val quarantineStore = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val transient = testError("APPLY-STORAGE-DOWN", ErrorCategory.STORAGE, Recoverability.RECOVERABLE)
        val storage = storageFailingApply(transient, transient, transient)

        val before = pipeline(
            resolver = SwitchableResolver(resolverId, ConflictResolutionDecision.UseRemote()),
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(quarantineStore, threshold = 3),
        )
        repeat(2) { assertIs<SynchronizationResult.Failed>(attempt(before, storage)) }
        assertEquals(0, count(quarantineStore))

        // "Restart": brand-new pipeline, tracker and log over the same persisted records.
        val after = pipeline(
            resolver = SwitchableResolver(resolverId, ConflictResolutionDecision.UseRemote()),
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(quarantineStore, threshold = 3),
        )
        assertIs<SynchronizationResult.Failed>(attempt(after, storage))
        assertEquals(0, count(quarantineStore))
        assertIs<SynchronizationResult.Succeeded>(attempt(after, storage))
        assertEquals(1, count(quarantineStore))
    }

    @Test
    fun aCreditThatCannotBePersistedNeverMasksTheRealFailure() {
        val inner = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val flaky = WriteFailureAfterFirstStore(inner)
        val transient = testError("APPLY-STORAGE-DOWN", ErrorCategory.STORAGE, Recoverability.RECOVERABLE)
        val pipeline = pipeline(
            resolver = SwitchableResolver(resolverId, ConflictResolutionDecision.UseRemote()),
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(flaky, threshold = 5),
        )

        val failed = assertIs<SynchronizationResult.Failed>(attempt(pipeline, storageFailingApply(transient)))

        // The apply error is reported; the credit failed, so the count stays as recorded (conservative).
        assertEquals(transient.code, failed.error.code)
        assertEquals(1, count(inner))
    }

    private class WriteFailureAfterFirstStore(
        private val delegate: DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord>,
    ) : DurableStateStore<ConflictQuarantineScope, ConflictQuarantineRecord> {
        private var writes = 0

        override suspend fun load(
            scope: ConflictQuarantineScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConflictQuarantineRecord>> = delegate.load(scope)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConflictQuarantineScope, ConflictQuarantineRecord>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConflictQuarantineRecord>> =
            if (writes++ == 0) {
                delegate.compareAndSet(request)
            } else {
                ProviderOperationResult.Failure(
                    TestError(ErrorCode("CREDIT-STORE-DOWN"), ErrorCategory.STORAGE, Recoverability.RECOVERABLE),
                )
            }
    }

    // -------------------------------------------------------------------------
    // Only the failing batch is credited
    // -------------------------------------------------------------------------

    @Test
    fun anEarlierAppliedBatchKeepsItsOccurrenceWhenALaterBatchFailsTransiently() {
        val quarantineStore = InMemoryDurableStore<ConflictQuarantineScope, ConflictQuarantineRecord>()
        val first = entity
        val second = EntityReference(entity.type, EntityId("document-2"))
        val secondLocal = ChangeEvent(ChangeEventId("local-2"), second, ChangeOperation.UPDATE)
        val secondRemote = ChangeEvent(ChangeEventId("remote-2"), second, ChangeOperation.UPDATE)
        val transient = testError("APPLY-STORAGE-DOWN", ErrorCategory.STORAGE, Recoverability.RECOVERABLE)
        val storage = FakeStorageProvider(
            candidateResults = mutableMapOf(
                first.id.value to ProviderOperationResult.Success(LocalConflictCandidateReadResult.Found(localEvent)),
                second.id.value to ProviderOperationResult.Success(LocalConflictCandidateReadResult.Found(secondLocal)),
            ),
            applyResults = mutableListOf(
                ProviderOperationResult.Success(Unit),
                ProviderOperationResult.Failure(transient),
            ),
        )
        val pipeline = pipeline(
            detector = MintingDetector(detectorId),
            resolver = SwitchableResolver(resolverId, ConflictResolutionDecision.UseRemote()),
            resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>(),
            quarantineTracker = tracker(quarantineStore, threshold = 5),
        )
        val pages = SequencedTransport(
            listOf(
                PullChangesResult.Changes(
                    ChangeSet(ChangeSetId("page-1"), listOf(remoteEvent)),
                    hasMore = true,
                    nextCheckpoint = checkpoint,
                ),
                PullChangesResult.Changes(
                    ChangeSet(ChangeSetId("page-2"), listOf(secondRemote)),
                    hasMore = false,
                    nextCheckpoint = checkpoint,
                ),
            ),
        )

        val failed = assertIs<SynchronizationResult.Failed>(runSuspend { pipeline.execute(context(storage, pages)) })

        assertEquals(transient.code, failed.error.code)
        // Page 1 was applied and checkpointed: a genuine, resolved occurrence that is never replayed.
        assertEquals(1, count(quarantineStore))
        // Page 2 failed transiently and will be replayed: its occurrence was credited back.
        assertEquals(0, quarantineStore.state(ConflictQuarantineScope.of(second))?.occurrenceCount)
    }

    private class SequencedTransport(private val pages: List<PullChangesResult>) : TransportProvider {
        private var next = 0

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId("transport-sequenced"),
            name = ProviderName("Sequenced transport"),
            type = ProviderType.TRANSPORT,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(context: ProviderInitializationContext): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(status = ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun pushChanges(request: PushChangesRequest): ProviderOperationResult<ChangeSetAcknowledgement> =
            error("push is not used by this pull-only test")

        override suspend fun pullChanges(request: PullChangesRequest): ProviderOperationResult<PullChangesResult> =
            ProviderOperationResult.Success(pages[next++])
    }
}
