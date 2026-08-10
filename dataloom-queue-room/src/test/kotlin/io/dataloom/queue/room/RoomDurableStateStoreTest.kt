package io.dataloom.queue.room

import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.DurableStateCompareAndSetEntityResult
import io.dataloom.queue.room.internal.DurableStateDao
import io.dataloom.queue.room.internal.DurableStateEntity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RoomDurableStateStoreTest {
    private val scope = "scope-1"
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: DurableStateDao
    private lateinit var store: RoomDurableStateStore<String, String>

    private val scopeKeyEncoder = DurableStateScopeKeyEncoder<String> { it }
    private val codec = object : DurableStateCodec<String> {
        override fun encode(state: String): String = state
        override fun decode(payload: String): String = payload
    }

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.durableStateDao()).thenReturn(dao)
        store = RoomDurableStateStore(database, "test-namespace", scopeKeyEncoder, codec)
    }

    @Test
    fun `blank namespace is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RoomDurableStateStore(database, "  ", scopeKeyEncoder, codec)
        }
    }

    @Test
    fun `missing row is returned as an explicit missing result`() {
        runBlocking {
            whenever(dao.load(any(), any())).thenReturn(null)

            val result = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<String>>>(
                store.load(scope),
            )

            assertIs<DurableStateLoadResult.Missing>(result.value)
        }
    }

    @Test
    fun `found row decodes into a durable state record`() {
        runBlocking {
            whenever(dao.load("test-namespace", scope)).thenReturn(entity(statePayload = "hello", recordVersion = 3L))

            val result = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<String>>>(
                store.load(scope),
            )

            val found = assertIs<DurableStateLoadResult.Found<String>>(result.value)
            assertEquals("hello", found.record.state)
            assertEquals(3L, found.record.version)
        }
    }

    @Test
    fun `compare and set inserts at version zero when expected version is null`() {
        runBlocking {
            whenever(dao.compareAndSet(eq(null), any())).thenAnswer { invocation ->
                DurableStateCompareAndSetEntityResult.Updated(invocation.getArgument(1))
            }

            val result = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<String>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest(scope, null, "v0", 0)),
            )

            val updated = assertIs<DurableStateCompareAndSetResult.Updated<String>>(result.value)
            assertEquals("v0", updated.record.state)
            assertEquals(0L, updated.record.version)
        }
    }

    @Test
    fun `compare and set conflict preserves the current durable record`() {
        runBlocking {
            val current = entity(statePayload = "current", recordVersion = 2L)
            whenever(dao.compareAndSet(eq(1L), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Conflict(current),
            )

            val result = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<String>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest(scope, 1L, "next", 0)),
            )

            val conflict = assertIs<DurableStateCompareAndSetResult.Conflict<String>>(result.value)
            assertEquals("current", conflict.current?.state)
            assertEquals(2L, conflict.current?.version)
        }
    }

    @Test
    fun `malformed persisted payload fails closed as an integrity failure`() {
        runBlocking {
            val throwingCodec = object : DurableStateCodec<String> {
                override fun encode(state: String): String = state
                override fun decode(payload: String): String = error("cannot decode")
            }
            val throwingStore = RoomDurableStateStore(database, "ns", scopeKeyEncoder, throwingCodec)
            whenever(dao.load(any(), any())).thenReturn(entity(statePayload = "broken", recordVersion = 0L))

            val result = assertIs<ProviderOperationResult.Failure>(throwingStore.load(scope))

            assertEquals("DURABLE_STATE_ROOM_STATE_CORRUPT", result.error.code.value)
            assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
        }
    }

    @Test
    fun `encode failure fails closed without reaching Room`() {
        runBlocking {
            val throwingCodec = object : DurableStateCodec<String> {
                override fun encode(state: String): String = error("cannot encode")
                override fun decode(payload: String): String = payload
            }
            val throwingStore = RoomDurableStateStore(database, "ns", scopeKeyEncoder, throwingCodec)

            val result = assertIs<ProviderOperationResult.Failure>(
                throwingStore.compareAndSet(DurableStateCompareAndSetRequest(scope, null, "v0", 0)),
            )

            assertEquals("DURABLE_STATE_ROOM_ENCODE_FAILURE", result.error.code.value)
            assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
            verifyNoInteractions(dao)
        }
    }

    @Test
    fun `oversized payload fails closed without reaching Room`() {
        runBlocking {
            val oversizedCodec = object : DurableStateCodec<String> {
                override fun encode(state: String): String = "x".repeat(5 * 1024 * 1024)
                override fun decode(payload: String): String = payload
            }
            val oversizedStore = RoomDurableStateStore(database, "ns", scopeKeyEncoder, oversizedCodec)

            val result = assertIs<ProviderOperationResult.Failure>(
                oversizedStore.compareAndSet(DurableStateCompareAndSetRequest(scope, null, "v0", 0)),
            )

            assertEquals("DURABLE_STATE_ROOM_PAYLOAD_TOO_LARGE", result.error.code.value)
            assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
            verifyNoInteractions(dao)
        }
    }

    @Test
    fun `record version exhaustion is non recoverable and does not access Room`() {
        runBlocking {
            val result = assertIs<ProviderOperationResult.Failure>(
                store.compareAndSet(DurableStateCompareAndSetRequest(scope, Long.MAX_VALUE, "v", 0)),
            )

            assertEquals("DURABLE_STATE_VERSION_EXHAUSTED", result.error.code.value)
            assertEquals(Recoverability.NON_RECOVERABLE, result.error.recoverability)
            verifyNoInteractions(dao)
        }
    }

    @Test
    fun `database failure is sanitized and recoverable`() {
        runBlocking {
            whenever(dao.load(any(), any())).thenThrow(mock<android.database.sqlite.SQLiteException>())

            val result = assertIs<ProviderOperationResult.Failure>(store.load(scope))

            assertEquals("DURABLE_STATE_ROOM_DATABASE_FAILURE", result.error.code.value)
            assertEquals(Recoverability.RECOVERABLE, result.error.recoverability)
        }
    }

    @Test
    fun `database cancellation propagates unchanged`() {
        val expected = CancellationException("cancelled")
        runBlocking {
            whenever(dao.load(any(), any())).thenThrow(expected)
        }

        val actual = assertFailsWith<CancellationException> {
            runBlocking { store.load(scope) }
        }

        assertEquals("cancelled", actual.message)
    }

    private fun entity(
        statePayload: String,
        recordVersion: Long,
        namespace: String = "test-namespace",
        scopeKey: String = scope,
        schemaVersion: Int = 0,
    ): DurableStateEntity = DurableStateEntity(
        namespace = namespace,
        scopeKey = scopeKey,
        statePayload = statePayload,
        schemaVersion = schemaVersion,
        recordVersion = recordVersion,
    )
}
