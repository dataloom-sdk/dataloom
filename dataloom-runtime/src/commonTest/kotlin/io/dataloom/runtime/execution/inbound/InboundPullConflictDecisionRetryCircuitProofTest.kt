package io.dataloom.runtime.execution.inbound

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.conflict.ConflictResolutionDecision
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.RetryPolicyId
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.conflict.ResolvedConflictDecisionRecord
import io.dataloom.api.identifier.ConflictId
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.core.provider.ResolvedSynchronizationProviders
import io.dataloom.runtime.execution.SynchronizationExecutionContext
import io.dataloom.runtime.execution.protection.ProviderProtectionEvidenceCollector
import io.dataloom.runtime.execution.protection.ProviderProtectionStorageBridge
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.RetryBackoffStrategy
import io.dataloom.runtime.retry.StandardRetryPolicy
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.StorageCircuitProtectionRuntime
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.SynchronizationRetryEvaluation
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves -- rather than reimplements -- that applying a resolved conflict
 * decision (`UseLocal`/`UseRemote`/`Merge`) is already retried and
 * circuit-protected exactly like every other `StorageProvider.applyInboundChanges`
 * call, because [InboundPullSynchronizationPipeline] applies a
 * conflict-prepared [io.dataloom.api.change.ChangeSet] through the exact same
 * call as any other batch, and [io.dataloom.runtime.execution.protection.ProviderProtectionStorageBridge]
 * (production circuit protection) and [SynchronizationRetryEvaluator]
 * (production whole-batch retry evaluation) do not distinguish the two.
 *
 * No new production code backs this: see the PR description/fragment for why.
 */
class InboundPullConflictDecisionRetryCircuitProofTest : ConflictDecisionApplicationFixture() {

    private val retryOperation = RetryOperation("inbound.pull")
    private val applyScope = CircuitBreakerScope.providerOperation(
        providerId = ProviderId("storage-test"),
        operation = StorageCircuitOperation.APPLY_INBOUND_CHANGES.retryOperation,
    )

    private fun transientStorageError() = TestError(
        code = ErrorCode("STORAGE-TRANSIENT"),
        category = ErrorCategory.STORAGE,
        recoverability = Recoverability.RECOVERABLE,
    )

    private fun retryPolicy(maximumAttempts: Int) = StandardRetryPolicy(
        id = RetryPolicyId("conflict-apply-proof"),
        strategy = RetryBackoffStrategy.Immediate,
        maximumAttempts = maximumAttempts,
    )

    /** Builds a real circuit-protected [SynchronizationExecutionContext] over [storage]. */
    private fun protectedContext(
        storage: FakeStorageProvider,
        circuitStateStore: InMemoryCircuitStore,
        transport: FakeTransportProvider,
    ): SynchronizationExecutionContext {
        val protectedOperations = StorageCircuitProtectionRuntime.create(
            storageProvider = storage,
            clock = FixedClock,
            circuitBreakerConfiguration = CircuitBreakerConfiguration(
                failureThreshold = 5,
                failureWindow = SchedulingDelay(60_000L),
                openDuration = SchedulingDelay(30_000L),
            ),
            circuitBreakerStateStore = circuitStateStore,
            scopes = storageScopes(),
        )
        val bridged = ProviderProtectionStorageBridge(
            protectedOperations = protectedOperations,
            evidenceCollector = ProviderProtectionEvidenceCollector(),
        )
        return SynchronizationExecutionContext(
            request = request,
            providers = ResolvedSynchronizationProviders(
                storageProvider = bridged,
                transportProvider = transport,
                schedulerProvider = null,
                connectivityProvider = null,
                queueProvider = null,
            ),
            runtimeDependencies = context(storage, transport).runtimeDependencies,
        )
    }

    private fun storageScopes(): StorageCircuitScopes {
        val providerId = ProviderId("storage-test")
        fun scope(operation: StorageCircuitOperation) =
            CircuitBreakerScope.providerOperation(providerId, operation.retryOperation)
        return StorageCircuitScopes(
            initialization = scope(StorageCircuitOperation.INITIALIZE),
            health = scope(StorageCircuitOperation.HEALTH),
            close = scope(StorageCircuitOperation.CLOSE),
            readOutboundChanges = scope(StorageCircuitOperation.READ_OUTBOUND_CHANGES),
            applyInboundChanges = applyScope,
            acknowledgeOutboundChanges = scope(StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES),
            readCheckpoint = scope(StorageCircuitOperation.READ_CHECKPOINT),
            writeCheckpoint = scope(StorageCircuitOperation.WRITE_CHECKPOINT),
            readLocalConflictCandidate = scope(StorageCircuitOperation.READ_LOCAL_CONFLICT_CANDIDATE),
        )
    }

    @Test
    fun aTransientApplyFailureIsRetriedThenSucceedsWithTheSameReplayedDecision() {
        val circuitStore = InMemoryCircuitStore()
        val resolver = MutableResolver(ConflictResolutionDecision.UseRemote())
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val pipeline = pipeline(resolver = resolver, resolvedStore = resolvedStore)
        val storage = storageWithCandidate(
            localEvent,
            applyResults = mutableListOf(ProviderOperationResult.Failure(transientStorageError())),
        )
        val transport = transport(changeSet(remoteEvent), nextCheckpoint = checkpoint)
        val context = protectedContext(storage, circuitStore, transport)

        val first = runSuspend { pipeline.execute(context) }
        val failed = assertIs<SynchronizationResult.Failed>(first)
        assertEquals(ErrorCategory.STORAGE, failed.error.category)
        assertTrue(storage.writtenCheckpoints.isEmpty(), "a transient apply failure must not advance the checkpoint")

        // The retry evaluator -- the exact production component a queue worker
        // uses on every failed synchronization result -- must request a retry
        // for this ordinary storage-category, recoverable failure: conflict
        // decisions never mark this path protected-from-retry (only Defer/Fail/
        // Quarantined/NonConvergent barriers, all CONFLICT-category, do that).
        val evaluator = SynchronizationRetryEvaluator(retryPolicy(maximumAttempts = 3), FixedClock)
        val evaluation = evaluator.evaluate(failed, RetryAttempt(1), retryOperation)
        assertIs<SynchronizationRetryEvaluation.ShouldRetry>(evaluation)

        // The circuit is far from tripping (threshold 5, one failure) and the
        // decision was already durably recorded before the failed apply
        // attempt, so a second attempt (a queue-worker replay) reuses it.
        val second = runSuspend { pipeline.execute(context) }
        val succeeded = assertIs<SynchronizationResult.Succeeded>(second)
        assertEquals(1L, succeeded.summary.inboundEventsApplied)
        // appliedChangeSets records every attempted call (attempt one, which
        // failed, and attempt two, which succeeded); both carry the same
        // replayed decision, so only the last (successful) one matters here.
        assertEquals(listOf(remoteEvent), storage.appliedChangeSets.last().events)
        assertEquals(2, storage.applyCallCount, "attempt one failed, attempt two applied")
        assertEquals(listOf(checkpoint), storage.writtenCheckpoints)
    }

    @Test
    fun retriesExhaustedFailClosedWithoutEverAdvancingTheCheckpoint() {
        val circuitStore = InMemoryCircuitStore()
        val resolver = MutableResolver(ConflictResolutionDecision.UseRemote())
        val pipeline = pipeline(
            resolver = resolver,
            resolvedStore = InMemoryDurableStore(),
        )
        // Every attempt fails: five queued failures comfortably covers the
        // bounded attempt loop below.
        val storage = storageWithCandidate(
            localEvent,
            applyResults = MutableList(5) { ProviderOperationResult.Failure(transientStorageError()) },
        )
        val transport = transport(changeSet(remoteEvent))
        val context = protectedContext(storage, circuitStore, transport)
        val evaluator = SynchronizationRetryEvaluator(retryPolicy(maximumAttempts = 2), FixedClock)

        var attempt = 1
        var stopped: SynchronizationRetryEvaluation.StopRetry? = null
        while (stopped == null) {
            val result = assertIs<SynchronizationResult.Failed>(runSuspend { pipeline.execute(context) })
            assertTrue(storage.writtenCheckpoints.isEmpty(), "no attempt may advance the checkpoint")
            when (val evaluation = evaluator.evaluate(result, RetryAttempt(attempt), retryOperation)) {
                is SynchronizationRetryEvaluation.ShouldRetry -> attempt++
                is SynchronizationRetryEvaluation.StopRetry -> stopped = evaluation
                SynchronizationRetryEvaluation.NotRequired -> error("Failed results are always evaluable.")
            }
            check(attempt <= 10) { "runaway loop guard" }
        }

        val stopDecision = assertIs<RetryDecision.Stop>(stopped.decisions.single())
        assertEquals(RetryStopReason.ATTEMPT_LIMIT_REACHED, stopDecision.reason)
        // The fail-closed guarantee under test is checkpoint advancement, not
        // whether applyInboundChanges was ever invoked -- every one of those
        // invocations failed (asserted inside the loop above), so nothing
        // reached durable storage even though the provider was called.
        assertTrue(storage.writtenCheckpoints.isEmpty())
        assertEquals(attempt, storage.applyCallCount, "one apply attempt per evaluated attempt, all failed")
    }

    @Test
    fun anOpenCircuitBlocksApplicationWithoutInvokingTheProviderOrLosingTheDecision() {
        val circuitStore = InMemoryCircuitStore()
        // Pre-trip only the apply-inbound-changes scope; detection and decision
        // recording use a different scope (readLocalConflictCandidate) and must
        // proceed normally.
        circuitStore.seedOpen(applyScope)

        val resolver = MutableResolver(ConflictResolutionDecision.UseRemote())
        val resolvedStore = InMemoryDurableStore<ConflictId, ResolvedConflictDecisionRecord>()
        val pipeline = pipeline(resolver = resolver, resolvedStore = resolvedStore)
        val storage = storageWithCandidate(localEvent)
        val transport = transport(changeSet(remoteEvent))
        val context = protectedContext(storage, circuitStore, transport)

        val result = runSuspend { pipeline.execute(context) }

        val failed = assertIs<SynchronizationResult.Failed>(result)
        assertEquals("PROVIDER_CIRCUIT_OPEN", failed.error.code.value)
        assertEquals(0, storage.applyCallCount, "the provider must never be invoked while the circuit is open")
        assertTrue(storage.writtenCheckpoints.isEmpty())
        // The decision itself is independent of application-circuit state: it
        // was already durably recorded, so a later attempt (once the circuit
        // closes) can still apply it.
        assertTrue(resolvedStore.state(conflictId) != null)
    }

    /** Minimal in-memory [CircuitBreakerStateStore] with direct state seeding for deterministic OPEN/CLOSED setup. */
    private class InMemoryCircuitStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()

        fun seedOpen(scope: CircuitBreakerScope) {
            records[scope] = CircuitBreakerStateRecord(
                state = CircuitBreakerState(
                    scope = scope,
                    phase = CircuitBreakerPhase.OPEN,
                    consecutiveFailures = 0,
                    failureWindowStartedAt = null,
                    openUntil = DataLoomInstant(FixedClock.now().epochMilliseconds + 60_000L),
                    probeGeneration = 0L,
                    probeInFlight = false,
                    updatedAt = FixedClock.now(),
                ),
                version = 0L,
            )
        }

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) CircuitBreakerLoadResult.Missing else CircuitBreakerLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(CircuitBreakerCompareAndSetResult.Conflict(current))
            }
            val updated = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(CircuitBreakerCompareAndSetResult.Updated(updated))
        }
    }
}
