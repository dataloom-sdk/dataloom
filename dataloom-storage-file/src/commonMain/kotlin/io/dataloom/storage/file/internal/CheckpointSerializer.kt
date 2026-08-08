package io.dataloom.storage.file.internal

import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.identifier.CheckpointKey
import io.dataloom.api.identifier.CheckpointToken
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Serialization and deserialization helpers for [SynchronizationCheckpoint]
 * to and from the `DATALOOM_CKPT_V1` text format.
 *
 * Format overview:
 * ```
 * DATALOOM_CKPT_V1
 * key=<checkpointKey>
 * token=<checkpointToken>
 * meta.<key>=<value>   (zero or more)
 * ```
 */
@OptIn(ExperimentalEncodingApi::class)
internal object CheckpointSerializer {

    private const val HEADER = "DATALOOM_CKPT_V1"
    private const val KEY_KEY = "key"
    private const val KEY_TOKEN = "token"
    private const val META_PREFIX = "meta."

    /**
     * Produces a filename-safe, collision-resistant key for the checkpoint
     * file, derived from the [CheckpointKey] value using URL-safe Base64.
     */
    fun fileNameFor(key: CheckpointKey): String {
        val encoded = Base64.UrlSafe.encode(key.value.encodeToByteArray())
        return "$encoded.chk"
    }

    /** Serializes a [SynchronizationCheckpoint] to a multi-line text record. */
    fun serialize(checkpoint: SynchronizationCheckpoint): String = buildString {
        appendLine(HEADER)
        appendLine("$KEY_KEY=${checkpoint.key.value}")
        appendLine("$KEY_TOKEN=${checkpoint.token.value}")
        for ((k, v) in checkpoint.metadata.entries) {
            appendLine("$META_PREFIX$k=$v")
        }
    }

    /**
     * Deserializes a [SynchronizationCheckpoint] from a text record produced
     * by [serialize]. Returns `null` on malformed input.
     */
    fun deserialize(text: String): SynchronizationCheckpoint? {
        val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty() || lines[0] != HEADER) return null

        val props = mutableMapOf<String, String>()
        val metaEntries = mutableMapOf<String, String>()

        for (line in lines.drop(1)) {
            val eqIndex = line.indexOf('=')
            if (eqIndex < 0) continue
            val key = line.substring(0, eqIndex)
            val value = line.substring(eqIndex + 1)
            if (key.startsWith(META_PREFIX)) {
                metaEntries[key.removePrefix(META_PREFIX)] = value
            } else {
                props[key] = value
            }
        }

        val keyValue = props[KEY_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val tokenValue = props[KEY_TOKEN]?.takeIf { it.isNotBlank() } ?: return null
        val metadata = if (metaEntries.isEmpty()) DataLoomMetadata.Empty else DataLoomMetadata.of(metaEntries)

        return SynchronizationCheckpoint(
            key = CheckpointKey(keyValue),
            token = CheckpointToken(tokenValue),
            metadata = metadata,
        )
    }
}
