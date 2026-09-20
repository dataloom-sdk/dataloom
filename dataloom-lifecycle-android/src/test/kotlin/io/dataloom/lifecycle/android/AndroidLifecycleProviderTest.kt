package io.dataloom.lifecycle.android

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.dataloom.api.lifecycle.AppLifecycleCapabilities
import io.dataloom.api.lifecycle.AppLifecycleObservationException
import io.dataloom.api.lifecycle.AppLifecycleState
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidLifecycleProviderTest {

    // Registries hold their owner weakly, so the test keeps every owner alive.
    private val owners: MutableList<RegistryOwner> = mutableListOf()

    private fun newRegistry(): LifecycleRegistry {
        val owner = RegistryOwner()
        owners += owner
        return owner.registry
    }

    private fun providerAt(state: Lifecycle.State): AndroidLifecycleProvider {
        val registry = newRegistry()
        registry.currentState = state
        return AndroidLifecycleProvider(registry)
    }

    @Test
    fun `descriptor is typed APP_LIFECYCLE with a non-blank id`() {
        val descriptor = AndroidLifecycleProvider(newRegistry()).descriptor

        assertEquals(ProviderType.APP_LIFECYCLE, descriptor.type)
        assertTrue(descriptor.id.value.isNotBlank())
    }

    @Test
    fun `descriptor declares the stream but not termination`() {
        val capabilities = AndroidLifecycleProvider(newRegistry()).descriptor.capabilities

        assertTrue(AppLifecycleCapabilities.STATE_STREAM in capabilities)
        assertFalse(AppLifecycleCapabilities.TERMINATING_SOON in capabilities)
    }

    @Test
    fun `current folds started and resumed into FOREGROUND`() {
        assertEquals(AppLifecycleState.FOREGROUND, providerAt(Lifecycle.State.STARTED).current)
        assertEquals(AppLifecycleState.FOREGROUND, providerAt(Lifecycle.State.RESUMED).current)
    }

    @Test
    fun `current folds created and destroyed into BACKGROUND`() {
        assertEquals(AppLifecycleState.BACKGROUND, providerAt(Lifecycle.State.CREATED).current)
        assertEquals(AppLifecycleState.BACKGROUND, providerAt(Lifecycle.State.DESTROYED).current)
    }

    @Test
    fun `current is BACKGROUND while the process lifecycle is not started`() {
        val registry = LifecycleRegistry.createUnsafe(FixedOwner())

        assertEquals(Lifecycle.State.INITIALIZED, registry.currentState)
        assertEquals(AppLifecycleState.BACKGROUND, AndroidLifecycleProvider(registry).current)
    }

    @Test
    fun `health is healthy once the process lifecycle has started`() = runBlocking {
        val result = providerAt(Lifecycle.State.CREATED).health()

        val success = assertIs<ProviderOperationResult.Success<io.dataloom.api.provider.ProviderHealth>>(result)
        assertEquals(ProviderHealthStatus.HEALTHY, success.value.status)
        assertNull(success.value.error)
    }

    @Test
    fun `health is unhealthy when the process lifecycle was never started`() = runBlocking {
        val registry = LifecycleRegistry.createUnsafe(FixedOwner())

        val result = AndroidLifecycleProvider(registry).health()

        val success = assertIs<ProviderOperationResult.Success<io.dataloom.api.provider.ProviderHealth>>(result)
        assertEquals(ProviderHealthStatus.UNHEALTHY, success.value.status)
        assertEquals("LIFECYCLE_PROCESS_NOT_STARTED", success.value.error?.code?.value)
    }

    @Test
    fun `close succeeds without touching the platform`() {
        val provider = providerAt(Lifecycle.State.CREATED)

        runBlocking { assertIs<ProviderOperationResult.Success<Unit>>(provider.close()) }
    }

    @Test
    fun `observer registration failure surfaces the canonical error without platform details`() = runBlocking {
        val provider = AndroidLifecycleProvider(FailingLifecycle())

        val failure = assertFailsWith<AppLifecycleObservationException> { provider.states().first() }

        assertEquals("LIFECYCLE_PLATFORM_FAILURE", failure.error.code.value)
        assertFalse(failure.message.orEmpty().contains("secret-platform-detail"))
        assertNull(failure.error.cause)
    }

    @Test
    fun `collection and cancellation from a background thread hop through the main looper`() {
        val harness = RegistryHarness()
        val received = CopyOnWriteArrayList<AppLifecycleState>()
        val scope = CoroutineScope(Dispatchers.Default)

        val job = scope.launch { harness.provider.states().collect { received += it } }
        awaitOnMainLooper("observer registered on the main thread") { harness.registry.observerCount == 1 }
        assertEquals(listOf(AppLifecycleState.BACKGROUND), received.toList())

        runBlocking { harness.drive(AppLifecycleState.FOREGROUND) }
        awaitOnMainLooper("FOREGROUND delivered") { received.lastOrNull() == AppLifecycleState.FOREGROUND }

        job.cancel()
        awaitOnMainLooper("observer removed on the main thread") {
            job.isCompleted && harness.registry.observerCount == 0
        }
    }

    /**
     * Pumps the Robolectric main looper (the test thread is the main thread)
     * until [condition] holds, so work a background thread posted to the main
     * looper actually runs.
     */
    private fun awaitOnMainLooper(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        error("Timed out waiting for: $what")
    }

    private class FixedOwner : LifecycleOwner {
        private val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class FailingLifecycle : Lifecycle() {
        override val currentState: State get() = State.STARTED

        override fun addObserver(observer: LifecycleObserver) {
            throw IllegalStateException("secret-platform-detail")
        }

        override fun removeObserver(observer: LifecycleObserver) = Unit
    }

    private companion object {
        const val TIMEOUT_NANOS: Long = 10_000_000_000L
        const val POLL_MILLIS: Long = 5L
    }
}
