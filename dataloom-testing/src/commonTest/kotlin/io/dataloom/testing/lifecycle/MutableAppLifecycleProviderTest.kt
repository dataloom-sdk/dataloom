package io.dataloom.testing.lifecycle

import io.dataloom.api.lifecycle.AppLifecycleObservationException
import io.dataloom.api.lifecycle.AppLifecycleProvider
import io.dataloom.api.lifecycle.AppLifecycleState
import io.dataloom.api.provider.ProviderType
import io.dataloom.testing.FakeDataLoomError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MutableAppLifecycleProviderTest {
    private class FakeHarness : AppLifecycleContractHarness {
        val fake: MutableAppLifecycleProvider = MutableAppLifecycleProvider(AppLifecycleState.BACKGROUND)
        override val provider: AppLifecycleProvider = fake

        override suspend fun drive(state: AppLifecycleState) {
            fake.setState(state)
        }

        override fun activeObserverCount(): Int = fake.activeCollectorCount
    }

    private val contract = AppLifecycleProviderContract(newHarness = { FakeHarness() })

    @Test
    fun `fake satisfies descriptor contract`() = runTest { contract.descriptorDeclaresLifecycleContract() }

    @Test
    fun `fake satisfies current tracking contract`() = runTest { contract.currentTracksPlatformState() }

    @Test
    fun `fake satisfies cold flow contract`() = runTest { contract.creatingFlowRegistersNothing() }

    @Test
    fun `fake satisfies seeding contract`() = runTest { contract.collectionIsSeededWithCurrentState() }

    @Test
    fun `fake satisfies ordering contract`() = runTest { contract.collectionDeliversTransitionsInOrder() }

    @Test
    fun `fake satisfies duplicate suppression contract`() =
        runTest { contract.collectionSuppressesConsecutiveDuplicates() }

    @Test
    fun `fake satisfies slow collector contract`() = runTest { contract.slowCollectorObservesLatestState() }

    @Test
    fun `fake satisfies observer release contract`() = runTest { contract.cancellingCollectionReleasesObserver() }

    @Test
    fun `fake satisfies collector independence contract`() = runTest { contract.collectorsAreIndependent() }

    @Test
    fun `fake satisfies no delivery after cancellation contract`() = runTest { contract.noDeliveryAfterCancellation() }

    @Test
    fun `fake satisfies recollection contract`() = runTest { contract.recollectionRegistersAgain() }

    @Test
    fun `fake satisfies terminating soon contract`() =
        runTest { contract.terminatingSoonIsDeliveredOnlyWhenDeclared() }

    @Test
    fun `fake passes the whole suite`() = runTest { contract.runAll() }

    @Test
    fun `descriptor uses app lifecycle type`() {
        assertEquals(ProviderType.APP_LIFECYCLE, MutableAppLifecycleProvider().descriptor.type)
    }

    @Test
    fun `terminating soon never becomes the current reading`() {
        val provider = MutableAppLifecycleProvider(AppLifecycleState.FOREGROUND)

        provider.setState(AppLifecycleState.TERMINATING_SOON)

        assertEquals(AppLifecycleState.FOREGROUND, provider.current)
    }

    @Test
    fun `initial state must not be terminating soon`() {
        assertFailsWith<IllegalArgumentException> {
            MutableAppLifecycleProvider(AppLifecycleState.TERMINATING_SOON)
        }
    }

    @Test
    fun `configured observation failure fails collection with the canonical error`() = runTest {
        val error = FakeDataLoomError(message = "lifecycle source unavailable")
        val provider = MutableAppLifecycleProvider(observationFailure = error)

        val failure = assertFailsWith<AppLifecycleObservationException> { provider.states().first() }

        assertSame(error, failure.error)
        assertEquals(0, provider.activeCollectorCount)
    }

    @Test
    fun `reset state restores the initial reading`() {
        val provider = MutableAppLifecycleProvider(AppLifecycleState.FOREGROUND)
        provider.setState(AppLifecycleState.BACKGROUND)

        provider.resetState()

        assertEquals(AppLifecycleState.FOREGROUND, provider.current)
    }
}
