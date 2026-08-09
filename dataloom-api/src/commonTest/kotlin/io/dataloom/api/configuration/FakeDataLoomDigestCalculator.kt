package io.dataloom.api.configuration

import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DataLoomDigestCalculator
import io.dataloom.api.security.DigestAlgorithm

/**
 * Deterministic, non-cryptographic [DataLoomDigestCalculator] test fake,
 * shared across this package's tests.
 *
 * This module (dataloom-api) has no platform-specific source set to host
 * the real SystemDataLoomDigestCalculator/AppleDataLoomDigestCalculator
 * implementations (those live in dataloom-model's jvmMain/iosMain), and
 * only determinism is needed for these tests — the same
 * fake-over-production-implementation posture docs/api/secure-random.md's
 * testing note already documents, and the same "inject a small test-local
 * fake" approach RuntimeDependenciesTest already uses for DataLoomClock.
 *
 * Same input always produces the same output, but the output has no
 * cryptographic integrity properties — do not use in production.
 *
 * A top-level Kotlin class cannot be scoped `private` to a single file the
 * way a top-level function can (it would still collide as one JVM class
 * name across files in the same package), so this fake is `internal` and
 * lives in its own file rather than being duplicated per test file.
 */
internal class FakeDataLoomDigestCalculator : DataLoomDigestCalculator {
    override fun digest(algorithm: DigestAlgorithm, input: ByteArray): DataLoomDigest {
        val length = when (algorithm) {
            DigestAlgorithm.SHA_256 -> 32
            DigestAlgorithm.SHA_512 -> 64
        }
        var hash = FNV_OFFSET_BASIS
        for (byte in input) {
            hash = hash xor byte.toUByte().toULong()
            hash *= FNV_PRIME
        }
        val bytes = ByteArray(length)
        for (i in bytes.indices) {
            bytes[i] = ((hash shr ((i % 8) * 8)) and 0xFFUL).toByte()
            hash *= FNV_PRIME
        }
        return DataLoomDigest(algorithm, bytes)
    }

    private companion object {
        const val FNV_OFFSET_BASIS: ULong = 1_469_598_103_934_665_603UL
        const val FNV_PRIME: ULong = 1_099_511_628_211UL
    }
}
