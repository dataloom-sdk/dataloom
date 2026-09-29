package io.dataloom.assets

import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.assets.memory.InMemoryAssetProvider
import io.dataloom.assets.memory.InMemoryAssetSink
import io.dataloom.assets.memory.InMemoryAssetSource
import io.dataloom.assets.transform.AssetChunkCipher
import io.dataloom.assets.transform.AssetKeyResolver
import io.dataloom.assets.transform.AssetSealedChunk
import io.dataloom.assets.transform.AssetTransferTransforms
import io.dataloom.assets.transform.AssetWireFormat
import io.dataloom.assets.transform.DeflateAssetCompressor
import io.dataloom.api.asset.AssetEncryptionAlgorithm
import io.dataloom.api.security.KeyReference
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transfer engine with the concrete transforms wired in: compress-then-encrypt on
 * upload, the reverse on download, digests over the logical bytes (ADR-0014, D24).
 * On the JVM the cipher is real AES-256-GCM; on Apple it is a test double because
 * AES-GCM is unsupported there.
 */
class TransformedTransferTest {

    private val digests = platformDigests()
    private val random = platformSecureRandom()
    private val chunkSize = 1_024

    private enum class Mode(val compress: Boolean, val encrypt: Boolean) {
        COMPRESS(true, false), ENCRYPT(false, true), BOTH(true, true),
    }

    private fun transforms(mode: Mode, keys: AssetKeyResolver = fixedKeys()) = AssetTransferTransforms(
        compressor = if (mode.compress) DeflateAssetCompressor() else null,
        cipher = if (mode.encrypt) testCipher(keys, random) else null,
        keyReference = if (mode.encrypt) testKeyReference else null,
    )

    private fun engine(
        provider: AssetProvider,
        store: AssetTransferSessionStore,
        transforms: AssetTransferTransforms,
    ) = AssetTransferEngine(
        provider, store, digests, chunkSizeBytes = chunkSize, verifyBufferBytes = 256, transforms = transforms,
    )

    private suspend fun upload(
        provider: AssetProvider,
        transforms: AssetTransferTransforms,
        bytes: ByteArray,
        session: String = "up",
        asset: String = "doc",
        store: AssetTransferSessionStore = InMemoryAssetTransferSessionStore(),
    ) = engine(provider, store, transforms)
        .upload(sessionId(session), AssetId(asset), 1, octetStream, InMemoryAssetSource(bytes))

    private suspend fun download(
        provider: AssetProvider,
        transforms: AssetTransferTransforms,
        sink: AssetSink = InMemoryAssetSink(),
        session: String = "down",
        asset: String = "doc",
        store: AssetTransferSessionStore = InMemoryAssetTransferSessionStore(),
    ) = engine(provider, store, transforms).download(sessionId(session), AssetId(asset), null, sink)

    /** Records every frame the provider was sent, by chunk index. */
    private fun recording(provider: AssetProvider = InMemoryAssetProvider(digests)): Pair<InterceptingProvider, MutableMap<Int, ByteArray>> {
        val wrapped = InterceptingProvider(provider)
        val frames = LinkedHashMap<Int, ByteArray>()
        wrapped.interceptUploadChunk = { request ->
            frames[request.index] = request.bytes.copyOf()
            null
        }
        return wrapped to frames
    }

    // ------------------------------------------------------------ round trips

    @Test
    fun `round trip at chunk boundaries in every transform mode`() = runTest {
        for (mode in Mode.entries) {
            for (size in listOf(1, chunkSize - 1, chunkSize, chunkSize + 1, 3 * chunkSize, 3 * chunkSize + 1)) {
                for (bytes in listOf(patternBytes(size, seed = size), noiseBytes(size, seed = size))) {
                    val provider = InMemoryAssetProvider(digests)
                    val t = transforms(mode)
                    val up = upload(provider, t, bytes)
                    assertIs<AssetTransferOutcome.Completed>(up, "$mode size $size")
                    val sink = InMemoryAssetSink()
                    val down = download(provider, t, sink)
                    assertIs<AssetTransferOutcome.Completed>(down, "$mode size $size")
                    assertContentEquals(bytes, sink.snapshot(), "$mode size $size")
                    assertEquals(up.session.manifest, down.session.manifest)
                }
            }
        }
    }

    @Test
    fun `an empty asset is still refused`() = runTest {
        val outcome = upload(InMemoryAssetProvider(digests), transforms(Mode.BOTH), ByteArray(0))
        assertIs<AssetTransferOutcome.NotStarted>(outcome)
        assertEquals(AssetErrorKind.EMPTY_ASSET, (outcome.error as AssetTransferError).kind)
    }

    @Test
    fun `the manifest records the transforms and digests stay over the logical bytes`() = runTest {
        val bytes = patternBytes(2_500)
        val up = upload(InMemoryAssetProvider(digests), transforms(Mode.BOTH), bytes)
        assertIs<AssetTransferOutcome.Completed>(up)
        val manifest = up.session.manifest
        assertEquals("zlib-deflate", manifest.compression?.algorithm?.value)
        assertEquals(2_500L, manifest.compression?.uncompressedSizeBytes)
        assertEquals(testKeyReference, manifest.encryption?.keyReference)
        assertEquals(0, manifest.encryption?.nonceSize, "nonces are per chunk, carried in each frame")
        assertEquals(2_500L, manifest.sizeBytes, "size is the logical, uncompressed, unencrypted size")
        // D24: whole-object and per-chunk digests are over the plaintext.
        assertEquals(digests.digest(DigestAlgorithm.SHA_256, bytes), manifest.checksum)
        for (d in manifest.chunkLayout.chunks) {
            val logical = bytes.copyOfRange(d.offsetBytes.toInt(), (d.offsetBytes + d.lengthBytes).toInt())
            assertEquals(digests.digest(DigestAlgorithm.SHA_256, logical), d.checksum)
        }
    }

    @Test
    fun `untransformed transfers are unchanged and carry no frame`() = runTest {
        val (provider, frames) = recording()
        val bytes = patternBytes(3_000)
        val up = upload(provider, AssetTransferTransforms.NONE, bytes)
        assertIs<AssetTransferOutcome.Completed>(up)
        assertNull(up.session.manifest.compression)
        assertNull(up.session.manifest.encryption)
        assertFalse(AssetWireFormat.isTransformed(up.session.manifest))
        assertContentEquals(bytes.copyOfRange(0, chunkSize), frames.getValue(0))
    }

    // ------------------------------------------------------ what the provider sees

    @Test
    fun `an encrypting upload never shows the provider plaintext`() = runTest {
        val marker = ByteArray(64) { 'S'.code.toByte() }
        val bytes = ByteArray(4 * chunkSize) { marker[it % marker.size] }
        val (provider, frames) = recording()
        assertIs<AssetTransferOutcome.Completed>(upload(provider, transforms(Mode.ENCRYPT), bytes))
        assertEquals(4, frames.size)
        for ((index, frame) in frames) {
            assertFalse(contains(frame, marker), "chunk $index leaked a run of plaintext")
            assertEquals(chunkSize + 3 + 12 + 16, frame.size, "header + nonce + ciphertext + tag")
        }
    }

    @Test
    fun `compression shrinks compressible chunks and stores incompressible ones raw`() = runTest {
        val (provider, frames) = recording()
        val compressible = ByteArray(chunkSize) { 'A'.code.toByte() }
        assertIs<AssetTransferOutcome.Completed>(upload(provider, transforms(Mode.COMPRESS), compressible))
        assertTrue(frames.getValue(0).size < 100, "frame is ${frames.getValue(0).size} bytes")

        val (provider2, frames2) = recording()
        val noise = noiseBytes(3 * chunkSize)
        assertIs<AssetTransferOutcome.Completed>(upload(provider2, transforms(Mode.COMPRESS), noise, session = "n"))
        for (frame in frames2.values) {
            assertEquals(3 + chunkSize, frame.size, "incompressible chunk grows by the 3-byte header only")
            assertEquals(0, frame[1].toInt() and 1, "compressed flag is clear")
        }
        assertTrue(frames2.values.all { it.size <= chunkSize + AssetWireFormat.MAX_FRAME_OVERHEAD_BYTES })
    }

    @Test
    fun `every sealed chunk gets a distinct nonce and ciphertext even for identical plaintext`() = runTest {
        val bytes = ByteArray(60 * chunkSize) { 7 }
        val (provider, frames) = recording()
        assertIs<AssetTransferOutcome.Completed>(upload(provider, transforms(Mode.ENCRYPT), bytes))
        assertEquals(60, frames.size)
        val nonces = frames.values.map { frame -> frame.copyOfRange(3, 15).toList() }.toSet()
        assertEquals(60, nonces.size, "nonces must never repeat across chunks")
        val ciphertexts = frames.values.map { it.copyOfRange(15, it.size).toList() }.toSet()
        assertEquals(60, ciphertexts.size)
    }

    @Test
    fun `a provider cannot verify logical digests of frames but still enforces plausible lengths`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val up = upload(provider, transforms(Mode.ENCRYPT), patternBytes(2_000), session = "seed")
        assertIs<AssetTransferOutcome.Completed>(up)
        val manifest = up.session.manifest
        provider.openUpload(AssetUploadRequest(sessionId("x"), manifest.copy(assetId = AssetId("other"))))
        val tooLong = ByteArray(chunkSize + AssetWireFormat.MAX_FRAME_OVERHEAD_BYTES + 1)
        val rejected = provider.uploadChunk(AssetChunkUpload(sessionId("x"), 0, tooLong))
        assertIs<ProviderOperationResult.Failure>(rejected)
        assertEquals(AssetErrorKind.CHUNK_LENGTH_MISMATCH, (rejected.error as AssetTransferError).kind)
        assertIs<ProviderOperationResult.Failure>(provider.uploadChunk(AssetChunkUpload(sessionId("x"), 0, ByteArray(0))))
        // Arbitrary opaque bytes of a plausible length are stored: only the client can verify them.
        assertIs<ProviderOperationResult.Success<*>>(provider.uploadChunk(AssetChunkUpload(sessionId("x"), 0, ByteArray(40))))
    }

    // --------------------------------------------------------------- tampering

    private suspend fun uploadedEncrypted(
        bytes: ByteArray = patternBytes(4 * chunkSize + 10),
        mode: Mode = Mode.BOTH,
        asset: String = "doc",
    ): Pair<InterceptingProvider, ByteArray> {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        assertIs<AssetTransferOutcome.Completed>(upload(provider, transforms(mode), bytes, session = "seed-$asset", asset = asset))
        return provider to bytes
    }

    private suspend fun failedDownload(
        provider: InterceptingProvider,
        mode: Mode = Mode.BOTH,
        keys: AssetKeyResolver = fixedKeys(),
    ): Pair<AssetTransferOutcome.Failed, RecordingSink> {
        val sink = RecordingSink(InMemoryAssetSink())
        val outcome = download(provider, transforms(mode, keys), sink)
        assertIs<AssetTransferOutcome.Failed>(outcome)
        return outcome to sink
    }

    @Test
    fun `a flipped ciphertext bit fails authentication and discards the sink`() = runTest {
        val (provider, _) = uploadedEncrypted()
        provider.interceptReadChunk = { index, bytes ->
            val tampered = bytes.copyOf()
            if (index == 2) tampered[tampered.size / 2] = (tampered[tampered.size / 2].toInt() xor 0x01).toByte()
            ProviderOperationResult.Success(tampered)
        }
        val (outcome, sink) = failedDownload(provider)
        assertEquals(AssetErrorKind.CHUNK_AUTHENTICATION_FAILED, outcome.session.failure)
        assertEquals(1, sink.discards)
        assertEquals(setOf(0, 1), outcome.session.committedChunks, "nothing unauthenticated was committed")
    }

    @Test
    fun `a flipped tag bit and a flipped nonce bit both fail authentication`() = runTest {
        for (position in listOf(-1, 5)) {
            val (provider, _) = uploadedEncrypted(mode = Mode.ENCRYPT)
            provider.interceptReadChunk = { index, bytes ->
                val tampered = bytes.copyOf()
                if (index == 0) {
                    val at = if (position < 0) tampered.size + position else position
                    tampered[at] = (tampered[at].toInt() xor 0x80).toByte()
                }
                ProviderOperationResult.Success(tampered)
            }
            val (outcome, _) = failedDownload(provider, Mode.ENCRYPT)
            assertEquals(AssetErrorKind.CHUNK_AUTHENTICATION_FAILED, outcome.session.failure, "position $position")
        }
    }

    @Test
    fun `a truncated frame is rejected`() = runTest {
        for (keep in listOf(0, 2, 3, 10, 20)) {
            val (provider, _) = uploadedEncrypted(mode = Mode.ENCRYPT)
            provider.interceptReadChunk = { index, bytes ->
                ProviderOperationResult.Success(if (index == 1) bytes.copyOf(keep) else bytes)
            }
            val (outcome, sink) = failedDownload(provider, Mode.ENCRYPT)
            val kind = outcome.session.failure
            assertTrue(
                kind == AssetErrorKind.TRANSFORM_FRAME_INVALID || kind == AssetErrorKind.CHUNK_AUTHENTICATION_FAILED,
                "keep $keep gave $kind",
            )
            assertEquals(1, sink.discards)
        }
        // Dropping the tail of an otherwise intact frame breaks the tag.
        val (provider, _) = uploadedEncrypted(mode = Mode.ENCRYPT)
        provider.interceptReadChunk = { index, bytes ->
            ProviderOperationResult.Success(if (index == 1) bytes.copyOf(bytes.size - 1) else bytes)
        }
        assertEquals(AssetErrorKind.CHUNK_AUTHENTICATION_FAILED, failedDownload(provider, Mode.ENCRYPT).first.session.failure)
    }

    @Test
    fun `an unknown frame version and unknown flags are rejected`() = runTest {
        for ((offset, value) in listOf(0 to 2, 0 to 0, 1 to 0x80, 1 to 0x04)) {
            val (provider, _) = uploadedEncrypted(mode = Mode.ENCRYPT)
            provider.interceptReadChunk = { index, bytes ->
                val tampered = bytes.copyOf()
                if (index == 0) tampered[offset] = value.toByte()
                ProviderOperationResult.Success(tampered)
            }
            val (outcome, _) = failedDownload(provider, Mode.ENCRYPT)
            assertEquals(AssetErrorKind.TRANSFORM_FRAME_INVALID, outcome.session.failure, "byte $offset = $value")
        }
    }

    @Test
    fun `a wrong key fails authentication`() = runTest {
        val (provider, _) = uploadedEncrypted()
        val (outcome, sink) = failedDownload(provider, keys = fixedKeys(seed = 2))
        assertEquals(AssetErrorKind.CHUNK_AUTHENTICATION_FAILED, outcome.session.failure)
        assertEquals(1, sink.discards)
    }

    @Test
    fun `a chunk moved to another position fails authentication`() = runTest {
        val (provider, _) = uploadedEncrypted(mode = Mode.ENCRYPT)
        val stored = HashMap<Int, ByteArray>()
        provider.interceptReadChunk = { index, bytes ->
            stored[index] = bytes
            // Serve chunk 0's frame for position 1 (a same-size, validly sealed frame from the same asset).
            ProviderOperationResult.Success(if (index == 1) stored.getValue(0) else bytes)
        }
        val (outcome, _) = failedDownload(provider, Mode.ENCRYPT)
        assertEquals(AssetErrorKind.CHUNK_AUTHENTICATION_FAILED, outcome.session.failure)
    }

    @Test
    fun `a chunk from another asset fails authentication even at the same position`() = runTest {
        val bytes = patternBytes(3 * chunkSize)
        val real = InMemoryAssetProvider(digests)
        val t = transforms(Mode.ENCRYPT)
        assertIs<AssetTransferOutcome.Completed>(upload(real, t, bytes, session = "a", asset = "asset-a"))
        val (recorder, otherFrames) = recording(InMemoryAssetProvider(digests))
        assertIs<AssetTransferOutcome.Completed>(upload(recorder, t, bytes, session = "b", asset = "asset-b"))

        val hostile = InterceptingProvider(real)
        hostile.interceptReadChunk = { index, _ -> ProviderOperationResult.Success(otherFrames.getValue(index)) }
        val sink = RecordingSink(InMemoryAssetSink())
        val outcome = download(hostile, t, sink, asset = "asset-a")
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.CHUNK_AUTHENTICATION_FAILED, outcome.session.failure)
    }

    @Test
    fun `a chunk from another version of the asset fails authentication`() = runTest {
        val bytes = patternBytes(2 * chunkSize)
        val real = InMemoryAssetProvider(digests)
        val t = transforms(Mode.ENCRYPT)
        val store = InMemoryAssetTransferSessionStore()
        assertIs<AssetTransferOutcome.Completed>(
            engine(real, store, t).upload(sessionId("v1"), AssetId("doc"), 1, octetStream, InMemoryAssetSource(bytes)),
        )
        val (recorder, v2Frames) = recording(InMemoryAssetProvider(digests))
        assertIs<AssetTransferOutcome.Completed>(
            engine(recorder, InMemoryAssetTransferSessionStore(), t)
                .upload(sessionId("v2"), AssetId("doc"), 2, octetStream, InMemoryAssetSource(bytes)),
        )
        val hostile = InterceptingProvider(real)
        hostile.interceptReadChunk = { index, _ -> ProviderOperationResult.Success(v2Frames.getValue(index)) }
        val outcome = engine(hostile, InMemoryAssetTransferSessionStore(), t)
            .download(sessionId("d"), AssetId("doc"), 1, InMemoryAssetSink())
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.CHUNK_AUTHENTICATION_FAILED, outcome.session.failure)
    }

    @Test
    fun `an encrypted asset cannot be downgraded to a plaintext frame`() = runTest {
        val bytes = patternBytes(2 * chunkSize)
        val (provider, _) = uploadedEncrypted(bytes, Mode.ENCRYPT)
        provider.interceptReadChunk = { _, _ ->
            // A frame with no sealing at all, carrying plausible bytes.
            ProviderOperationResult.Success(byteArrayOf(1, 0, 0) + ByteArray(chunkSize))
        }
        val (outcome, _) = failedDownload(provider, Mode.ENCRYPT)
        assertEquals(AssetErrorKind.TRANSFORM_FRAME_INVALID, outcome.session.failure)
    }

    @Test
    fun `corrupt compressed data in an unencrypted asset is caught by frame or digest checks`() = runTest {
        val bytes = ByteArray(3 * chunkSize) { 'Z'.code.toByte() }
        val (provider, _) = uploadedEncrypted(bytes, Mode.COMPRESS)
        provider.interceptReadChunk = { index, stored ->
            val tampered = stored.copyOf()
            if (index == 1) tampered[tampered.size - 3] = (tampered[tampered.size - 3].toInt() xor 0x10).toByte()
            ProviderOperationResult.Success(tampered)
        }
        val (outcome, sink) = failedDownload(provider, Mode.COMPRESS)
        assertEquals(AssetErrorKind.TRANSFORM_FRAME_INVALID, outcome.session.failure)
        assertEquals(1, sink.discards)
    }

    @Test
    fun `a corrupted raw frame in a compress-only asset fails the chunk digest and stays recoverable`() = runTest {
        val bytes = noiseBytes(3 * chunkSize)
        val (provider, _) = uploadedEncrypted(bytes, Mode.COMPRESS)
        provider.interceptReadChunk = { index, stored ->
            val tampered = stored.copyOf()
            if (index == 1) tampered[100] = (tampered[100].toInt() xor 0x01).toByte()
            ProviderOperationResult.Success(tampered)
        }
        val outcome = download(provider, transforms(Mode.COMPRESS))
        assertIs<AssetTransferOutcome.Interrupted>(outcome)
        assertEquals(AssetErrorKind.CHUNK_DIGEST_MISMATCH, (outcome.error as AssetTransferError).kind)
        assertEquals(Recoverability.RECOVERABLE, outcome.error.recoverability)
    }

    // ------------------------------------------------- unsupported and mismatched

    private class UnsupportedCipher : AssetChunkCipher {
        override val algorithm = AssetEncryptionAlgorithm("unsupported-here")
        override val isSupported: Boolean get() = false
        override suspend fun seal(keyReference: KeyReference, chunkIndex: Int, plaintext: ByteArray, associatedData: ByteArray): AssetSealedChunk =
            error("must never be called")
        override suspend fun open(keyReference: KeyReference, chunkIndex: Int, sealed: AssetSealedChunk, associatedData: ByteArray): ByteArray =
            error("must never be called")
    }

    @Test
    fun `an unsupported cipher refuses the upload before touching the source or provider`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val source = RecordingSource(InMemoryAssetSource(patternBytes(2_000)))
        val store = InMemoryAssetTransferSessionStore()
        val t = AssetTransferTransforms(cipher = UnsupportedCipher(), keyReference = testKeyReference)
        val outcome = engine(provider, store, t).upload(sessionId("u"), AssetId("a"), 1, octetStream, source)
        assertIs<AssetTransferOutcome.NotStarted>(outcome)
        val error = outcome.error as AssetTransferError
        assertEquals(AssetErrorKind.TRANSFORM_UNSUPPORTED, error.kind)
        assertEquals(Recoverability.NON_RECOVERABLE, error.recoverability)
        assertEquals(0, source.reads)
        assertEquals(0, provider.openCalls)
        assertNull(store.load(sessionId("u")), "no session is created for an unsupported transform")
    }

    @Test
    fun `downloading an encrypted asset without a cipher is refused and creates no session`() = runTest {
        val (provider, _) = uploadedEncrypted()
        val store = InMemoryAssetTransferSessionStore()
        val sink = RecordingSink(InMemoryAssetSink())
        val outcome = download(provider, AssetTransferTransforms.NONE, sink, store = store)
        assertIs<AssetTransferOutcome.NotStarted>(outcome)
        assertEquals(AssetErrorKind.TRANSFORM_UNSUPPORTED, (outcome.error as AssetTransferError).kind)
        assertNull(store.load(sessionId("down")))
        assertTrue(provider.readChunkCalls.isEmpty())
    }

    @Test
    fun `downloading a compressed asset without the compressor is refused`() = runTest {
        val (provider, _) = uploadedEncrypted(mode = Mode.COMPRESS)
        val outcome = download(provider, AssetTransferTransforms.NONE)
        assertIs<AssetTransferOutcome.NotStarted>(outcome)
        assertEquals(AssetErrorKind.TRANSFORM_UNSUPPORTED, (outcome.error as AssetTransferError).kind)
    }

    @Test
    fun `a session is never silently resumed with different transforms`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(5 * chunkSize)
        provider.interceptUploadChunk = { if (it.index == 2) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null }
        val first = upload(provider, transforms(Mode.BOTH), bytes, session = "s", store = store)
        assertIs<AssetTransferOutcome.Interrupted>(first)

        // The host now (mis)configures no encryption: resuming would upload plaintext for an encrypted session.
        provider.interceptUploadChunk = { null }
        val resumed = upload(provider, transforms(Mode.COMPRESS), bytes, session = "s", store = store)
        assertIs<AssetTransferOutcome.Failed>(resumed)
        assertEquals(AssetErrorKind.TRANSFORM_UNSUPPORTED, resumed.session.failure)
        assertEquals(setOf(0, 1), resumed.session.committedChunks)
    }

    @Test
    fun `a session resumed under a different key reference is refused`() = runTest {
        val provider = InterceptingProvider(InMemoryAssetProvider(digests))
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(3 * chunkSize)
        provider.interceptUploadChunk = { if (it.index == 1) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else null }
        assertIs<AssetTransferOutcome.Interrupted>(upload(provider, transforms(Mode.ENCRYPT), bytes, session = "s", store = store))
        provider.interceptUploadChunk = { null }
        val rotated = AssetTransferTransforms(
            cipher = testCipher(fixedKeys(), random),
            keyReference = KeyReference("rotated-key"),
        )
        val outcome = upload(provider, rotated, bytes, session = "s", store = store)
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.TRANSFORM_UNSUPPORTED, outcome.session.failure)
    }

    // ------------------------------------------------------------- key handling

    @Test
    fun `an unavailable key leaves the transfer resumable and it completes once the key returns`() = runTest {
        var available = false
        val keys = AssetKeyResolver { if (available) testKey() else throw IllegalStateException("keystore locked") }
        val provider = InMemoryAssetProvider(digests)
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(3 * chunkSize)
        val t = transforms(Mode.ENCRYPT, keys)
        val first = upload(provider, t, bytes, session = "s", store = store)
        assertIs<AssetTransferOutcome.Interrupted>(first)
        assertEquals(AssetErrorKind.ENCRYPTION_KEY_UNAVAILABLE, (first.error as AssetTransferError).kind)
        assertEquals(Recoverability.RECOVERABLE, first.error.recoverability)
        assertEquals(AssetTransferPhase.TRANSFERRING, first.session.phase)

        available = true
        val second = upload(provider, t, bytes, session = "s", store = store)
        assertIs<AssetTransferOutcome.Completed>(second)
        val sink = InMemoryAssetSink()
        assertIs<AssetTransferOutcome.Completed>(download(provider, t, sink))
        assertContentEquals(bytes, sink.snapshot())
    }

    @Test
    fun `a key of the wrong size fails the session terminally`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val outcome = upload(provider, transforms(Mode.ENCRYPT, AssetKeyResolver { ByteArray(16) }), patternBytes(2_000))
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.ENCRYPTION_KEY_INVALID, outcome.session.failure)
    }

    @Test
    fun `a rotated resolver that returns the same key for the reference still opens old assets`() = runTest {
        val provider = InMemoryAssetProvider(digests)
        val bytes = patternBytes(2 * chunkSize + 5)
        assertIs<AssetTransferOutcome.Completed>(upload(provider, transforms(Mode.BOTH), bytes))
        // A new resolver and a new cipher instance, as after an app restart.
        val sink = InMemoryAssetSink()
        assertIs<AssetTransferOutcome.Completed>(download(provider, transforms(Mode.BOTH, AssetKeyResolver { testKey() }), sink))
        assertContentEquals(bytes, sink.snapshot())
    }

    // ------------------------------------------------ resume and whole-object check

    @Test
    fun `an upload resumed after a partial commit completes and the download verifies the whole object`() = runTest {
        val (provider, frames) = recording()
        val store = InMemoryAssetTransferSessionStore()
        val bytes = patternBytes(8 * chunkSize + 100)
        val t = transforms(Mode.BOTH)
        var failOnce = true
        val recordingHook = provider.interceptUploadChunk
        provider.interceptUploadChunk = { request ->
            if (request.index == 5 && failOnce) {
                failOnce = false
                failure(AssetErrorKind.PROVIDER_UNAVAILABLE)
            } else {
                recordingHook(request)
            }
        }
        val first = upload(provider, t, bytes, session = "s", store = store)
        assertIs<AssetTransferOutcome.Interrupted>(first)
        assertEquals(setOf(0, 1, 2, 3, 4), first.session.committedChunks)

        provider.uploadAttempts.clear()
        // A new engine instance, as after a process restart, with a new cipher instance.
        val second = upload(provider, transforms(Mode.BOTH), bytes, session = "s", store = store)
        assertIs<AssetTransferOutcome.Completed>(second)
        assertEquals(listOf(5, 6, 7, 8), provider.uploadAttempts, "only the missing chunks are sent")
        assertEquals(9, frames.size)

        val sink = InMemoryAssetSink()
        val down = download(provider, transforms(Mode.BOTH), sink)
        assertIs<AssetTransferOutcome.Completed>(down)
        assertContentEquals(bytes, sink.snapshot())
        assertEquals(digests.digest(DigestAlgorithm.SHA_256, bytes), down.session.manifest.checksum)
    }

    @Test
    fun `a resumed download skips committed chunks and still verifies the whole object`() = runTest {
        val (provider, _) = uploadedEncrypted(patternBytes(8 * chunkSize + 100))
        val store = InMemoryAssetTransferSessionStore()
        val sink = InMemoryAssetSink()
        provider.interceptReadChunk = { index, bytes ->
            if (index == 4) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else ProviderOperationResult.Success(bytes)
        }
        val first = download(provider, transforms(Mode.BOTH), sink, store = store)
        assertIs<AssetTransferOutcome.Interrupted>(first)
        assertEquals(setOf(0, 1, 2, 3), first.session.committedChunks)

        provider.interceptReadChunk = { _, bytes -> ProviderOperationResult.Success(bytes) }
        provider.readChunkCalls.clear()
        val second = download(provider, transforms(Mode.BOTH), sink, store = store)
        assertIs<AssetTransferOutcome.Completed>(second)
        assertEquals(listOf(4, 5, 6, 7, 8), provider.readChunkCalls)
        assertContentEquals(patternBytes(8 * chunkSize + 100), sink.snapshot())
    }

    @Test
    fun `a resumed download whose staged plaintext was corrupted fails the whole-object digest`() = runTest {
        val bytes = patternBytes(6 * chunkSize)
        val (provider, _) = uploadedEncrypted(bytes)
        val store = InMemoryAssetTransferSessionStore()
        val sink = InMemoryAssetSink()
        provider.interceptReadChunk = { index, stored ->
            if (index == 3) failure(AssetErrorKind.PROVIDER_UNAVAILABLE) else ProviderOperationResult.Success(stored)
        }
        assertIs<AssetTransferOutcome.Interrupted>(download(provider, transforms(Mode.BOTH), sink, store = store))
        // Chunk 1 was committed, then its staged bytes rotted while the process was down.
        sink.write(chunkSize.toLong() + 10, byteArrayOf(0x55, 0x55), 0, 2)
        provider.interceptReadChunk = { _, stored -> ProviderOperationResult.Success(stored) }
        val outcome = download(provider, transforms(Mode.BOTH), sink, store = store)
        assertIs<AssetTransferOutcome.Failed>(outcome)
        assertEquals(AssetErrorKind.OBJECT_DIGEST_MISMATCH, outcome.session.failure)
    }

    // ------------------------------------------------------------ persistence

    @Test
    fun `a transformed session survives the durable codec`() = runTest {
        val up = upload(InMemoryAssetProvider(digests), transforms(Mode.BOTH), patternBytes(2_500))
        assertIs<AssetTransferOutcome.Completed>(up)
        val codec = AssetTransferSessionCodec()
        val decoded = codec.decode(codec.encode(up.session))
        assertEquals(up.session, decoded)
        assertNotNull(decoded.manifest.compression)
        assertNotNull(decoded.manifest.encryption)
        assertEquals(0, decoded.manifest.encryption?.nonceSize)
    }

    @Test
    fun `configuration rules are enforced`() {
        assertTrue(AssetTransferTransforms.NONE.isEmpty)
        assertFalse(AssetTransferTransforms(DeflateAssetCompressor()).isEmpty)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AssetTransferTransforms(cipher = testCipher(fixedKeys(), random))
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AssetTransferTransforms(keyReference = testKeyReference)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AssetTransferTransforms(cipher = io.dataloom.assets.transform.IdentityAssetCipher(), keyReference = testKeyReference)
        }
        assertTrue(AssetTransferTransforms(DeflateAssetCompressor()).toString().contains("zlib-deflate"))
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) if (haystack[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }
}
