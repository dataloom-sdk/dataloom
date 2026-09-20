package io.dataloom.governance

import io.dataloom.api.security.DataLoomHmacCalculator
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * The real platform HMAC-SHA256 implementation from dataloom-model
 * (`SystemDataLoomHmacCalculator` on the JVM, `AppleDataLoomHmacCalculator` on
 * Apple targets), never a fake: tamper-detection tests are only meaningful
 * against genuine keyed MACs.
 */
internal expect fun platformHmacCalculator(): DataLoomHmacCalculator

/** Clock that returns [start], [start] + [step], ... on successive reads. */
internal class SteppingClock(
    start: Long = 1_000L,
    private val step: Long = 10L,
) : DataLoomClock {
    private var next: Long = start

    override fun now(): DataLoomInstant {
        val instant = DataLoomInstant(next)
        next += step
        return instant
    }
}

internal fun testKey(): ByteArray = "0123456789abcdef0123456789abcdef".encodeToByteArray()
