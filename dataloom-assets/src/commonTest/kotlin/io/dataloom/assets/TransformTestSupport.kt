package io.dataloom.assets

import io.dataloom.api.asset.AssetEncryptionAlgorithm
import io.dataloom.api.random.DataLoomSecureRandom
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.security.KeyReference
import io.dataloom.assets.transform.AssetChunkAuthenticationException
import io.dataloom.assets.transform.AssetChunkCipher
import io.dataloom.assets.transform.AssetKeyInvalidException
import io.dataloom.assets.transform.AssetKeyResolver
import io.dataloom.assets.transform.AssetKeyUnavailableException
import io.dataloom.assets.transform.AssetSealedChunk
import kotlinx.coroutines.CancellationException

/** The platform's real secure random (JVM `SecureRandom`, Apple `arc4random_buf`). */
expect fun platformSecureRandom(): DataLoomSecureRandom

/**
 * The strongest cipher the platform test run can exercise: the real
 * [io.dataloom.assets.transform.AesGcmAssetChunkCipher] on the JVM, and on
 * Apple (where AES-GCM is unsupported, ADR-0014) the [TestAeadCipher] double so
 * that the shared frame, associated-data and engine tests still run there.
 */
expect fun testCipher(keys: AssetKeyResolver, random: DataLoomSecureRandom): AssetChunkCipher

val testKeyReference = KeyReference("test-key-1")

/** A fixed 32-byte test key; distinct per seed. */
fun testKey(seed: Int = 1): ByteArray = ByteArray(32) { (it * 7 + seed * 13 + 1).toByte() }

fun fixedKeys(seed: Int = 1): AssetKeyResolver = AssetKeyResolver { testKey(seed) }

/** High-entropy, incompressible bytes (xorshift32). */
fun noiseBytes(size: Int, seed: Int = 1): ByteArray {
    var state = 0x2545F491 xor (seed * 0x9E3779B1.toInt())
    if (state == 0) state = 1
    return ByteArray(size) {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        (state ushr 11).toByte()
    }
}

/**
 * A toy AEAD for tests only: SHA-256 keystream plus a truncated SHA-256 tag over
 * key, nonce, chunk index, associated data and ciphertext. It has the same
 * observable properties as a real AEAD (fresh nonce, output = ciphertext||tag,
 * any change to chunk, nonce, key, index or associated data fails
 * authentication) but is NOT a secure cipher and must never ship.
 */
class TestAeadCipher(
    private val keys: AssetKeyResolver,
    private val random: DataLoomSecureRandom,
) : AssetChunkCipher {
    private val digests = platformDigests()

    override val algorithm = AssetEncryptionAlgorithm("TEST-AEAD-DO-NOT-USE")

    override suspend fun seal(
        keyReference: KeyReference,
        chunkIndex: Int,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): AssetSealedChunk {
        val key = key(keyReference)
        val nonce = random.nextBytes(12)
        val ciphertext = xor(key, nonce, plaintext)
        return AssetSealedChunk(ciphertext + tag(key, nonce, chunkIndex, associatedData, ciphertext), nonce)
    }

    override suspend fun open(
        keyReference: KeyReference,
        chunkIndex: Int,
        sealed: AssetSealedChunk,
        associatedData: ByteArray,
    ): ByteArray {
        val key = key(keyReference)
        val nonce = sealed.copyNonce()
        val all = sealed.copyCiphertext()
        if (nonce.size != 12 || all.size < 16) throw AssetChunkAuthenticationException("malformed")
        val ciphertext = all.copyOfRange(0, all.size - 16)
        val tag = all.copyOfRange(all.size - 16, all.size)
        if (!tag.contentEquals(tag(key, nonce, chunkIndex, associatedData, ciphertext))) {
            throw AssetChunkAuthenticationException("bad tag")
        }
        return xor(key, nonce, ciphertext)
    }

    private suspend fun key(keyReference: KeyReference): ByteArray {
        val key = try {
            keys.resolve(keyReference)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AssetKeyUnavailableException("unavailable", e)
        }
        if (key.size != 32) throw AssetKeyInvalidException("bad key size")
        return key
    }

    private fun xor(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var block = 0
        var offset = 0
        while (offset < input.size) {
            val counter = byteArrayOf(block.toByte(), (block shr 8).toByte(), (block shr 16).toByte())
            val stream = digests.digest(DigestAlgorithm.SHA_256, key + nonce + counter).copyBytes()
            for (i in 0 until minOf(32, input.size - offset)) {
                out[offset + i] = (input[offset + i].toInt() xor stream[i].toInt()).toByte()
            }
            offset += 32
            block++
        }
        return out
    }

    private fun tag(key: ByteArray, nonce: ByteArray, index: Int, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val header = byteArrayOf(index.toByte(), (index shr 8).toByte(), (index shr 16).toByte(), aad.size.toByte(), (aad.size shr 8).toByte())
        return digests.digest(DigestAlgorithm.SHA_256, key + nonce + header + aad + ciphertext).copyBytes().copyOf(16)
    }
}
