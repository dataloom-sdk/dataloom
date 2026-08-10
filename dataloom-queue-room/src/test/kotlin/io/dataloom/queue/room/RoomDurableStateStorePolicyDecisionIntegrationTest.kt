package io.dataloom.queue.room

import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.PolicyCheckId
import io.dataloom.api.identifier.PolicySetId
import io.dataloom.api.policy.PolicyCheckEvidence
import io.dataloom.api.policy.PolicyCheckOutcome
import io.dataloom.api.policy.PolicyDecision
import io.dataloom.api.policy.PolicyDecisionRecord
import io.dataloom.api.policy.PolicyDecisionRecordCodec
import io.dataloom.api.policy.PolicyDecisionScope
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.state.DurableStateCompareAndSetRequest
import io.dataloom.api.state.DurableStateCompareAndSetResult
import io.dataloom.api.state.DurableStateLoadResult
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.queue.room.internal.DataLoomRoomDatabase
import io.dataloom.queue.room.internal.DurableStateCompareAndSetEntityResult
import io.dataloom.queue.room.internal.DurableStateDao
import io.dataloom.queue.room.internal.DurableStateEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertIs

/**
 * Proves [RoomDurableStateStore] genuinely generalizes across domains: this
 * is the second real [io.dataloom.api.state.DurableStateStore] adopter after
 * `RoomDurableStateStoreTest`'s configuration-history-shaped fixtures,
 * exercised here with the real [PolicyDecisionRecordCodec] and
 * [PolicyDecisionScope.KeyEncoder] instead of test stand-ins — no new Room
 * DAO/entity code was written for this domain.
 */
class RoomDurableStateStorePolicyDecisionIntegrationTest {
    private lateinit var database: DataLoomRoomDatabase
    private lateinit var dao: DurableStateDao
    private lateinit var store: RoomDurableStateStore<PolicyDecisionScope, PolicyDecisionRecord>

    private val scope = PolicyDecisionScope(PolicySetId("retry-policy"), ExecutionId("execution-1"))
    private val record = PolicyDecisionRecord(
        decision = PolicyDecision(
            policySetId = scope.policySetId,
            outcome = PolicyCheckOutcome.Deny("quota exceeded"),
            winningCheckId = PolicyCheckId("quota-check"),
            evidence = listOf(
                PolicyCheckEvidence(PolicyCheckId("auth-check"), PolicyCheckOutcome.Allow("authorized")),
                PolicyCheckEvidence(PolicyCheckId("quota-check"), PolicyCheckOutcome.Deny("quota exceeded")),
            ),
        ),
        committedAt = DataLoomInstant(10_000L),
    )

    @Before
    fun setUp() {
        database = mock()
        dao = mock()
        whenever(database.durableStateDao()).thenReturn(dao)
        store = RoomDurableStateStore(
            database,
            "policy-decisions",
            PolicyDecisionScope.KeyEncoder,
            PolicyDecisionRecordCodec(),
        )
    }

    @Test
    fun insertsAndRoundTripsAPolicyDecisionRecordThroughTheGenericRoomStore() {
        runBlocking {
            val encodedKey = PolicyDecisionScope.KeyEncoder.encode(scope)
            val encodedPayload = PolicyDecisionRecordCodec().encode(record)
            val persistedEntity = DurableStateEntity(
                namespace = "policy-decisions",
                scopeKey = encodedKey,
                statePayload = encodedPayload,
                schemaVersion = 1,
                recordVersion = 0L,
            )
            whenever(dao.compareAndSet(eq(null), any())).thenReturn(
                DurableStateCompareAndSetEntityResult.Updated(persistedEntity),
            )
            whenever(dao.load("policy-decisions", encodedKey)).thenReturn(persistedEntity)

            val inserted = assertIs<ProviderOperationResult.Success<DurableStateCompareAndSetResult<PolicyDecisionRecord>>>(
                store.compareAndSet(DurableStateCompareAndSetRequest(scope, null, record, 1)),
            )
            val updated = assertIs<DurableStateCompareAndSetResult.Updated<PolicyDecisionRecord>>(inserted.value)
            assertEquals(record, updated.record.state)

            val loaded = assertIs<ProviderOperationResult.Success<DurableStateLoadResult<PolicyDecisionRecord>>>(
                store.load(scope),
            )
            val found = assertIs<DurableStateLoadResult.Found<PolicyDecisionRecord>>(loaded.value)
            assertEquals(record, found.record.state)
        }
    }
}
