package io.dataloom.assets

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DigestAlgorithm

val octetStream = AssetMediaType("application/octet-stream")

fun sessionId(value: String) = AssetTransferSessionId(value)

/** Deterministic, non-repeating-looking bytes. */
fun patternBytes(size: Int, seed: Int = 1): ByteArray = ByteArray(size) { ((it * 31 + seed * 17 + (it shr 8) * 7) and 0xFF).toByte() }

class TestAsset(val assetId: AssetId, val version: Long, val bytes: ByteArray, val manifest: AssetManifest) {
    fun chunk(index: Int): ByteArray {
        val d = manifest.chunkLayout.chunks[index]
        return bytes.copyOfRange(d.offsetBytes.toInt(), (d.offsetBytes + d.lengthBytes).toInt())
    }
}

suspend fun testAsset(
    id: String = "asset-1",
    version: Long = 1,
    size: Int = 10_000,
    chunkSize: Int = 1_024,
    seed: Int = 1,
): TestAsset {
    val bytes = patternBytes(size, seed)
    val manifest = AssetIntegrityVerifier(platformDigests()).prepareManifest(
        AssetId(id), version, octetStream, io.dataloom.assets.memory.InMemoryAssetSource(bytes),
        AssetChunkPlan(size.toLong(), chunkSize), DigestAlgorithm.SHA_256,
    )
    return TestAsset(AssetId(id), version, bytes, manifest)
}

fun failure(kind: AssetErrorKind, message: String = "test failure"): ProviderOperationResult.Failure =
    ProviderOperationResult.Failure(AssetTransferError(kind, message))

/** A non-asset error with a chosen recoverability, like a foreign provider might return. */
class ForeignError(override val recoverability: Recoverability) : DataLoomError {
    override val code = ErrorCode("foreign.error")
    override val category = ErrorCategory.PROVIDER
    override val severity = ErrorSeverity.ERROR
    override val message = "foreign provider error"
    override val cause: Throwable? = null
}

/**
 * Wraps a real provider so tests can observe and interfere with individual
 * calls: count calls, fail or corrupt specific chunks, run a hook mid-transfer.
 */
class InterceptingProvider(private val delegate: AssetProvider) : AssetProvider by delegate {
    /** Chunk indices passed to uploadChunk, in call order (including failed calls). */
    val uploadAttempts = mutableListOf<Int>()

    /** Chunk indices whose upload the delegate accepted. */
    val uploadsAccepted = mutableListOf<Int>()

    /** Largest chunk byte array ever passed to uploadChunk. */
    var maxUploadedChunkSize = 0
    var openCalls = 0
    var completeCalls = 0
    var abortCalls = 0
    val readChunkCalls = mutableListOf<Int>()
    var maxReadChunkSize = 0

    /** If it returns non-null, that result replaces the delegate call. */
    var interceptUploadChunk: suspend (AssetChunkUpload) -> ProviderOperationResult<AssetUploadStatus>? = { null }
    var interceptReadChunk: suspend (Int, ByteArray) -> ProviderOperationResult<ByteArray> = { _, bytes ->
        ProviderOperationResult.Success(bytes)
    }
    var interceptReadManifest: (AssetManifest) -> AssetManifest = { it }
    var interceptComplete: suspend (AssetTransferSessionId) -> ProviderOperationResult<AssetManifest>? = { null }

    override suspend fun openUpload(request: AssetUploadRequest): ProviderOperationResult<AssetUploadStatus> {
        openCalls++
        return delegate.openUpload(request)
    }

    override suspend fun uploadChunk(request: AssetChunkUpload): ProviderOperationResult<AssetUploadStatus> {
        uploadAttempts += request.index
        maxUploadedChunkSize = maxOf(maxUploadedChunkSize, request.bytes.size)
        interceptUploadChunk(request)?.let { return it }
        val result = delegate.uploadChunk(request)
        if (result is ProviderOperationResult.Success) uploadsAccepted += request.index
        return result
    }

    override suspend fun completeUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<AssetManifest> {
        completeCalls++
        interceptComplete(sessionId)?.let { return it }
        return delegate.completeUpload(sessionId)
    }

    override suspend fun abortUpload(sessionId: AssetTransferSessionId): ProviderOperationResult<Unit> {
        abortCalls++
        return delegate.abortUpload(sessionId)
    }

    override suspend fun readManifest(assetId: AssetId, version: Long?): ProviderOperationResult<AssetManifest> =
        when (val result = delegate.readManifest(assetId, version)) {
            is ProviderOperationResult.Success -> ProviderOperationResult.Success(interceptReadManifest(result.value))
            is ProviderOperationResult.Failure -> result
        }

    override suspend fun readChunk(assetId: AssetId, version: Long, index: Int): ProviderOperationResult<ByteArray> {
        readChunkCalls += index
        return when (val result = delegate.readChunk(assetId, version, index)) {
            is ProviderOperationResult.Success -> {
                maxReadChunkSize = maxOf(maxReadChunkSize, result.value.size)
                interceptReadChunk(index, result.value)
            }
            is ProviderOperationResult.Failure -> result
        }
    }
}

/** Records the largest single read it was asked for, and can run a hook per read. */
class RecordingSource(private val inner: AssetSource) : AssetSource {
    var maxReadLength = 0
    var reads = 0
    var onRead: suspend (position: Long) -> Unit = {}

    override suspend fun sizeBytes(): Long = inner.sizeBytes()

    override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int {
        reads++
        maxReadLength = maxOf(maxReadLength, length)
        onRead(position)
        return inner.read(position, destination, destinationOffset, length)
    }
}

/** Records the largest single write and read the sink saw. */
class RecordingSink(private val inner: AssetSink) : AssetSink {
    var maxWriteLength = 0
    var maxReadLength = 0
    val writePositions = mutableListOf<Long>()
    var discards = 0

    override suspend fun reserve(sizeBytes: Long) = inner.reserve(sizeBytes)

    override suspend fun write(position: Long, source: ByteArray, sourceOffset: Int, length: Int) {
        maxWriteLength = maxOf(maxWriteLength, length)
        writePositions += position
        inner.write(position, source, sourceOffset, length)
    }

    override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int {
        maxReadLength = maxOf(maxReadLength, length)
        return inner.read(position, destination, destinationOffset, length)
    }

    override suspend fun discard() {
        discards++
        inner.discard()
    }
}
