package io.dataloom.api.policy

import io.dataloom.api.configuration.ConfigurationSnapshot
import io.dataloom.api.configuration.ConfigurationValue
import io.dataloom.api.context.DataLoomMetadata
import io.dataloom.api.context.ExecutionContext
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DataLoomDigestCalculator
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.api.time.DataLoomMonotonicClock
import io.dataloom.api.time.DataLoomMonotonicReading

/**
 * Deterministic, non-cryptographic [DataLoomDigestCalculator] test fake,
 * shared across this package's tests — needed only to build
 * [ConfigurationSnapshot] instances for [PolicyEvaluationInput]. This module
 * (dataloom-api) has no platform-specific source set to host the real
 * System/AppleDataLoomDigestCalculator implementations (those live in
 * dataloom-model). Same posture as
 * io.dataloom.api.configuration.FakeDataLoomDigestCalculator from this
 * module's own configuration-package tests.
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

/**
 * Deterministic [DataLoomMonotonicClock] test fake that returns a
 * pre-programmed sequence of readings, one per [mark] call, so tests can
 * control elapsed-time measurements exactly without a real timer.
 *
 * @param readingsNanoseconds the nanosecond value [mark] returns on each
 *   successive call, in order. The last value repeats once exhausted.
 */
internal class ScriptedDataLoomMonotonicClock(
    private val readingsNanoseconds: List<Long>,
) : DataLoomMonotonicClock {
    private var callIndex = 0

    override fun mark(): DataLoomMonotonicReading {
        val index = callIndex.coerceAtMost(readingsNanoseconds.size - 1)
        callIndex++
        return DataLoomMonotonicReading(readingsNanoseconds[index])
    }
}

/**
 * [PolicyCheck] test fake that always returns a fixed, pre-supplied
 * [PolicyCheckOutcome], recording every [PolicyEvaluationInput] it was
 * called with.
 */
internal class StubPolicyCheck(
    override val id: PolicyCheckId,
    private val outcome: PolicyCheckOutcome,
) : PolicyCheck {
    val receivedInputs: MutableList<PolicyEvaluationInput> = mutableListOf()

    override fun evaluate(input: PolicyEvaluationInput): PolicyCheckOutcome {
        receivedInputs += input
        return outcome
    }
}

/** Minimal, valid [ExecutionContext] for tests that don't care about its fields. */
internal fun testExecutionContext(): ExecutionContext = ExecutionContext(
    executionId = ExecutionId("execution-001"),
    correlationId = CorrelationId("correlation-001"),
)

/**
 * Minimal, schema-valid, empty [ConfigurationSnapshot] for tests that don't
 * need to exercise [PolicyConfigurationKeys.DEFER_DOMINATES_REQUIRE_USER_ACTION].
 */
internal fun testConfigurationSnapshot(
    deferDominatesRequireUserAction: Boolean? = null,
): ConfigurationSnapshot {
    val entries = if (deferDominatesRequireUserAction == null) {
        emptyMap()
    } else {
        mapOf(
            PolicyConfigurationKeys.DEFER_DOMINATES_REQUIRE_USER_ACTION to
                ConfigurationValue.BooleanValue(deferDominatesRequireUserAction),
        )
    }
    return ConfigurationSnapshot.create(
        version = 1L,
        entries = entries,
        digestCalculator = FakeDataLoomDigestCalculator(),
    )
}

/** Minimal [PolicyEvaluationInput] for tests that don't need custom evidence/health. */
internal fun testPolicyInput(
    deferDominatesRequireUserAction: Boolean? = null,
): PolicyEvaluationInput = PolicyEvaluationInput(
    executionContext = testExecutionContext(),
    configurationSnapshot = testConfigurationSnapshot(deferDominatesRequireUserAction),
    stateEvidence = DataLoomMetadata.Empty,
)
