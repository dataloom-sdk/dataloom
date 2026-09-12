package io.dataloom.scheduler.bgtask

import io.dataloom.api.identifier.QueueConsumerId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.queue.QueueAcquireRequest
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.facade.DataLoomQueueWorker
import io.dataloom.runtime.queue.QueueProcessingFailureStage
import io.dataloom.runtime.queue.QueueProcessingRequest
import io.dataloom.runtime.queue.QueueProcessingResult
import io.dataloom.runtime.queue.QueueProcessingSummary
import io.dataloom.runtime.worker.QueueWorkerRunRequest
import io.dataloom.runtime.worker.QueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerSchedulingResult
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.BackgroundTasks.BGTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [DataLoomBackgroundTaskHandler] end to end against a real, if
 * inert, `BGTask` instance.
 *
 * ## What "real" means here, and what does not
 *
 * [RecordingBGTask] genuinely subclasses the real Kotlin/Native
 * `platform.BackgroundTasks.BGTask` binding and is constructed through its
 * real generated `init`; [expirationHandler] is the real ObjC property this
 * class's getter/setter bridges to. `setTaskCompletedWithSuccess` is
 * deliberately overridden (it is declared `open` in the generated binding)
 * to record its argument instead of forwarding to `BGTaskScheduler`'s real
 * native implementation -- that native path is undocumented to run outside
 * a genuine OS-driven background-task lifecycle, and this test's Windows
 * cross-compilation host cannot confirm whether it is safe to invoke on a
 * `BGTask` this test constructed directly rather than one the OS supplied.
 * `AppleSchedulerProvider`'s own `submitBackgroundTaskRequest` /
 * `cancelBackgroundTaskRequest` remain undischarged of the identical
 * disclosure (see `docs/apple/scheduler-provider.md`); this test narrows,
 * but does not remove, that boundary for the one additional method this
 * module's own production code calls.
 *
 * This test class type-checks and klib-compiles for all three iOS targets
 * on this Windows host but has never actually been executed -- doing so
 * requires a macOS host running `iosSimulatorArm64Test`/`iosX64Test`. See
 * `docs/apple/background-task-handler.md` for the exact verification
 * performed and what remains open.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
class DataLoomBackgroundTaskHandlerTest {

    @Test
    fun `handle runs exactly one queue-worker cycle and reports success`() = runTest {
        val request = sampleRequest()
        val recordedRequests = mutableListOf<QueueWorkerRunRequest>()
        val queueWorker = object : DataLoomQueueWorker {
            override suspend fun run(request: QueueWorkerRunRequest): QueueWorkerRunResult {
                recordedRequests += request
                return QueueWorkerRunResult.ProcessingCompleted(
                    recoveryResult = null,
                    processingResult = QueueProcessingResult.NoWork,
                    schedulingResult = QueueWorkerSchedulingResult.NotRequired,
                )
            }
        }
        val handler = DataLoomBackgroundTaskHandler(
            queueWorker = queueWorker,
            requestFactory = QueueWorkerRunRequestFactory { request },
            scope = this,
        )
        val task = RecordingBGTask()

        handler.handle(task)
        advanceUntilIdle()

        assertEquals(listOf(request), recordedRequests)
        assertEquals(true, task.completedWithSuccess)
    }

    @Test
    fun `handle reports failure for a genuine provider failure`() = runTest {
        val queueWorker = object : DataLoomQueueWorker {
            override suspend fun run(request: QueueWorkerRunRequest): QueueWorkerRunResult {
                return QueueWorkerRunResult.ProcessingFailed(
                    recoveryResult = null,
                    processingResult = QueueProcessingResult.QueueProviderFailure(
                        error = fakeError(),
                        stage = QueueProcessingFailureStage.ACQUISITION,
                        summary = zeroSummary(),
                    ),
                )
            }
        }
        val handler = DataLoomBackgroundTaskHandler(
            queueWorker = queueWorker,
            requestFactory = QueueWorkerRunRequestFactory { sampleRequest() },
            scope = this,
        )
        val task = RecordingBGTask()

        handler.handle(task)
        advanceUntilIdle()

        assertEquals(false, task.completedWithSuccess)
    }

    @Test
    fun `expiration cancels the in-flight cycle and reports failure exactly once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reachedRun = false
        val queueWorker = object : DataLoomQueueWorker {
            override suspend fun run(request: QueueWorkerRunRequest): QueueWorkerRunResult {
                reachedRun = true
                gate.await() // suspends until cancelled by expiration below
                error("unreachable: expiration must cancel this cycle first")
            }
        }
        val handler = DataLoomBackgroundTaskHandler(
            queueWorker = queueWorker,
            requestFactory = QueueWorkerRunRequestFactory { sampleRequest() },
            scope = this,
        )
        val task = RecordingBGTask()

        handler.handle(task)
        runCurrent()
        assertTrue(reachedRun)
        assertNull(task.completedWithSuccess)

        task.expirationHandler?.invoke()
        advanceUntilIdle()

        assertEquals(false, task.completedWithSuccess)
    }

    private fun sampleRequest(): QueueWorkerRunRequest {
        val acquiredAt = DataLoomInstant(epochMilliseconds = 0L)
        return QueueWorkerRunRequest(
            processingRequest = QueueProcessingRequest(
                acquireRequest = QueueAcquireRequest(
                    consumerId = QueueConsumerId("bgtask-handler-test-consumer"),
                    leaseId = QueueLeaseId("bgtask-handler-test-lease"),
                    acquiredAt = acquiredAt,
                    leaseExpiresAt = DataLoomInstant(epochMilliseconds = 60_000L),
                    maxEntries = 1,
                ),
            ),
            recoveryRequest = null,
        )
    }

    private fun zeroSummary(): QueueProcessingSummary = QueueProcessingSummary(
        acquired = 0,
        executed = 0,
        completed = 0,
        rescheduled = 0,
        failed = 0,
        cancelled = 0,
        deferred = 0,
    )

    private fun fakeError(): DataLoomError = FakeError()

    private data class FakeError(
        override val code: ErrorCode = ErrorCode("DL-FAKE-001"),
        override val category: ErrorCategory = ErrorCategory.PROVIDER,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.RECOVERABLE,
        override val message: String = "Fake error.",
        override val cause: Throwable? = null,
    ) : DataLoomError

    /**
     * Real `BGTask` subclass that records
     * [BGTask.setTaskCompletedWithSuccess]'s argument instead of forwarding
     * to the platform. See the class KDoc above for exactly why.
     */
    private class RecordingBGTask : BGTask() {
        var completedWithSuccess: Boolean? = null
            private set

        override fun setTaskCompletedWithSuccess(success: Boolean) {
            completedWithSuccess = success
        }
    }
}
