package io.dataloom.assets

import io.dataloom.api.random.DataLoomSecureRandom
import io.dataloom.api.security.KeyReference
import io.dataloom.assets.transform.AesGcmAssetChunkCipher
import io.dataloom.assets.transform.AssetChunkAuthenticationException
import io.dataloom.assets.transform.AssetKeyInvalidException
import io.dataloom.assets.transform.AssetKeyResolver
import io.dataloom.assets.transform.AssetKeyUnavailableException
import io.dataloom.assets.transform.AssetSealedChunk
import io.dataloom.assets.transform.platformAesGcmOpen
import io.dataloom.assets.transform.platformAesGcmSeal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The real AES-256-GCM cipher against `javax.crypto`, including published known-answer vectors. */
class AesGcmAssetChunkCipherTest {

    private val random = platformSecureRandom()
    private val cipher = AesGcmAssetChunkCipher(fixedKeys(), random)
    private val ref = testKeyReference
    private val aad = "asset-1|1|3".encodeToByteArray()

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun `matches the GCM specification known-answer vectors`() {
        // McGrew and Viega, "The Galois/Counter Mode of Operation", test case 14 (AES-256, zero key/IV/plaintext).
        val tc14 = platformAesGcmSeal(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(16))
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919", tc14.toHex())

        // Test case 16 (AES-256 with associated data).
        val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val iv = hex("cafebabefacedbaddecaf888")
        val plaintext = hex(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
        )
        val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val expected = hex(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
                "76fc6ece0f4e1768cddf8853bb2d551b",
        )
        val sealed = platformAesGcmSeal(key, iv, aad, plaintext)
        assertContentEquals(expected, sealed)
        assertContentEquals(plaintext, platformAesGcmOpen(key, iv, aad, sealed))
    }

    @Test
    fun `round trips at chunk boundaries`() = runTest {
        for (size in listOf(1, 1_023, 1_024, 1_025, 64 * 1024)) {
            val plaintext = noiseBytes(size, seed = size)
            val sealed = cipher.seal(ref, 4, plaintext, aad)
            assertEquals(size + 16, sealed.copyCiphertext().size)
            assertEquals(12, sealed.copyNonce().size)
            assertContentEquals(plaintext, cipher.open(ref, 4, sealed, aad), "size $size")
        }
    }

    @Test
    fun `the label and support are as documented`() {
        assertEquals("AES-256-GCM", cipher.algorithm.value)
        assertTrue(cipher.isSupported)
    }

    @Test
    fun `a flipped ciphertext or tag bit is rejected`() = runTest {
        val sealed = cipher.seal(ref, 0, patternBytes(500), aad)
        val ciphertext = sealed.copyCiphertext()
        for (position in listOf(0, 250, ciphertext.size - 16, ciphertext.size - 1)) {
            val tampered = ciphertext.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            assertFailsWith<AssetChunkAuthenticationException>("byte $position") {
                cipher.open(ref, 0, AssetSealedChunk(tampered, sealed.copyNonce()), aad)
            }
        }
    }

    @Test
    fun `a flipped nonce bit is rejected`() = runTest {
        val sealed = cipher.seal(ref, 0, patternBytes(500), aad)
        val nonce = sealed.copyNonce().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertFailsWith<AssetChunkAuthenticationException> {
            cipher.open(ref, 0, AssetSealedChunk(sealed.copyCiphertext(), nonce), aad)
        }
    }

    @Test
    fun `truncated and malformed sealed chunks are rejected`() = runTest {
        val sealed = cipher.seal(ref, 0, patternBytes(100), aad)
        val ciphertext = sealed.copyCiphertext()
        for (keep in listOf(0, 1, 15, 16, ciphertext.size - 1)) {
            assertFailsWith<AssetChunkAuthenticationException>("keep $keep") {
                cipher.open(ref, 0, AssetSealedChunk(ciphertext.copyOf(keep), sealed.copyNonce()), aad)
            }
        }
        assertFailsWith<AssetChunkAuthenticationException> {
            cipher.open(ref, 0, AssetSealedChunk(ciphertext, ByteArray(11)), aad)
        }
        assertFailsWith<AssetChunkAuthenticationException> {
            cipher.open(ref, 0, AssetSealedChunk(ciphertext, ByteArray(0)), aad)
        }
    }

    @Test
    fun `a wrong key is rejected`() = runTest {
        val sealed = cipher.seal(ref, 0, patternBytes(100), aad)
        val other = AesGcmAssetChunkCipher(fixedKeys(seed = 9), random)
        assertFailsWith<AssetChunkAuthenticationException> { other.open(ref, 0, sealed, aad) }
    }

    @Test
    fun `a wrong chunk index or wrong associated data is rejected`() = runTest {
        val sealed = cipher.seal(ref, 3, patternBytes(100), aad)
        assertContentEquals(patternBytes(100), cipher.open(ref, 3, sealed, aad))
        assertFailsWith<AssetChunkAuthenticationException> { cipher.open(ref, 4, sealed, aad) }
        assertFailsWith<AssetChunkAuthenticationException> { cipher.open(ref, 3, sealed, "asset-2|1|3".encodeToByteArray()) }
        assertFailsWith<AssetChunkAuthenticationException> { cipher.open(ref, 3, sealed, ByteArray(0)) }
        assertFailsWith<AssetChunkAuthenticationException> { cipher.open(ref, 3, sealed, aad + byteArrayOf(0)) }
    }

    @Test
    fun `nonces are fresh for every seal`() = runTest {
        val plaintext = ByteArray(64)
        val sealed = (0 until 500).map { cipher.seal(ref, 0, plaintext, aad) }
        assertEquals(500, sealed.map { it.copyNonce().toList() }.toSet().size, "no nonce may repeat")
        assertEquals(500, sealed.map { it.copyCiphertext().toList() }.toSet().size)
    }

    @Test
    fun `the nonce comes from the injected secure random and has 96 bits`() = runTest {
        val requested = mutableListOf<Int>()
        val counting = object : DataLoomSecureRandom {
            override fun nextBytes(byteCount: Int): ByteArray {
                requested += byteCount
                return ByteArray(byteCount) { requested.size.toByte() }
            }
        }
        val sealed = AesGcmAssetChunkCipher(fixedKeys(), counting).seal(ref, 0, patternBytes(10), aad)
        assertEquals(listOf(12), requested)
        assertContentEquals(ByteArray(12) { 1 }, sealed.copyNonce())
    }

    @Test
    fun `key problems are typed`() = runTest {
        val locked = AesGcmAssetChunkCipher(AssetKeyResolver { throw IllegalStateException("locked") }, random)
        assertFailsWith<AssetKeyUnavailableException> { locked.seal(ref, 0, patternBytes(10), aad) }
        val short = AesGcmAssetChunkCipher(AssetKeyResolver { ByteArray(16) }, random)
        assertFailsWith<AssetKeyInvalidException> { short.seal(ref, 0, patternBytes(10), aad) }
        val sealed = cipher.seal(ref, 0, patternBytes(10), aad)
        assertFailsWith<AssetKeyInvalidException> { short.open(ref, 0, sealed, aad) }
        assertFailsWith<AssetKeyUnavailableException> { locked.open(ref, 0, sealed, aad) }
    }

    @Test
    fun `the key is resolved by reference and the key bytes are not modified`() = runTest {
        val seen = mutableListOf<KeyReference>()
        val key = testKey()
        val snapshot = key.copyOf()
        val resolving = AesGcmAssetChunkCipher(AssetKeyResolver { seen += it; key }, random)
        val sealed = resolving.seal(KeyReference("named-key"), 0, patternBytes(10), aad)
        resolving.open(KeyReference("named-key"), 0, sealed, aad)
        assertEquals(listOf(KeyReference("named-key"), KeyReference("named-key")), seen)
        assertContentEquals(snapshot, key)
    }

    @Test
    fun `the same plaintext seals differently each time`() = runTest {
        val a = cipher.seal(ref, 0, patternBytes(100), aad)
        val b = cipher.seal(ref, 0, patternBytes(100), aad)
        assertNotEquals(a, b)
    }
}
