package io.dataloom.platform.ios.lifecycle

import io.dataloom.api.lifecycle.AppLifecycleProvider
import io.dataloom.api.lifecycle.AppLifecycleState
import io.dataloom.platform.ios.lifecycle.internal.ApplicationLifecycleSource
import io.dataloom.platform.ios.lifecycle.internal.ImmediateMainThreadExecutor
import io.dataloom.platform.ios.lifecycle.internal.LifecycleNotification
import io.dataloom.platform.ios.lifecycle.internal.NotificationBinder
import io.dataloom.platform.ios.lifecycle.internal.NotificationDriver
import io.dataloom.platform.ios.lifecycle.internal.NsNotificationBinder
import io.dataloom.platform.ios.lifecycle.internal.Unbind
import io.dataloom.platform.ios.lifecycle.internal.notificationName
import io.dataloom.testing.lifecycle.AppLifecycleContractHarness
import io.dataloom.testing.lifecycle.AppLifecycleProviderContract
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSNotificationCenter
import kotlin.test.Test

/**
 * Runs the shared contract suite against [AppleLifecycleProvider] over a real
 * `NSNotificationCenter`.
 *
 * ## What is real, and what is not
 *
 * Real: [NsNotificationBinder] (real `addObserverForName`/`removeObserver`),
 * real `NSNotification` posting under the real `UIApplication*Notification`
 * names, and every layer above them ([ApplicationLifecycleSource],
 * [AppleLifecycleProvider]). Observer release is checked through the
 * suite's own cancellation checks and by counting the binder's registrations
 * and removals around the real center.
 *
 * Not real: the notifications come from a private `NSNotificationCenter` and
 * are posted by the test rather than by UIKit, the "application state" is a
 * test variable rather than `UIApplication.applicationState`, and the main
 * thread executor is inline. A test process has no `UIApplication`, so the
 * production reader and the `dispatch` main-queue executor are covered
 * separately (see `DispatchMainThreadExecutorTest`) or, for the reader, not
 * at all. The provider has never observed a genuine UIKit lifecycle
 * transition.
 */
class AppleLifecycleProviderIosContractTest {

    /** Counts registrations and removals around the real binder. */
    private class CountingBinder(private val delegate: NotificationBinder) : NotificationBinder {
        var active: Int = 0
            private set

        override fun bind(sink: (LifecycleNotification) -> Unit): Unbind {
            val unbind = delegate.bind(sink)
            active++
            var removed = false
            return Unbind {
                unbind.unbind()
                if (!removed) {
                    removed = true
                    active--
                }
            }
        }
    }

    private class Harness : AppLifecycleContractHarness {
        private val center = NSNotificationCenter()
        private val binder = CountingBinder(NsNotificationBinder(center))
        private val driver = NotificationDriver { notification ->
            center.postNotificationName(notification.notificationName(), `object` = null)
        }

        override val provider: AppLifecycleProvider = AppleLifecycleProvider(
            ApplicationLifecycleSource(ImmediateMainThreadExecutor, binder) { driver.platformState },
        )

        override suspend fun drive(state: AppLifecycleState) = driver.drive(state)

        override fun activeObserverCount(): Int = binder.active
    }

    private val contract = AppLifecycleProviderContract(newHarness = { Harness() })

    @Test
    fun descriptorDeclaresLifecycleContract() = runTest { contract.descriptorDeclaresLifecycleContract() }

    @Test
    fun currentTracksPlatformState() = runTest { contract.currentTracksPlatformState() }

    @Test
    fun creatingFlowRegistersNothing() = runTest { contract.creatingFlowRegistersNothing() }

    @Test
    fun collectionIsSeededWithCurrentState() = runTest { contract.collectionIsSeededWithCurrentState() }

    @Test
    fun collectionDeliversTransitionsInOrder() = runTest { contract.collectionDeliversTransitionsInOrder() }

    @Test
    fun collectionSuppressesConsecutiveDuplicates() =
        runTest { contract.collectionSuppressesConsecutiveDuplicates() }

    @Test
    fun slowCollectorObservesLatestState() = runTest { contract.slowCollectorObservesLatestState() }

    @Test
    fun cancellingCollectionReleasesObserver() = runTest { contract.cancellingCollectionReleasesObserver() }

    @Test
    fun collectorsAreIndependent() = runTest { contract.collectorsAreIndependent() }

    @Test
    fun noDeliveryAfterCancellation() = runTest { contract.noDeliveryAfterCancellation() }

    @Test
    fun recollectionRegistersAgain() = runTest { contract.recollectionRegistersAgain() }

    @Test
    fun terminatingSoonIsDeliveredAndTerminal() =
        runTest { contract.terminatingSoonIsDeliveredOnlyWhenDeclared() }

    @Test
    fun wholeSuitePasses() = runTest { contract.runAll() }
}
