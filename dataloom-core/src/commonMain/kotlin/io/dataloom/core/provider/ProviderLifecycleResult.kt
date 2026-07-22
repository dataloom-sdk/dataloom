package io.dataloom.core.provider

/**
 * Sealed result of a [ProviderLifecycleCoordinator] lifecycle operation.
 *
 * [ProviderLifecycleResult] provides structured outcome information for
 * [ProviderLifecycleCoordinator.initialize] and
 * [ProviderLifecycleCoordinator.shutdown] calls.
 *
 * ## Security restrictions
 *
 * Results must not expose stack traces, credentials, tokens, encryption keys,
 * personal data, or other sensitive values. [ProviderLifecycleFailure.error]
 * must contain only sanitized diagnostic information.
 *
 * ## Collection immutability
 *
 * Collections in result subtypes are defensively copied at construction time.
 * They are exposed as immutable [List] instances. Mutations to original
 * collections after construction have no effect on result instances.
 */
public sealed interface ProviderLifecycleResult {

    /**
     * All registered providers were successfully initialized.
     *
     * Corresponds to a transition to
     * [ProviderLifecycleCoordinatorState.INITIALIZED].
     */
    public data object InitializeSuccess : ProviderLifecycleResult

    /**
     * All successfully initialized providers were successfully shut down.
     *
     * Corresponds to a transition to
     * [ProviderLifecycleCoordinatorState.SHUT_DOWN].
     */
    public data object ShutdownSuccess : ProviderLifecycleResult

    /**
     * A provider returned a canonical initialization failure.
     *
     * [primaryFailure] identifies the provider and the error that caused
     * initialization to stop. Providers registered after the failed provider
     * were not initialized.
     *
     * [rollbackFailures] contains any failures that occurred while shutting
     * down previously initialized providers during rollback. Rollback failures
     * are secondary and do not replace [primaryFailure].
     *
     * Corresponds to a transition to [ProviderLifecycleCoordinatorState.FAILED].
     *
     * @param primaryFailure the failure that stopped initialization.
     * @param rollbackFailures failures encountered during rollback shutdown;
     *   may be empty when rollback completes without secondary failures.
     */
    public class InitializeFailure(
        /** The canonical failure that stopped initialization. */
        public val primaryFailure: ProviderLifecycleFailure,
        rollbackFailures: List<ProviderLifecycleFailure> = emptyList(),
    ) : ProviderLifecycleResult {

        /** Failures encountered during rollback; may be empty. */
        public val rollbackFailures: List<ProviderLifecycleFailure> = rollbackFailures.toList()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is InitializeFailure) return false
            return primaryFailure == other.primaryFailure &&
                rollbackFailures == other.rollbackFailures
        }

        override fun hashCode(): Int {
            var result = primaryFailure.hashCode()
            result = 31 * result + rollbackFailures.hashCode()
            return result
        }

        override fun toString(): String =
            "InitializeFailure(primaryFailure=$primaryFailure, rollbackFailures=$rollbackFailures)"
    }

    /**
     * One or more providers returned a canonical shutdown failure.
     *
     * Shutdown continues past individual provider failures. All initialized
     * providers are shut down. [failures] contains every failure that occurred,
     * in reverse-initialization order (shutdown invocation order).
     *
     * Corresponds to a transition to [ProviderLifecycleCoordinatorState.FAILED].
     *
     * @param failures all shutdown failures in shutdown invocation order.
     */
    public class ShutdownFailure(
        failures: List<ProviderLifecycleFailure>,
    ) : ProviderLifecycleResult {

        /** All shutdown failures in shutdown invocation order; never empty. */
        public val failures: List<ProviderLifecycleFailure> = failures.toList()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ShutdownFailure) return false
            return failures == other.failures
        }

        override fun hashCode(): Int = failures.hashCode()

        override fun toString(): String = "ShutdownFailure(failures=$failures)"
    }

    /**
     * The lifecycle operation was not valid in the coordinator's current state.
     *
     * This result is returned — not thrown — when a caller invokes
     * [ProviderLifecycleCoordinator.initialize] or
     * [ProviderLifecycleCoordinator.shutdown] in a state that does not permit
     * the operation.
     *
     * @param state the coordinator state at the time of the invalid call.
     * @param operation the operation that was rejected.
     */
    public data class InvalidOperation(
        /** The coordinator state at the time of the invalid call. */
        public val state: ProviderLifecycleCoordinatorState,

        /** The operation that was not valid in [state]. */
        public val operation: ProviderLifecycleOperation,
    ) : ProviderLifecycleResult
}
