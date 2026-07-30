package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CircuitBreakerProviderOperationAdapterTest {
    private val scope = CircuitBreakerScope.provider(ProviderId("adapter-provider"))

    @Test
    fun `default classifier counts only recoverable availability failures`() {
        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_FAILURE,
            DefaultCircuitBreakerFailureClassifier.classify(
                error(ErrorCategory.NETWORK, Recoverability.RECOVERABLE),
            ),
        )
        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_SUCCESS,
            DefaultCircuitBreakerFailureClassifier.classify(
                error(ErrorCategory.VALIDATION, Recoverability.RECOVERABLE),
            ),
        )
        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_SUCCESS,
            DefaultCircuitBreakerFailureClassifier.classify(
                error(ErrorCategory.NETWORK, Recoverability.NON_RECOVERABLE),
            ),
        )
        assertEquals(
            CircuitBreakerFailureDisposition.RECORD_SUCCESS,
            DefaultCircuitBreakerFailureClassifier.classify(
                error(ErrorCategory.PROVIDER, Recoverability.UNKNOWN),
            ),
        )
    }

    @Test
    fun `provider success is preserved and recorded as circuit success`() {
        val context = context()

        val executed = assertIs<CircuitBreakerExecutionResult.Executed<String>>(
            runSuspend {
                context.adapter.execute(scope) {
                    ProviderOperationResult.Success("provider-value")
                }
            },
        )

        assertEquals(
            "provider-value",
            assertIs<CircuitProtectedOperationResult.Success<String>>(executed.operationResult).value,
        )
        assertIs<CircuitBreakerRecordResult.Ignored>(executed.recordResult)
        assertNull(context.store.record(scope))
    }

    @Test
    fun `recoverable provider failure contributes and opens configured circuit`() {
        val context = context(failureThreshold = 1)
        val providerError = error(ErrorCategory.PROVIDER, Recoverability.RECOVERABLE)
        var calls = 0

        val first = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(
            runSuspend {
                context.adapter.execute(scope) {
                    calls += 1
                    ProviderOperationResult.Failure(providerError)
                }
            },
        )
        assertEquals(
            providerError,
            assertIs<CircuitProtectedOperationResult.Failure>(first.operationResult).error,
        )
        assertIs<CircuitBreakerRecordResult.Recorded>(first.recordResult)

        val rejected = runSuspend {
            context.adapter.execute(scope) {
                calls += 1
                ProviderOperationResult.Success("must-not-run")
            }
        }
        assertIs<CircuitBreakerExecutionResult.Rejected>(rejected)
        assertEquals(1, calls)
    }

    @Test
    fun `semantic provider failure is preserved without degrading circuit health`() {
        val context = context()
        val validationError = error(ErrorCategory.VALIDATION, Recoverability.NON_RECOVERABLE)

        val executed = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(
            runSuspend {
                context.adapter.execute(scope) {
                    ProviderOperationResult.Failure(validationError)
                }
            },
        )

        assertEquals(
            validationError,
            assertIs<CircuitProtectedOperationResult.NonCircuitFailure>(
                executed.operationResult,
            ).error,
        )
        assertIs<CircuitBreakerRecordResult.Ignored>(executed.recordResult)
        assertNull(context.store.record(scope))
    }

    @Test
    fun `custom classifier may include a host approved canonical failure`() {
        val context = context(
            failureThreshold = 1,
            classifier = CircuitBreakerFailureClassifier {
                CircuitBreakerFailureDisposition.RECORD_FAILURE
            },
        )
        val hostApproved = error(ErrorCategory.VALIDATION, Recoverability.NON_RECOVERABLE)

        val executed = assertIs<CircuitBreakerExecutionResult.Executed<Nothing>>(
            runSuspend {
                context.adapter.execute(scope) {
                    ProviderOperationResult.Failure(hostApproved)
                }
            },
        )

        assertIs<CircuitProtectedOperationResult.Failure>(executed.operationResult)
        assertIs<CircuitBreakerRecordResult.Recorded>(executed.recordResult)
        assertEquals(
            CircuitBreakerRejectionReason.OPEN,
            assertIs<CircuitBreakerExecutionResult.Rejected>(
                runSuspend {
                    context.adapter.execute(scope) {
                        ProviderOperationResult.Success("must-not-run")
                    }
                },
            ).reason,
        )
    }

    private fun error(
        category: ErrorCategory,
        recoverability: Recoverability,
    ): DataLoomError = TestError(
        code = ErrorCode("ADAPTER-${category.name}-${recoverability.name}"),
        category = category,
        recoverability = recoverability,
    )

    private fun context(
        failureThreshold: Int = 2,
        classifier: CircuitBreakerFailureClassifier = DefaultCircuitBreakerFailureClassifier,
    ): TestContext = TestContext(failureThreshold, classifier)

    private data class TestError(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String = "Sanitized provider adapter failure.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    private class TestContext(
        failureThreshold: Int,
        classifier: CircuitBreakerFailureClassifier,
    ) {
        val store = InMemoryCircuitStore()
        private val coordinator = CircuitBreakerCoordinator(
            configuration = CircuitBreakerConfiguration(
                failureThreshold = failureThreshold,
                failureWindow = SchedulingDelay(1_000L),
                openDuration = SchedulingDelay(1_000L),
            ),
            clock = FixedClock,
            stateStore = store,
        )
        val adapter = CircuitBreakerProviderOperationAdapter(
            executionGate = CircuitBreakerExecutionGate(coordinator),
            failureClassifier = classifier,
        )
    }

    private object FixedClock : DataLoomClock {
        override fun now(): DataLoomInstant = DataLoomInstant(100L)
    }

    private class InMemoryCircuitStore : CircuitBreakerStateStore {
        private val records = mutableMapOf<CircuitBreakerScope, CircuitBreakerStateRecord>()

        fun record(scope: CircuitBreakerScope): CircuitBreakerStateRecord? = records[scope]

        override suspend fun load(
            scope: CircuitBreakerScope,
        ): ProviderOperationResult<CircuitBreakerLoadResult> = ProviderOperationResult.Success(
            records[scope]?.let(CircuitBreakerLoadResult::Found)
                ?: CircuitBreakerLoadResult.Missing,
        )

        override suspend fun compareAndSet(
            request: CircuitBreakerCompareAndSetRequest,
        ): ProviderOperationResult<CircuitBreakerCompareAndSetResult> {
            val current = records[request.scope]
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(
                    CircuitBreakerCompareAndSetResult.Conflict(current),
                )
            }
            val updated = CircuitBreakerStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(
                CircuitBreakerCompareAndSetResult.Updated(updated),
            )
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context: CoroutineContext = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    completed = result
                }
            },
        )
        return checkNotNull(completed).getOrThrow()
    }
}
