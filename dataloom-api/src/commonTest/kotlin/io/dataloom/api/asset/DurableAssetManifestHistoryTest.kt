package io.dataloom.api.asset

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataLoomDigest
import io.dataloom.api.security.DigestAlgorithm
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
 * Verifies [DurableAssetManifestHistory]'s monotonic-versioning, bounded
 * retention, asset-identity consistency, and compare-and-set retry/failure
 * paths — the same shape [io.dataloom.api.configuration.DurableConfigurationHistoryTest]
 * already proves for [io.dataloom.api.configuration.DurableConfigurationHistory],
 * adapted for [AssetManifest].
 */
class DurableAssetManifestHistoryTest {

    private val assetId = AssetId("asset-001")

    @Test
    fun keyEncoderEncodesEqualScopesIdenticallyAndDistinctScopesDifferently() {
        val encoder = DurableAssetManifestHistory.KeyEncoder
        assertEquals(encoder.encode(AssetId("asset-001")), encoder.encode(AssetId("asset-001")))
        assertEquals("asset-001", encoder.encode(AssetId("asset-001")))
        assertEquals(
            encoder.encode(AssetId("asset-1")) != encoder.encode(AssetId("asset-2")),
            true,
        )
    }

    @Test
    fun currentIsNullBeforeAnySuccessfulApply() = runTest {
        val history = DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore())
        val result = assertIs<ProviderOperationResult.Success<AssetManifest?>>(history.current(assetId))
        assertNull(result.value)
    }

    @Test
    fun firstApplySucceedsRegardlessOfVersion() = runTest {
        val history = DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore())
        val outcome = history.apply(assetId, manifest(version = 5L))
        assertIs<DurableAssetManifestApplyOutcome.Applied>(outcome)
        assertEquals(5L, currentVersion(history))
    }

    @Test
    fun applyingAHigherVersionSucceedsAndBecomesCurrent() = runTest {
        val history = DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore())
        history.apply(assetId, manifest(version = 1L))
        history.apply(assetId, manifest(version = 2L))
        assertEquals(2L, currentVersion(history))
    }

    @Test
    fun applyingAnEqualVersionIsRejected() = runTest {
        val history = DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore())
        history.apply(assetId, manifest(version = 3L))
        val outcome = history.apply(assetId, manifest(version = 3L))
        val rejected = assertIs<DurableAssetManifestApplyOutcome.VersionNotMonotonic>(outcome)
        assertEquals(3L, rejected.currentVersion)
        assertEquals(3L, currentVersion(history))
    }

    @Test
    fun applyingALowerVersionIsRejected() = runTest {
        val history = DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore())
        history.apply(assetId, manifest(version = 5L))
        val outcome = history.apply(assetId, manifest(version = 4L))
        assertIs<DurableAssetManifestApplyOutcome.VersionNotMonotonic>(outcome)
        assertEquals(5L, currentVersion(history))
    }

    @Test
    fun applyingAManifestWithADifferentAssetIdIsRejectedWithoutTouchingTheStore() = runTest {
        val history = DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore())
        val wrongManifest = manifest(version = 1L, assetId = AssetId("some-other-asset"))
        val outcome = history.apply(assetId, wrongManifest)
        val mismatch = assertIs<DurableAssetManifestApplyOutcome.AssetIdMismatch>(outcome)
        assertEquals(assetId, mismatch.scope)
        assertEquals(wrongManifest, mismatch.manifest)
        assertNull(currentVersion(history))
    }

    @Test
    fun retentionIsBoundedByMaxRetainedVersions() = runTest {
        val history = DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore(), maxRetainedVersions = 2)
        history.apply(assetId, manifest(version = 1L))
        history.apply(assetId, manifest(version = 2L))
        history.apply(assetId, manifest(version = 3L))
        val versions = assertIs<ProviderOperationResult.Success<List<Long>>>(history.retainedVersions(assetId))
        assertEquals(listOf(2L, 3L), versions.value)
    }

    @Test
    fun historyReturnsEveryRetainedManifestOldestFirst() = runTest {
        val history = DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore(), maxRetainedVersions = 3)
        val first = manifest(version = 1L)
        val second = manifest(version = 2L)
        history.apply(assetId, first)
        history.apply(assetId, second)

        val retrieved = assertIs<ProviderOperationResult.Success<List<AssetManifest>>>(history.history(assetId))

        assertEquals(listOf(first, second), retrieved.value)
    }

    @Test
    fun historyIsEmptyBeforeAnySuccessfulApply() = runTest {
        val history = DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore())
        val retrieved = assertIs<ProviderOperationResult.Success<List<AssetManifest>>>(history.history(assetId))
        assertEquals(emptyList(), retrieved.value)
    }

    @Test
    fun maxRetainedVersionsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore(), maxRetainedVersions = 0)
        }
    }

    @Test
    fun maximumStateUpdateAttemptsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore(), maximumStateUpdateAttempts = 0)
        }
    }

    @Test
    fun distinctScopesAreIndependent() = runTest {
        val history = DurableAssetManifestHistory(InMemoryDurableAssetManifestHistoryStore())
        val other = AssetId("other-asset")
        history.apply(assetId, manifest(version = 1L))
        history.apply(other, manifest(version = 9L, assetId = other))

        assertEquals(1L, currentVersion(history))
        assertEquals(
            9L,
            (assertIs<ProviderOperationResult.Success<AssetManifest?>>(history.current(other))).value?.version,
        )
    }

    @Test
    fun applyRetriesAfterATransientConflictAndSucceeds() = runTest {
        val store = InMemoryDurableAssetManifestHistoryStore()
        val history = DurableAssetManifestHistory(store)
        history.apply(assetId, manifest(version = 1L))
        store.conflictOnNextCompareAndSetCalls = 1

        val outcome = history.apply(assetId, manifest(version = 2L))

        assertIs<DurableAssetManifestApplyOutcome.Applied>(outcome)
        assertEquals(2L, currentVersion(history))
    }

    @Test
    fun applyReturnsPersistenceFailureWhenLoadFails() = runTest {
        val history = DurableAssetManifestHistory(FailingLoadStore())
        val outcome = history.apply(assetId, manifest(version = 1L))
        assertIs<DurableAssetManifestApplyOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun applyReturnsPersistenceFailureWhenCompareAndSetFails() = runTest {
        val history = DurableAssetManifestHistory(FailingCompareAndSetStore())
        val outcome = history.apply(assetId, manifest(version = 1L))
        assertIs<DurableAssetManifestApplyOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun applyReturnsContentionLimitReachedWhenCompareAndSetAlwaysConflicts() = runTest {
        val history = DurableAssetManifestHistory(AlwaysConflictStore(), maximumStateUpdateAttempts = 3)
        val outcome = history.apply(assetId, manifest(version = 1L))
        assertIs<DurableAssetManifestApplyOutcome.ContentionLimitReached>(outcome)
    }

    private suspend fun currentVersion(history: DurableAssetManifestHistory): Long? =
        (assertIs<ProviderOperationResult.Success<AssetManifest?>>(history.current(assetId))).value?.version

    private fun digestOf(seed: Int): DataLoomDigest =
        DataLoomDigest(DigestAlgorithm.SHA_256, ByteArray(32) { (it + seed).toByte() })

    private fun manifest(version: Long, assetId: AssetId = this.assetId): AssetManifest =
        AssetManifest(
            assetId = assetId,
            version = version,
            sizeBytes = 10L,
            mediaType = AssetMediaType("application/octet-stream"),
            checksum = digestOf(version.toInt()),
            chunkLayout = AssetChunkLayout(
                listOf(AssetChunkDescriptor(0, 0L, 10L, digestOf(version.toInt() + 100))),
            ),
        )

    /**
     * Minimal, non-thread-safe in-memory [DurableStateStore] fake used only to
     * prove [DurableAssetManifestHistory] behaves as documented. Not a
     * production reference implementation — see `RoomDurableStateStore` in
     * `dataloom-queue-room` for one.
     */
    private class InMemoryDurableAssetManifestHistoryStore : DurableStateStore<AssetId, AssetManifestHistoryState> {
        private val records = mutableMapOf<AssetId, DurableStateRecord<AssetManifestHistoryState>>()

        /** When positive, the next N compare-and-set calls report a conflict instead of applying. */
        var conflictOnNextCompareAndSetCalls: Int = 0

        override suspend fun load(
            scope: AssetId,
        ): ProviderOperationResult<DurableStateLoadResult<AssetManifestHistoryState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<AssetId, AssetManifestHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<AssetManifestHistoryState>> {
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

    private class FailingLoadStore : DurableStateStore<AssetId, AssetManifestHistoryState> {
        override suspend fun load(
            scope: AssetId,
        ): ProviderOperationResult<DurableStateLoadResult<AssetManifestHistoryState>> =
            ProviderOperationResult.Failure(testError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<AssetId, AssetManifestHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<AssetManifestHistoryState>> =
            error("must not be called when load already failed")
    }

    private class FailingCompareAndSetStore : DurableStateStore<AssetId, AssetManifestHistoryState> {
        override suspend fun load(
            scope: AssetId,
        ): ProviderOperationResult<DurableStateLoadResult<AssetManifestHistoryState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<AssetId, AssetManifestHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<AssetManifestHistoryState>> =
            ProviderOperationResult.Failure(testError())
    }

    private class AlwaysConflictStore : DurableStateStore<AssetId, AssetManifestHistoryState> {
        override suspend fun load(
            scope: AssetId,
        ): ProviderOperationResult<DurableStateLoadResult<AssetManifestHistoryState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<AssetId, AssetManifestHistoryState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<AssetManifestHistoryState>> =
            ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(null))
    }
}

private fun testError(): DataLoomError = DurableAssetManifestHistoryTestError(
    code = ErrorCode("DURABLE_ASSET_MANIFEST_HISTORY_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurableAssetManifestHistoryTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
