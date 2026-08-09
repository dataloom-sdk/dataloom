package io.dataloom.api.configuration

import io.dataloom.api.security.DataLoomDigestCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Verifies [DataLoomConfigurationHistory]'s monotonic-versioning, bounded
 * retention, and rollback behavior.
 */
class DataLoomConfigurationHistoryTest {

    private val digestCalculator: DataLoomDigestCalculator = FakeDataLoomDigestCalculator()

    @Test
    fun currentIsNullBeforeAnySuccessfulApply() {
        assertNull(DataLoomConfigurationHistory().current)
    }

    @Test
    fun firstApplySucceedsRegardlessOfVersion() {
        val history = DataLoomConfigurationHistory()
        val outcome = history.apply(snapshot(version = 5L))
        assertIs<ConfigurationApplyOutcome.Applied>(outcome)
        assertEquals(5L, history.current?.version)
    }

    @Test
    fun applyingAHigherVersionSucceedsAndBecomesCurrent() {
        val history = DataLoomConfigurationHistory()
        history.apply(snapshot(version = 1L))
        history.apply(snapshot(version = 2L))
        assertEquals(2L, history.current?.version)
    }

    @Test
    fun applyingAnEqualVersionIsRejected() {
        val history = DataLoomConfigurationHistory()
        history.apply(snapshot(version = 3L))
        val outcome = history.apply(snapshot(version = 3L))
        val rejected = assertIs<ConfigurationApplyOutcome.VersionNotMonotonic>(outcome)
        assertEquals(3L, rejected.currentVersion)
        assertEquals(3L, history.current?.version)
    }

    @Test
    fun applyingALowerVersionIsRejected() {
        val history = DataLoomConfigurationHistory()
        history.apply(snapshot(version = 5L))
        val outcome = history.apply(snapshot(version = 4L))
        assertIs<ConfigurationApplyOutcome.VersionNotMonotonic>(outcome)
        assertEquals(5L, history.current?.version)
    }

    @Test
    fun rollbackWithFewerThanTwoRetainedSnapshotsReturnsNull() {
        val history = DataLoomConfigurationHistory()
        assertNull(history.rollbackToLastKnownGood())
        history.apply(snapshot(version = 1L))
        assertNull(history.rollbackToLastKnownGood())
    }

    @Test
    fun rollbackRestoresThePreviouslyAppliedSnapshot() {
        val history = DataLoomConfigurationHistory()
        history.apply(snapshot(version = 1L))
        history.apply(snapshot(version = 2L))
        val restored = history.rollbackToLastKnownGood()
        assertEquals(1L, restored?.version)
        assertEquals(1L, history.current?.version)
    }

    @Test
    fun reapplyingTheRolledBackVersionSucceedsBecauseItExceedsTheRestoredCurrentVersion() {
        val history = DataLoomConfigurationHistory()
        history.apply(snapshot(version = 1L))
        history.apply(snapshot(version = 2L))
        history.rollbackToLastKnownGood() // current becomes version 1 again
        val outcome = history.apply(snapshot(version = 2L))
        assertIs<ConfigurationApplyOutcome.Applied>(outcome)
        assertEquals(2L, history.current?.version)
    }

    @Test
    fun applyingAVersionNotExceedingTheRestoredCurrentVersionIsStillRejectedAfterRollback() {
        val history = DataLoomConfigurationHistory()
        history.apply(snapshot(version = 1L))
        history.apply(snapshot(version = 2L))
        history.rollbackToLastKnownGood() // current becomes version 1 again
        val outcome = history.apply(snapshot(version = 1L))
        assertIs<ConfigurationApplyOutcome.VersionNotMonotonic>(outcome)
    }

    @Test
    fun retentionIsBoundedByMaxRetainedVersions() {
        val history = DataLoomConfigurationHistory(maxRetainedVersions = 2)
        history.apply(snapshot(version = 1L))
        history.apply(snapshot(version = 2L))
        history.apply(snapshot(version = 3L))
        assertEquals(listOf(2L, 3L), history.retainedVersions)
    }

    @Test
    fun rollbackIsUnavailableOnceTheOlderSnapshotIsEvictedByRetentionBound() {
        val history = DataLoomConfigurationHistory(maxRetainedVersions = 1)
        history.apply(snapshot(version = 1L))
        history.apply(snapshot(version = 2L))
        // maxRetainedVersions = 1 means only the current snapshot survives.
        assertNull(history.rollbackToLastKnownGood())
    }

    @Test
    fun maxRetainedVersionsBelowOneIsRejected() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            DataLoomConfigurationHistory(maxRetainedVersions = 0)
        }
    }

    private fun snapshot(version: Long): ConfigurationSnapshot =
        ConfigurationSnapshot.create(
            version,
            mapOf(ConfigurationKey("k") to ConfigurationValue.LongValue(version)),
            digestCalculator,
        )
}
