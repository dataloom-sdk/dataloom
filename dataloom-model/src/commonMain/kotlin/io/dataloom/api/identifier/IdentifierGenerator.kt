package io.dataloom.api.identifier

/**
 * Contract for producing a new identifier of type [T].
 *
 * ## Purpose
 *
 * [IdentifierGenerator] provides a platform-independent abstraction over
 * identifier creation. Runtime components use it to produce strongly typed
 * identifiers for queue entries, queue leases, synchronization events,
 * conflicts, and other runtime-owned values.
 *
 * ## Strong typing
 *
 * The type parameter [T] constrains the generator to a specific DataLoom
 * identifier type such as [io.dataloom.api.identifier.QueueEntryId],
 * [io.dataloom.api.identifier.QueueLeaseId],
 * [io.dataloom.api.identifier.SynchronizationEventId], or
 * [io.dataloom.api.identifier.ConflictId]. This prevents accidental
 * interchange of identifier types at compile time.
 *
 * ## Uniqueness
 *
 * Uniqueness guarantees are implementation-defined. The interface alone does
 * not guarantee global or persistent uniqueness. Callers must not rely on
 * uniqueness guarantees beyond what the specific implementation documents.
 *
 * ## Security limitations
 *
 * - Cryptographic randomness is not guaranteed by this interface.
 * - Identifier generation is not authentication.
 * - Identifier generation is not authorization.
 * - Generated identifiers must not contain credentials, access tokens,
 *   encryption keys, personal data, or device identifiers.
 * - Identifiers must not be treated as secrets.
 *
 * ## Injection
 *
 * [IdentifierGenerator] must be injected into components that require
 * identifier creation. It must not be accessed through a global singleton.
 * Injecting the generator enables deterministic testing without real
 * randomness or platform dependencies.
 *
 * ## Model-construction boundary
 *
 * Immutable DataLoom models receive explicit identifier values as constructor
 * parameters. Models must not invoke generators during construction. The
 * generator is called only at the point in the runtime where a new identifier
 * is needed, and the resulting value is then passed explicitly to the model.
 *
 * ## Thread safety
 *
 * Implementations shared across threads must be thread-safe. Callers that
 * share an [IdentifierGenerator] instance across threads are responsible for
 * selecting a thread-safe implementation.
 *
 * ## Potential production strategies
 *
 * Production implementations are deferred. Options under consideration for
 * future issues include:
 *
 * ```
 * UUID
 * ULID
 * Database-generated sequence
 * Application-controlled identifier
 * Platform-specific secure random source
 * ```
 *
 * Test utilities in `dataloom-testing` provide [IdentifierGenerator]
 * implementations for deterministic testing.
 *
 * @param T the strongly typed identifier produced by this generator.
 */
public interface IdentifierGenerator<T> {

    /**
     * Produces a new identifier candidate.
     *
     * Each call may return a different value depending on the implementation.
     * Implementations backed by real randomness return a new value per call.
     * Test implementations may return fixed, sequential, or controlled values.
     *
     * This method does not perform I/O, access the filesystem, database, or
     * network, sleep, schedule work, or expose platform-specific types.
     *
     * Callers must not assume that the returned value is unique, unpredictable,
     * or cryptographically random unless the specific implementation documents
     * these properties.
     *
     * @return a new identifier of type [T].
     * @throws IllegalStateException or a subtype if the implementation is
     *   unable to produce a value, such as when a test sequence is exhausted.
     */
    public fun generate(): T
}
