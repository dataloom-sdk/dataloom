package io.dataloom.runtime.facade

import io.dataloom.api.identifier.IdentifierGenerator
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.lifecycle.AppLifecycleState
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Behavior of the lifecycle drain against a fake lifecycle provider and a fake
 * queue worker. Nothing here depends on wall-clock time: the runtime clock is a
 * mutable fake and coroutine interleaving is driven with `runCurrent`, so the
 * tests are deterministic on every target.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDataLoomLifecycleDrainTest {

    private class Harness(
        val policy: LifecycleDrainPolicy = LifecycleDrainPolicy(minimumInterval = SchedulingDelay.ZERO),
        val provider: FakeAppLifecycleProvider = FakeAppLifecycleProvider(),
        recoverExpiredLeases: Boolean = false,
        startMillis: Long = 1_000L,
        leaseIds: IdentifierGenerator<QueueLeaseId>? = null,
    ) {
        var nowMillis: Long = startMillis
        val requests = mutableListOf<QueueWorkerRunRequest>()
        var concurrentRuns = 0
        var maxConcurrentRuns = 0
        var cancelledRuns = 0
        var behavior: suspend (Int) -> Unit = {}
        var end: LifecycleDrainEnd? = null
        private var leaseCounter = 0

        val drain = DefaultDataLoomLifecycleDrain(
            lifecycleProvider = provider,
            consumerId = QueueConsumerId("drain-consumer"),
            policy = policy,
            clock = object : DataLoomClock {
                override fun now(): DataLoomInstant = DataLoomInstant(nowMillis)
            },
            leaseIds = leaseIds ?: object : IdentifierGenerator<QueueLeaseId> {
                override fun generate(): QueueLeaseId = QueueLeaseId("lease-${++leaseCounter}")
            },
            recoverExpiredLeases = recoverExpiredLeases,
            runWorker = { request ->
                requests += request
                val index = requests.size
                concurrentRuns++
                if (concurrentRuns > maxConcurrentRuns) maxConcurrentRuns = concurrentRuns
                try {
                    behavior(index)
                } catch (cancelled: CancellationException) {
                    cancelledRuns++
                    throw cancelled
                } finally {
                    concurrentRuns--
                }
            },
        )
    }

    /** Starts [Harness.drain] collecting, runs [block], then cancels the collector. */
    private suspend fun TestScope.collecting(harness: Harness, block: suspend () -> Unit) {
        val job = launch { harness.end = harness.drain.run() }
        runCurrent()
        try {
            block()
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun TestScope.transition(harness: Harness, state: AppLifecycleState) {
        harness.provider.set(state)
        runCurrent()
    }

    // -- which transitions trigger ---------------------------------------------

    @Test
    fun enteringBackgroundTriggersOneBoundedDrain() = runTest {
        val h = Harness(policy = LifecycleDrainPolicy(minimumInterval = SchedulingDelay.ZERO, maxEntriesPerDrain = 3))
        collecting(h) {
            transition(h, AppLifecycleState.BACKGROUND)

            assertEquals(1, h.requests.size)
            val request = h.requests.single()
            val acquire = request.processingRequest.acquireRequest
            assertEquals(QueueConsumerId("drain-consumer"), acquire.consumerId)
            assertEquals(QueueLeaseId("lease-1"), acquire.leaseId)
            assertEquals(3, acquire.maxEntries)
            assertEquals(1_000L, acquire.acquiredAt.epochMilliseconds)
            assertEquals(1_000L + 60_000L, acquire.leaseExpiresAt.epochMilliseconds)
            assertNull(request.recoveryRequest)
        }
    }

    @Test
    fun terminatingSoonTriggersByDefault() = runTest {
        val h = Harness()
        collecting(h) {
            transition(h, AppLifecycleState.TERMINATING_SOON)

            assertEquals(1, h.requests.size)
        }
    }

    @Test
    fun foregroundDoesNotTriggerByDefault() = runTest {
        val h = Harness(provider = FakeAppLifecycleProvider(initial = AppLifecycleState.BACKGROUND))
        collecting(h) {
            transition(h, AppLifecycleState.FOREGROUND)

            assertEquals(0, h.requests.size)
        }
    }

    @Test
    fun foregroundTriggersWhenConfigured() = runTest {
        val h = Harness(
            policy = LifecycleDrainPolicy(
                triggers = setOf(AppLifecycleState.FOREGROUND),
                minimumInterval = SchedulingDelay.ZERO,
            ),
            provider = FakeAppLifecycleProvider(initial = AppLifecycleState.BACKGROUND),
        )
        collecting(h) {
            transition(h, AppLifecycleState.FOREGROUND)
            assertEquals(1, h.requests.size)

            transition(h, AppLifecycleState.BACKGROUND)
            assertEquals(1, h.requests.size, "BACKGROUND is not a trigger in this policy")
        }
    }

    @Test
    fun aStateNotInTheTriggerSetDoesNotDrain() = runTest {
        val h = Harness(
            policy = LifecycleDrainPolicy(
                triggers = setOf(AppLifecycleState.TERMINATING_SOON),
                minimumInterval = SchedulingDelay.ZERO,
            ),
        )
        collecting(h) {
            transition(h, AppLifecycleState.BACKGROUND)
            assertEquals(0, h.requests.size)

            transition(h, AppLifecycleState.TERMINATING_SOON)
            assertEquals(1, h.requests.size)
        }
    }

    @Test
    fun theStateAtCollectionStartIsNotATransition() = runTest {
        val h = Harness(provider = FakeAppLifecycleProvider(initial = AppLifecycleState.BACKGROUND))
        collecting(h) {
            assertEquals(0, h.requests.size)
        }
    }

    // -- minimum interval ------------------------------------------------------

    @Test
    fun minimumIntervalDropsATransitionInsideTheIntervalAndAllowsOneAtItsBoundary() = runTest {
        val h = Harness(policy = LifecycleDrainPolicy(minimumInterval = SchedulingDelay(10_000L)))
        collecting(h) {
            transition(h, AppLifecycleState.BACKGROUND) // t = 1_000: drains
            assertEquals(1, h.requests.size)

            h.nowMillis = 5_000L
            transition(h, AppLifecycleState.FOREGROUND)
            transition(h, AppLifecycleState.BACKGROUND) // 4_000 ms after the last start: dropped
            assertEquals(1, h.requests.size)

            h.nowMillis = 10_999L
            transition(h, AppLifecycleState.FOREGROUND)
            transition(h, AppLifecycleState.BACKGROUND) // 9_999 ms: still inside
            assertEquals(1, h.requests.size)

            h.nowMillis = 11_000L
            transition(h, AppLifecycleState.FOREGROUND)
            transition(h, AppLifecycleState.BACKGROUND) // exactly the interval: allowed
            assertEquals(2, h.requests.size)
        }
    }

    @Test
    fun aDroppedTransitionDoesNotRestartTheInterval() = runTest {
        val h = Harness(policy = LifecycleDrainPolicy(minimumInterval = SchedulingDelay(10_000L)))
        collecting(h) {
            transition(h, AppLifecycleState.BACKGROUND) // t = 1_000: drains

            h.nowMillis = 8_000L
            transition(h, AppLifecycleState.FOREGROUND)
            transition(h, AppLifecycleState.BACKGROUND) // dropped
            h.nowMillis = 11_000L
            transition(h, AppLifecycleState.FOREGROUND)
            transition(h, AppLifecycleState.BACKGROUND) // measured from t = 1_000, not 8_000

            assertEquals(2, h.requests.size)
        }
    }

    @Test
    fun aClockThatMovesBackwardsNeverBlocksADrain() = runTest {
        val h = Harness(policy = LifecycleDrainPolicy(minimumInterval = SchedulingDelay(10_000L)), startMillis = 500_000L)
        collecting(h) {
            transition(h, AppLifecycleState.BACKGROUND)
            h.nowMillis = 1_000L
            transition(h, AppLifecycleState.FOREGROUND)
            transition(h, AppLifecycleState.BACKGROUND)

            assertEquals(2, h.requests.size)
        }
    }

    // -- coalescing -------------------------------------------------------------

    @Test
    fun transitionsDuringAnInFlightDrainAreCoalescedAndNeverOverlap() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.behavior = { gate.await() }
        collecting(h) {
            transition(h, AppLifecycleState.BACKGROUND)
            assertEquals(1, h.requests.size)
            assertEquals(1, h.concurrentRuns)

            transition(h, AppLifecycleState.FOREGROUND)
            transition(h, AppLifecycleState.BACKGROUND)
            transition(h, AppLifecycleState.TERMINATING_SOON)
            assertEquals(1, h.requests.size, "transitions during the drain must not start another")
            assertEquals(1, h.maxConcurrentRuns)

            gate.complete(Unit)
            runCurrent()
            assertEquals(0, h.concurrentRuns)
            assertEquals(1, h.requests.size, "a coalesced transition is not replayed after the drain")
        }
    }

    @Test
    fun aQualifyingTransitionAfterTheDrainFinishedDrainsAgain() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.behavior = { index -> if (index == 1) gate.await() }
        collecting(h) {
            transition(h, AppLifecycleState.BACKGROUND)
            gate.complete(Unit)
            runCurrent()
            transition(h, AppLifecycleState.FOREGROUND)
            transition(h, AppLifecycleState.BACKGROUND)

            assertEquals(2, h.requests.size)
            assertEquals(1, h.maxConcurrentRuns)
            assertEquals(QueueLeaseId("lease-1"), h.requests[0].processingRequest.acquireRequest.leaseId)
            assertEquals(QueueLeaseId("lease-2"), h.requests[1].processingRequest.acquireRequest.leaseId)
        }
    }

    @Test
    fun concurrentCollectorsOnOneInstanceNeverRunOverlappingDrains() = runTest {
        val h = Harness()
        val gate = CompletableDeferred<Unit>()
        h.behavior = { gate.await() }
        val second = launch { h.drain.run() }
        collecting(h) {
            runCurrent()
            transition(h, AppLifecycleState.BACKGROUND)

            assertEquals(1, h.requests.size, "two collectors saw the transition; only one may drain")
            assertEquals(1, h.maxConcurrentRuns)
            gate.complete(Unit)
            runCurrent()
        }
        second.cancelAndJoin()
    }

    // -- recovery request -------------------------------------------------------

    @Test
    fun aRecoveryRequestIsIncludedOnlyWhenTheWorkerRequiresOne() = runTest {
        val h = Harness(recoverExpiredLeases = true)
        collecting(h) {
            transition(h, AppLifecycleState.BACKGROUND)

            val recovery = assertNotNull(h.requests.single().recoveryRequest)
            assertEquals(1_000L, recovery.currentTime.epochMilliseconds)
        }
    }

    // -- cancellation -----------------------------------------------------------

    @Test
    fun cancellingTheCollectorMidDrainCancelsTheDrainAndReleasesTheGuard() = runTest {
        val h = Harness()
        h.behavior = { CompletableDeferred<Unit>().await() } // never completes on its own
        val job = launch { h.drain.run() }
        runCurrent()
        transition(h, AppLifecycleState.BACKGROUND)
        assertEquals(1, h.concurrentRuns)

        job.cancelAndJoin()

        assertEquals(1, h.cancelledRuns)
        assertEquals(0, h.concurrentRuns)
        assertTrue(job.isCancelled)

        // The guard was released: a fresh collector on the same instance drains.
        h.behavior = {}
        collecting(h) {
            transition(h, AppLifecycleState.FOREGROUND)
            transition(h, AppLifecycleState.BACKGROUND)
            assertEquals(2, h.requests.size)
        }
    }

    // -- failure isolation ------------------------------------------------------

    @Test
    fun aWorkerExceptionDoesNotKillTheCollector() = runTest {
        val h = Harness()
        h.behavior = { index -> if (index == 1) throw IllegalStateException("worker exploded") }
        collecting(h) {
            transition(h, AppLifecycleState.BACKGROUND)
            transition(h, AppLifecycleState.FOREGROUND)
            transition(h, AppLifecycleState.BACKGROUND)

            assertEquals(2, h.requests.size)
            assertNull(h.end, "the collector must still be running")
        }
    }

    @Test
    fun anInvalidRequestFromTheLeaseGeneratorDoesNotKillTheCollector() = runTest {
        var calls = 0
        val h = Harness(
            leaseIds = object : IdentifierGenerator<QueueLeaseId> {
                override fun generate(): QueueLeaseId {
                    calls++
                    check(calls > 1) { "generator exhausted" }
                    return QueueLeaseId("lease-$calls")
                }
            },
        )
        collecting(h) {
            transition(h, AppLifecycleState.BACKGROUND) // request cannot be built
            assertEquals(0, h.requests.size)

            transition(h, AppLifecycleState.FOREGROUND)
            transition(h, AppLifecycleState.BACKGROUND)
            assertEquals(1, h.requests.size)
            assertNull(h.end)
        }
    }

    // -- lifecycle observation failures -----------------------------------------

    @Test
    fun anObservationFailureEndsRunWithTheCanonicalErrorAndDoesNotThrow() = runTest {
        val error = LifecycleDrainTestError()
        val h = Harness(provider = FakeAppLifecycleProvider(streamFailure = observationFailure(error)))

        val end = h.drain.run()

        val failed = assertIs<LifecycleDrainEnd.ObservationFailed>(end)
        assertEquals(error, failed.error)
        assertEquals(0, h.requests.size)
    }

    @Test
    fun aNonContractStreamFailureIsReportedWithASanitizedError() = runTest {
        val h = Harness(provider = FakeAppLifecycleProvider(streamFailure = IllegalStateException("secret platform detail")))

        val failed = assertIs<LifecycleDrainEnd.ObservationFailed>(h.drain.run())

        assertEquals("LIFECYCLE_DRAIN_OBSERVATION_FAILED", failed.error.code.value)
        assertFalse(failed.error.message.contains("secret"))
    }

    @Test
    fun aCompletedStreamEndsRunNormally() = runTest {
        val h = Harness(provider = FakeAppLifecycleProvider(completesAfterSeed = true))

        assertEquals(LifecycleDrainEnd.StreamCompleted, h.drain.run())
    }

    // -- policy and spec validation ---------------------------------------------

    @Test
    fun policyDefaultsAreTheDocumentedOnes() {
        val policy = LifecycleDrainPolicy()

        assertEquals(setOf(AppLifecycleState.BACKGROUND, AppLifecycleState.TERMINATING_SOON), policy.triggers)
        assertEquals(30_000L, policy.minimumInterval.milliseconds)
        assertEquals(25, policy.maxEntriesPerDrain)
        assertEquals(60_000L, policy.leaseDuration.milliseconds)
        assertEquals(LifecycleDrainPolicy(), policy)
    }

    @Test
    fun policyRejectsInvalidBounds() {
        assertFailsWith<IllegalArgumentException> { LifecycleDrainPolicy(triggers = emptySet()) }
        assertFailsWith<IllegalArgumentException> { LifecycleDrainPolicy(maxEntriesPerDrain = 0) }
        assertFailsWith<IllegalArgumentException> { LifecycleDrainPolicy(leaseDuration = SchedulingDelay.ZERO) }
    }

    @Test
    fun policyCopiesItsTriggerSet() {
        val triggers = mutableSetOf(AppLifecycleState.BACKGROUND)
        val policy = LifecycleDrainPolicy(triggers = triggers)

        triggers.add(AppLifecycleState.FOREGROUND)

        assertEquals(setOf(AppLifecycleState.BACKGROUND), policy.triggers)
    }

    @Test
    fun specRejectsAProviderThatIsNotALifecycleProvider() {
        assertFailsWith<IllegalArgumentException> {
            DataLoomLifecycleDrainSpec(
                lifecycleProvider = FakeAppLifecycleProvider(type = ProviderType.QUEUE),
                consumerId = QueueConsumerId("drain-consumer"),
            )
        }
    }

    @Test
    fun specAndPolicyConstructionNeverCollectTheProvider() {
        val provider = FakeAppLifecycleProvider()

        DataLoomLifecycleDrainSpec(provider, QueueConsumerId("drain-consumer"))

        assertEquals(0, provider.statesCallCount)
    }
}
