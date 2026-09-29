package io.dataloom.runtime.state

import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.provider.ProviderOperationResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/** What one domain-level write attempt did, independent of the domain's own outcome type. */
internal enum class ProofAttempt {
    /** The attempt created (or advanced) the durable record. */
    WROTE,

    /** The attempt found the same facts already durable and changed nothing (or was rejected as stale). */
    DUPLICATE,

    /** Any other domain outcome, including persistence failure or contention exhaustion. */
    UNEXPECTED,
}

/**
 * Shared, parameterized proof that one domain wired through
 * [AppleFileDurableDomainStores] behaves, on a real file, the way its
 * Room-backed counterpart's domain tests assert: the first write is durable,
 * a brand-new store instance (the process-restart shape, as in
 * `AppleFileDurableStateStoreTest`) recovers it, an identical duplicate is
 * absorbed without changing the persisted record, and two racing writers of
 * the same facts converge on exactly one write plus one absorbed duplicate.
 *
 * Each proof method runs on `runBlocking`, not `runTest`, because the store's
 * lock retry uses real `delay`.
 *
 * @param prepare optional setup performed once on a fresh directory before
 *   the measured attempts (for example, bringing a quarantine entry to the
 *   quarantined state so a release can be attempted).
 * @param attempt performs the domain's write and classifies its outcome.
 * @param versionAfterWrite the compare-and-set version the persisted record
 *   must carry after prepare plus one [ProofAttempt.WROTE].
 */
internal class AppleFileDurableDomainProof<TScope : Any, TState : Any>(
    private val fileName: String,
    private val scope: TScope,
    private val openStore: (directoryPath: String) -> AppleFileDurableStateStore<TScope, TState>,
    private val prepare: suspend (DurableStateStore<TScope, TState>) -> Unit = {},
    private val attempt: suspend (DurableStateStore<TScope, TState>) -> ProofAttempt,
    private val versionAfterWrite: Long = 0L,
    private val assertPersisted: (TState) -> Unit,
) {
    fun recordThenRecoverFromAFreshStoreInstance(): Unit = runBlocking {
        val directory = uniqueDirectory()
        prepare(openStore(directory))
        assertEquals(ProofAttempt.WROTE, attempt(openStore(directory)))

        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath("$directory/$fileName"),
            "the domain's own explicit snapshot file must exist",
        )
        assertPersistedRecord(directory)
    }

    fun identicalDuplicateIsAbsorbedWithoutChangingTheRecord(): Unit = runBlocking {
        val directory = uniqueDirectory()
        prepare(openStore(directory))
        assertEquals(ProofAttempt.WROTE, attempt(openStore(directory)))
        assertEquals(ProofAttempt.DUPLICATE, attempt(openStore(directory)))

        assertPersistedRecord(directory)
    }

    fun concurrentDuplicateConvergesOnOneWriteAndOneAbsorbedDuplicate(): Unit = runBlocking {
        val directory = uniqueDirectory()
        prepare(openStore(directory))
        val first = openStore(directory)
        val second = openStore(directory)

        val outcomes = listOf(
            async(Dispatchers.Default) { attempt(first) },
            async(Dispatchers.Default) { attempt(second) },
        ).awaitAll()

        assertEquals(1, outcomes.count { it == ProofAttempt.WROTE }, "outcomes were $outcomes")
        assertEquals(1, outcomes.count { it == ProofAttempt.DUPLICATE }, "outcomes were $outcomes")
        assertPersistedRecord(directory)
    }

    private suspend fun assertPersistedRecord(directory: String) {
        val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<TState>>>(
            openStore(directory).load(scope),
        )
        val found = assertIs<DurableStateLoadResult.Found<TState>>(loaded.value)
        assertEquals(versionAfterWrite, found.record.version)
        assertPersisted(found.record.state)
    }

    companion object {
        fun uniqueDirectory(): String =
            NSTemporaryDirectory().trimEnd('/') + "/dataloom-apple-domain-stores-" + NSUUID().UUIDString
    }
}
