package io.dataloom.core.provider

/**
 * Sealed result of a provider resolution attempt by [SynchronizationProviderResolver].
 *
 * ## Variants
 *
 * - [Success]: all configured bindings resolved to valid, compatible providers.
 * - [Failure]: one or more configured bindings could not be resolved.
 *
 * ## Deterministic failure ordering
 *
 * When [Failure] is returned, failures are ordered by the runtime role
 * validation sequence:
 *
 * 1. Storage
 * 2. Transport
 * 3. Scheduler
 * 4. Connectivity
 * 5. Queue
 *
 * Optional roles that were not configured do not produce failures.
 *
 * ## No partial resolution
 *
 * [Failure] does not expose partially resolved provider instances. When any
 * binding fails, [Success] is not returned.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom core types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface ProviderResolutionResult {

    /**
     * All configured provider bindings resolved successfully.
     *
     * [providers] contains the exact instances registered under each configured
     * [io.dataloom.api.provider.ProviderId].
     *
     * @param providers the fully resolved provider set.
     */
    public data class Success(
        /** The fully resolved synchronization provider set. */
        public val providers: ResolvedSynchronizationProviders,
    ) : ProviderResolutionResult

    /**
     * One or more configured provider bindings could not be resolved.
     *
     * [failures] is a non-empty ordered list of [ProviderBindingFailure]
     * records, one for each role that failed validation. Failures are ordered
     * by role-validation sequence: Storage, Transport, Scheduler, Connectivity,
     * Queue.
     *
     * ## Empty failure rejection
     *
     * Construction throws [IllegalArgumentException] when [failures] is empty.
     * A [Failure] must always represent at least one real failure condition.
     *
     * ## Defensive copy
     *
     * The supplied collection is defensively copied. Mutations to the original
     * collection after construction have no effect on this result.
     *
     * ## No partial provider set
     *
     * [Failure] exposes no provider instances. Resolved providers from
     * successful roles are discarded to prevent partial access.
     *
     * ## Value semantics
     *
     * [Failure] is a `data class` that provides value-based equality based on
     * the ordered failure list.
     *
     * @param failures a non-empty ordered list of [ProviderBindingFailure]
     *   records.
     * @throws IllegalArgumentException if [failures] is empty.
     */
    public data class Failure(
        private val failures: List<ProviderBindingFailure>,
    ) : ProviderResolutionResult {

        init {
            require(failures.isNotEmpty()) {
                "ProviderResolutionResult.Failure must contain at least one ProviderBindingFailure."
            }
        }

        private val failuresSnapshot: List<ProviderBindingFailure> = failures.toList()

        /**
         * The non-empty ordered list of [ProviderBindingFailure] records for
         * every configured role that failed validation.
         *
         * The list is immutable and ordered by role-validation sequence:
         * Storage, Transport, Scheduler, Connectivity, Queue.
         */
        public val bindingFailures: List<ProviderBindingFailure>
            get() = failuresSnapshot

        // Override equals/hashCode/toString to use the snapshot instead of the
        // constructor parameter to ensure defensive copy semantics are preserved.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Failure) return false
            return failuresSnapshot == other.failuresSnapshot
        }

        override fun hashCode(): Int = failuresSnapshot.hashCode()

        override fun toString(): String =
            "ProviderResolutionResult.Failure(failures=$failuresSnapshot)"
    }
}
