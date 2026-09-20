package io.dataloom.assets

import io.dataloom.api.error.Recoverability
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurableAssetTransferSessionStoreTest {

    private val id = sessionId("s-1")

    private suspend fun newSession(size: Int = 5_000, sid: AssetTransferSessionId = id): AssetTransferSession =
        AssetTransferSession(sid, AssetTransferDirection.UPLOAD, testAsset(size = size).manifest)

    private fun advanced(s: AssetTransferSession, event: AssetTransferEvent): AssetTransferSession =
        (s.reduce(event) as AssetTransferTransition.Applied).session

    // ----------------------------------------------------------- basic typed outcomes

    @Test
    fun `a new session is saved and loaded back and persisted through the codec`() = runTest {
        val backing = EncodingDurableStateStore()
        val store = DurableAssetTransferSessionStore(backing)
        val s = newSession()
        assertNull(store.load(id))
        assertEquals(DurableAssetTransferSessionLoadOutcome.Missing, store.tryLoad(id))
        assertIs<DurableAssetTransferSessionSaveOutcome.Saved>(store.trySave(s, expectedRevision = null))
        assertEquals(s, store.load(id))
        assertEquals(1, backing.rows.size)
        assertTrue(backing.rows.getValue("s-1").payload.startsWith("DATALOOM_ASSET_TRANSFER_SESSION"))
    }

    @Test
    fun `save enforces the expected revision in every direction`() = runTest {
        val store = DurableAssetTransferSessionStore(EncodingDurableStateStore())
        val v0 = newSession()
        val v1 = advanced(v0, AssetTransferEvent.Start)
        val v2 = advanced(v1, AssetTransferEvent.ChunkCommitted(0))

        // Absent, but a revision was expected.
        assertEquals(DurableAssetTransferSessionSaveOutcome.StaleRevision(null), store.trySave(v1, expectedRevision = 0))
        assertFalse(store.save(v1, 0))
        assertTrue(store.save(v0, null))
        // Present, but absence was expected.
        assertEquals(DurableAssetTransferSessionSaveOutcome.StaleRevision(v0), store.trySave(v0, expectedRevision = null))
        // Present at a different revision.
        assertEquals(DurableAssetTransferSessionSaveOutcome.StaleRevision(v0), store.trySave(v2, expectedRevision = 1))
        // Matching revision.
        assertTrue(store.save(v1, 0))
        assertTrue(store.save(v2, 1))
        assertEquals(v2, store.load(id))
        // Rewinding is impossible: the stale writer loses.
        assertFalse(store.save(v1, 0))
        assertEquals(v2, store.load(id))
    }

    @Test
    fun `sessions with different ids are independent`() = runTest {
        val store = DurableAssetTransferSessionStore(EncodingDurableStateStore())
        val a = newSession(sid = sessionId("a"))
        val b = newSession(sid = sessionId("b"))
        assertTrue(store.save(a, null))
        assertTrue(store.save(b, null))
        assertTrue(store.save(advanced(a, AssetTransferEvent.Start), 0))
        assertEquals(AssetTransferPhase.CREATED, store.load(sessionId("b"))!!.phase)
    }

    @Test
    fun `a second store instance over the same rows sees the persisted session as after a restart`() = runTest {
        val backing = EncodingDurableStateStore()
        val first = DurableAssetTransferSessionStore(backing)
        val s = advanced(advanced(newSession(), AssetTransferEvent.Start), AssetTransferEvent.ChunkCommitted(2))
        val base = newSession()
        first.save(base, null)
        first.save(advanced(base, AssetTransferEvent.Start), 0)
        first.save(s, 1)
        assertEquals(s, DurableAssetTransferSessionStore(backing).load(id))
    }

    // ------------------------------------------------------------ concurrency

    @Test
    fun `two workers committing the same chunk converge on one session`() = runTest {
        val backing = EncodingDurableStateStore()
        backing.yieldAfterLoad = true // both workers read revision 1 before either writes
        val worker1 = DurableAssetTransferSessionStore(backing)
        val worker2 = DurableAssetTransferSessionStore(backing)
        val started = advanced(newSession(), AssetTransferEvent.Start)
        worker1.save(newSession(), null)
        worker1.save(started, 0)

        val results = listOf(worker1, worker2).map { worker ->
            async { worker.applyEvent(id, AssetTransferEvent.ChunkCommitted(3)) }
        }.awaitAll()

        val final = worker1.load(id)!!
        assertEquals(setOf(3), final.committedChunks)
        assertEquals(started.revision + 1, final.revision, "the chunk is recorded exactly once")
        assertEquals(1, results.count { it is AssetTransferTransition.Applied })
        assertEquals(1, results.count { it is AssetTransferTransition.Unchanged })
        assertTrue(backing.conflictsReported >= 1, "the race must actually have occurred")
    }

    @Test
    fun `two workers committing different chunks both land`() = runTest {
        val backing = EncodingDurableStateStore()
        backing.yieldAfterLoad = true
        val a = DurableAssetTransferSessionStore(backing)
        val b = DurableAssetTransferSessionStore(backing)
        a.save(newSession(), null)
        a.save(advanced(newSession(), AssetTransferEvent.Start), 0)
        listOf(
            async { a.applyEvent(id, AssetTransferEvent.ChunkCommitted(1)) },
            async { b.applyEvent(id, AssetTransferEvent.ChunkCommitted(4)) },
        ).awaitAll()
        assertEquals(setOf(1, 4), a.load(id)!!.committedChunks)
    }

    @Test
    fun `a retry after a lost race reloads and eventually saves`() = runTest {
        val backing = EncodingDurableStateStore()
        val store = DurableAssetTransferSessionStore(backing, maximumStateUpdateAttempts = 4)
        val s = newSession()
        backing.forcedConflicts = 2
        assertIs<DurableAssetTransferSessionSaveOutcome.Saved>(store.trySave(s, null))
        assertEquals(3, backing.compareAndSetCalls)
    }

    @Test
    fun `contention past the retry bound is reported and nothing is persisted`() = runTest {
        val backing = EncodingDurableStateStore()
        val store = DurableAssetTransferSessionStore(backing, maximumStateUpdateAttempts = 3)
        backing.forcedConflicts = 10
        assertEquals(DurableAssetTransferSessionSaveOutcome.ContentionLimitReached, store.trySave(newSession(), null))
        assertEquals(3, backing.compareAndSetCalls)
        assertTrue(backing.rows.isEmpty())
        val thrown = assertFailsWith<AssetTransferSessionStoreException> { store.save(newSession(), null) }
        assertEquals(AssetErrorKind.SESSION_STORE_FAILURE.code, thrown.error.code)
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun `a failing load is a persistence failure and a thrown store exception`() = runTest {
        val backing = EncodingDurableStateStore()
        val store = DurableAssetTransferSessionStore(backing)
        backing.failNextLoads = 1
        assertIs<DurableAssetTransferSessionLoadOutcome.PersistenceFailure>(store.tryLoad(id))
        backing.failNextLoads = 2
        assertIs<DurableAssetTransferSessionSaveOutcome.PersistenceFailure>(store.trySave(newSession(), null))
        val thrown = assertFailsWith<AssetTransferSessionStoreException> { store.load(id) }
        assertEquals(Recoverability.RECOVERABLE, thrown.error.recoverability)
    }

    @Test
    fun `a failing compare-and-set is a persistence failure and persists nothing`() = runTest {
        val backing = EncodingDurableStateStore()
        val store = DurableAssetTransferSessionStore(backing)
        backing.failNextCompareAndSets = 1
        assertIs<DurableAssetTransferSessionSaveOutcome.PersistenceFailure>(store.trySave(newSession(), null))
        assertTrue(backing.rows.isEmpty())
        assertFailsWith<AssetTransferSessionStoreException> {
            backing.failNextCompareAndSets = 1
            store.save(newSession(), null)
        }
    }

    @Test
    fun `a corrupt persisted payload surfaces as a store failure and is never guessed at`() = runTest {
        val backing = EncodingDurableStateStore()
        val store = DurableAssetTransferSessionStore(backing)
        store.save(newSession(), null)
        val row = backing.rows.getValue("s-1")
        backing.rows["s-1"] = EncodingDurableStateStore.Row(row.payload.replace("CREATED", "VERIFYING"), row.version, row.schemaVersion)
        assertFailsWith<AssetTransferSessionStoreException> { store.load(id) }
        assertIs<DurableAssetTransferSessionSaveOutcome.PersistenceFailure>(store.trySave(newSession(), 0))
        // The corrupt row was not overwritten.
        assertTrue("VERIFYING" in backing.rows.getValue("s-1").payload)
    }

    @Test
    fun `a record persisted under a different schema version is reported corrupt`() = runTest {
        val backing = EncodingDurableStateStore()
        DurableAssetTransferSessionStore(backing, schemaVersion = 7).save(newSession(), null)
        val reader = DurableAssetTransferSessionStore(backing, schemaVersion = 1)
        val outcome = reader.tryLoad(id)
        assertIs<DurableAssetTransferSessionLoadOutcome.PersistenceFailure>(outcome)
        assertEquals(AssetErrorKind.SESSION_STATE_CORRUPT.code, outcome.error.code)
        assertEquals(Recoverability.NON_RECOVERABLE, outcome.error.recoverability)
        assertIs<DurableAssetTransferSessionSaveOutcome.PersistenceFailure>(reader.trySave(newSession(), 0))
    }

    @Test
    fun `a record stored under another session's scope is reported corrupt`() = runTest {
        val backing = EncodingDurableStateStore()
        val store = DurableAssetTransferSessionStore(backing)
        store.save(newSession(sid = sessionId("other")), null)
        backing.rows["s-1"] = backing.rows.getValue("other")
        val outcome = store.tryLoad(id)
        assertIs<DurableAssetTransferSessionLoadOutcome.PersistenceFailure>(outcome)
        assertEquals(AssetErrorKind.SESSION_STATE_CORRUPT.code, outcome.error.code)
    }

    @Test
    fun `the retry bound must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            DurableAssetTransferSessionStore(EncodingDurableStateStore(), maximumStateUpdateAttempts = 0)
        }
    }
}
