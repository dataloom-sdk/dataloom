package io.dataloom.platform.ios.lifecycle

import io.dataloom.api.lifecycle.AppLifecycleCapabilities
import io.dataloom.api.lifecycle.AppLifecycleObservationException
import io.dataloom.api.lifecycle.AppLifecycleProvider
import io.dataloom.api.lifecycle.AppLifecycleState
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.platform.ios.lifecycle.internal.ApplicationLifecycleSource
import io.dataloom.platform.ios.lifecycle.internal.FakeNotificationBinder
import io.dataloom.platform.ios.lifecycle.internal.ImmediateMainThreadExecutor
import io.dataloom.platform.ios.lifecycle.internal.NotificationDriver
import io.dataloom.testing.lifecycle.AppLifecycleContractHarness
import io.dataloom.testing.lifecycle.AppLifecycleProviderContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the shared contract suite against [AppleLifecycleProvider] with a
 * pure-Kotlin notification source: everything above the `NSNotificationCenter`
 * binding is the real code. The iOS-target tests run the same suite over a
 * real `NSNotificationCenter`.
 */
class AppleLifecycleProviderContractTest {

    private class Harness : AppLifecycleContractHarness {
        val binder = FakeNotificationBinder()
        private val driver = NotificationDriver(binder::post)

        override val provider: AppLifecycleProvider = AppleLifecycleProvider(
            ApplicationLifecycleSource(ImmediateMainThreadExecutor, binder) { driver.platformState },
        )

        override suspend fun drive(state: AppLifecycleState) = driver.drive(state)

        override fun activeObserverCount(): Int = binder.activeBindings
    }

    private val contract = AppLifecycleProviderContract(newHarness = { Harness() })

    @Test
    fun `descriptor declares lifecycle contract`() = runTest { contract.descriptorDeclaresLifecycleContract() }

    @Test
    fun `current tracks platform state`() = runTest { contract.currentTracksPlatformState() }

    @Test
    fun `creating a flow registers nothing`() = runTest { contract.creatingFlowRegistersNothing() }

    @Test
    fun `collection is seeded with current state`() = runTest { contract.collectionIsSeededWithCurrentState() }

    @Test
    fun `collection delivers transitions in order`() = runTest { contract.collectionDeliversTransitionsInOrder() }

    @Test
    fun `collection suppresses consecutive duplicates`() =
        runTest { contract.collectionSuppressesConsecutiveDuplicates() }

    @Test
    fun `slow collector observes latest state`() = runTest { contract.slowCollectorObservesLatestState() }

    @Test
    fun `cancelling a collection releases its observer`() =
        runTest { contract.cancellingCollectionReleasesObserver() }

    @Test
    fun `collectors are independent`() = runTest { contract.collectorsAreIndependent() }

    @Test
    fun `no delivery after cancellation`() = runTest { contract.noDeliveryAfterCancellation() }

    @Test
    fun `recollection registers again`() = runTest { contract.recollectionRegistersAgain() }

    @Test
    fun `terminating soon is delivered and terminal`() =
        runTest { contract.terminatingSoonIsDeliveredOnlyWhenDeclared() }

    @Test
    fun `whole suite passes`() = runTest { contract.runAll() }

    @Test
    fun `descriptor is typed APP_LIFECYCLE and declares termination`() {
        val descriptor = Harness().provider.descriptor

        assertEquals(ProviderType.APP_LIFECYCLE, descriptor.type)
        assertTrue(AppLifecycleCapabilities.STATE_STREAM in descriptor.capabilities)
        assertTrue(AppLifecycleCapabilities.TERMINATING_SOON in descriptor.capabilities)
    }

    @Test
    fun `health is healthy and initialize and close succeed`() = runTest {
        val provider = Harness().provider

        val health = assertIs<ProviderOperationResult.Success<io.dataloom.api.provider.ProviderHealth>>(provider.health())
        assertEquals(ProviderHealthStatus.HEALTHY, health.value.status)
        assertIs<ProviderOperationResult.Success<Unit>>(provider.close())
    }

    @Test
    fun `registration failure surfaces the canonical error without platform details`() = runTest {
        val harness = Harness()
        harness.binder.failOnBind = true

        val failure = assertFailsWith<AppLifecycleObservationException> { harness.provider.states().first() }

        assertEquals("LIFECYCLE_PLATFORM_FAILURE", failure.error.code.value)
        assertTrue("secret-platform-detail" !in failure.message.orEmpty())
        assertNull(failure.error.cause)
        assertEquals(0, harness.binder.activeBindings)
    }
}
