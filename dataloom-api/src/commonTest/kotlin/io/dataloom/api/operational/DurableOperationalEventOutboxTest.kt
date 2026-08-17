package io.dataloom.api.operational

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataClassification
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateRecord
import io.dataloom.api.state.DurableStateStore
import io.dataloom.api.time.DataLoomInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies [DurableOperationalEventOutbox]'s ordered-append, idempotent-retry,
 * and conflict-detection behavior -- the bounded first slice of DL-042's
 * durable-outbox requirement.
 */
class DurableOperationalEventOutboxTest {

    private val scope = OperationalEventOutboxScope("outbox-1")

    @Test
    fun keyEncoderEncodesEqualScopesIdenticallyAndDistinctScopesDifferently() {
        val encoder = OperationalEventOutboxScope.KeyEncoder
        assertEquals(
            encoder.encode(OperationalEventOutboxScope("s1")),
            encoder.encode(OperationalEventOutboxScope("s1")),
        )
        assertEquals("s1", encoder.encode(OperationalEventOutboxScope("s1")))
        assertTrue(
            encoder.encode(OperationalEventOutboxScope("s1")) !=
                encoder.encode(OperationalEventOutboxScope("s2")),
        )
    }

    @Test
    fun scopeRejectsBlankValue() {
        assertFailsWith<IllegalArgumentException> { OperationalEventOutboxScope(" ") }
    }

    @Test
    fun entriesAreEmptyBeforeAnySuccessfulAppend() = runTest {
        val outbox = DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore())
        val result = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(emptyList(), result.value)
    }

    @Test
    fun firstAppendSucceedsAndSurvivesAReloadOfTheSameStore() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val envelope = envelope("event-1")

        val outcome = outbox.append(scope, envelope)

        val appended = assertIs<DurableOperationalEventOutboxAppendOutcome.Appended>(outcome)
        assertEquals(envelope, appended.envelope)

        // A fresh DurableOperationalEventOutbox wrapping the same underlying
        // store simulates a process restart: no in-memory outbox state
        // survives, only what the store itself persisted.
        val reopened = DurableOperationalEventOutbox(store)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(reopened.entries(scope))
        assertEquals(listOf(envelope), entries.value)
    }

    @Test
    fun secondAppendPreservesOrderOldestFirst() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val first = envelope("event-1")
        val second = envelope("event-2")

        outbox.append(scope, first)
        outbox.append(scope, second)

        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(first, second), entries.value)
    }

    @Test
    fun appendingTheSameEnvelopeAgainIsIdempotent() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val envelope = envelope("event-1")
        outbox.append(scope, envelope)

        val outcome = outbox.append(scope, envelope)

        val already = assertIs<DurableOperationalEventOutboxAppendOutcome.AlreadyAppended>(outcome)
        assertEquals(envelope, already.envelope)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(envelope), entries.value)
    }

    @Test
    fun appendingADifferentEnvelopeWithTheSameIdConflicts() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val first = envelope("event-1", correlationId = "correlation-a")
        outbox.append(scope, first)
        val second = envelope("event-1", correlationId = "correlation-b")

        val outcome = outbox.append(scope, second)

        val conflict = assertIs<DurableOperationalEventOutboxAppendOutcome.Conflict>(outcome)
        assertEquals(first, conflict.existing)
        assertEquals(second, conflict.attempted)
        val entries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        assertEquals(listOf(first), entries.value)
    }

    @Test
    fun distinctScopesAreIndependent() = runTest {
        val store = InMemoryOperationalEventOutboxStore()
        val outbox = DurableOperationalEventOutbox(store)
        val other = OperationalEventOutboxScope("outbox-2")
        val a = envelope("event-1")
        val b = envelope("event-2")

        outbox.append(scope, a)
        outbox.append(other, b)

        val scopeEntries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(scope))
        val otherEntries = assertIs<ProviderOperationResult.Success<List<OperationalEventEnvelope>>>(outbox.entries(other))
        assertEquals(listOf(a), scopeEntries.value)
        assertEquals(listOf(b), otherEntries.value)
    }

    @Test
    fun maximumStateUpdateAttemptsBelowOneIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DurableOperationalEventOutbox(InMemoryOperationalEventOutboxStore(), maximumStateUpdateAttempts = 0)
        }
    }

    @Test
    fun appendReturnsPersistenceFailureWhenLoadFails() = runTest {
        val outbox = DurableOperationalEventOutbox(FailingLoadStore())
        val outcome = outbox.append(scope, envelope("event-1"))
        assertIs<DurableOperationalEventOutboxAppendOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun appendReturnsPersistenceFailureWhenCompareAndSetFails() = runTest {
        val outbox = DurableOperationalEventOutbox(FailingCompareAndSetStore())
        val outcome = outbox.append(scope, envelope("event-1"))
        assertIs<DurableOperationalEventOutboxAppendOutcome.PersistenceFailure>(outcome)
    }

    @Test
    fun appendReturnsContentionLimitReachedWhenTheInsertRaceIsAlwaysLost() = runTest {
        val outbox = DurableOperationalEventOutbox(AlwaysConflictStore(), maximumStateUpdateAttempts = 3)
        val outcome = outbox.append(scope, envelope("event-1"))
        assertIs<DurableOperationalEventOutboxAppendOutcome.ContentionLimitReached>(outcome)
    }

    @Test
    fun appendRetriesAfterLosingTheInsertRaceAndReportsAlreadyAppended() = runTest {
        val envelope = envelope("event-1")
        val winningRecord = DurableStateRecord(
            state = OperationalEventOutboxState(listOf(envelope)),
            version = 0L,
            schemaVersion = 1,
        )
        val store = RaceThenConsistentStore(winningRecord)
        val outbox = DurableOperationalEventOutbox(store)

        val outcome = outbox.append(scope, envelope)

        assertIs<DurableOperationalEventOutboxAppendOutcome.AlreadyAppended>(outcome)
    }

    private fun envelope(
        id: String,
        correlationId: String = "correlation-1",
    ): OperationalEventEnvelope = OperationalEventEnvelope(
        id = OperationalEventId(id),
        type = OperationalEventType("dataloom.retry.scheduled"),
        source = OperationalEventSource("dataloom.runtime.retry"),
        category = OperationalEventCategory.TELEMETRY,
        schemaVersion = OperationalSchemaVersion(1),
        occurredAt = DataLoomInstant(1_000L),
        correlationId = CorrelationId(correlationId),
        payload = OperationalPayloadDescriptor(
            type = OperationalPayloadType("dataloom.retry.signal"),
            schemaVersion = OperationalSchemaVersion(1),
            encoding = OperationalPayloadEncoding("application/json"),
            classification = DataClassification.INTERNAL,
        ),
    )

    private class InMemoryOperationalEventOutboxStore :
        DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private val records = mutableMapOf<OperationalEventOutboxScope, DurableStateRecord<OperationalEventOutboxState>>()

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            val current = records[request.scope]
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

    private class FailingLoadStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(testError())

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            error("must not be called when load already failed")
    }

    private class FailingCompareAndSetStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Failure(testError())
    }

    private class AlwaysConflictStore : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Success(DurableStateLoadResult.Missing)

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> =
            ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(null))
    }

    /**
     * Reports [DurableStateLoadResult.Missing] on its first [load], then a
     * losing [DurableStateCompareAndSetResult.Conflict] against
     * [winningRecord] on its first [compareAndSet] -- simulating a
     * concurrent appender's insert landing between this call's load and its
     * own compareAndSet.
     */
    private class RaceThenConsistentStore(
        private val winningRecord: DurableStateRecord<OperationalEventOutboxState>,
    ) : DurableStateStore<OperationalEventOutboxScope, OperationalEventOutboxState> {
        private var loadCalls = 0
        private var compareAndSetCalls = 0

        override suspend fun load(
            scope: OperationalEventOutboxScope,
        ): ProviderOperationResult<DurableStateLoadResult<OperationalEventOutboxState>> {
            loadCalls += 1
            return ProviderOperationResult.Success(
                if (loadCalls == 1) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(winningRecord),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<OperationalEventOutboxScope, OperationalEventOutboxState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<OperationalEventOutboxState>> {
            compareAndSetCalls += 1
            check(compareAndSetCalls == 1) { "must not be called again once load() reports Found" }
            return ProviderOperationResult.Success(DurableStateCompareAndSetResult.Conflict(winningRecord))
        }
    }
}

private fun testError(): DataLoomError = DurableOperationalEventOutboxTestError(
    code = ErrorCode("DURABLE_OPERATIONAL_EVENT_OUTBOX_TEST_FAILURE"),
    category = ErrorCategory.STORAGE,
    severity = ErrorSeverity.ERROR,
    recoverability = Recoverability.RECOVERABLE,
    message = "Simulated store failure.",
)

private data class DurableOperationalEventOutboxTestError(
    override val code: ErrorCode,
    override val category: ErrorCategory,
    override val severity: ErrorSeverity,
    override val recoverability: Recoverability,
    override val message: String,
    override val cause: Throwable? = null,
) : DataLoomError
