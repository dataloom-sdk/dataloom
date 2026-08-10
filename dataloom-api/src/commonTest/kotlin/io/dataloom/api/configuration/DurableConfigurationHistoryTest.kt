package io.dataloom.api.configuration

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataLoomDigestCalculator
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Verifies [DurableConfigurationHistory]'s monotonic-versioning, bounded
 * retention, and rollback behavior stay identical to
 * [DataLoomConfigurationHistory]'s once persisted through a
 * [DurableStateStore], plus the compare-and-set retry/failure paths that
 * only exist because persistence can fail or race.
 */
class DurableConfigurationHistoryTest {

    private val digestCalculator: DataLoomDigestCalculator = FakeDataLoomDigestCalculator()
    private val scope = ConfigurationHistoryScope("app")

    @Test
    fun currentIsNullBeforeAnySuccessfulApply() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore())
        val result = assertIs<ProviderOperationResult.Success<ConfigurationSnapshot?>>(history.current(scope))
        assertNull(result.value)
    }

    @Test
    fun firstApplySucceedsRegardlessOfVersion() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore())
        val outcome = history.apply(scope, snapshot(version = 5L))
        assertIs<DurableConfigurationApplyOutcome.Applied>(outcome)
        assertEquals(5L, currentVersion(history))
    }

    @Test
    fun applyingAHigherVersionSucceedsAndBecomesCurrent() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore())
        history.apply(scope, snapshot(version = 1L))
        history.apply(scope, snapshot(version = 2L))
        assertEquals(2L, currentVersion(history))
    }

    @Test
    fun applyingAnEqualVersionIsRejected() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore())
        history.apply(scope, snapshot(version = 3L))
        val outcome = history.apply(scope, snapshot(version = 3L))
        val rejected = assertIs<DurableConfigurationApplyOutcome.VersionNotMonotonic>(outcome)
        assertEquals(3L, rejected.currentVersion)
        assertEquals(3L, currentVersion(history))
    }

    @Test
    fun applyingALowerVersionIsRejected() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore())
        history.apply(scope, snapshot(version = 5L))
        val outcome = history.apply(scope, snapshot(version = 4L))
        assertIs<DurableConfigurationApplyOutcome.VersionNotMonotonic>(outcome)
        assertEquals(5L, currentVersion(history))
    }

    @Test
    fun rollbackWithFewerThanTwoRetainedSnapshotsReturnsNoEarlierSnapshot() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore())
        assertIs<DurableConfigurationRollbackOutcome.NoEarlierSnapshot>(history.rollbackToLastKnownGood(scope))
        history.apply(scope, snapshot(version = 1L))
        assertIs<DurableConfigurationRollbackOutcome.NoEarlierSnapshot>(history.rollbackToLastKnownGood(scope))
    }

    @Test
    fun rollbackRestoresThePreviouslyAppliedSnapshot() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore())
        history.apply(scope, snapshot(version = 1L))
        history.apply(scope, snapshot(version = 2L))
        val restored = assertIs<DurableConfigurationRollbackOutcome.RolledBack>(history.rollbackToLastKnownGood(scope))
        assertEquals(1L, restored.snapshot.version)
        assertEquals(1L, currentVersion(history))
    }

    @Test
    fun reapplyingTheRolledBackVersionSucceedsBecauseItExceedsTheRestoredCurrentVersion() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore())
        history.apply(scope, snapshot(version = 1L))
        history.apply(scope, snapshot(version = 2L))
        history.rollbackToLastKnownGood(scope) // current becomes version 1 again
        val outcome = history.apply(scope, snapshot(version = 2L))
        assertIs<DurableConfigurationApplyOutcome.Applied>(outcome)
        assertEquals(2L, currentVersion(history))
    }

    @Test
    fun applyingAVersionNotExceedingTheRestoredCurrentVersionIsStillRejectedAfterRollback() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore())
        history.apply(scope, snapshot(version = 1L))
        history.apply(scope, snapshot(version = 2L))
        history.rollbackToLastKnownGood(scope) // current becomes version 1 again
        val outcome = history.apply(scope, snapshot(version = 1L))
        assertIs<DurableConfigurationApplyOutcome.VersionNotMonotonic>(outcome)
    }

    @Test
    fun retentionIsBoundedByMaxRetainedVersions() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore(), maxRetainedVersions = 2)
        history.apply(scope, snapshot(version = 1L))
        history.apply(scope, snapshot(version = 2L))
        history.apply(scope, snapshot(version = 3L))
        val versions = assertIs<ProviderOperationResult.Success<List<Long>>>(history.retainedVersions(scope))
        assertEquals(listOf(2L, 3L), versions.value)
    }

    @Test
    fun rollbackIsUnavailableOnceTheOlderSnapshotIsEvictedByRetentionBound() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore(), maxRetainedVersions = 1)
        history.apply(scope, snapshot(version = 1L))
        history.apply(scope, snapshot(version = 2L))
        // maxRetainedVersions = 1 means only the current snapshot survives.
        assertIs<DurableConfigurationRollbackOutcome.NoEarlierSnapshot>(history.rollbackToLastKnownGood(scope))
    }

    @Test
    fun maxRetainedVersionsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore(), maxRetainedVersions = 0)
        }
    }

    @Test
    fun maximumStateUpdateAttemptsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore(), maximumStateUpdateAttempts = 0)
        }
    }

    @Test
    fun distinctScopesAreIndependent() = runTest {
        val history = DurableConfigurationHistory(InMemoryDurableConfigurationHistoryStore())
        val other = ConfigurationHistoryScope("other-app")
        history.apply(scope, snapshot(version = 1L))
        history.apply(other, snapshot(version = 9L))

        assertEquals(1L, currentVersion(history))
        assertEquals(
            9L,
            (
                assertIs<ProviderOperationResult.Success<ConfigurationSnapshot?>>(history.current(other)).value
                )?.version,
        )
    }

    @Test
    fun applyRetriesAfterATransientConflictAndSucceeds() = runTest {
        val store = InMemoryDurableConfigurationHistoryStore()
        val history = DurableConfigurationHistory(store)
        history.apply(scope, snapshot(version = 1L))
        store.conflictOnNextCompareAndSetCalls = 1

        val outcome = history.apply(scope, snapshot(version = 2L))

        assertIs<DurableConfigurationApplyOutcome.Applied>(outcome)
        assertEquals(2L, currentVersion(history))
    }

    @Test
    fun applyReturnsPersistenceFailureWhenLoadFails() = runTest {
        val history = DurableConfigurationHistory(FailingLoadStore())
        val outcome = history.apply(scope, snapshot(version = 1L))
        assertIs<DurableConfigurationApplyOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun applyReturnsPersistenceFailureWhenCompareAndSetFails() = runTest {
        val history = DurableConfigurationHistory(FailingCompareAndSetStore())
        val outcome = history.apply(scope, snapshot(version = 1L))
        assertIs<DurableConfigurationApplyOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun applyReturnsContentionLimitReachedWhenCompareAndSetAlwaysConflicts() = runTest {
        val history = DurableConfigurationHistory(AlwaysConflictStore(), maximumStateUpdateAttempts = 3)
        val outcome = history.apply(scope, snapshot(version = 1L))
        assertIs<DurableConfigurationApplyOutcome.ContentionLimitReached>(outcome)
    }

    @Test
    fun rollbackReturnsPersistenceFailureWhenLoadFails() = runTest {
        val history = DurableConfigurationHistory(FailingLoadStore())
        val outcome = history.rollbackToLastKnownGood(scope)
        assertIs<DurableConfigurationRollbackOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun rollbackReturnsContentionLimitReachedWhenCompareAndSetAlwaysConflicts() = runTest {
        val seeded = InMemoryDurableConfigurationHistoryStore()
        DurableConfigurationHistory(seeded).apply {
            apply(scope, snapshot(version = 1L))
            apply(scope, snapshot(version = 2L))
        }
        val store = AlwaysConflictAfterSeedStore(seeded)
        val history = DurableConfigurationHistory(store, maximumStateUpdateAttempts = 3)

        val outcome = history.rollbackToLastKnownGood(scope)

        assertIs<DurableConfigurationRollbackOutcome.ContentionLimitReached>(outcome)
    }

    private suspend fun currentVersion(history: DurableConfigurationHistory): Long? =
        (assertIs<ProviderOperationResult.Success<ConfigurationSnapshot?>>(history.current(scope))).value?.version

    private fun snapshot(version: Long): ConfigurationSnapshot =
        ConfigurationSnapshot.create(
            version,
            mapOf(ConfigurationKey("k") to ConfigurationValue.LongValue(version)),
            digestCalculator,
        )

    /**
     * Minimal, non-thread-safe in-memory [DurableStateStore] fake used only to
     * prove [DurableConfigurationHistory] behaves as documented. Not a
     * production reference implementation — see `RoomDurableStateStore` in
     * `dataloom-queue-room` for one.
     */
    private class InMemoryDurableConfigurationHistoryStore :
        DurableStateStore<ConfigurationHistoryScope, ConfigurationHistoryState> {
        private val records = mutableMapOf<ConfigurationHistoryScope, DurableStateRecord<ConfigurationHistoryState>>()

        /** When positive, the next N compare-and-set calls report a conflict instead of applying. */
        var conflictOnNextCompareAndSetCalls: Int = 0

        override suspend fun load(
            scope: ConfigurationHistoryScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConfigurationHistoryState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConfigurationHistoryScope, ConfigurationHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConfigurationHistoryState>> {
            val current = records[request.scope]
            if (conflictOnNextCompareAndSetCalls > 0) {
                conflictOnNextCompareAndSetCalls -= 1
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            if (current?.version != request.expectedVersion) {
                return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(current))
            }
            val updated = DurableStateRecord(
                state = request.nextState,
                version = (current?.version ?: -1L) + 1L,
                schemaVersion = request.nextSchemaVersion,
            )
            records[request.scope] = updated
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Updated(updated))
        }
    }

    private class FailingLoadStore : DurableStateStore<ConfigurationHistoryScope, ConfigurationHistoryState> {
        override suspend fun load(
            scope: ConfigurationHistoryScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConfigurationHistoryState>> =
            ProviderOperationResult.Failure(testError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConfigurationHistoryScope, ConfigurationHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConfigurationHistoryState>> =
            error("must not be called when load already failed")
    }

    private class FailingCompareAndSetStore : DurableStateStore<ConfigurationHistoryScope, ConfigurationHistoryState> {
        override suspend fun load(
            scope: ConfigurationHistoryScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConfigurationHistoryState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConfigurationHistoryScope, ConfigurationHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConfigurationHistoryState>> =
            ProviderOperationResult.Failure(testError())
    }

    private class AlwaysConflictStore : DurableStateStore<ConfigurationHistoryScope, ConfigurationHistoryState> {
        override suspend fun load(
            scope: ConfigurationHistoryScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConfigurationHistoryState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConfigurationHistoryScope, ConfigurationHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConfigurationHistoryState>> =
            ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(null))
    }

    private class AlwaysConflictAfterSeedStore(
        private val delegate: InMemoryDurableConfigurationHistoryStore,
    ) : DurableStateStore<ConfigurationHistoryScope, ConfigurationHistoryState> {
        override suspend fun load(
            scope: ConfigurationHistoryScope,
        ): ProviderOperationResult<DurableStateLoadResult<ConfigurationHistoryState>> = delegate.load(scope)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<ConfigurationHistoryScope, ConfigurationHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<ConfigurationHistoryState>> {
            val current = (delegate.load(request.scope) as ProviderOperationResult.Success).value
            val record = (current as? DurableStateLoadResult.Found)?.record
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(record))
        }
    }

}

private fun testError(): DataLoomError = DurableConfigurationHistoryTestError(
    code = ErrorCode("DURABLE_CONFIGURATION_HISTORY_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurableConfigurationHistoryTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
