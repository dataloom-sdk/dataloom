package io.dataloom.lifecycle.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.dataloom.api.lifecycle.AppLifecycleProvider
import io.dataloom.api.lifecycle.AppLifecycleState
import io.dataloom.testing.lifecycle.AppLifecycleContractHarness
import io.dataloom.testing.lifecycle.AppLifecycleProviderContract
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs the shared [AppLifecycleProviderContract] suite against the real
 * [AndroidLifecycleProvider], driven through a real (main-thread enforcing)
 * AndroidX [LifecycleRegistry] under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidLifecycleProviderContractTest {

    private val contract = AppLifecycleProviderContract(newHarness = { RegistryHarness() })

    @Test
    fun `descriptor declares lifecycle contract`() = runBlocking { contract.descriptorDeclaresLifecycleContract() }

    @Test
    fun `current tracks platform state`() = runBlocking { contract.currentTracksPlatformState() }

    @Test
    fun `creating a flow registers nothing`() = runBlocking { contract.creatingFlowRegistersNothing() }

    @Test
    fun `collection is seeded with current state`() = runBlocking { contract.collectionIsSeededWithCurrentState() }

    @Test
    fun `collection delivers transitions in order`() =
        runBlocking { contract.collectionDeliversTransitionsInOrder() }

    @Test
    fun `collection suppresses consecutive duplicates`() =
        runBlocking { contract.collectionSuppressesConsecutiveDuplicates() }

    @Test
    fun `slow collector observes latest state`() = runBlocking { contract.slowCollectorObservesLatestState() }

    @Test
    fun `cancelling a collection releases its observer`() =
        runBlocking { contract.cancellingCollectionReleasesObserver() }

    @Test
    fun `collectors are independent`() = runBlocking { contract.collectorsAreIndependent() }

    @Test
    fun `no delivery after cancellation`() = runBlocking { contract.noDeliveryAfterCancellation() }

    @Test
    fun `recollection registers again`() = runBlocking { contract.recollectionRegistersAgain() }

    @Test
    fun `terminating soon is not delivered because it is not declared`() =
        runBlocking { contract.terminatingSoonIsDeliveredOnlyWhenDeclared() }

    @Test
    fun `whole suite passes`() = runBlocking { contract.runAll() }
}

/**
 * A [LifecycleOwner] that keeps its [LifecycleRegistry] alive: the registry
 * only holds a weak reference to its owner, so tests must hold the owner.
 */
internal class RegistryOwner : LifecycleOwner {
    val registry: LifecycleRegistry = LifecycleRegistry(this).also {
        // A started process lifecycle sits at CREATED while in the background.
        it.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override val lifecycle: Lifecycle get() = registry
}

/** Drives a real [LifecycleRegistry] through the same events the process lifecycle dispatches. */
internal class RegistryHarness(
    private val owner: RegistryOwner = RegistryOwner(),
) : AppLifecycleContractHarness {
    val registry: LifecycleRegistry get() = owner.registry

    override val provider: AppLifecycleProvider = AndroidLifecycleProvider(owner.registry)

    override suspend fun drive(state: AppLifecycleState) {
        when (state) {
            AppLifecycleState.FOREGROUND -> {
                if (registry.currentState == Lifecycle.State.CREATED) {
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                }
                if (registry.currentState == Lifecycle.State.STARTED) {
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                }
            }
            AppLifecycleState.BACKGROUND -> {
                if (registry.currentState == Lifecycle.State.RESUMED) {
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                }
                if (registry.currentState == Lifecycle.State.STARTED) {
                    registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                }
            }
            AppLifecycleState.TERMINATING_SOON ->
                error("Android has no termination signal; the suite must not request it.")
        }
    }

    override fun activeObserverCount(): Int = registry.observerCount
}
