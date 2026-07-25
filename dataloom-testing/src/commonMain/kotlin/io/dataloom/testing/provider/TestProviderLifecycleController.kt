package io.dataloom.testing.provider

import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderHealthStatus
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderLifecycleState
import io.dataloom.api.provider.ProviderOperationResult

/**
 * Reusable lifecycle controller for provider test fakes.
 *
 * Call-order recording is not thread-safe. Callers that share an instance
 * across threads must serialize mutation externally.
 *
 * @param initializeResult result returned from [initialize].
 * @param healthResult result returned from [health].
 * @param closeResult result returned from [close].
 */
public class TestProviderLifecycleController(
    private val initializeResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
    private val healthResult: ProviderOperationResult<ProviderHealth> = ProviderOperationResult.Success(
        ProviderHealth(ProviderHealthStatus.HEALTHY),
    ),
    private val closeResult: ProviderOperationResult<Unit> = ProviderOperationResult.Success(Unit),
) {
    private var recordedInitializeCallCount: Int = 0
    private var recordedCloseCallCount: Int = 0
    private var recordedHealthCallCount: Int = 0
    private val recordedLifecycleCalls: MutableList<String> = mutableListOf()
    private val recordedInitializationContexts: MutableList<ProviderInitializationContext> = mutableListOf()
    private var currentLifecycleState: ProviderLifecycleState = ProviderLifecycleState.CREATED

    /** Number of times [initialize] has been called since the last [clearRecordings]. */
    public val initializeCallCount: Int
        get() = recordedInitializeCallCount

    /** Number of times [close] has been called since the last [clearRecordings]. */
    public val closeCallCount: Int
        get() = recordedCloseCallCount

    /** Number of times [health] has been called since the last [clearRecordings]. */
    public val healthCallCount: Int
        get() = recordedHealthCallCount

    /** Current lifecycle state inferred from the most recent lifecycle result. */
    public val lifecycleState: ProviderLifecycleState
        get() = currentLifecycleState

    /** Ordered snapshot of lifecycle method names recorded so far. */
    public val lifecycleCalls: List<String>
        get() = recordedLifecycleCalls.toList()

    /** Snapshot of initialization contexts recorded so far. */
    public val initializationContexts: List<ProviderInitializationContext>
        get() = recordedInitializationContexts.toList()

    /**
     * Records an initialize call and returns the configured initialize result.
     *
     * Successful initialization transitions the controller to `READY`.
     * Failed initialization transitions the controller to `FAILED`.
     *
     * @param context immutable initialization context to record.
     * @return the configured initialize result.
     */
    public suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> {
        recordedInitializeCallCount += 1
        recordedLifecycleCalls += INITIALIZE_CALL
        recordedInitializationContexts += context
        currentLifecycleState = ProviderLifecycleState.INITIALIZING
        return initializeResult.also { result ->
            currentLifecycleState = when (result) {
                is ProviderOperationResult.Success -> ProviderLifecycleState.READY
                is ProviderOperationResult.Failure -> ProviderLifecycleState.FAILED
            }
        }
    }

    /**
     * Records a health call and returns the configured health result.
     *
     * Health results update the lifecycle state to reflect the returned health
     * snapshot unless the controller is already `CLOSED`.
     *
     * @return the configured health result.
     */
    public suspend fun health(): ProviderOperationResult<ProviderHealth> {
        recordedHealthCallCount += 1
        recordedLifecycleCalls += HEALTH_CALL
        return healthResult.also { result ->
            if (currentLifecycleState == ProviderLifecycleState.CLOSED) {
                return@also
            }
            currentLifecycleState = when (result) {
                is ProviderOperationResult.Success -> result.value.status.toLifecycleState()
                is ProviderOperationResult.Failure -> ProviderLifecycleState.FAILED
            }
        }
    }

    /**
     * Records a close call and returns the configured close result.
     *
     * Successful close transitions the controller to `CLOSED`. Failed close
     * transitions the controller to `FAILED`.
     *
     * @return the configured close result.
     */
    public suspend fun close(): ProviderOperationResult<Unit> {
        recordedCloseCallCount += 1
        recordedLifecycleCalls += CLOSE_CALL
        currentLifecycleState = ProviderLifecycleState.CLOSING
        return closeResult.also { result ->
            currentLifecycleState = when (result) {
                is ProviderOperationResult.Success -> ProviderLifecycleState.CLOSED
                is ProviderOperationResult.Failure -> ProviderLifecycleState.FAILED
            }
        }
    }

    /**
     * Clears recorded call counts and recorded request snapshots.
     *
     * The current [lifecycleState] is preserved.
     */
    public fun clearRecordings() {
        recordedInitializeCallCount = 0
        recordedCloseCallCount = 0
        recordedHealthCallCount = 0
        recordedLifecycleCalls.clear()
        recordedInitializationContexts.clear()
    }

    private fun ProviderHealthStatus.toLifecycleState(): ProviderLifecycleState = when (this) {
        ProviderHealthStatus.HEALTHY -> ProviderLifecycleState.READY
        ProviderHealthStatus.DEGRADED -> ProviderLifecycleState.DEGRADED
        ProviderHealthStatus.UNKNOWN,
        ProviderHealthStatus.UNHEALTHY,
        -> ProviderLifecycleState.FAILED
    }

    private companion object {
        private const val INITIALIZE_CALL: String = "initialize"
        private const val HEALTH_CALL: String = "health"
        private const val CLOSE_CALL: String = "close"
    }
}
