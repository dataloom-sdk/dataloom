package io.dataloom.runtime.facade

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationResult

/**
 * Public additive capability for direct synchronization through the provider
 * timeout and circuit boundaries configured on [DataLoomBuilder].
 *
 * The historical [DataLoom.synchronize] methods remain unchanged. Applications
 * must select this capability explicitly through [DataLoom.protectedSynchronization].
 */
public interface DataLoomProtectedSynchronization {

    /**
     * Executes [request] using the default provider bindings configured on the
     * builder and returns the exact provider/circuit evidence.
     *
     * Caller cancellation and unexpected exceptions propagate unchanged.
     */
    public suspend fun synchronize(
        request: SynchronizationRequest,
    ): ProviderProtectedSynchronizationExecutionResult
}

/** Terminal facade result for protected synchronization admission or execution. */
public sealed interface ProviderProtectedSynchronizationExecutionResult {

    /** A pipeline ran and returned its exact synchronization and provider evidence. */
    public data class Executed(
        public val result: ProviderProtectedSynchronizationResult,
    ) : ProviderProtectedSynchronizationExecutionResult

    /**
     * Admission stopped before the protected pipeline ran.
     *
     * The exact existing rejection model is preserved rather than duplicated.
     */
    public data class Rejected(
        public val rejection: SynchronizationExecutionResult.Rejected,
    ) : ProviderProtectedSynchronizationExecutionResult
}
