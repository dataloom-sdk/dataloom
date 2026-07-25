package io.dataloom.testing.provider

import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderLifecycleState
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.testing.FakeDataLoomError
import io.dataloom.testing.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TestProviderLifecycleControllerTest {
    @Test
    fun `starts in created state with no recordings`() {
        val controller = TestProviderLifecycleController()
        assertEquals(ProviderLifecycleState.CREATED, controller.lifecycleState)
        assertEquals(0, controller.initializeCallCount)
        assertEquals(0, controller.healthCallCount)
        assertEquals(0, controller.closeCallCount)
        assertEquals(emptyList(), controller.lifecycleCalls)
    }

    @Test
    fun `initialize records context and call order`() {
        val controller = TestProviderLifecycleController()
        val context = ProviderInitializationContext()

        val result = runSuspend { controller.initialize(context) }

        assertIs<ProviderOperationResult.Success<Unit>>(result)
        assertEquals(1, controller.initializeCallCount)
        assertEquals(listOf("initialize"), controller.lifecycleCalls)
        assertEquals(listOf(context), controller.initializationContexts)
    }

    @Test
    fun `initialize success transitions to ready`() {
        val controller = TestProviderLifecycleController()
        runSuspend { controller.initialize(ProviderInitializationContext()) }
        assertEquals(ProviderLifecycleState.READY, controller.lifecycleState)
    }

    @Test
    fun `initialize failure transitions to failed`() {
        val controller = TestProviderLifecycleController(
            initializeResult = ProviderOperationResult.Failure(FakeDataLoomError(message = "boom")),
        )

        val result = runSuspend { controller.initialize(ProviderInitializationContext()) }

        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals(ProviderLifecycleState.FAILED, controller.lifecycleState)
    }

    @Test
    fun `health success records call`() {
        val controller = TestProviderLifecycleController()
        runSuspend { controller.health() }
        assertEquals(1, controller.healthCallCount)
        assertEquals(listOf("health"), controller.lifecycleCalls)
    }

    @Test
    fun `healthy health result transitions to ready`() {
        val controller = TestProviderLifecycleController(
            healthResult = ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY)),
        )
        runSuspend { controller.health() }
        assertEquals(ProviderLifecycleState.READY, controller.lifecycleState)
    }

    @Test
    fun `degraded health result transitions to degraded`() {
        val controller = TestProviderLifecycleController(
            healthResult = ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.DEGRADED)),
        )
        runSuspend { controller.health() }
        assertEquals(ProviderLifecycleState.DEGRADED, controller.lifecycleState)
    }

    @Test
    fun `unknown health result transitions to failed`() {
        val controller = TestProviderLifecycleController(
            healthResult = ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.UNKNOWN)),
        )
        runSuspend { controller.health() }
        assertEquals(ProviderLifecycleState.FAILED, controller.lifecycleState)
    }

    @Test
    fun `health failure transitions to failed`() {
        val controller = TestProviderLifecycleController(
            healthResult = ProviderOperationResult.Failure(FakeDataLoomError(message = "health failed")),
        )
        val result = runSuspend { controller.health() }
        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals(ProviderLifecycleState.FAILED, controller.lifecycleState)
    }

    @Test
    fun `close success records call and transitions to closed`() {
        val controller = TestProviderLifecycleController()
        val result = runSuspend { controller.close() }
        assertIs<ProviderOperationResult.Success<Unit>>(result)
        assertEquals(1, controller.closeCallCount)
        assertEquals(listOf("close"), controller.lifecycleCalls)
        assertEquals(ProviderLifecycleState.CLOSED, controller.lifecycleState)
    }

    @Test
    fun `close failure transitions to failed`() {
        val controller = TestProviderLifecycleController(
            closeResult = ProviderOperationResult.Failure(FakeDataLoomError(message = "close failed")),
        )
        val result = runSuspend { controller.close() }
        assertIs<ProviderOperationResult.Failure>(result)
        assertEquals(ProviderLifecycleState.FAILED, controller.lifecycleState)
    }

    @Test
    fun `lifecycle calls preserve overall ordering`() {
        val controller = TestProviderLifecycleController()
        runSuspend { controller.initialize(ProviderInitializationContext()) }
        runSuspend { controller.health() }
        runSuspend { controller.close() }
        assertEquals(listOf("initialize", "health", "close"), controller.lifecycleCalls)
    }

    @Test
    fun `multiple calls accumulate counts`() {
        val controller = TestProviderLifecycleController()
        runSuspend { controller.initialize(ProviderInitializationContext()) }
        runSuspend { controller.health() }
        runSuspend { controller.health() }
        runSuspend { controller.close() }
        assertEquals(1, controller.initializeCallCount)
        assertEquals(2, controller.healthCallCount)
        assertEquals(1, controller.closeCallCount)
    }

    @Test
    fun `clear recordings resets counts and lists`() {
        val controller = TestProviderLifecycleController()
        runSuspend { controller.initialize(ProviderInitializationContext()) }
        runSuspend { controller.health() }
        controller.clearRecordings()
        assertEquals(0, controller.initializeCallCount)
        assertEquals(0, controller.healthCallCount)
        assertEquals(0, controller.closeCallCount)
        assertEquals(emptyList(), controller.lifecycleCalls)
        assertEquals(emptyList(), controller.initializationContexts)
    }

    @Test
    fun `clear recordings preserves lifecycle state`() {
        val controller = TestProviderLifecycleController()
        runSuspend { controller.initialize(ProviderInitializationContext()) }
        controller.clearRecordings()
        assertEquals(ProviderLifecycleState.READY, controller.lifecycleState)
    }

    @Test
    fun `returned initialization context snapshot is defensive`() {
        val controller = TestProviderLifecycleController()
        val context = ProviderInitializationContext()
        runSuspend { controller.initialize(context) }
        val snapshot = controller.initializationContexts
        assertEquals(listOf(context), snapshot)
        assertEquals(listOf(context), controller.initializationContexts)
    }
}
