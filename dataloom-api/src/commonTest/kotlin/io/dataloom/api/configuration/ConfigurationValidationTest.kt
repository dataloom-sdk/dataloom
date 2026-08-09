package io.dataloom.api.configuration

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationValidationTest {

    @Test
    fun emptyFindingsIsValid() {
        assertTrue(ConfigurationValidationResult(emptyList()).isValid)
    }

    @Test
    fun onlyWarningFindingsIsStillValid() {
        val result = ConfigurationValidationResult(
            listOf(
                ConfigurationValidationFinding(
                    ConfigurationFindingSeverity.WARNING,
                    ConfigurationKey("k"),
                    "just a warning",
                ),
            ),
        )
        assertTrue(result.isValid)
    }

    @Test
    fun anyErrorFindingMakesTheResultInvalid() {
        val result = ConfigurationValidationResult(
            listOf(
                ConfigurationValidationFinding(
                    ConfigurationFindingSeverity.WARNING,
                    ConfigurationKey("a"),
                    "warning",
                ),
                ConfigurationValidationFinding(
                    ConfigurationFindingSeverity.ERROR,
                    ConfigurationKey("b"),
                    "error",
                ),
            ),
        )
        assertFalse(result.isValid)
    }

    @Test
    fun findingKeyMayBeNullForNonKeySpecificFindings() {
        val finding = ConfigurationValidationFinding(ConfigurationFindingSeverity.ERROR, null, "no sources supplied")
        assertTrue(finding.key == null)
    }
}
