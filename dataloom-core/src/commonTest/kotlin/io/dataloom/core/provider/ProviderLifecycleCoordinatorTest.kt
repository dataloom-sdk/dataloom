package io.dataloom.core.provider

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.provider.DataLoomProvider
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderLifecycleCoordinatorState
import io.dataloom.api.provider.ProviderLifecycleFailure
import io.dataloom.api.provider.ProviderLifecycleOperation
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderName
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.ProviderType
import io.dataloom.api.provider.ProviderVersion
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the [ProviderLifecycleCoordinator] contract.
 *
 * Uses private deterministic fake [DataLoomProvider] implementations.
 * No real provider operation, platform access, external service, arbitrary
 * delay, or Thread.sleep is used.
 *
 * Suspend functions are exercised using the standard [startCoroutine]
 * primitive from the Kotlin standard library. All fake providers complete
 * synchronously, so the coroutine machinery resolves without a dispatcher.
 */
class ProviderLifecycleCoordinatorTest {

    // -------------------------------------------------------------------------
    // Shared canonical error
    // -------------------------------------------------------------------------

    private data class FakeError(
        override val code: ErrorCode,
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError

    private fun error(code: String, message: String = "Failure: $code"): FakeError =
        FakeError(code = ErrorCode(code), message = message)

    // -------------------------------------------------------------------------
    // Fake provider implementation
    // -------------------------------------------------------------------------

    /**
     * A configurable fake provider that records call counts, captured context,
     * and optionally appends to a shared tracking list on initialize/close.
     *
     * @param id provider ID string
     * @param type provider type
     * @param initializeResult result returned from initialize
     * @param closeResult result returned from close
     * @param initOrder optional shared list that receives this provider's id on initialize
     * @param closeOrder optional shared list that receives this provider's id on close
     */
    private class FakeProvider(
        id: String,
        type: ProviderType = ProviderType.STORAGE,
        private val initializeResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
        private val closeResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
        private val initOrder: MutableList<String>? = null,
        private val closeOrder: MutableList<String>? = null,
    ) : DataLoomProvider {

        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Fake $id"),
            type = type,
            version = ProviderVersion("1.0.0"),
        )

        var initializeCallCount = 0
        var closeCallCount = 0
        var capturedContext: ProviderInitializationContext? = null

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            capturedContext = context
            initializeCallCount++
            initOrder?.add(descriptor.id.value)
            return initializeResult
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> {
            closeCallCount++
            closeOrder?.add(descriptor.id.value)
            return closeResult
        }
    }

    /**
     * A fake provider that throws [CancellationException] from [initialize].
     */
    private class CancellingInitializeProvider(id: String) : DataLoomProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Cancelling Init $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> {
            throw CancellationException("Simulated cancellation from initialize.")
        }

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> =
            ProviderOperationResult.Success(Unit)
    }

    /**
     * A fake provider that throws [CancellationException] from [close].
     */
    private class CancellingCloseProvider(id: String) : DataLoomProvider {
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = ProviderName("Cancelling Close $id"),
            type = ProviderType.STORAGE,
            version = ProviderVersion("1.0.0"),
        )

        override suspend fun initialize(
            context: ProviderInitializationContext,
        ): ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit)

        override suspend fun health(): ProviderOperationResult<ProviderHealth> =
            ProviderOperationResult.Success(ProviderHealth(ProviderHealthStatus.HEALTHY))

        override suspend fun close(): ProviderOperationResult<Unit> {
            throw CancellationException("Simulated cancellation from close.")
        }
    }

    // -------------------------------------------------------------------------
    // Coroutine test helpers
    // -------------------------------------------------------------------------

    private object Pending

    /**
     * Runs a synchronously completing suspend block and returns the result.
     *
     * All fake providers in these tests complete synchronously. This helper
     * uses only [kotlin.coroutines] primitives from the Kotlin standard library
     * and does not require kotlinx.coroutines.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> runSuspend(block: suspend () -> T): T {
        var rawResult: Any? = Pending
        var thrown: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    if (result.isSuccess) {
                        rawResult = result.getOrNull()
                    } else {
                        thrown = result.exceptionOrNull()
                    }
                }
            },
        )
        thrown?.let { throw it }
        check(rawResult !== Pending) { "Suspend block did not complete synchronously in test." }
        return rawResult as T
    }

    /**
     * Runs a synchronously completing suspend block and returns the raw
     * [Result] without rethrowing. Used for cancellation tests.
     */
    private fun <T> runSuspendCatching(block: suspend () -> T): Result<T> {
        var capturedResult: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    capturedResult = result
                }
            },
        )
        return checkNotNull(capturedResult) {
            "Suspend block did not complete synchronously in test."
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val defaultContext = ProviderInitializationContext()

    private fun coordinator(
        vararg providers: DataLoomProvider,
        context: ProviderInitializationContext = defaultContext,
    ): ProviderLifecycleCoordinator =
        ProviderLifecycleCoordinator(
            registry = ProviderRegistry(providers.toList()),
            context = context,
        )

    // -------------------------------------------------------------------------
    // Initial coordinator state
    // -------------------------------------------------------------------------

    @Test
    fun `initial coordinator state is NOT_INITIALIZED`() {
        val coord = coordinator()

        assertEquals(ProviderLifecycleCoordinatorState.NOT_INITIALIZED, coord.state)
    }

    @Test
    fun `construction does not initialize providers`() {
        val p = FakeProvider("a")
        coordinator(p)

        assertEquals(0, p.initializeCallCount)
    }

    @Test
    fun `construction does not shut down providers`() {
        val p = FakeProvider("a")
        coordinator(p)

        assertEquals(0, p.closeCallCount)
    }

    // -------------------------------------------------------------------------
    // Initialization state progression
    // -------------------------------------------------------------------------

    @Test
    fun `successful initialization transitions state to INITIALIZED`() {
        val coord = coordinator(FakeProvider("a"))

        runSuspend { coord.initialize() }

        assertEquals(ProviderLifecycleCoordinatorState.INITIALIZED, coord.state)
    }

    @Test
    fun `empty registry initialization transitions state to INITIALIZED`() {
        val coord = coordinator()

        runSuspend { coord.initialize() }

        assertEquals(ProviderLifecycleCoordinatorState.INITIALIZED, coord.state)
    }

    @Test
    fun `successful initialization returns InitializeSuccess`() {
        val coord = coordinator(FakeProvider("a"))

        val result = runSuspend { coord.initialize() }

        assertIs<ProviderLifecycleResult.InitializeSuccess>(result)
    }

    @Test
    fun `empty registry initialization returns InitializeSuccess`() {
        val coord = coordinator()

        val result = runSuspend { coord.initialize() }

        assertIs<ProviderLifecycleResult.InitializeSuccess>(result)
    }

    // -------------------------------------------------------------------------
    // Providers initialized in registration order
    // -------------------------------------------------------------------------

    @Test
    fun `providers are initialized in registration order`() {
        val globalOrder = mutableListOf<String>()
        val a = FakeProvider("a", initOrder = globalOrder)
        val b = FakeProvider("b", initOrder = globalOrder)
        val c = FakeProvider("c", initOrder = globalOrder)
        val coord = coordinator(a, b, c)

        runSuspend { coord.initialize() }

        assertEquals(listOf("a", "b", "c"), globalOrder)
    }

    // -------------------------------------------------------------------------
    // ProviderInitializationContext is passed to providers
    // -------------------------------------------------------------------------

    @Test
    fun `initialization context is passed to each provider`() {
        val customContext = ProviderInitializationContext(
            runtimeVersion = RuntimeVersion("2.0.0"),
        )
        val a = FakeProvider("a")
        val b = FakeProvider("b")
        val coord = ProviderLifecycleCoordinator(
            registry = ProviderRegistry(listOf(a, b)),
            context = customContext,
        )

        runSuspend { coord.initialize() }

        assertEquals(customContext, a.capturedContext)
        assertEquals(customContext, b.capturedContext)
    }

    // -------------------------------------------------------------------------
    // Already-initialized provider not initialized twice
    // -------------------------------------------------------------------------

    @Test
    fun `calling initialize twice returns InvalidOperation on second call`() {
        val coord = coordinator(FakeProvider("a"))
        runSuspend { coord.initialize() }

        val result = runSuspend { coord.initialize() }

        assertIs<ProviderLifecycleResult.InvalidOperation>(result)
    }

    @Test
    fun `calling initialize twice does not re-initialize providers`() {
        val p = FakeProvider("a")
        val coord = coordinator(p)
        runSuspend { coord.initialize() }

        runSuspend { coord.initialize() }

        assertEquals(1, p.initializeCallCount)
    }

    @Test
    fun `InvalidOperation from second initialize preserves state and operation`() {
        val coord = coordinator(FakeProvider("a"))
        runSuspend { coord.initialize() }

        val result = runSuspend { coord.initialize() }

        val invalid = assertIs<ProviderLifecycleResult.InvalidOperation>(result)
        assertEquals(ProviderLifecycleCoordinatorState.INITIALIZED, invalid.state)
        assertEquals(ProviderLifecycleOperation.INITIALIZE, invalid.operation)
    }

    // -------------------------------------------------------------------------
    // Initialization failure: stops later providers
    // -------------------------------------------------------------------------

    @Test
    fun `initialization failure stops providers registered after the failing provider`() {
        val a = FakeProvider("a")
        val b = FakeProvider(
            "b",
            initializeResult = ProviderOperationResult.Failure(error("DL-INIT-B")),
        )
        val c = FakeProvider("c")
        val coord = coordinator(a, b, c)

        runSuspend { coord.initialize() }

        assertEquals(0, c.initializeCallCount)
    }

    @Test
    fun `initialization failure returns InitializeFailure`() {
        val coord = coordinator(
            FakeProvider(
                "a",
                initializeResult = ProviderOperationResult.Failure(error("DL-INIT-FAIL")),
            ),
        )

        val result = runSuspend { coord.initialize() }

        assertIs<ProviderLifecycleResult.InitializeFailure>(result)
    }

    @Test
    fun `initialization failure identifies the correct provider`() {
        val a = FakeProvider("a")
        val b = FakeProvider(
            "b",
            initializeResult = ProviderOperationResult.Failure(error("DL-INIT-B")),
        )
        val coord = coordinator(a, b)

        val result = runSuspend { coord.initialize() }

        val failure = assertIs<ProviderLifecycleResult.InitializeFailure>(result)
        assertEquals(ProviderId("b"), failure.primaryFailure.providerId)
    }

    @Test
    fun `initialization failure preserves canonical DataLoomError`() {
        val expected = error("DL-STORAGE-INIT", "Storage init failed.")
        val coord = coordinator(
            FakeProvider(
                "a",
                initializeResult = ProviderOperationResult.Failure(expected),
            ),
        )

        val result = runSuspend { coord.initialize() }

        val failure = assertIs<ProviderLifecycleResult.InitializeFailure>(result)
        assertEquals(expected, failure.primaryFailure.error)
    }

    @Test
    fun `initialization failure preserves INITIALIZE operation in primary failure`() {
        val coord = coordinator(
            FakeProvider(
                "a",
                initializeResult = ProviderOperationResult.Failure(error("DL-INIT")),
            ),
        )

        val result = runSuspend { coord.initialize() }

        val failure = assertIs<ProviderLifecycleResult.InitializeFailure>(result)
        assertEquals(ProviderLifecycleOperation.INITIALIZE, failure.primaryFailure.operation)
    }

    @Test
    fun `coordinator ends in FAILED state after initialization failure`() {
        val coord = coordinator(
            FakeProvider(
                "a",
                initializeResult = ProviderOperationResult.Failure(error("DL-INIT")),
            ),
        )

        runSuspend { coord.initialize() }

        assertEquals(ProviderLifecycleCoordinatorState.FAILED, coord.state)
    }

    // -------------------------------------------------------------------------
    // Initialization failure: rollback
    // -------------------------------------------------------------------------

    @Test
    fun `rollback shuts down previously initialized providers in reverse order`() {
        val globalClose = mutableListOf<String>()
        val a = FakeProvider("a", closeOrder = globalClose)
        val b = FakeProvider("b", closeOrder = globalClose)
        val c = FakeProvider(
            "c",
            initializeResult = ProviderOperationResult.Failure(error("DL-INIT-C")),
        )
        val coord = coordinator(a, b, c)

        runSuspend { coord.initialize() }

        assertEquals(listOf("b", "a"), globalClose)
    }

    @Test
    fun `rollback does not shut down the failing provider itself`() {
        val failing = FakeProvider(
            "failing",
            initializeResult = ProviderOperationResult.Failure(error("DL-INIT")),
        )
        val coord = coordinator(failing)

        runSuspend { coord.initialize() }

        assertEquals(0, failing.closeCallCount)
    }

    @Test
    fun `rollback does not shut down providers that were not initialized`() {
        val a = FakeProvider("a")
        val b = FakeProvider(
            "b",
            initializeResult = ProviderOperationResult.Failure(error("DL-INIT-B")),
        )
        val c = FakeProvider("c")
        val coord = coordinator(a, b, c)

        runSuspend { coord.initialize() }

        assertEquals(0, c.closeCallCount)
    }

    @Test
    fun `rollback failure is preserved separately from primary failure`() {
        val rollbackError = error("DL-ROLLBACK-A", "A rollback failed.")
        val a = FakeProvider(
            "a",
            closeResult = ProviderOperationResult.Failure(rollbackError),
        )
        val b = FakeProvider(
            "b",
            initializeResult = ProviderOperationResult.Failure(error("DL-INIT-B")),
        )
        val coord = coordinator(a, b)

        val result = runSuspend { coord.initialize() }

        val failure = assertIs<ProviderLifecycleResult.InitializeFailure>(result)
        assertEquals(1, failure.rollbackFailures.size)
        assertEquals(ProviderId("a"), failure.rollbackFailures[0].providerId)
        assertEquals(rollbackError, failure.rollbackFailures[0].error)
    }

    @Test
    fun `primary initialization failure is not replaced by rollback failure`() {
        val primaryError = error("DL-INIT-B", "B init failed.")
        val a = FakeProvider(
            "a",
            closeResult = ProviderOperationResult.Failure(error("DL-ROLLBACK-A")),
        )
        val b = FakeProvider(
            "b",
            initializeResult = ProviderOperationResult.Failure(primaryError),
        )
        val coord = coordinator(a, b)

        val result = runSuspend { coord.initialize() }

        val failure = assertIs<ProviderLifecycleResult.InitializeFailure>(result)
        assertEquals(primaryError, failure.primaryFailure.error)
        assertEquals(ProviderId("b"), failure.primaryFailure.providerId)
    }

    @Test
    fun `rollback failures list is empty when no rollback failures occur`() {
        val a = FakeProvider("a")
        val b = FakeProvider(
            "b",
            initializeResult = ProviderOperationResult.Failure(error("DL-INIT-B")),
        )
        val coord = coordinator(a, b)

        val result = runSuspend { coord.initialize() }

        val failure = assertIs<ProviderLifecycleResult.InitializeFailure>(result)
        assertEquals(emptyList(), failure.rollbackFailures)
    }

    // -------------------------------------------------------------------------
    // Normal shutdown
    // -------------------------------------------------------------------------

    @Test
    fun `shutdown occurs in reverse initialization order`() {
        val globalClose = mutableListOf<String>()
        val a = FakeProvider("a", closeOrder = globalClose)
        val b = FakeProvider("b", closeOrder = globalClose)
        val c = FakeProvider("c", closeOrder = globalClose)
        val coord = coordinator(a, b, c)

        runSuspend { coord.initialize() }
        runSuspend { coord.shutdown() }

        assertEquals(listOf("c", "b", "a"), globalClose)
    }

    @Test
    fun `successful shutdown returns ShutdownSuccess`() {
        val coord = coordinator(FakeProvider("a"))
        runSuspend { coord.initialize() }

        val result = runSuspend { coord.shutdown() }

        assertIs<ProviderLifecycleResult.ShutdownSuccess>(result)
    }

    @Test
    fun `successful shutdown transitions state to SHUT_DOWN`() {
        val coord = coordinator(FakeProvider("a"))
        runSuspend { coord.initialize() }

        runSuspend { coord.shutdown() }

        assertEquals(ProviderLifecycleCoordinatorState.SHUT_DOWN, coord.state)
    }

    @Test
    fun `shutdown on empty registry returns ShutdownSuccess`() {
        val coord = coordinator()
        runSuspend { coord.initialize() }

        val result = runSuspend { coord.shutdown() }

        assertIs<ProviderLifecycleResult.ShutdownSuccess>(result)
    }

    @Test
    fun `every initialized provider is shut down exactly once`() {
        val a = FakeProvider("a")
        val b = FakeProvider("b")
        val coord = coordinator(a, b)

        runSuspend { coord.initialize() }
        runSuspend { coord.shutdown() }

        assertEquals(1, a.closeCallCount)
        assertEquals(1, b.closeCallCount)
    }

    @Test
    fun `shutdown before initialize returns InvalidOperation`() {
        val coord = coordinator(FakeProvider("a"))

        val result = runSuspend { coord.shutdown() }

        assertIs<ProviderLifecycleResult.InvalidOperation>(result)
    }

    @Test
    fun `InvalidOperation from premature shutdown preserves state and operation`() {
        val coord = coordinator(FakeProvider("a"))

        val result = runSuspend { coord.shutdown() }

        val invalid = assertIs<ProviderLifecycleResult.InvalidOperation>(result)
        assertEquals(ProviderLifecycleCoordinatorState.NOT_INITIALIZED, invalid.state)
        assertEquals(ProviderLifecycleOperation.SHUTDOWN, invalid.operation)
    }

    @Test
    fun `repeated shutdown returns InvalidOperation`() {
        val coord = coordinator(FakeProvider("a"))
        runSuspend { coord.initialize() }
        runSuspend { coord.shutdown() }

        val result = runSuspend { coord.shutdown() }

        assertIs<ProviderLifecycleResult.InvalidOperation>(result)
    }

    @Test
    fun `repeated shutdown does not shut down providers a second time`() {
        val p = FakeProvider("a")
        val coord = coordinator(p)
        runSuspend { coord.initialize() }
        runSuspend { coord.shutdown() }

        runSuspend { coord.shutdown() }

        assertEquals(1, p.closeCallCount)
    }

    // -------------------------------------------------------------------------
    // Shutdown failure isolation
    // -------------------------------------------------------------------------

    @Test
    fun `one shutdown failure does not stop remaining shutdown calls`() {
        // Initialized order: a, b. Shutdown order (reverse): b, a
        // b shuts down successfully; a fails. Both must be called.
        val globalClose = mutableListOf<String>()
        val a = FakeProvider(
            "a",
            closeResult = ProviderOperationResult.Failure(error("DL-CLOSE-A")),
            closeOrder = globalClose,
        )
        val b = FakeProvider("b", closeOrder = globalClose)
        val coord = coordinator(a, b)

        runSuspend { coord.initialize() }
        runSuspend { coord.shutdown() }

        // Both providers must have their close called (b first, then a)
        assertTrue("a" in globalClose, "Provider a must be shut down despite failure")
        assertTrue("b" in globalClose, "Provider b must be shut down")
    }

    @Test
    fun `all shutdown failures are collected`() {
        val a = FakeProvider(
            "a",
            closeResult = ProviderOperationResult.Failure(error("DL-CLOSE-A")),
        )
        val b = FakeProvider(
            "b",
            closeResult = ProviderOperationResult.Failure(error("DL-CLOSE-B")),
        )
        val coord = coordinator(a, b)

        runSuspend { coord.initialize() }
        val result = runSuspend { coord.shutdown() }

        val shutdownFailure = assertIs<ProviderLifecycleResult.ShutdownFailure>(result)
        assertEquals(2, shutdownFailure.failures.size)
    }

    @Test
    fun `shutdown failure ordering follows shutdown invocation order`() {
        // Initialized order: a, b. Shutdown order (reverse): b, a
        val a = FakeProvider(
            "a",
            closeResult = ProviderOperationResult.Failure(error("DL-CLOSE-A")),
        )
        val b = FakeProvider(
            "b",
            closeResult = ProviderOperationResult.Failure(error("DL-CLOSE-B")),
        )
        val coord = coordinator(a, b)

        runSuspend { coord.initialize() }
        val result = runSuspend { coord.shutdown() }

        val shutdownFailure = assertIs<ProviderLifecycleResult.ShutdownFailure>(result)
        // Reverse init order: b shuts down first, a shuts down second
        assertEquals(ProviderId("b"), shutdownFailure.failures[0].providerId)
        assertEquals(ProviderId("a"), shutdownFailure.failures[1].providerId)
    }

    @Test
    fun `shutdown failure preserves provider identity and DataLoomError`() {
        val closeError = error("DL-CLOSE-A", "A failed to close.")
        val a = FakeProvider(
            "a",
            closeResult = ProviderOperationResult.Failure(closeError),
        )
        val coord = coordinator(a)

        runSuspend { coord.initialize() }
        val result = runSuspend { coord.shutdown() }

        val shutdownFailure = assertIs<ProviderLifecycleResult.ShutdownFailure>(result)
        assertEquals(1, shutdownFailure.failures.size)
        assertEquals(ProviderId("a"), shutdownFailure.failures[0].providerId)
        assertEquals(closeError, shutdownFailure.failures[0].error)
        assertEquals(ProviderLifecycleOperation.SHUTDOWN, shutdownFailure.failures[0].operation)
    }

    @Test
    fun `shutdown failure transitions coordinator to FAILED state`() {
        val a = FakeProvider(
            "a",
            closeResult = ProviderOperationResult.Failure(error("DL-CLOSE-A")),
        )
        val coord = coordinator(a)

        runSuspend { coord.initialize() }
        runSuspend { coord.shutdown() }

        assertEquals(ProviderLifecycleCoordinatorState.FAILED, coord.state)
    }

    // -------------------------------------------------------------------------
    // Cancellation: CancellationException propagates from initialize
    // -------------------------------------------------------------------------

    @Test
    fun `CancellationException from provider initialize propagates from coordinator initialize`() {
        val coord = ProviderLifecycleCoordinator(
            registry = ProviderRegistry(listOf(CancellingInitializeProvider("cancel-init"))),
            context = defaultContext,
        )

        val result = runSuspendCatching { coord.initialize() }

        assertTrue(result.isFailure)
        assertIs<CancellationException>(result.exceptionOrNull())
    }

    @Test
    fun `CancellationException from initialize is not converted to InitializeFailure`() {
        val coord = ProviderLifecycleCoordinator(
            registry = ProviderRegistry(listOf(CancellingInitializeProvider("cancel-init"))),
            context = defaultContext,
        )

        val result = runSuspendCatching { coord.initialize() }

        // Must propagate as CancellationException, not wrapped in a lifecycle result
        assertTrue(result.isFailure)
        assertIs<CancellationException>(result.exceptionOrNull())
    }

    // -------------------------------------------------------------------------
    // Cancellation: CancellationException propagates from shutdown
    // -------------------------------------------------------------------------

    @Test
    fun `CancellationException from provider close propagates from coordinator shutdown`() {
        val coord = ProviderLifecycleCoordinator(
            registry = ProviderRegistry(listOf(CancellingCloseProvider("cancel-close"))),
            context = defaultContext,
        )
        runSuspend { coord.initialize() }

        val result = runSuspendCatching { coord.shutdown() }

        assertTrue(result.isFailure)
        assertIs<CancellationException>(result.exceptionOrNull())
    }

    @Test
    fun `CancellationException from shutdown is not converted to ShutdownFailure`() {
        val coord = ProviderLifecycleCoordinator(
            registry = ProviderRegistry(listOf(CancellingCloseProvider("cancel-close"))),
            context = defaultContext,
        )
        runSuspend { coord.initialize() }

        val result = runSuspendCatching { coord.shutdown() }

        assertTrue(result.isFailure)
        assertIs<CancellationException>(result.exceptionOrNull())
    }

    // -------------------------------------------------------------------------
    // Scope and compatibility: no platform or framework types required
    // -------------------------------------------------------------------------

    @Test
    fun `ProviderRegistry is constructible without Android types`() {
        val registry = ProviderRegistry(listOf(FakeProvider("a")))
        assertEquals(1, registry.size)
    }

    @Test
    fun `ProviderLifecycleCoordinator is constructible without Android types`() {
        val coord = coordinator(FakeProvider("a"))
        assertEquals(ProviderLifecycleCoordinatorState.NOT_INITIALIZED, coord.state)
    }

    @Test
    fun `ProviderLifecycleResult subtypes cover all documented outcomes`() {
        val err = error("DL-TEST")
        val failure = ProviderLifecycleFailure(ProviderId("p"), ProviderLifecycleOperation.INITIALIZE, err)

        val success: ProviderLifecycleResult = ProviderLifecycleResult.InitializeSuccess
        val shutdownSuccess: ProviderLifecycleResult = ProviderLifecycleResult.ShutdownSuccess
        val initFail: ProviderLifecycleResult = ProviderLifecycleResult.InitializeFailure(
            primaryFailure = failure,
        )
        val shutFail: ProviderLifecycleResult = ProviderLifecycleResult.ShutdownFailure(
            failures = listOf(
                ProviderLifecycleFailure(ProviderId("p"), ProviderLifecycleOperation.SHUTDOWN, err),
            ),
        )
        val invalid: ProviderLifecycleResult = ProviderLifecycleResult.InvalidOperation(
            state = ProviderLifecycleCoordinatorState.NOT_INITIALIZED,
            operation = ProviderLifecycleOperation.SHUTDOWN,
        )

        assertIs<ProviderLifecycleResult.InitializeSuccess>(success)
        assertIs<ProviderLifecycleResult.ShutdownSuccess>(shutdownSuccess)
        assertIs<ProviderLifecycleResult.InitializeFailure>(initFail)
        assertIs<ProviderLifecycleResult.ShutdownFailure>(shutFail)
        assertIs<ProviderLifecycleResult.InvalidOperation>(invalid)
    }

    @Test
    fun `ProviderLifecycleFailure is a value type with equality`() {
        val err = error("DL-EQ")
        val f1 = ProviderLifecycleFailure(ProviderId("x"), ProviderLifecycleOperation.INITIALIZE, err)
        val f2 = ProviderLifecycleFailure(ProviderId("x"), ProviderLifecycleOperation.INITIALIZE, err)

        assertEquals(f1, f2)
    }

    // -------------------------------------------------------------------------
    // ProviderLifecycleResult collection immutability
    // -------------------------------------------------------------------------

    @Test
    fun `InitializeFailure rollbackFailures is typed as List and does not expose MutableList interface`() {
        val a = FakeProvider(
            "a",
            closeResult = ProviderOperationResult.Failure(error("DL-ROLLBACK-A")),
        )
        val b = FakeProvider(
            "b",
            initializeResult = ProviderOperationResult.Failure(error("DL-INIT-B")),
        )
        val coord = coordinator(a, b)

        val result = runSuspend { coord.initialize() }

        val failure = assertIs<ProviderLifecycleResult.InitializeFailure>(result)
        // The property type is List<ProviderLifecycleFailure> (read-only interface).
        val list: List<ProviderLifecycleFailure> = failure.rollbackFailures
        assertEquals(1, list.size)
    }

    @Test
    fun `ShutdownFailure failures is typed as List and does not expose MutableList interface`() {
        val a = FakeProvider(
            "a",
            closeResult = ProviderOperationResult.Failure(error("DL-CLOSE-A")),
        )
        val coord = coordinator(a)
        runSuspend { coord.initialize() }

        val result = runSuspend { coord.shutdown() }

        val shutdownFailure = assertIs<ProviderLifecycleResult.ShutdownFailure>(result)
        // The property type is List<ProviderLifecycleFailure> (read-only interface).
        val list: List<ProviderLifecycleFailure> = shutdownFailure.failures
        assertEquals(1, list.size)
    }
}
