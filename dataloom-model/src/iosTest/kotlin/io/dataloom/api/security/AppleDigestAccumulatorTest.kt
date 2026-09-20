package io.dataloom.api.security

/**
 * Runs the shared incremental-digest contract against CommonCrypto's
 * `Init`/`Update`/`Final` trio. Requires a macOS host (Apple simulator/native
 * test execution); on other hosts this file is only cross-compiled.
 */
class AppleDigestAccumulatorTest : IncrementalDigestCalculatorContract() {

    override fun calculator(): DataLoomIncrementalDigestCalculator = AppleDataLoomDigestCalculator()
}
