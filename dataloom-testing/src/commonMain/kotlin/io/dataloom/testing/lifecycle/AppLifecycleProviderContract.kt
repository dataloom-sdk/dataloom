package io.dataloom.testing.lifecycle

import io.dataloom.api.lifecycle.AppLifecycleCapabilities
import io.dataloom.api.lifecycle.AppLifecycleProvider
import io.dataloom.api.lifecycle.AppLifecycleState
import io.dataloom.api.provider.ProviderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Test-side view of one lifecycle provider under contract test.
 *
 * A harness pairs the provider with the only two things a platform-neutral
 * suite cannot do on its own: move the platform lifecycle source, and count
 * the platform observers the provider currently holds.
 */
public interface AppLifecycleContractHarness {
    /** The provider under test. */
    public val provider: AppLifecycleProvider

    /**
     * Makes the platform lifecycle source report [state] and returns once the
     * provider can observe it. The suite only requests
     * [AppLifecycleState.TERMINATING_SOON] from providers that declare
     * [AppLifecycleCapabilities.TERMINATING_SOON].
     *
     * The harness starts in [AppLifecycleState.FOREGROUND] or
     * [AppLifecycleState.BACKGROUND]; the suite establishes the state it needs
     * before every assertion.
     */
    public suspend fun drive(state: AppLifecycleState)

    /** Number of platform observers the provider currently has registered. */
    public fun activeObserverCount(): Int
}

/**
 * Shared contract suite for [AppLifecycleProvider].
 *
 * The fake and every real implementation run this same suite from their own
 * test sources. The suite is a plain class of `suspend` checks rather than an
 * abstract test class: annotation-based test discovery does not reliably
 * cross library boundaries on every Kotlin target, so each host declares one
 * thin `@Test` per check (or calls [runAll]) and stays free to choose its own
 * test runner.
 *
 * Checks are deterministic: they use no clock and no real time. Collectors run
 * on [Dispatchers.Unconfined] so delivery is observed without depending on a
 * test scheduler.
 *
 * A failed check throws [IllegalStateException].
 *
 * @param newHarness creates a fresh harness (fresh provider, fresh platform
 *   source) for each check.
 */
public class AppLifecycleProviderContract(
    private val newHarness: () -> AppLifecycleContractHarness,
) {
    /** Runs every check, each against a fresh harness. */
    public suspend fun runAll() {
        descriptorDeclaresLifecycleContract()
        currentTracksPlatformState()
        creatingFlowRegistersNothing()
        collectionIsSeededWithCurrentState()
        collectionDeliversTransitionsInOrder()
        collectionSuppressesConsecutiveDuplicates()
        slowCollectorObservesLatestState()
        cancellingCollectionReleasesObserver()
        collectorsAreIndependent()
        noDeliveryAfterCancellation()
        recollectionRegistersAgain()
        terminatingSoonIsDeliveredOnlyWhenDeclared()
    }

    /** The descriptor is typed and declares the capabilities the contract requires. */
    public suspend fun descriptorDeclaresLifecycleContract() {
        val descriptor = newHarness().provider.descriptor
        expect(ProviderType.APP_LIFECYCLE, descriptor.type, "descriptor type")
        check(descriptor.id.value.isNotBlank()) { "descriptor id must not be blank" }
        check(AppLifecycleCapabilities.STATE_STREAM in descriptor.capabilities) {
            "descriptor must declare ${AppLifecycleCapabilities.STATE_STREAM}"
        }
    }

    /** [AppLifecycleProvider.current] follows the platform and only reports coarse visible states. */
    public suspend fun currentTracksPlatformState() {
        val harness = newHarness()
        harness.drive(AppLifecycleState.FOREGROUND)
        expect(AppLifecycleState.FOREGROUND, harness.provider.current, "current after FOREGROUND")
        harness.drive(AppLifecycleState.BACKGROUND)
        expect(AppLifecycleState.BACKGROUND, harness.provider.current, "current after BACKGROUND")
        harness.drive(AppLifecycleState.FOREGROUND)
        expect(AppLifecycleState.FOREGROUND, harness.provider.current, "current after FOREGROUND again")
        expect(0, harness.activeObserverCount(), "reading current must register nothing")
    }

    /** Cold: creating the flow, even repeatedly, touches no platform observer. */
    public suspend fun creatingFlowRegistersNothing() {
        val harness = newHarness()
        harness.provider.states()
        harness.provider.states()
        expect(0, harness.activeObserverCount(), "observers after creating flows")
    }

    /** The first emission is the state at the moment collection starts. */
    public suspend fun collectionIsSeededWithCurrentState() {
        val harness = newHarness()
        for (state in visibleStates) {
            harness.drive(state)
            coroutineScope {
                val collection = collect(harness.provider.states())
                expect(listOf(state), collection.received.toList(), "seed for $state")
                collection.job.cancelAndJoin()
            }
        }
    }

    /** Transitions arrive in platform order. */
    public suspend fun collectionDeliversTransitionsInOrder() {
        val harness = newHarness()
        harness.drive(AppLifecycleState.FOREGROUND)
        coroutineScope {
            val collection = collect(harness.provider.states())
            harness.drive(AppLifecycleState.BACKGROUND)
            settle()
            harness.drive(AppLifecycleState.FOREGROUND)
            settle()
            harness.drive(AppLifecycleState.BACKGROUND)
            settle()
            expect(
                listOf(
                    AppLifecycleState.FOREGROUND,
                    AppLifecycleState.BACKGROUND,
                    AppLifecycleState.FOREGROUND,
                    AppLifecycleState.BACKGROUND,
                ),
                collection.received.toList(),
                "ordered transitions",
            )
            collection.job.cancelAndJoin()
        }
    }

    /** A platform signal that does not change the coarse state emits nothing. */
    public suspend fun collectionSuppressesConsecutiveDuplicates() {
        val harness = newHarness()
        harness.drive(AppLifecycleState.FOREGROUND)
        coroutineScope {
            val collection = collect(harness.provider.states())
            harness.drive(AppLifecycleState.FOREGROUND)
            settle()
            harness.drive(AppLifecycleState.BACKGROUND)
            settle()
            harness.drive(AppLifecycleState.BACKGROUND)
            settle()
            expect(
                listOf(AppLifecycleState.FOREGROUND, AppLifecycleState.BACKGROUND),
                collection.received.toList(),
                "duplicates suppressed",
            )
            collection.job.cancelAndJoin()
        }
    }

    /**
     * A collector that is busy while the platform changes state several times
     * still ends on the latest state (conflation never loses the last value)
     * and never sees two equal values in a row.
     */
    public suspend fun slowCollectorObservesLatestState() {
        val harness = newHarness()
        harness.drive(AppLifecycleState.FOREGROUND)
        coroutineScope {
            val received = mutableListOf<AppLifecycleState>()
            val gate = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                harness.provider.states().collect {
                    received += it
                    // Block the first delivery until the platform has moved on.
                    if (received.size == 1) gate.await()
                }
            }
            settle()
            harness.drive(AppLifecycleState.BACKGROUND)
            harness.drive(AppLifecycleState.FOREGROUND)
            harness.drive(AppLifecycleState.BACKGROUND)
            gate.complete(Unit)
            settle()
            expect(
                AppLifecycleState.BACKGROUND,
                received.lastOrNull(),
                "last value seen by a slow collector",
            )
            received.zipWithNext().forEach { (before, after) ->
                check(before != after) { "consecutive duplicate in $received" }
            }
            job.cancelAndJoin()
        }
    }

    /** Cancelling a collection releases its platform observer. */
    public suspend fun cancellingCollectionReleasesObserver() {
        val harness = newHarness()
        harness.drive(AppLifecycleState.FOREGROUND)
        coroutineScope {
            val collection = collect(harness.provider.states())
            expect(1, harness.activeObserverCount(), "observers while collecting")
            collection.job.cancelAndJoin()
            expect(0, harness.activeObserverCount(), "observers after cancellation")
        }
    }

    /** Each collection owns its observer: cancelling one leaves the other working. */
    public suspend fun collectorsAreIndependent() {
        val harness = newHarness()
        harness.drive(AppLifecycleState.FOREGROUND)
        coroutineScope {
            val first = collect(harness.provider.states())
            val second = collect(harness.provider.states())
            expect(2, harness.activeObserverCount(), "observers with two collectors")

            first.job.cancelAndJoin()
            expect(1, harness.activeObserverCount(), "observers after cancelling one")

            harness.drive(AppLifecycleState.BACKGROUND)
            settle()
            expect(
                listOf(AppLifecycleState.FOREGROUND, AppLifecycleState.BACKGROUND),
                second.received.toList(),
                "surviving collector",
            )
            expect(
                listOf(AppLifecycleState.FOREGROUND),
                first.received.toList(),
                "cancelled collector sees nothing more",
            )
            second.job.cancelAndJoin()
            expect(0, harness.activeObserverCount(), "observers after cancelling both")
        }
    }

    /** Driving the platform after cancellation delivers nothing and fails nothing. */
    public suspend fun noDeliveryAfterCancellation() {
        val harness = newHarness()
        harness.drive(AppLifecycleState.FOREGROUND)
        coroutineScope {
            val collection = collect(harness.provider.states())
            collection.job.cancelAndJoin()
            harness.drive(AppLifecycleState.BACKGROUND)
            settle()
            harness.drive(AppLifecycleState.FOREGROUND)
            settle()
            expect(listOf(AppLifecycleState.FOREGROUND), collection.received.toList(), "after cancellation")
        }
    }

    /** The same flow instance is cold and may be collected again. */
    public suspend fun recollectionRegistersAgain() {
        val harness = newHarness()
        harness.drive(AppLifecycleState.FOREGROUND)
        val flow = harness.provider.states()
        coroutineScope {
            val first = collect(flow)
            first.job.cancelAndJoin()
            harness.drive(AppLifecycleState.BACKGROUND)
            val second = collect(flow)
            expect(listOf(AppLifecycleState.BACKGROUND), second.received.toList(), "second collection seed")
            expect(1, harness.activeObserverCount(), "observers during second collection")
            second.job.cancelAndJoin()
            expect(0, harness.activeObserverCount(), "observers after second collection")
        }
    }

    /**
     * [AppLifecycleState.TERMINATING_SOON] is delivered, and is terminal, when
     * the provider declares [AppLifecycleCapabilities.TERMINATING_SOON]. A
     * provider that does not declare it is only checked to never emit it for
     * visible-state changes.
     */
    public suspend fun terminatingSoonIsDeliveredOnlyWhenDeclared() {
        val harness = newHarness()
        val declared = AppLifecycleCapabilities.TERMINATING_SOON in harness.provider.descriptor.capabilities
        harness.drive(AppLifecycleState.FOREGROUND)
        coroutineScope {
            val collection = collect(harness.provider.states())
            harness.drive(AppLifecycleState.BACKGROUND)
            settle()
            if (declared) {
                harness.drive(AppLifecycleState.TERMINATING_SOON)
                settle()
                harness.drive(AppLifecycleState.FOREGROUND)
                settle()
                expect(
                    listOf(
                        AppLifecycleState.FOREGROUND,
                        AppLifecycleState.BACKGROUND,
                        AppLifecycleState.TERMINATING_SOON,
                    ),
                    collection.received.toList(),
                    "TERMINATING_SOON is delivered and terminal",
                )
                expect(
                    AppLifecycleState.FOREGROUND,
                    harness.provider.current,
                    "current is never TERMINATING_SOON",
                )
            } else {
                check(AppLifecycleState.TERMINATING_SOON !in collection.received) {
                    "provider does not declare terminating-soon but emitted it"
                }
            }
            collection.job.cancelAndJoin()
        }
    }

    private class Collection(
        val received: MutableList<AppLifecycleState>,
        val job: Job,
    )

    private suspend fun CoroutineScope.collect(flow: Flow<AppLifecycleState>): Collection {
        val received = mutableListOf<AppLifecycleState>()
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            flow.collect { received += it }
        }
        settle()
        return Collection(received, job)
    }

    private suspend fun settle() {
        repeat(SETTLE_YIELDS) { yield() }
    }

    private fun <T> expect(expected: T, actual: T, what: String) {
        check(expected == actual) { "$what: expected <$expected> but was <$actual>" }
    }

    private companion object {
        const val SETTLE_YIELDS: Int = 5

        val visibleStates: List<AppLifecycleState> =
            listOf(AppLifecycleState.FOREGROUND, AppLifecycleState.BACKGROUND)
    }
}
