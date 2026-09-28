package io.dataloom.governance.audit

import io.dataloom.api.security.DataLoomMac
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.governance.CanonicalWriter

/**
 * Deterministic, unambiguous byte encoding of everything a record MAC covers.
 *
 * Layout (all integers big-endian; every variable-length field is prefixed by
 * its byte length as a u32, so no field boundary is ambiguous):
 *
 * ```
 * string  DOMAIN_TAG ("dataloom.governance.audit.v1")
 * u64     sequence
 * u64     recordedAt.epochMilliseconds
 * u8      previous-mac flag (0 = none, 1 = present)
 * bytes   previous MAC tag        (only when flag == 1)
 * string  event.tenantId
 * string  event.principalId
 * string  event.eventType
 * u32     detail entry count
 * repeat, ordered by key (UTF-16 code-unit order):
 *   string key
 *   string value
 * ```
 *
 * Strings are UTF-8. [io.dataloom.governance.requireWellFormedUnicode] keeps
 * lone surrogates out of every encoded string, which is what makes the encoding
 * identical on the JVM and Kotlin/Native. The version suffix in the domain tag
 * separates this encoding from any future change to it.
 */
internal object AuditCanonicalEncoding {

    const val DOMAIN_TAG: String = "dataloom.governance.audit.v1"

    fun encode(
        sequence: Long,
        recordedAt: DataLoomInstant,
        previousMac: DataLoomMac?,
        event: AuditEvent,
    ): ByteArray {
        val writer = CanonicalWriter()
        writer.string(DOMAIN_TAG)
        writer.u64(sequence)
        writer.u64(recordedAt.epochMilliseconds)
        if (previousMac == null) {
            writer.u8(0)
        } else {
            writer.u8(1)
            writer.bytes(previousMac.copyBytes())
        }
        writer.string(event.tenantId.value)
        writer.string(event.principalId.value)
        writer.string(event.eventType.value)
        val details = event.details.entries.entries.sortedBy { it.key }
        writer.u32(details.size)
        for ((key, value) in details) {
            writer.string(key)
            writer.string(value)
        }
        return writer.toByteArray()
    }
}
