package io.dataloom.runtime.facade

import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.execution.protection.ProviderProtectedSynchronizationResult

/**
 * Public additive capability for synchronization through the provider timeout
 * and circuit boundaries configured on [DataLoomBuilder].
 *
 * The historical [DataLoom.synchronize] methods remain unchanged. Applications
 * and queue runtimes must select this capability explicitly through
 * [DataLoom.protectedSynchronization].
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

    /**
     * Executes [request] using the exact caller-supplied [bindings].
     *
     * This overload is required by durable queued work, which carries its
     * accepted provider bindings explicitly. Provider resolution, protected
     * provider identity validation, connectivity admission, and pipeline
     * selection remain unchanged. No fallback to default bindings occurs.
     *
     * Caller cancellation and unexpected exceptions propagate unchanged.
     */
    public suspend fun synchronize(
        request: SynchronizationRequest,
        bindings: SynchronizationProviderBindings,
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
