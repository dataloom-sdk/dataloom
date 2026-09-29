package io.dataloom.assets

import io.dataloom.api.identifier.AssetId
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSource
import io.dataloom.assets.transform.AesGcmAssetChunkCipher
import io.dataloom.assets.transform.AssetSealedChunk
import io.dataloom.assets.transform.AssetTransferTransforms
import io.dataloom.assets.transform.AssetTransformUnsupportedException
import io.dataloom.assets.transform.DeflateAssetCompressor
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Apple has zlib (compression) but no AES-GCM binding (ADR-0014): the unsupported
 * state must be typed and must refuse a transfer instead of degrading to plaintext.
 * Runs only in the macOS CI job.
 */
class AppleTransformSupportTest {

    private val cipher = AesGcmAssetChunkCipher(fixedKeys(), platformSecureRandom())

    @Test
    fun `the AES-GCM cipher reports itself unsupported and throws a typed exception`() { runBlocking {
        assertFalse(cipher.isSupported)
        assertFailsWith<AssetTransformUnsupportedException> {
            cipher.seal(testKeyReference, 0, ByteArray(10), ByteArray(0))
        }
        assertFailsWith<AssetTransformUnsupportedException> {
            cipher.open(testKeyReference, 0, AssetSealedChunk(ByteArray(40), ByteArray(12)), ByteArray(0))
        }
    } }

    @Test
    fun `an upload configured with AES-GCM is refused and nothing is sent`() { runBlocking {
        val digests = platformDigests()
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val engine = AssetTransferEngine(
            provider, store, digests,
            transforms = AssetTransferTransforms(DeflateAssetCompressor(), cipher, testKeyReference),
        )
        val outcome = engine.upload(sessionId("u"), AssetId("a"), 1, octetStream, InMemoryAssetSource(patternBytes(2_000)))
        assertIs<AssetTransferOutcome.NotStarted>(outcome)
        assertEquals(AssetErrorKind.TRANSFORM_UNSUPPORTED, (outcome.error as AssetTransferError).kind)
        assertEquals(0, provider.openCalls)
        assertNull(store.load(sessionId("u")))
    } }

    @Test
    fun `compression alone is supported on Apple`() {
        assertTrue(DeflateAssetCompressor().isSupported)
    }
}
