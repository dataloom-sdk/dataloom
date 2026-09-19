package io.dataloom.assets

import io.dataloom.api.security.DataLoomIncrementalDigestCalculator

/**
 * The platform's real production digest calculator (JVM `MessageDigest`,
 * Apple CommonCrypto), so these tests exercise real hashing — including the
 * incremental accumulator — on every platform rather than a fake.
 */
expect fun platformDigests(): DataLoomIncrementalDigestCalculator
