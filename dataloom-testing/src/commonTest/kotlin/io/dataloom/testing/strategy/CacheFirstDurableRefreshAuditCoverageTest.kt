package io.dataloom.testing.strategy

import io.dataloom.api.error.ErrorCode
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.ScheduleId
import io.dataloom.api.provider.ProviderLifecycleResult
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueIdempotentAdmissionResult
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.runtime.strategy.StrategyCacheDurableRefreshResult
import io.dataloom.runtime.strategy.StrategyCacheServedWithDurableRefreshResult
import io.dataloom.runtime.strategy.StrategyExecutionRejectionReason
import io.dataloom.runtime.strategy.StrategySynchronizationExecutionResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CacheFirstDurableRefreshAuditCoverageTest {

    @Test
    fun protectedDurableRefreshFailsClosedBeforeEveryProviderSideEffect() =
        runDurableRefreshAudit {
            val fixture = durableRefreshAuditFixture(protected = true)
            assertIs<ProviderLifecycleResult.InitializeSuccess>(fixture.dataLoom.initialize())

            val result = requireNotNull(fixture.dataLoom.protectedStrategySynchronization)
                .synchronize(durableRefreshAuditRequest())
            val rejected = assertIs<StrategySynchronizationExecutionResult.Rejected>(
                result.strategyResult,
            )

            assertEquals(
                StrategyExecutionRejectionReason.PROVIDER_PROTECTION_NOT_CONFIGURED,
                rejected.reason,
            )
            assertTrue(result.operationEvidence.isEmpty())
            assertEquals(0, fixture.storage.cacheCalls)
            assertEquals(0, fixture.storage.storageCalls)
            assertEquals(0, fixture.queue.admitCalls)
            assertEquals(0, fixture.scheduler.scheduleCalls)
            assertEquals(0, fixture.transport.pullCalls)
            assertEquals(0, fixture.transport.pushCalls)
            assertEquals(0, fixture.storageCircuitStore.loadCalls)
            assertEquals(0, fixture.cacheCircuitStore.loadCalls)
        }

    @Test
    fun queueFailureReturnsTypedFailureAndNeverSchedules() = runDurableRefreshAudit {
        val error = DurableRefreshAuditError(ErrorCode("QUEUE_UNAVAILABLE"))
        val fixture = durableRefreshAuditFixture(
            queueAdmission = { _, _ -> ProviderOperationResult.Failure(error) },
        )
        assertIs<ProviderLifecycleResult.InitializeSuccess>(fixture.dataLoom.initialize())

        val served = assertIs<StrategyCacheServedWithDurableRefreshResult>(
            fixture.dataLoom.synchronize(durableRefreshAuditRequest()),
        )
        val failed = assertIs<StrategyCacheDurableRefreshResult.QueueFailed>(served.refresh)

        assertEquals(error, failed.error)
        assertEquals(1, fixture.storage.cacheCalls)
        assertEquals(1, fixture.queue.admitCalls)
        assertEquals(0, fixture.scheduler.scheduleCalls)
        assertEquals(0, fixture.queue.delegate.entryCount)
    }

    @Test
    fun queueIdentityMismatchReturnsCanonicalFailureAndNeverSchedules() =
        runDurableRefreshAudit {
            val fixture = durableRefreshAuditFixture(
                queueAdmission = { _, _ ->
                    ProviderOperationResult.Success(
                        QueueIdempotentAdmissionResult.Accepted(
                            QueueEntryId("different-durable-refresh-entry"),
                        ),
                    )
                },
            )
            assertIs<ProviderLifecycleResult.InitializeSuccess>(fixture.dataLoom.initialize())

            val served = assertIs<StrategyCacheServedWithDurableRefreshResult>(
                fixture.dataLoom.synchronize(durableRefreshAuditRequest()),
            )
            val failed = assertIs<StrategyCacheDurableRefreshResult.QueueFailed>(served.refresh)

            assertEquals(
                "STRATEGY_DURABLE_REFRESH_QUEUE_IDENTITY_MISMATCH",
                failed.error.code.value,
            )
            assertEquals(1, fixture.queue.admitCalls)
            assertEquals(0, fixture.scheduler.scheduleCalls)
            assertEquals(0, fixture.queue.delegate.entryCount)
        }

    @Test
    fun schedulerReceiptMismatchPreservesAdmittedQueueEntry() = runDurableRefreshAudit {
        val fixture = durableRefreshAuditFixture(
            schedulerBehavior = {
                ProviderOperationResult.Success(
                    ScheduleReceipt(ScheduleId("different-durable-refresh-schedule")),
                )
            },
        )
        assertIs<ProviderLifecycleResult.InitializeSuccess>(fixture.dataLoom.initialize())

        val served = assertIs<StrategyCacheServedWithDurableRefreshResult>(
            fixture.dataLoom.synchronize(durableRefreshAuditRequest()),
        )
        val failed = assertIs<StrategyCacheDurableRefreshResult.ScheduleFailed>(served.refresh)

        assertEquals(
            "STRATEGY_DURABLE_REFRESH_SCHEDULE_IDENTITY_MISMATCH",
            failed.error.code.value,
        )
        assertEquals(1, fixture.queue.delegate.entryCount)
        assertEquals(1, fixture.scheduler.scheduleCalls)
    }

    @Test
    fun schedulerCancellationPropagatesAfterQueueAdmissionWithoutDeletingWork() {
        val fixture = durableRefreshAuditFixture(
            schedulerBehavior = {
                throw CancellationException("cancel after durable admission")
            },
        )
        runDurableRefreshAudit {
            assertIs<ProviderLifecycleResult.InitializeSuccess>(fixture.dataLoom.initialize())
        }

        assertFailsWith<CancellationException> {
            runDurableRefreshAudit {
                fixture.dataLoom.synchronize(durableRefreshAuditRequest())
            }
        }

        assertEquals(1, fixture.storage.cacheCalls)
        assertEquals(1, fixture.queue.admitCalls)
        assertEquals(1, fixture.scheduler.scheduleCalls)
        assertEquals(1, fixture.queue.delegate.entryCount)
    }
}
