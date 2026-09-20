package io.dataloom.api.random

import java.security.SecureRandom

/**
 * Production [DataLoomSecureRandom] backed by [java.security.SecureRandom].
 *
 * This is the default secure-randomness implementation for the JVM target,
 * which also currently serves native Android because the current Android
 * adapter modules consume this module's JVM target directly.
 * [java.security.SecureRandom] instances are documented as safe for
 * concurrent use, so this class has no additional synchronization.
 */
public class SystemDataLoomSecureRandom : DataLoomSecureRandom {

    private val secureRandom: SecureRandom = SecureRandom()

    override fun nextBytes(byteCount: Int): ByteArray {
        require(byteCount > 0) {
            "byteCount must be greater than zero, but was $byteCount."
        }
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes
    }
}
