package io.dataloom.api.policy

import io.dataloom.api.context.DataLoomMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicyEvaluationInputTest {

    @Test
    fun providerHealthDefaultsToEmpty() {
        val input = PolicyEvaluationInput(
            executionContext = testExecutionContext(),
            configurationSnapshot = testConfigurationSnapshot(),
        )
        assertTrue(input.providerHealth.isEmpty())
    }

    @Test
    fun stateEvidenceDefaultsToEmptyMetadata() {
        val input = PolicyEvaluationInput(
            executionContext = testExecutionContext(),
            configurationSnapshot = testConfigurationSnapshot(),
        )
        assertEquals(DataLoomMetadata.Empty, input.stateEvidence)
    }

    @Test
    fun equalInputsCompareAsEqual() {
        val a = testPolicyInput()
        val b = testPolicyInput()
        assertEquals(a, b)
    }
}
