package io.dataloom.assets

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.security.KeyReference
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import io.dataloom.assets.transform.AssetSealedChunk
import io.dataloom.assets.transform.IdentityAssetCipher
import io.dataloom.assets.transform.IdentityAssetCompressor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssetSupportTypesTest {

    // ---------------------------------------------------------------- streams

    @Test
    fun `source reads at a position and reports end of data`() = runTest {
        val source = InMemoryAssetSource(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(5L, source.sizeBytes())
        val buffer = ByteArray(10)
        assertEquals(3, source.read(2, buffer, 0, 10))
        assertContentEquals(byteArrayOf(3, 4, 5), buffer.copyOf(3))
        assertEquals(-1, source.read(5, buffer, 0, 10))
        assertEquals(-1, source.read(99, buffer, 0, 10))
    }

    @Test
    fun `source defensively copies its input`() = runTest {
        val input = byteArrayOf(1, 2, 3)
        val source = InMemoryAssetSource(input)
        input[0] = 9
        val out = ByteArray(1)
        source.read(0, out, 0, 1)
        assertEquals(1, out[0].toInt())
    }

    @Test
    fun `readFully loops over short reads and reports a short total only at end of data`() = runTest {
        val trickle = object : AssetReadable {
            val inner = InMemoryAssetSource(patternBytes(10))
            override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int) =
                inner.read(position, destination, destinationOffset, minOf(length, 3))
        }
        val buffer = ByteArray(10)
        assertEquals(10, trickle.readFully(0, buffer, 0, 10))
        assertContentEquals(patternBytes(10), buffer)
        assertEquals(4, trickle.readFully(6, ByteArray(10), 0, 10))
        assertEquals(0, trickle.readFully(50, ByteArray(10), 0, 10))
    }

    @Test
    fun `sink writes ranges out of order and reads them back`() = runTest {
        val sink = InMemoryAssetSink()
        sink.write(4, byteArrayOf(5, 6, 7, 8), 0, 4)
        sink.write(0, byteArrayOf(1, 2, 3, 4), 0, 4)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), sink.snapshot())
        val out = ByteArray(8)
        assertEquals(8, sink.readFully(0, out, 0, 8))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), out)
    }

    @Test
    fun `sink treats an unwritten hole as end of data so a lost chunk can never verify`() = runTest {
        val sink = InMemoryAssetSink()
        sink.write(4, byteArrayOf(5, 6, 7, 8), 0, 4)
        assertEquals(-1, sink.read(0, ByteArray(4), 0, 4))
        assertEquals(0, sink.readFully(0, ByteArray(8), 0, 8))
    }

    @Test
    fun `rewriting the same range with the same bytes is harmless`() = runTest {
        val sink = InMemoryAssetSink()
        repeat(3) { sink.write(0, byteArrayOf(1, 2), 0, 2) }
        assertContentEquals(byteArrayOf(1, 2), sink.snapshot())
    }

    @Test
    fun `sink discard drops everything and is idempotent`() = runTest {
        val sink = InMemoryAssetSink()
        sink.write(0, byteArrayOf(1, 2), 0, 2)
        sink.discard()
        sink.discard()
        assertEquals(0, sink.snapshot().size)
        assertEquals(-1, sink.read(0, ByteArray(2), 0, 2))
    }

    @Test
    fun `sink enforces its capacity at reserve and at write`() = runTest {
        val sink = InMemoryAssetSink(maxBytes = 4)
        sink.reserve(4)
        assertFailsWith<AssetSinkFullException> { sink.reserve(5) }
        assertFailsWith<AssetSinkFullException> { sink.write(2, byteArrayOf(1, 2, 3), 0, 3) }
        sink.write(0, byteArrayOf(1, 2, 3, 4), 0, 4)
    }

    // ------------------------------------------------------------------ store

    @Test
    fun `store saves a new session only when none exists`() = runTest {
        val store = InMemoryAssetTransferSessionStore()
        val asset = testAsset(size = 100, chunkSize = 50)
        val session = AssetTransferSession(sessionId("s"), AssetTransferDirection.UPLOAD, asset.manifest)
        assertNull(store.load(session.sessionId))
        assertTrue(store.save(session, expectedRevision = null))
        assertFalse(store.save(session, expectedRevision = null), "second create must lose")
        assertEquals(session, store.load(session.sessionId))
    }

    @Test
    fun `store compare-and-set rejects a stale revision`() = runTest {
        val store = InMemoryAssetTransferSessionStore()
        val asset = testAsset(size = 100, chunkSize = 50)
        val v0 = AssetTransferSession(sessionId("s"), AssetTransferDirection.UPLOAD, asset.manifest)
        store.save(v0, null)
        val v1 = (v0.reduce(AssetTransferEvent.Start) as AssetTransferTransition.Applied).session
        assertFalse(store.save(v1, expectedRevision = 7))
        assertTrue(store.save(v1, expectedRevision = v0.revision))
        assertFalse(store.save(v1, expectedRevision = v0.revision), "stale writer must lose")
    }

    @Test
    fun `applyEvent persists applied transitions and retries when it loses a race`() = runTest {
        val asset = testAsset(size = 100, chunkSize = 50)
        val real = InMemoryAssetTransferSessionStore()
        val v0 = AssetTransferSession(sessionId("s"), AssetTransferDirection.UPLOAD, asset.manifest)
        real.save(v0, null)
        var interfered = false
        val racing = object : AssetTransferSessionStore by real {
            override suspend fun save(updated: AssetTransferSession, expectedRevision: Long?): Boolean {
                if (!interfered) {
                    interfered = true
                    // Another writer advances the session between our load and our save.
                    real.save(
                        (v0.reduce(AssetTransferEvent.Start) as AssetTransferTransition.Applied).session,
                        v0.revision,
                    )
                }
                return real.save(updated, expectedRevision)
            }
        }
        val transition = racing.applyEvent(v0.sessionId, AssetTransferEvent.Cancel)
        assertTrue(interfered)
        assertTrue(transition is AssetTransferTransition.Applied)
        assertEquals(AssetTransferPhase.CANCELLED, real.load(v0.sessionId)!!.phase)
        assertEquals(2L, real.load(v0.sessionId)!!.revision)
    }

    @Test
    fun `applyEvent reports a rejection with the current stored session and persists nothing`() = runTest {
        val store = InMemoryAssetTransferSessionStore()
        val asset = testAsset(size = 100, chunkSize = 50)
        val v0 = AssetTransferSession(sessionId("s"), AssetTransferDirection.UPLOAD, asset.manifest)
        store.save(v0, null)
        val transition = store.applyEvent(v0.sessionId, AssetTransferEvent.BeginVerification)
        assertTrue(transition is AssetTransferTransition.Rejected)
        assertEquals(v0, store.load(v0.sessionId))
    }

    @Test
    fun `applyEvent on an unknown session is a programming error`() = runTest {
        assertFailsWith<IllegalStateException> {
            InMemoryAssetTransferSessionStore().applyEvent(sessionId("missing"), AssetTransferEvent.Start)
        }
    }

    // ----------------------------------------------------------------- errors

    @Test
    fun `every error kind has a stable prefixed code and a category and recoverability`() {
        val codes = AssetErrorKind.entries.map { it.code.value }
        assertEquals(codes.toSet().size, codes.size, "codes must be unique")
        assertTrue(codes.all { it.startsWith("dataloom.assets.") })
        assertEquals("dataloom.assets.quota_exceeded", AssetErrorKind.QUOTA_EXCEEDED.code.value)
    }

    @Test
    fun `retransmittable and transient kinds are recoverable and the rest are terminal`() {
        val recoverable = AssetErrorKind.entries.filter { it.recoverability == Recoverability.RECOVERABLE }.toSet()
        assertEquals(
            setOf(
                AssetErrorKind.CHUNK_DIGEST_MISMATCH,
                AssetErrorKind.PROVIDER_UNAVAILABLE,
                AssetErrorKind.SOURCE_FAILURE,
                AssetErrorKind.SINK_FAILURE,
                AssetErrorKind.SESSION_STORE_FAILURE,
            ),
            recoverable,
        )
        assertTrue(AssetErrorKind.entries.none { it.recoverability == Recoverability.UNKNOWN })
        assertEquals(ErrorCategory.STORAGE, AssetErrorKind.QUOTA_EXCEEDED.category)
    }

    @Test
    fun `error exposes its kind's classification and renders without the cause message`() {
        val cause = IllegalStateException("secret-path-/data/user/0/file")
        val error = AssetTransferError(AssetErrorKind.SINK_FAILURE, "Local asset I/O failed.", cause)
        assertEquals(AssetErrorKind.SINK_FAILURE.code, error.code)
        assertEquals(Recoverability.RECOVERABLE, error.recoverability)
        assertEquals(cause, error.cause)
        assertFalse("secret-path" in error.toString())
        assertTrue("IllegalStateException" in error.toString())
    }

    @Test
    fun `errors compare by kind and message`() {
        assertEquals(
            AssetTransferError(AssetErrorKind.QUOTA_EXCEEDED, "m"),
            AssetTransferError(AssetErrorKind.QUOTA_EXCEEDED, "m"),
        )
        assertNotEquals(
            AssetTransferError(AssetErrorKind.QUOTA_EXCEEDED, "m"),
            AssetTransferError(AssetErrorKind.CHUNK_LENGTH_MISMATCH, "m"),
        )
    }

    // ------------------------------------------------------------------ quota

    @Test
    fun `quota validates its limits`() {
        assertFailsWith<IllegalArgumentException> { AssetQuota(maxAssetSizeBytes = -1) }
        assertFailsWith<IllegalArgumentException> { AssetQuota(maxTotalBytes = -1) }
        assertEquals(AssetQuota(), AssetQuota.UNLIMITED)
    }

    // ------------------------------------------------------------- transforms

    @Test
    fun `identity compressor round trips and enforces the expected size`() = runTest {
        val compressor = IdentityAssetCompressor()
        val chunk = patternBytes(64)
        val compressed = compressor.compress(chunk)
        assertContentEquals(chunk, compressed)
        assertContentEquals(chunk, compressor.decompress(compressed, 64))
        assertFailsWith<IllegalArgumentException> { compressor.decompress(compressed, 63) }
        assertEquals("identity", compressor.algorithm.value)
    }

    @Test
    fun `identity cipher round trips through the AEAD-style contract`() = runTest {
        val cipher = IdentityAssetCipher()
        val key = KeyReference("key-1")
        val chunk = patternBytes(32)
        val sealed = cipher.seal(key, 3, chunk, byteArrayOf(1))
        assertContentEquals(chunk, cipher.open(key, 3, sealed, byteArrayOf(1)))
        assertEquals(0, sealed.copyNonce().size)
        assertEquals("identity", cipher.algorithm.value)
    }

    @Test
    fun `sealed chunk copies its bytes and never renders them`() {
        val ciphertext = byteArrayOf(1, 2, 3)
        val nonce = byteArrayOf(9, 9)
        val sealed = AssetSealedChunk(ciphertext, nonce)
        ciphertext[0] = 42
        assertEquals(1, sealed.copyCiphertext()[0].toInt())
        sealed.copyCiphertext()[0] = 42
        assertEquals(1, sealed.copyCiphertext()[0].toInt())
        assertEquals("AssetSealedChunk(ciphertextSize=3, nonceSize=2)", sealed.toString())
        assertEquals(AssetSealedChunk(byteArrayOf(1, 2, 3), byteArrayOf(9, 9)), sealed)
    }
}
