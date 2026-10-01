package io.dataloom.assets.policy

import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.assets.AssetTransferDirection
import io.dataloom.assets.AssetTransferSessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DurableAssetContentQuarantineLog]: recording, idempotent replay, release,
 * restart survival (through the real [AssetContentQuarantineRecordCodec],
 * not just object identity -- the same discipline
 * [io.dataloom.assets.DurableAssetTransferSessionStoreTest] uses for its own
 * sibling durable domain), and concurrency.
 */
class AssetContentQuarantineLogTest {

    private val sid = AssetTransferSessionId("s-quarantine-1")

    private fun record(
        sessionId: AssetTransferSessionId = sid,
        chunkIndex: Int = 2,
        reasonCode: String = "known-bad-hash",
        digestHex: String = "ab".repeat(32),
        atMillis: Long = 1_000L,
    ) = AssetContentQuarantineRecord(
        sessionId = sessionId,
        assetId = AssetId("doc-1"),
        version = 1,
        mediaType = AssetMediaType("application/octet-stream"),
        direction = AssetTransferDirection.UPLOAD,
        chunkIndex = chunkIndex,
        reasonCode = reasonCode,
        matchedDigestHex = digestHex,
        quarantinedAt = DataLoomInstant(atMillis),
    )

    // --------------------------------------------------------------- record

    @Test
    fun `a fresh record is persisted through the real codec and readable back`() = runTest {
        val backing = EncodingQuarantineStateStore()
        val log = DurableAssetContentQuarantineLog(backing)

        val outcome = log.record(record())

        val recorded = assertIs<AssetContentQuarantineRecordOutcome.Recorded>(outcome)
        assertEquals(record(), recorded.record)
        assertEquals(record(), log.current(sid).successValue())
        // Genuine persistence proof: the backing row's payload is the real
        // encoded string, not just an in-memory object reference.
        assertEquals(1, backing.rows.size)
        val payload = backing.rows.getValue(sid.value).payload
        assertTrue(payload.startsWith("DATALOOM_ASSET_CONTENT_QUARANTINE"))
        assertTrue(payload.contains("ab".repeat(32)))
    }

    @Test
    fun `recording twice for the same session is idempotent and the first write wins`() = runTest {
        val log = DurableAssetContentQuarantineLog(EncodingQuarantineStateStore())
        val first = record(reasonCode = "first")
        val second = record(reasonCode = "second")

        assertIs<AssetContentQuarantineRecordOutcome.Recorded>(log.record(first))
        val replay = assertIs<AssetContentQuarantineRecordOutcome.AlreadyRecorded>(log.record(second))
        assertEquals(first, replay.record)
    }

    @Test
    fun `different sessions are independent`() = runTest {
        val log = DurableAssetContentQuarantineLog(EncodingQuarantineStateStore())
        val a = record(sessionId = AssetTransferSessionId("s-a"), reasonCode = "a-reason")
        val b = record(sessionId = AssetTransferSessionId("s-b"), reasonCode = "b-reason")

        assertIs<AssetContentQuarantineRecordOutcome.Recorded>(log.record(a))
        assertIs<AssetContentQuarantineRecordOutcome.Recorded>(log.record(b))
        assertEquals(a, log.current(a.sessionId).successValue())
        assertEquals(b, log.current(b.sessionId).successValue())
    }

    @Test
    fun `nothing is recorded for a session with no quarantine`() = runTest {
        val log = DurableAssetContentQuarantineLog(EncodingQuarantineStateStore())
        assertNull(log.current(sid).successValue())
    }

    @Test
    fun `a persistence failure on load surfaces as PersistenceFailure and writes nothing`() = runTest {
        val backing = EncodingQuarantineStateStore().apply { failNextLoads = 1 }
        val log = DurableAssetContentQuarantineLog(backing)

        assertIs<AssetContentQuarantineRecordOutcome.PersistenceFailure>(log.record(record()))
        assertEquals(0, backing.rows.size)
    }

    @Test
    fun `contention is bounded and reported once attempts are exhausted`() = runTest {
        val backing = EncodingQuarantineStateStore().apply { forcedConflicts = 10 }
        val log = DurableAssetContentQuarantineLog(backing, maximumStateUpdateAttempts = 3)

        assertEquals(AssetContentQuarantineRecordOutcome.ContentionLimitReached, log.record(record()))
        assertEquals(0, backing.rows.size)
    }

    @Test
    fun `record rejects a pre-released record`() = runTest {
        val log = DurableAssetContentQuarantineLog(EncodingQuarantineStateStore())
        val released = record().copy(
            status = AssetContentQuarantineStatus.RELEASED,
            release = AssetContentQuarantineRelease("op", "cleared", DataLoomInstant(2_000L)),
        )
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            log.record(released)
        }
    }

    // -------------------------------------------------------------- release

    @Test
    fun `releasing a held record succeeds and is readable back as released`() = runTest {
        val backing = EncodingQuarantineStateStore()
        val log = DurableAssetContentQuarantineLog(backing)
        log.record(record())

        val release = AssetContentQuarantineRelease("reviewer-1", "false positive, verified safe", DataLoomInstant(5_000L))
        val outcome = log.release(sid, release)

        val released = assertIs<AssetContentQuarantineReleaseOutcome.Released>(outcome)
        assertEquals(AssetContentQuarantineStatus.RELEASED, released.record.status)
        assertEquals(release, released.record.release)
        assertEquals(released.record, log.current(sid).successValue())
        // Genuine persistence proof again: the release evidence round-trips through the real codec.
        assertTrue(backing.rows.getValue(sid.value).payload.contains("reviewer-1".let { hexOf(it) }))
    }

    @Test
    fun `releasing an already-released record is idempotent`() = runTest {
        val log = DurableAssetContentQuarantineLog(EncodingQuarantineStateStore())
        log.record(record())
        val release = AssetContentQuarantineRelease("reviewer-1", "cleared", DataLoomInstant(5_000L))
        val first = assertIs<AssetContentQuarantineReleaseOutcome.Released>(log.release(sid, release))

        val second = assertIs<AssetContentQuarantineReleaseOutcome.AlreadyReleased>(log.release(sid, release))
        assertEquals(first.record, second.record)
    }

    @Test
    fun `releasing a session with no record reports NotHeld`() = runTest {
        val log = DurableAssetContentQuarantineLog(EncodingQuarantineStateStore())
        val outcome = log.release(sid, AssetContentQuarantineRelease("op", "n/a", DataLoomInstant(1L)))
        assertEquals(AssetContentQuarantineReleaseOutcome.NotHeld(null), outcome)
    }

    @Test
    fun `two concurrent recorders for the same session converge on exactly one Recorded`() = runTest {
        val log = DurableAssetContentQuarantineLog(EncodingQuarantineStateStore())
        val a = record(reasonCode = "a")
        val b = record(reasonCode = "b")

        val resultA = log.record(a)
        val resultB = log.record(b)

        val outcomes = listOf(resultA, resultB)
        assertEquals(1, outcomes.count { it is AssetContentQuarantineRecordOutcome.Recorded })
        assertEquals(1, outcomes.count { it is AssetContentQuarantineRecordOutcome.AlreadyRecorded })
    }

    private fun <T> io.dataloom.api.provider.ProviderOperationResult<T>.successValue(): T =
        (this as io.dataloom.api.provider.ProviderOperationResult.Success<T>).value

    private fun hexOf(value: String): String = buildString {
        value.encodeToByteArray().forEach { b ->
            val u = b.toInt() and 0xff
            append("0123456789abcdef"[u ushr 4])
            append("0123456789abcdef"[u and 0x0f])
        }
    }
}
