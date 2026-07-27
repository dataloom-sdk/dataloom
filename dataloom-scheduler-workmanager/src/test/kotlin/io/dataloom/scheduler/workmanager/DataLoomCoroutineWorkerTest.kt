package io.dataloom.scheduler.workmanager

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import io.dataloom.runtime.facade.DataLoomQueueWorker
import io.dataloom.runtime.queue.QueueProcessingResult
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerSchedulingResult
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DataLoomWorkerFactoryTest {

    private val context: Context = mock()
    private val queueWorker: DataLoomQueueWorker = mock()
    private val requestFactory: QueueWorkerRunRequestFactory = mock()
    private val workerParameters: WorkerParameters = mock()
    private val factory = DataLoomWorkerFactory(queueWorker, requestFactory)

    @Test
    fun `createWorker returns DataLoom worker for matching class name`() {
        val worker = factory.createWorker(
            appContext = context,
            workerClassName = DataLoomCoroutineWorker::class.java.name,
            workerParameters = workerParameters,
        )
        assertIs<DataLoomCoroutineWorker>(worker)
    }

    @Test
    fun `createWorker returns null for unknown class name`() {
        val worker = factory.createWorker(
            appContext = context,
            workerClassName = "com.example.OtherWorker",
            workerParameters = workerParameters,
        )
        assertNull(worker)
    }

    @Test
    fun `worker creates and executes exactly one fresh request`() = runTest {
        val request: QueueWorkerRunRequest = mock()
        whenever(requestFactory.create()).thenReturn(request)
        whenever(queueWorker.run(request)).thenReturn(
            QueueWorkerRunResult.ProcessingCompleted(
                recoveryResult = null,
                processingResult = QueueProcessingResult.NoWork,
                schedulingResult = QueueWorkerSchedulingResult.NotRequired,
            ),
        )
        val worker = assertIs<DataLoomCoroutineWorker>(
            factory.createWorker(
                appContext = context,
                workerClassName = DataLoomCoroutineWorker::class.java.name,
                workerParameters = workerParameters,
            ),
        )

        val result = worker.doWork()

        verify(requestFactory).create()
        verify(queueWorker).run(request)
        assertEquals(ListenableWorker.Result.success(), result)
    }
}
