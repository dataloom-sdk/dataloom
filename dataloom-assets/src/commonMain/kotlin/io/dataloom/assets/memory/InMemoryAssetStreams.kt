package io.dataloom.assets.memory

import io.dataloom.assets.AssetSink
import io.dataloom.assets.AssetSinkFullException
import io.dataloom.assets.AssetSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [AssetSource] over a byte array. The array is defensively copied. */
public class InMemoryAssetSource(bytes: ByteArray) : AssetSource {
    private val bytes: ByteArray = bytes.copyOf()

    override suspend fun sizeBytes(): Long = bytes.size.toLong()

    override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(destination, destinationOffset, position.toInt(), position.toInt() + count)
        return count
    }
}

/**
 * [AssetSink] holding its bytes in memory, growing as ranges are written.
 *
 * @param maxBytes total capacity; a [reserve] or write beyond it throws
 *   [AssetSinkFullException], modelling a full disk or an exhausted local
 *   quota. `null` means unbounded.
 */
public class InMemoryAssetSink(private val maxBytes: Long? = null) : AssetSink {
    private val mutex = Mutex()
    private var buffer = ByteArray(0)
    private var written: BooleanArray = BooleanArray(0)

    override suspend fun reserve(sizeBytes: Long) {
        if (maxBytes != null && sizeBytes > maxBytes) {
            throw AssetSinkFullException("Sink capacity is smaller than the requested reservation.")
        }
    }

    override suspend fun write(position: Long, source: ByteArray, sourceOffset: Int, length: Int): Unit =
        mutex.withLock {
            val end = position + length
            if (maxBytes != null && end > maxBytes) throw AssetSinkFullException("Sink capacity exceeded.")
            if (end > buffer.size) {
                buffer = buffer.copyOf(end.toInt())
                written = written.copyOf(end.toInt())
            }
            source.copyInto(buffer, position.toInt(), sourceOffset, sourceOffset + length)
            written.fill(true, position.toInt(), end.toInt())
        }

    override suspend fun read(position: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int =
        mutex.withLock {
            // Only bytes actually written are readable: a sparse hole reads as end of data.
            if (position >= buffer.size || !written[position.toInt()]) return@withLock -1
            var count = 0
            while (count < length && position + count < buffer.size && written[(position + count).toInt()]) count++
            buffer.copyInto(destination, destinationOffset, position.toInt(), position.toInt() + count)
            count
        }

    override suspend fun discard(): Unit =
        mutex.withLock {
            buffer = ByteArray(0)
            written = BooleanArray(0)
        }

    /** A copy of everything written so far, for assertions. */
    public suspend fun snapshot(): ByteArray = mutex.withLock { buffer.copyOf() }
}
