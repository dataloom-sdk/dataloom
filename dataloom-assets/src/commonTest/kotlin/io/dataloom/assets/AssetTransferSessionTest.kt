package io.dataloom.assets

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.assets.AssetTransferPhase.CANCELLED
import io.dataloom.assets.AssetTransferPhase.COMPLETED
import io.dataloom.assets.AssetTransferPhase.CREATED
import io.dataloom.assets.AssetTransferPhase.FAILED
import io.dataloom.assets.AssetTransferPhase.TRANSFERRING
import io.dataloom.assets.AssetTransferPhase.VERIFYING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AssetTransferSessionTest {

    private fun digest(seed: Int) = DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { seed.toByte() })

    /** Three chunks: 1024 + 1024 + 452 bytes. */
    private val manifest: AssetManifest = run {
        val plan = AssetChunkPlan(2_500, 1_024)
        AssetManifest(
            AssetId("a"), 1, 2_500, octetStream, digest(0),
            plan.toLayout(listOf(digest(1), digest(2), digest(3))),
        )
    }

    private val id = AssetTransferSessionId("s1")

    private fun session(
        phase: AssetTransferPhase,
        committed: Set<Int> = emptySet(),
        failure: AssetErrorKind? = null,
        revision: Long = 5,
    ) = AssetTransferSession(id, AssetTransferDirection.UPLOAD, manifest, phase, committed, failure, revision)

    private val created = session(CREATED)
    private val transferring = session(TRANSFERRING, setOf(0))
    private val verifying = session(VERIFYING, setOf(0, 1, 2))
    private val completed = session(COMPLETED, setOf(0, 1, 2))
    private val failed = session(FAILED, setOf(0), AssetErrorKind.QUOTA_EXCEEDED)
    private val cancelled = session(CANCELLED, setOf(0))

    private val phases = listOf(
        "CREATED" to created,
        "TRANSFERRING" to transferring,
        "VERIFYING" to verifying,
        "COMPLETED" to completed,
        "FAILED" to failed,
        "CANCELLED" to cancelled,
    )

    private val events: List<Pair<String, AssetTransferEvent>> = listOf(
        "Start" to AssetTransferEvent.Start,
        "Reconcile same" to AssetTransferEvent.ReconcileCommitted(setOf(0)),
        "Reconcile different" to AssetTransferEvent.ReconcileCommitted(setOf(0, 1)),
        "Reconcile out of range" to AssetTransferEvent.ReconcileCommitted(setOf(7)),
        "Commit known" to AssetTransferEvent.ChunkCommitted(0),
        "Commit new" to AssetTransferEvent.ChunkCommitted(1),
        "Commit out of range" to AssetTransferEvent.ChunkCommitted(9),
        "BeginVerification" to AssetTransferEvent.BeginVerification,
        "VerificationSucceeded" to AssetTransferEvent.VerificationSucceeded,
        "Fail same kind" to AssetTransferEvent.Fail(AssetErrorKind.QUOTA_EXCEEDED),
        "Fail other kind" to AssetTransferEvent.Fail(AssetErrorKind.OBJECT_DIGEST_MISMATCH),
        "Cancel" to AssetTransferEvent.Cancel,
    )

    /**
     * The complete (phase x event) transition table, in phase order
     * CREATED, TRANSFERRING, VERIFYING, COMPLETED, FAILED, CANCELLED.
     * `A:<phase>` = applied and now in that phase; `U` = unchanged idempotent
     * duplicate; `R:<reason>` = rejected.
     */
    private val expected: Map<String, List<String>> = mapOf(
        "Start" to listOf("A:TRANSFERRING", "U", "U", "U", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL"),
        "Reconcile same" to listOf("R:INVALID_PHASE", "U", "R:INVALID_PHASE", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL"),
        "Reconcile different" to listOf("R:INVALID_PHASE", "A:TRANSFERRING", "R:INVALID_PHASE", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL"),
        "Reconcile out of range" to listOf("R:INVALID_PHASE", "R:CHUNK_OUT_OF_RANGE", "R:INVALID_PHASE", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL"),
        "Commit known" to listOf("R:INVALID_PHASE", "U", "U", "U", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL"),
        "Commit new" to listOf("R:INVALID_PHASE", "A:TRANSFERRING", "U", "U", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL"),
        "Commit out of range" to List(6) { "R:CHUNK_OUT_OF_RANGE" },
        "BeginVerification" to listOf("R:INVALID_PHASE", "R:CHUNKS_MISSING", "U", "U", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL"),
        "VerificationSucceeded" to listOf("R:INVALID_PHASE", "R:INVALID_PHASE", "A:COMPLETED", "U", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL"),
        "Fail same kind" to listOf("A:FAILED", "A:FAILED", "A:FAILED", "R:SESSION_TERMINAL", "U", "R:SESSION_TERMINAL"),
        "Fail other kind" to listOf("A:FAILED", "A:FAILED", "A:FAILED", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL"),
        "Cancel" to listOf("A:CANCELLED", "A:CANCELLED", "A:CANCELLED", "R:SESSION_TERMINAL", "R:SESSION_TERMINAL", "U"),
    )

    private fun describe(transition: AssetTransferTransition): String = when (transition) {
        is AssetTransferTransition.Applied -> "A:${transition.session.phase}"
        is AssetTransferTransition.Unchanged -> "U"
        is AssetTransferTransition.Rejected -> "R:${transition.reason}"
    }

    @Test
    fun `every phase and event pair matches the documented transition table`() {
        assertEquals(events.map { it.first }.toSet(), expected.keys, "table must cover every event")
        for ((eventName, event) in events) {
            for ((index, phase) in phases.withIndex()) {
                val (phaseName, session) = phase
                assertEquals(
                    expected.getValue(eventName)[index],
                    describe(session.reduce(event)),
                    "phase=$phaseName event=$eventName",
                )
            }
        }
    }

    @Test
    fun `applied transitions bump the revision by one and leave the input untouched`() {
        for ((_, event) in events) {
            for ((_, session) in phases) {
                val before = session.hashCode() to session.toString()
                val transition = session.reduce(event)
                assertEquals(before, session.hashCode() to session.toString())
                if (transition is AssetTransferTransition.Applied) {
                    assertEquals(session.revision + 1, transition.session.revision)
                }
            }
        }
    }

    @Test
    fun `unchanged and rejected transitions return the very same session`() {
        for ((_, event) in events) {
            for ((_, session) in phases) {
                val transition = session.reduce(event)
                if (transition !is AssetTransferTransition.Applied) assertSame(session, transition.session)
            }
        }
    }

    @Test
    fun `the happy path walks CREATED to COMPLETED with chunks committed out of order`() {
        var s = session(CREATED, revision = 0)
        fun step(event: AssetTransferEvent) {
            val t = s.reduce(event)
            assertIs<AssetTransferTransition.Applied>(t, "event $event")
            s = t.session
        }
        step(AssetTransferEvent.Start)
        step(AssetTransferEvent.ChunkCommitted(2))
        step(AssetTransferEvent.ChunkCommitted(0))
        assertEquals(listOf(1), s.missingChunks)
        assertFalse(s.isFullyCommitted)
        step(AssetTransferEvent.ChunkCommitted(1))
        assertTrue(s.isFullyCommitted)
        step(AssetTransferEvent.BeginVerification)
        step(AssetTransferEvent.VerificationSucceeded)
        assertEquals(COMPLETED, s.phase)
        assertEquals(6L, s.revision)
        assertEquals(listOf(0, 1, 2), s.committedChunks.toList())
    }

    @Test
    fun `BeginVerification succeeds once the last chunk is committed`() {
        val nearlyDone = session(TRANSFERRING, setOf(0, 2))
        assertEquals("R:CHUNKS_MISSING", describe(nearlyDone.reduce(AssetTransferEvent.BeginVerification)))
        val full = (nearlyDone.reduce(AssetTransferEvent.ChunkCommitted(1)) as AssetTransferTransition.Applied).session
        assertEquals("A:VERIFYING", describe(full.reduce(AssetTransferEvent.BeginVerification)))
    }

    @Test
    fun `reconcile can drop chunks the provider lost`() {
        val t = session(TRANSFERRING, setOf(0, 1)).reduce(AssetTransferEvent.ReconcileCommitted(setOf(1)))
        assertIs<AssetTransferTransition.Applied>(t)
        assertEquals(setOf(1), t.session.committedChunks)
        assertEquals(listOf(0, 2), t.session.missingChunks)
    }

    @Test
    fun `a failed session keeps its committed chunks and records the failure kind`() {
        val t = session(TRANSFERRING, setOf(0, 1)).reduce(AssetTransferEvent.Fail(AssetErrorKind.SOURCE_CONTENT_CHANGED))
        assertIs<AssetTransferTransition.Applied>(t)
        assertEquals(FAILED, t.session.phase)
        assertEquals(AssetErrorKind.SOURCE_CONTENT_CHANGED, t.session.failure)
        assertEquals(setOf(0, 1), t.session.committedChunks)
    }

    @Test
    fun `a terminal session never leaves its terminal phase`() {
        val allEvents = events.map { it.second }
        for (terminal in listOf(completed, failed, cancelled)) {
            for (event in allEvents) {
                assertEquals(terminal.phase, terminal.reduce(event).session.phase, "$event")
            }
        }
    }

    @Test
    fun `duplicate delivery of every event in a script converges to the same state as single delivery`() {
        val script = listOf(
            AssetTransferEvent.Start,
            AssetTransferEvent.ChunkCommitted(1),
            AssetTransferEvent.ChunkCommitted(0),
            AssetTransferEvent.ChunkCommitted(2),
            AssetTransferEvent.BeginVerification,
            AssetTransferEvent.VerificationSucceeded,
        )
        fun run(times: Int): AssetTransferSession {
            var s = session(CREATED, revision = 0)
            for (event in script) repeat(times) { s = s.reduce(event).session }
            return s
        }
        assertEquals(run(1), run(3))
        assertEquals(COMPLETED, run(3).phase)
    }

    @Test
    fun `random event sequences never violate the session invariants`() {
        var seed = 12345L
        fun next(bound: Int): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) % bound).toInt()
        }
        val pool = events.map { it.second } + listOf(
            AssetTransferEvent.ChunkCommitted(2),
            AssetTransferEvent.ReconcileCommitted(emptySet()),
            AssetTransferEvent.ReconcileCommitted(setOf(0, 1, 2)),
        )
        repeat(300) {
            var s = session(CREATED, revision = 0)
            repeat(40) {
                val event = pool[next(pool.size)]
                val before = s
                val t = s.reduce(event)
                s = t.session
                // Rebuilding validates every constructor invariant.
                AssetTransferSession(s.sessionId, s.direction, s.manifest, s.phase, s.committedChunks, s.failure, s.revision)
                if (before.phase.isTerminal) assertEquals(before.phase, s.phase)
                if (event !is AssetTransferEvent.ReconcileCommitted) {
                    assertTrue(s.committedChunks.containsAll(before.committedChunks), "$event shrank committed chunks")
                }
                when (t) {
                    is AssetTransferTransition.Applied -> assertEquals(before.revision + 1, s.revision)
                    else -> assertSame(before, s)
                }
                if (s.phase == COMPLETED || s.phase == VERIFYING) assertTrue(s.isFullyCommitted)
            }
        }
    }

    // ------------------------------------------------------------ construction

    @Test
    fun `a session cannot be built in an impossible state`() {
        assertFailsWith<IllegalArgumentException> { session(VERIFYING, setOf(0, 1)) }
        assertFailsWith<IllegalArgumentException> { session(COMPLETED, setOf(0)) }
        assertFailsWith<IllegalArgumentException> { session(TRANSFERRING, setOf(3)) }
        assertFailsWith<IllegalArgumentException> { session(TRANSFERRING, setOf(-1)) }
        assertFailsWith<IllegalArgumentException> { session(FAILED, setOf(0), failure = null) }
        assertFailsWith<IllegalArgumentException> { session(TRANSFERRING, setOf(0), failure = AssetErrorKind.QUOTA_EXCEEDED) }
        assertFailsWith<IllegalArgumentException> { session(CREATED, revision = -1) }
    }

    @Test
    fun `committed chunks are sorted and defensively copied`() {
        val source = mutableSetOf(2, 0)
        val s = session(TRANSFERRING, source)
        source.add(1)
        assertEquals(listOf(0, 2), s.committedChunks.toList())
    }

    @Test
    fun `sessions compare by value and render without content`() {
        assertEquals(session(TRANSFERRING, setOf(0)), session(TRANSFERRING, setOf(0)))
        assertEquals(session(TRANSFERRING, setOf(0)).hashCode(), session(TRANSFERRING, setOf(0)).hashCode())
        assertTrue(session(TRANSFERRING, setOf(0)) != session(TRANSFERRING, setOf(1)))
        assertTrue("committed=1/3" in session(TRANSFERRING, setOf(0)).toString())
    }

    @Test
    fun `session ids must be non-blank and bounded`() {
        assertFailsWith<IllegalArgumentException> { AssetTransferSessionId(" ") }
        assertFailsWith<IllegalArgumentException> { AssetTransferSessionId("x".repeat(257)) }
        assertEquals("abc", AssetTransferSessionId("abc").toString())
    }

    @Test
    fun `terminal phases are exactly COMPLETED FAILED and CANCELLED`() {
        assertEquals(
            setOf(COMPLETED, FAILED, CANCELLED),
            AssetTransferPhase.entries.filter { it.isTerminal }.toSet(),
        )
    }
}
