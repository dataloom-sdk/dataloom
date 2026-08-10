package io.dataloom.api.state

import io.dataloom.api.provider.ProviderOperationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

class DurableStateStoreTest {

    // -------------------------------------------------------------------------
    // DurableStateRecord / DurableStateCompareAndSetRequest validation
    // -------------------------------------------------------------------------

    @Test
    fun `record rejects negative version`() {
        assertFailsWith<IllegalArgumentException> {
            DurableStateRecord(state = "x", version = -1L, schemaVersion = 0)
        }
    }

    @Test
    fun `record rejects negative schema version`() {
        assertFailsWith<IllegalArgumentException> {
            DurableStateRecord(state = "x", version = 0L, schemaVersion = -1)
        }
    }

    @Test
    fun `record accepts zero version and zero schema version`() {
        val record = DurableStateRecord(state = "x", version = 0L, schemaVersion = 0)
        assertEquals(0L, record.version)
        assertEquals(0, record.schemaVersion)
    }

    @Test
    fun `compare and set request rejects negative expected version`() {
        assertFailsWith<IllegalArgumentException> {
            DurableStateCompareAndSetRequest(
                scope = "scope",
                expectedVersion = -1L,
                nextState = "x",
                nextSchemaVersion = 0,
            )
        }
    }

    @Test
    fun `compare and set request accepts null expected version for insert-if-absent`() {
        val request = DurableStateCompareAndSetRequest(
            scope = "scope",
            expectedVersion = null,
            nextState = "x",
            nextSchemaVersion = 0,
        )
        assertEquals(null, request.expectedVersion)
    }

    @Test
    fun `compare and set request rejects negative next schema version`() {
        assertFailsWith<IllegalArgumentException> {
            DurableStateCompareAndSetRequest(
                scope = "scope",
                expectedVersion = null,
                nextState = "x",
                nextSchemaVersion = -1,
            )
        }
    }

    // -------------------------------------------------------------------------
    // applyMigration
    // -------------------------------------------------------------------------

    @Test
    fun `apply migration is a no-op when schema version already matches`() {
        val record = DurableStateRecord(state = "x", version = 3L, schemaVersion = 2)
        val migration = DurableStateMigration<String> { _, _ ->
            error("must not be called when schema version already matches")
        }

        val result = applyMigration(record, currentSchemaVersion = 2, migration = migration)

        assertSame(record, result)
    }

    @Test
    fun `apply migration upcasts state and updates schema version`() {
        val record = DurableStateRecord(state = "v1-payload", version = 5L, schemaVersion = 1)
        val migration = DurableStateMigration<String> { fromSchemaVersion, state ->
            "$state->upcast-from-$fromSchemaVersion"
        }

        val result = applyMigration(record, currentSchemaVersion = 2, migration = migration)

        assertEquals("v1-payload->upcast-from-1", result.state)
        assertEquals(2, result.schemaVersion)
        assertEquals(5L, result.version, "migration must not change the compare-and-set version")
    }

    @Test
    fun `apply migration rejects an attempted downgrade`() {
        val record = DurableStateRecord(state = "x", version = 0L, schemaVersion = 3)
        assertFailsWith<IllegalArgumentException> {
            applyMigration(record, currentSchemaVersion = 1, migration = DurableStateMigration { _, s -> s })
        }
    }

    // -------------------------------------------------------------------------
    // Contract usability: a real in-memory implementation, exercised end to end
    // -------------------------------------------------------------------------

    @Test
    fun `load on an unpopulated scope returns Missing`() = runTest {
        val store = InMemoryDurableStateStore<String, String>()

        val result = store.load("scope-1")

        val success = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<String>>>(result)
        assertIs<DurableStateLoadResult.Missing>(success.value)
    }

    @Test
    fun `compare and set with null expected version inserts a new record at version zero`() = runTest {
        val store = InMemoryDurableStateStore<String, String>()

        val result = store.compareAndSet(
            DurableStateCompareAndSetRequest(
                scope = "scope-1",
                expectedVersion = null,
                nextState = "initial",
                nextSchemaVersion = 0,
            ),
        )

        val success = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<String>>>(result)
        val updated = assertIs<DurableStateCompareAndSetResult.Updated<String>>(success.value)
        assertEquals("initial", updated.record.state)
        assertEquals(0L, updated.record.version)
    }

    @Test
    fun `compare and set with null expected version conflicts when a record already exists`() = runTest {
        val store = InMemoryDurableStateStore<String, String>()
        store.compareAndSet(
            DurableStateCompareAndSetRequest("scope-1", null, "initial", 0),
        )

        val result = store.compareAndSet(
            DurableStateCompareAndSetRequest("scope-1", null, "duplicate-insert", 0),
        )

        val success = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<String>>>(result)
        val conflict = assertIs<DurableStateCompareAndSetResult.Conflict<String>>(success.value)
        assertEquals("initial", conflict.current?.state)
    }

    @Test
    fun `compare and set with the correct expected version updates and increments version`() = runTest {
        val store = InMemoryDurableStateStore<String, String>()
        store.compareAndSet(DurableStateCompareAndSetRequest("scope-1", null, "v0", 0))

        val result = store.compareAndSet(
            DurableStateCompareAndSetRequest("scope-1", expectedVersion = 0L, nextState = "v1", nextSchemaVersion = 0),
        )

        val success = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<String>>>(result)
        val updated = assertIs<DurableStateCompareAndSetResult.Updated<String>>(success.value)
        assertEquals("v1", updated.record.state)
        assertEquals(1L, updated.record.version)
    }

    @Test
    fun `compare and set with a stale expected version conflicts and preserves current state`() = runTest {
        val store = InMemoryDurableStateStore<String, String>()
        store.compareAndSet(DurableStateCompareAndSetRequest("scope-1", null, "v0", 0))
        store.compareAndSet(DurableStateCompareAndSetRequest("scope-1", 0L, "v1", 0))

        val result = store.compareAndSet(
            DurableStateCompareAndSetRequest("scope-1", expectedVersion = 0L, nextState = "stale-write", nextSchemaVersion = 0),
        )

        val success = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<String>>>(result)
        val conflict = assertIs<DurableStateCompareAndSetResult.Conflict<String>>(success.value)
        assertEquals("v1", conflict.current?.state)
        assertEquals(1L, conflict.current?.version)
    }

    @Test
    fun `load reflects the most recent successful compare and set`() = runTest {
        val store = InMemoryDurableStateStore<String, String>()
        store.compareAndSet(DurableStateCompareAndSetRequest("scope-1", null, "v0", 0))
        store.compareAndSet(DurableStateCompareAndSetRequest("scope-1", 0L, "v1", 0))

        val result = store.load("scope-1")

        val success = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<String>>>(result)
        val found = assertIs<DurableStateLoadResult.Found<String>>(success.value)
        assertEquals("v1", found.record.state)
        assertEquals(1L, found.record.version)
    }

    @Test
    fun `distinct scopes are independent`() = runTest {
        val store = InMemoryDurableStateStore<String, String>()
        store.compareAndSet(DurableStateCompareAndSetRequest("scope-1", null, "a", 0))
        store.compareAndSet(DurableStateCompareAndSetRequest("scope-2", null, "b", 0))

        val scope1 = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<String>>>(store.load("scope-1"))
        val scope2 = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<String>>>(store.load("scope-2"))

        assertEquals("a", (scope1.value as DurableStateLoadResult.Found<String>).record.state)
        assertEquals("b", (scope2.value as DurableStateLoadResult.Found<String>).record.state)
    }

    /**
     * Minimal, non-thread-safe in-memory [DurableStateStore] used only to prove
     * the contract is genuinely implementable and behaves as documented. Not a
     * production reference implementation.
     */
    private class InMemoryDurableStateStore<TScope : Any, TState : Any> : DurableStateStore<TScope, TState> {
        private val records = mutableMapOf<TScope, DurableStateRecord<TState>>()

        override suspend fun load(
            scope: TScope,
        ): ProviderOperationResult<DurableStateLoadResult<TState>> {
            val record = records[scope]
            return ProviderOperationResult.Success(
                if (record == null) DurableStateLoadResult.Missing else DurableStateLoadResult.Found(record),
            )
        }

        override suspend fun compareAndSet(
            request: DurableStateCompareAndSetRequest<TScope, TState>,
        ): ProviderOperationResult<DurableStateCompareAndSetResult<TState>> {
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
}
