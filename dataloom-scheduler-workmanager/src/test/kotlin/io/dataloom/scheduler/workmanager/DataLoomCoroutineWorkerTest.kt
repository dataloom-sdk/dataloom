package io.dataloom.scheduler.workmanager

import android.content.Context
import androidx.work.WorkerParameters
import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.runtime.facade.DataLoomQueueWorker
import org.mockito.kotlin.mock
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class DataLoomWorkerFactoryTest {

    private val mockContext: Context = mock()
    private val mockQueueWorker: DataLoomQueueWorker = mock()

    private fun makeFactory() = DataLoomWorkerFactory(
        queueWorker = mockQueueWorker,
        consumerId = QueueConsumerId("test-consumer"),
        leaseId = QueueLeaseId("test-lease"),
        acquiredAtMillis = 1_000_000L,
        leaseExpiresAtMillis = 1_060_000L,
        maxEntries = 10,
        recoverExpiredLeases = false,
    )

    @Test
    fun `createWorker returns DataLoomCoroutineWorker for matching class name`() {
        val factory = makeFactory()
        val mockParams: WorkerParameters = mock()

        val worker = factory.createWorker(
            appContext = mockContext,
            workerClassName = DataLoomCoroutineWorker::class.java.name,
            workerParameters = mockParams,
        )

        assertIs<DataLoomCoroutineWorker>(worker)
    }

    @Test
    fun `createWorker returns null for unknown class name`() {
        val factory = makeFactory()
        val mockParams: WorkerParameters = mock()

        val worker = factory.createWorker(
            appContext = mockContext,
            workerClassName = "com.example.SomeOtherWorker",
            workerParameters = mockParams,
        )

        assertNull(worker)
    }

    @Test
    fun `createWorker returns null for empty class name`() {
        val factory = makeFactory()
        val mockParams: WorkerParameters = mock()

        val worker = factory.createWorker(
            appContext = mockContext,
            workerClassName = "",
            workerParameters = mockParams,
        )

        assertNull(worker)
    }
}

