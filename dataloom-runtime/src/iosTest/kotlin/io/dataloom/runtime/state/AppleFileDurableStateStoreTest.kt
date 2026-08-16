@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.runtime.state

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleFileDurableStateStoreTest {

    private val scopeKeyEncoder = DurableStateScopeKeyEncoder<String> { it }
    private val codec = object : DurableStateCodec<String> {
        override fun encode(state: String): String = state
        override fun decode(payload: String): String = payload
    }

    @Test
    fun `missing state is created and survives a new store instance`() = runTest {
        val directory = uniqueDirectory()
        val first = store(directory)

        assertIs<DurableStateLoadResult.Missing>(first.loadSuccess("scope-1"))
        val created = first.compareSuccess(
            DurableStateCompareAndSetRequest("scope-1", null, "initial", 0),
        )
        val createdRecord = assertIs<DurableStateCompareAndSetResult.Updated<String>>(created).record
        assertEquals(0L, createdRecord.version)
        assertEquals("initial", createdRecord.state)

        val reopened = store(directory)
        val found = assertIs<DurableStateLoadResult.Found<String>>(reopened.loadSuccess("scope-1"))
        assertEquals(createdRecord, found.record)
    }

    @Test
    fun `compare and set preserves exact conflict evidence`() = runTest {
        val theStore = store(uniqueDirectory())
        val created = assertIs<DurableStateCompareAndSetResult.Updated<String>>(
            theStore.compareSuccess(DurableStateCompareAndSetRequest("scope-1", null, "v0", 0)),
        )

        val staleInsert = theStore.compareSuccess(
            DurableStateCompareAndSetRequest("scope-1", null, "duplicate-insert", 0),
        )
        val conflict = assertIs<DurableStateCompareAndSetResult.Conflict<String>>(staleInsert)
        assertEquals(created.record, conflict.current)

        val updated = assertIs<DurableStateCompareAndSetResult.Updated<String>>(
            theStore.compareSuccess(
                DurableStateCompareAndSetRequest(
                    scope = "scope-1",
                    expectedVersion = created.record.version,
                    nextState = "v1",
                    nextSchemaVersion = 0,
                ),
            ),
        )
        assertEquals(1L, updated.record.version)
        assertEquals("v1", updated.record.state)
    }

    @Test
    fun `two store instances serialize first creation exactly`() = runTest {
        val directory = uniqueDirectory()
        val request = DurableStateCompareAndSetRequest("scope-contention", null, "v0", 0)
        val first = store(directory)
        val second = store(directory)

        val results = listOf(
            async(Dispatchers.Default) { first.compareSuccess(request) },
            async(Dispatchers.Default) { second.compareSuccess(request) },
        ).awaitAll()

        assertEquals(1, results.count { it is DurableStateCompareAndSetResult.Updated<String> })
        assertEquals(1, results.count { it is DurableStateCompareAndSetResult.Conflict<String> })
        val persisted = assertIs<DurableStateLoadResult.Found<String>>(first.loadSuccess("scope-contention")).record
        assertEquals(0L, persisted.version)
        assertEquals("v0", persisted.state)
    }

    @Test
    fun `distinct scopes are independent and survive restart`() = runTest {
        val directory = uniqueDirectory()
        store(directory).compareSuccess(DurableStateCompareAndSetRequest("scope-1", null, "a", 0))
        store(directory).compareSuccess(DurableStateCompareAndSetRequest("scope-2", null, "b", 0))

        val reopened = store(directory)
        val scope1 = assertIs<DurableStateLoadResult.Found<String>>(reopened.loadSuccess("scope-1"))
        val scope2 = assertIs<DurableStateLoadResult.Found<String>>(reopened.loadSuccess("scope-2"))
        assertEquals("a", scope1.record.state)
        assertEquals("b", scope2.record.state)
    }

    @Test
    fun `scope keys and payloads containing tabs and newlines round trip exactly`() = runTest {
        val directory = uniqueDirectory()
        val tab = Char(9)
        val newline = Char(10)
        val trickyScope = "scope" + tab + "with" + tab + "tabs" + newline + "and" + newline + "newlines"
        val trickyPayload = "payload" + tab + "with" + tab + "tabs" + newline + "and" + newline + "newlines-adjacent-marker"
        store(directory).compareSuccess(
            DurableStateCompareAndSetRequest(trickyScope, null, trickyPayload, 0),
        )

        val found = assertIs<DurableStateLoadResult.Found<String>>(
            store(directory).loadSuccess(trickyScope),
        )
        assertEquals(trickyPayload, found.record.state)
    }

    @Test
    fun `corrupt snapshot fails closed without leaking file content`() = runTest {
        val directory = uniqueDirectory()
        val theStore = store(directory)
        assertIs<DurableStateLoadResult.Missing>(theStore.loadSuccess("scope-1"))
        val dataPath = "$directory/${AppleFileDurableStateStore.DEFAULT_FILE_NAME}"
        writeDurableStateUtf8FileAtomically(
            temporaryPath = "$dataPath.test-tmp",
            destinationPath = dataPath,
            content = "not-a-dataloom-durable-state-snapshot\nsecret-payload",
        )

        val failure = assertIs<ProviderOperationResult.Failure>(theStore.load("scope-1"))
        assertEquals("DURABLE_STATE_APPLE_STATE_CORRUPT", failure.error.code.value)
        assertEquals(ErrorCategory.STATE, failure.error.category)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
        assertTrue("secret-payload" !in failure.error.message)
        assertEquals(null, failure.error.cause)
    }

    @Test
    fun `malformed persisted payload fails closed as an integrity failure`() = runTest {
        val throwingCodec = object : DurableStateCodec<String> {
            override fun encode(state: String): String = state
            override fun decode(payload: String): String = error("cannot decode")
        }
        val directory = uniqueDirectory()
        store(directory).compareSuccess(DurableStateCompareAndSetRequest("scope-1", null, "broken", 0))
        val throwingStore = AppleFileDurableStateStore(directory, scopeKeyEncoder = scopeKeyEncoder, codec = throwingCodec)

        val failure = assertIs<ProviderOperationResult.Failure>(throwingStore.load("scope-1"))

        assertEquals("DURABLE_STATE_APPLE_STATE_CORRUPT", failure.error.code.value)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `encode failure fails closed without touching the file`() = runTest {
        val directory = uniqueDirectory()
        val throwingCodec = object : DurableStateCodec<String> {
            override fun encode(state: String): String = error("cannot encode")
            override fun decode(payload: String): String = payload
        }
        val throwingStore = AppleFileDurableStateStore(directory, scopeKeyEncoder = scopeKeyEncoder, codec = throwingCodec)

        val failure = assertIs<ProviderOperationResult.Failure>(
            throwingStore.compareAndSet(DurableStateCompareAndSetRequest("scope-1", null, "v0", 0)),
        )

        assertEquals("DURABLE_STATE_APPLE_ENCODE_FAILURE", failure.error.code.value)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
        // The file must never have been created: no directory or lock file exists.
        assertIs<DurableStateLoadResult.Missing>(store(directory).loadSuccess("scope-1"))
    }

    @Test
    fun `oversized payload fails closed without touching the file`() = runTest {
        val directory = uniqueDirectory()
        val oversizedCodec = object : DurableStateCodec<String> {
            override fun encode(state: String): String = "x".repeat(5 * 1024 * 1024)
            override fun decode(payload: String): String = payload
        }
        val oversizedStore = AppleFileDurableStateStore(directory, scopeKeyEncoder = scopeKeyEncoder, codec = oversizedCodec)

        val failure = assertIs<ProviderOperationResult.Failure>(
            oversizedStore.compareAndSet(DurableStateCompareAndSetRequest("scope-1", null, "v0", 0)),
        )

        assertEquals("DURABLE_STATE_APPLE_PAYLOAD_TOO_LARGE", failure.error.code.value)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `record version exhaustion is non recoverable and does not access the file`() = runTest {
        val theStore = store(uniqueDirectory())

        val failure = assertIs<ProviderOperationResult.Failure>(
            theStore.compareAndSet(DurableStateCompareAndSetRequest("scope-1", Long.MAX_VALUE, "v", 0)),
        )

        assertEquals("DURABLE_STATE_VERSION_EXHAUSTED", failure.error.code.value)
        assertEquals(Recoverability.NON_RECOVERABLE, failure.error.recoverability)
    }

    @Test
    fun `cancelled caller does not enter the store`() = runTest {
        val theStore = store(uniqueDirectory())
        val deferred = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            theStore.load("scope-1")
        }
        deferred.cancel(CancellationException("caller cancelled"))

        val failure = assertFailsWith<CancellationException> { deferred.await() }
        assertEquals("caller cancelled", failure.message)
    }

    @Test
    fun `constructor rejects unsafe paths without side effects`() {
        assertFailsWith<IllegalArgumentException> {
            AppleFileDurableStateStore("relative/path", scopeKeyEncoder = scopeKeyEncoder, codec = codec)
        }
        assertFailsWith<IllegalArgumentException> {
            AppleFileDurableStateStore("/tmp/safe", "../unsafe", scopeKeyEncoder, codec)
        }
        assertFailsWith<IllegalArgumentException> {
            AppleFileDurableStateStore("/tmp/../unsafe", scopeKeyEncoder = scopeKeyEncoder, codec = codec)
        }
    }

    private fun store(directoryPath: String): AppleFileDurableStateStore<String, String> =
        AppleFileDurableStateStore(directoryPath, scopeKeyEncoder = scopeKeyEncoder, codec = codec)

    private suspend fun AppleFileDurableStateStore<String, String>.loadSuccess(
        scope: String,
    ): DurableStateLoadResult<String> = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<String>>>(
        load(scope),
    ).value

    private suspend fun AppleFileDurableStateStore<String, String>.compareSuccess(
        request: DurableStateCompareAndSetRequest<String, String>,
    ): DurableStateCompareAndSetResult<String> =
        assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<String>>>(
            compareAndSet(request),
        ).value

    private fun uniqueDirectory(): String = buildString {
        append(NSTemporaryDirectory().trimEnd('/'))
        append("/dataloom-apple-durable-state-")
        append(NSUUID().UUIDString)
    }
}
