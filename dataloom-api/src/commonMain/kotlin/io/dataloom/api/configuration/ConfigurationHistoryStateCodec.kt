package io.dataloom.api.configuration

import io.dataloom.api.security.DataLoomDigestCalculator
import io.dataloom.api.security.KeyReference
import io.dataloom.api.state.DurableStateCodec

/**
 * Deterministic bounded V1 text codec for [ConfigurationHistoryState], for use
 * with a generic string-payload [io.dataloom.api.state.DurableStateStore]
 * implementation (for example a Room- or file-backed one).
 *
 * ## Integrity
 *
 * Each encoded snapshot carries its [ConfigurationSnapshot.checksum] hex.
 * [decode] recomputes the checksum from the decoded entries via
 * [ConfigurationSnapshot.create] and requires it to match the persisted hex
 * — so storage-layer corruption that still parses as well-formed fields
 * (a flipped bit inside a value, a dropped entry) fails closed as a malformed
 * payload instead of silently returning a snapshot with the wrong checksum,
 * or one that doesn't match what was actually applied.
 *
 * ## What this never encodes
 *
 * [io.dataloom.api.configuration.ConfigurationValue.SecretReferenceValue]
 * only ever carries a [KeyReference] — an opaque label, never secret
 * material — so this codec's output never contains anything beyond what
 * [ConfigurationSnapshot] itself already exposes.
 *
 * @param digestCalculator used to recompute and verify each decoded
 *   snapshot's checksum. Must use the same algorithm the snapshot was
 *   originally created with (see [ConfigurationSnapshot.create]).
 */
public class ConfigurationHistoryStateCodec(
    private val digestCalculator: DataLoomDigestCalculator,
) : DurableStateCodec<ConfigurationHistoryState> {

    override fun encode(state: ConfigurationHistoryState): String {
        val lines = mutableListOf("$HEADER\t$FORMAT_VERSION")
        state.retainedSnapshots.forEach { lines += encodeSnapshotLine(it) }
        val encoded = lines.joinToString("\n")
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Encoded configuration history exceeds the bounded V1 limit."
        }
        return encoded
    }

    override fun decode(payload: String): ConfigurationHistoryState {
        require(payload.length <= MAX_ENCODED_LENGTH) {
            "Encoded configuration history exceeds the bounded V1 limit."
        }
        return try {
            val lines = payload.split('\n')
            require(lines.isNotEmpty())
            val header = lines.first().split('\t')
            require(header.size == 2)
            require(header[0] == HEADER)
            require(header[1] == FORMAT_VERSION)
            val snapshots = (1 until lines.size).map { decodeSnapshotLine(lines[it]) }
            ConfigurationHistoryState(snapshots)
        } catch (malformed: Exception) {
            throw IllegalArgumentException("Malformed configuration history payload.", malformed)
        }
    }

    private fun encodeSnapshotLine(snapshot: ConfigurationSnapshot): String {
        val entries = snapshot.keys.sortedBy { it.value }.map { key -> encodeEntry(key, snapshot[key]!!) }
        return (
            listOf(snapshot.version.toString(), snapshot.checksum.toHex(), entries.size.toString()) + entries
            ).joinToString("|")
    }

    private fun decodeSnapshotLine(line: String): ConfigurationSnapshot {
        val fields = line.split('|')
        require(fields.size >= 3)
        val version = fields[0].toLong()
        val checksumHex = fields[1]
        val entryCount = fields[2].toInt()
        require(entryCount >= 0)
        require(fields.size == 3 + entryCount)
        val entries = linkedMapOf<ConfigurationKey, ConfigurationValue>()
        for (index in 0 until entryCount) {
            val (key, value) = decodeEntry(fields[3 + index])
            require(entries.put(key, value) == null) { "Duplicate configuration key in one snapshot." }
        }
        val snapshot = ConfigurationSnapshot.create(version, entries, digestCalculator)
        require(snapshot.checksum.toHex() == checksumHex) {
            "Decoded configuration snapshot checksum does not match the persisted checksum."
        }
        return snapshot
    }

    private fun encodeEntry(key: ConfigurationKey, value: ConfigurationValue): String {
        val valueField = when (value) {
            is ConfigurationValue.StringValue -> hexEncode(value.value)
            is ConfigurationValue.LongValue -> value.value.toString()
            is ConfigurationValue.DoubleValue -> value.value.toString()
            is ConfigurationValue.BooleanValue -> if (value.value) "1" else "0"
            is ConfigurationValue.SecretReferenceValue -> hexEncode(value.reference.value)
        }
        return "${hexEncode(key.value)}:${value.type.name}:$valueField"
    }

    private fun decodeEntry(field: String): Pair<ConfigurationKey, ConfigurationValue> {
        val parts = field.split(':', limit = 3)
        require(parts.size == 3)
        val key = ConfigurationKey(hexDecode(parts[0]))
        val type = ConfigurationValueType.valueOf(parts[1])
        val value: ConfigurationValue = when (type) {
            ConfigurationValueType.STRING -> ConfigurationValue.StringValue(hexDecode(parts[2]))
            ConfigurationValueType.LONG -> ConfigurationValue.LongValue(parts[2].toLong())
            ConfigurationValueType.DOUBLE -> ConfigurationValue.DoubleValue(parts[2].toDouble())
            ConfigurationValueType.BOOLEAN -> ConfigurationValue.BooleanValue(parts[2].toStrictBoolean())
            ConfigurationValueType.SECRET_REFERENCE ->
                ConfigurationValue.SecretReferenceValue(KeyReference(hexDecode(parts[2])))
        }
        return key to value
    }

    private fun String.toStrictBoolean(): Boolean = when (this) {
        "1" -> true
        "0" -> false
        else -> error("Invalid boolean field.")
    }

    private fun hexEncode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0f])
        }
    }

    private fun hexDecode(value: String): String {
        require(value.length % 2 == 0)
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = value[index * 2].hexValue()
            val low = value[(index * 2) + 1].hexValue()
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        else -> error("Invalid hex digit.")
    }

    private companion object {
        const val HEADER: String = "DATALOOM_CONFIG_HISTORY"
        const val FORMAT_VERSION: String = "1"
        const val MAX_ENCODED_LENGTH: Int = 1_048_576
        const val HEX: String = "0123456789abcdef"
    }
}
