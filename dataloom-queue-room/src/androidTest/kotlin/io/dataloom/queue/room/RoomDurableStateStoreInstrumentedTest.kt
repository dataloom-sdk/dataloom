package io.dataloom.queue.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCodec
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.state.DurableStateScopeKeyEncoder
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDurableStateStoreInstrumentedTest {

    private val scopeKeyEncoder = DurableStateScopeKeyEncoder<String> { it }
    private val codec = object : DurableStateCodec<String> {
        override fun encode(state: String): String = state
        override fun decode(payload: String): String = payload
    }

    @Test
    fun compareAndSetPersistsUpdatesConflictsAndReopens() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "dataloom-durable-state-cas-test"
        context.deleteDatabase(name)

        val firstDatabase = database(context, name)
        try {
            val store = RoomDurableStateStore(firstDatabase, "configuration-history", scopeKeyEncoder, codec)
            val inserted = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<String>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest("app", null, "v0", 1)),
            )
            assertEquals(
                0L,
                assertIs<DurableStateCompareAndSetResult.Updated<String>>(inserted.value).record.version,
            )

            val updated = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<String>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest("app", 0L, "v1", 1)),
            )
            assertEquals(
                1L,
                assertIs<DurableStateCompareAndSetResult.Updated<String>>(updated.value).record.version,
            )

            val conflict = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<String>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest("app", 0L, "stale", 1)),
            )
            val current = assertIs<DurableStateCompareAndSetResult.Conflict<String>>(conflict.value).current
            assertEquals(1L, current?.version)
            assertEquals("v1", current?.state)
        } finally {
            firstDatabase.close()
        }

        val reopened = database(context, name)
        try {
            val store = RoomDurableStateStore(reopened, "configuration-history", scopeKeyEncoder, codec)
            val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<String>>>(
                store.load("app"),
            )
            val record = assertIs<DurableStateLoadResult.Found<String>>(loaded.value).record
            assertEquals(1L, record.version)
            assertEquals("v1", record.state)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun distinctNamespacesDoNotCollideOnTheSameEncodedScopeKey() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "dataloom-durable-state-namespace-test"
        context.deleteDatabase(name)
        val database = database(context, name)
        try {
            val configurationStore = RoomDurableStateStore(database, "configuration-history", scopeKeyEncoder, codec)
            val policyStore = RoomDurableStateStore(database, "policy-decisions", scopeKeyEncoder, codec)

            configurationStore.compareAndSet(DurableStateCompareAndSetRequest("shared-key", null, "config-value", 1))
            policyStore.compareAndSet(DurableStateCompareAndSetRequest("shared-key", null, "policy-value", 1))

            val configurationLoaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<String>>>(
                configurationStore.load("shared-key"),
            )
            val policyLoaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<String>>>(
                policyStore.load("shared-key"),
            )

            assertEquals(
                "config-value",
                assertIs<DurableStateLoadResult.Found<String>>(configurationLoaded.value).record.state,
            )
            assertEquals(
                "policy-value",
                assertIs<DurableStateLoadResult.Found<String>>(policyLoaded.value).record.state,
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun database(
        context: android.content.Context,
        name: String,
    ): DataLoomRoomDatabase = Room.databaseBuilder(
        context,
        DataLoomRoomDatabase::class.java,
        name,
    ).addMigrations(*DataLoomRoomMigrations.ALL)
        .build()
}
