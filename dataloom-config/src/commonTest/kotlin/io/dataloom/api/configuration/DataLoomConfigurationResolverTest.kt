package io.dataloom.api.configuration

import io.dataloom.api.security.DataLoomDigestCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies [DataLoomConfigurationResolver]'s precedence, validation, and
 * admission behavior.
 *
 * Uses a deterministic, non-cryptographic [FakeDataLoomDigestCalculator]
 * test stub — see [ConfigurationSnapshotTest] for why.
 */
class DataLoomConfigurationResolverTest {

    private val digestCalculator: DataLoomDigestCalculator = FakeDataLoomDigestCalculator()

    private val timeoutKey = ConfigurationKey("timeout-ms")
    private val featureKey = ConfigurationKey("feature-enabled")
    private val optionalKey = ConfigurationKey("optional-label")

    private val schema = ConfigurationSchema(
        listOf(
            ConfigurationEntrySchema(timeoutKey, ConfigurationValueType.LONG),
            ConfigurationEntrySchema(featureKey, ConfigurationValueType.BOOLEAN),
            ConfigurationEntrySchema(optionalKey, ConfigurationValueType.STRING, required = false),
        ),
    )

    private val resolver = DataLoomConfigurationResolver(schema, digestCalculator)

    @Test
    fun localOverrideWinsOverRemoteAssignedForTheSameKey() {
        val sources = listOf(
            ConfigurationSource(
                ConfigurationScope.REMOTE_ASSIGNED,
                mapOf(timeoutKey to ConfigurationValue.LongValue(5_000L), featureKey to ConfigurationValue.BooleanValue(false)),
            ),
            ConfigurationSource(
                ConfigurationScope.LOCAL_OVERRIDE,
                mapOf(timeoutKey to ConfigurationValue.LongValue(9_000L)),
            ),
        )
        val resolution = assertIs<ConfigurationResolution.Admitted>(resolver.resolve(sources, version = 1L))
        assertEquals(ConfigurationValue.LongValue(9_000L), resolution.snapshot[timeoutKey])
        assertEquals(ConfigurationValue.BooleanValue(false), resolution.snapshot[featureKey])
    }

    @Test
    fun remoteAssignedWinsOverBuiltInDefaultForTheSameKey() {
        val sources = listOf(
            ConfigurationSource(
                ConfigurationScope.BUILT_IN_DEFAULT,
                mapOf(timeoutKey to ConfigurationValue.LongValue(1_000L), featureKey to ConfigurationValue.BooleanValue(false)),
            ),
            ConfigurationSource(
                ConfigurationScope.REMOTE_ASSIGNED,
                mapOf(timeoutKey to ConfigurationValue.LongValue(2_000L)),
            ),
        )
        val resolution = assertIs<ConfigurationResolution.Admitted>(resolver.resolve(sources, version = 1L))
        assertEquals(ConfigurationValue.LongValue(2_000L), resolution.snapshot[timeoutKey])
    }

    @Test
    fun sourceListOrderDoesNotAffectPrecedenceOnlyScopeDoes() {
        val defaults = ConfigurationSource(
            ConfigurationScope.BUILT_IN_DEFAULT,
            mapOf(timeoutKey to ConfigurationValue.LongValue(1_000L), featureKey to ConfigurationValue.BooleanValue(false)),
        )
        val override = ConfigurationSource(
            ConfigurationScope.LOCAL_OVERRIDE,
            mapOf(timeoutKey to ConfigurationValue.LongValue(9_000L)),
        )
        val forward = assertIs<ConfigurationResolution.Admitted>(
            resolver.resolve(listOf(defaults, override), version = 1L),
        )
        val reversed = assertIs<ConfigurationResolution.Admitted>(
            resolver.resolve(listOf(override, defaults), version = 1L),
        )
        assertEquals(forward.snapshot[timeoutKey], reversed.snapshot[timeoutKey])
    }

    @Test
    fun unknownKeyIsRejectedWithAnErrorFinding() {
        val sources = listOf(
            fullyValidDefaultsSource(),
            ConfigurationSource(
                ConfigurationScope.LOCAL_OVERRIDE,
                mapOf(ConfigurationKey("not-in-schema") to ConfigurationValue.StringValue("x")),
            ),
        )
        val resolution = assertIs<ConfigurationResolution.Rejected>(resolver.resolve(sources, version = 1L))
        assertTrue(
            resolution.findings.any {
                it.severity == ConfigurationFindingSeverity.ERROR && it.key == ConfigurationKey("not-in-schema")
            },
        )
    }

    @Test
    fun typeMismatchIsRejectedWithAnErrorFinding() {
        val sources = listOf(
            fullyValidDefaultsSource(),
            ConfigurationSource(
                ConfigurationScope.LOCAL_OVERRIDE,
                mapOf(timeoutKey to ConfigurationValue.StringValue("not-a-long")),
            ),
        )
        val resolution = assertIs<ConfigurationResolution.Rejected>(resolver.resolve(sources, version = 1L))
        assertTrue(resolution.findings.any { it.severity == ConfigurationFindingSeverity.ERROR && it.key == timeoutKey })
    }

    @Test
    fun missingRequiredKeyIsRejectedWithAnErrorFinding() {
        val sources = listOf(
            ConfigurationSource(ConfigurationScope.BUILT_IN_DEFAULT, mapOf(timeoutKey to ConfigurationValue.LongValue(1_000L))),
        )
        val resolution = assertIs<ConfigurationResolution.Rejected>(resolver.resolve(sources, version = 1L))
        assertTrue(
            resolution.findings.any {
                it.severity == ConfigurationFindingSeverity.ERROR && it.key == featureKey
            },
        )
    }

    @Test
    fun missingOptionalKeyIsNotAFinding() {
        val resolution = assertIs<ConfigurationResolution.Admitted>(
            resolver.resolve(listOf(fullyValidDefaultsSource()), version = 1L),
        )
        assertFalse(resolution.snapshot.keys.contains(optionalKey))
        assertTrue(resolution.findings.none { it.key == optionalKey })
    }

    @Test
    fun conflictingEntriesAtTheSameScopeProduceAWarningButAreStillAdmitted() {
        val sources = listOf(
            fullyValidDefaultsSource(),
            ConfigurationSource(ConfigurationScope.LOCAL_OVERRIDE, mapOf(timeoutKey to ConfigurationValue.LongValue(5L))),
            ConfigurationSource(ConfigurationScope.LOCAL_OVERRIDE, mapOf(timeoutKey to ConfigurationValue.LongValue(6L))),
        )
        val resolution = assertIs<ConfigurationResolution.Admitted>(resolver.resolve(sources, version = 1L))
        assertTrue(
            resolution.findings.any {
                it.severity == ConfigurationFindingSeverity.WARNING && it.key == timeoutKey
            },
        )
    }

    @Test
    fun validationIsExhaustiveNotFailFast() {
        val sources = listOf(
            ConfigurationSource(
                ConfigurationScope.LOCAL_OVERRIDE,
                mapOf(
                    ConfigurationKey("unknown-one") to ConfigurationValue.StringValue("x"),
                    timeoutKey to ConfigurationValue.StringValue("wrong-type"),
                ),
            ),
        )
        val resolution = assertIs<ConfigurationResolution.Rejected>(resolver.resolve(sources, version = 1L))
        // Unknown key, type mismatch, AND the still-missing required featureKey.
        assertEquals(3, resolution.findings.count { it.severity == ConfigurationFindingSeverity.ERROR })
    }

    @Test
    fun admittedSnapshotCarriesTheRequestedVersion() {
        val resolution = assertIs<ConfigurationResolution.Admitted>(
            resolver.resolve(listOf(fullyValidDefaultsSource()), version = 42L),
        )
        assertEquals(42L, resolution.snapshot.version)
    }

    private fun fullyValidDefaultsSource(): ConfigurationSource = ConfigurationSource(
        ConfigurationScope.BUILT_IN_DEFAULT,
        mapOf(
            timeoutKey to ConfigurationValue.LongValue(1_000L),
            featureKey to ConfigurationValue.BooleanValue(false),
        ),
    )
}
