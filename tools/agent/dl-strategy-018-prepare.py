from __future__ import annotations

import subprocess
from pathlib import Path
from textwrap import dedent, indent

PATCH_PATH = Path("tools/agent/dl-strategy-018.patch")
EXECUTOR_PATH = Path(
    "dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/"
    "CacheFirstStrategyExecutor.kt"
)
TEST_PATH = Path(
    "dataloom-testing/src/commonTest/kotlin/io/dataloom/testing/strategy/"
    "CacheFirstDurableRefreshAdmissionIntegrationTest.kt"
)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} anchor, found {count}")
    return text.replace(old, new, 1)


def class_block(raw: str) -> str:
    return indent(dedent(raw).strip("\n"), "    ") + "\n\n"


def apply_reviewed_patch() -> None:
    subprocess.run(
        [
            "git",
            "apply",
            "--recount",
            "--exclude=dataloom-runtime/src/commonMain/kotlin/io/dataloom/runtime/strategy/CacheFirstStrategyExecutor.kt",
            "--exclude=docs/strategies/cache-first.md",
            "--exclude=README.md",
            str(PATCH_PATH),
        ],
        check=True,
    )


def reconstruct_executor() -> None:
    text = EXECUTOR_PATH.read_text()

    text = replace_once(
        text,
        "import io.dataloom.api.error.DataLoomError\n",
        "import io.dataloom.api.connectivity.ConnectivityRequirement\n"
        "import io.dataloom.api.context.DataLoomMetadata\n"
        "import io.dataloom.api.error.DataLoomError\n"
        "import io.dataloom.api.error.ErrorCategory\n"
        "import io.dataloom.api.error.ErrorCode\n"
        "import io.dataloom.api.error.ErrorSeverity\n"
        "import io.dataloom.api.error.Recoverability\n",
        "error import",
    )
    text = replace_once(
        text,
        "import io.dataloom.api.provider.ProviderOperationResult\n",
        "import io.dataloom.api.provider.ProviderOperationResult\n"
        "import io.dataloom.api.queue.QueueEnqueueRequest\n"
        "import io.dataloom.api.queue.QueueEntry\n"
        "import io.dataloom.api.queue.QueueEntryState\n"
        "import io.dataloom.api.queue.QueueIdempotentAdmissionProvider\n"
        "import io.dataloom.api.queue.QueueIdempotentAdmissionResult\n",
        "queue import",
    )
    text = replace_once(
        text,
        "import io.dataloom.api.runtime.RuntimeDependencies\n",
        "import io.dataloom.api.runtime.RuntimeDependencies\n"
        "import io.dataloom.api.scheduling.ExistingSchedulePolicy\n"
        "import io.dataloom.api.scheduling.ScheduleConstraints\n"
        "import io.dataloom.api.scheduling.ScheduleRequest\n"
        "import io.dataloom.api.scheduling.SchedulerProvider\n",
        "scheduler import",
    )

    old_execute = class_block(
        """
        suspend fun execute(
            request: StrategySynchronizationRequest,
            evaluation: StrategyEvaluationResult,
            providers: StrategyProviderSet,
        ): StrategySynchronizationExecutionResult {
            if (request.input !is StrategyOperationInput.ProviderBacked) {
                return rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT)
            }

            return when {
                isSupportedLocalServingPlan(evaluation) ->
                    executeLocalServing(request, evaluation, providers)
                isSupportedInlineRefreshPlan(request, evaluation) ->
                    executeInlineRefresh(request, evaluation, providers)
                isSupportedRemotePlan(request, evaluation) ->
                    executeRemotePlan(request, evaluation, providers)
                else -> rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
            }
        }
        """
    )
    new_execute = class_block(
        """
        suspend fun execute(
            request: StrategySynchronizationRequest,
            evaluation: StrategyEvaluationResult,
            providers: StrategyProviderSet,
        ): StrategySynchronizationExecutionResult {
            val durableRefresh = isSupportedDurableRefreshPlan(request, evaluation)
            if (
                durableRefresh &&
                request.input !is StrategyOperationInput.CacheFirstDurableRefresh
            ) {
                return rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT)
            }
            if (!durableRefresh && request.input !is StrategyOperationInput.ProviderBacked) {
                return rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT)
            }

            return when {
                isSupportedLocalServingPlan(evaluation) ->
                    executeLocalServing(request, evaluation, providers)
                isSupportedInlineRefreshPlan(request, evaluation) ->
                    executeInlineRefresh(request, evaluation, providers)
                durableRefresh ->
                    executeDurableRefresh(request, evaluation, providers)
                isSupportedRemotePlan(request, evaluation) ->
                    executeRemotePlan(request, evaluation, providers)
                else -> rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
            }
        }
        """
    )
    text = replace_once(text, old_execute, new_execute, "execute method")

    durable_methods = class_block(
        """
        private suspend fun executeDurableRefresh(
            request: StrategySynchronizationRequest,
            evaluation: StrategyEvaluationResult,
            providers: StrategyProviderSet,
        ): StrategySynchronizationExecutionResult {
            val input = request.input as? StrategyOperationInput.CacheFirstDurableRefresh
                ?: return rejected(evaluation, StrategyExecutionRejectionReason.INCOMPATIBLE_INPUT)
            val queueProvider = providers.queueProvider as? QueueIdempotentAdmissionProvider
                ?: return rejected(
                    evaluation,
                    StrategyExecutionRejectionReason
                        .IDEMPOTENT_QUEUE_ADMISSION_PROVIDER_NOT_CONFIGURED,
                )
            val schedulerProvider = providers.schedulerProvider
                ?: return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
            val queueAdmission = when (val admission = StrategyQueueAdmissionEvaluator.evaluate(evaluation)) {
                is StrategyQueueAdmissionResult.Admitted -> admission
                is StrategyQueueAdmissionResult.Rejected ->
                    return rejected(evaluation, StrategyExecutionRejectionReason.UNSUPPORTED_PLAN)
            }

            return when (val access = evaluateCacheAccess(request, evaluation, providers)) {
                is CacheAccessBoundaryResult.Terminal -> access.result
                is CacheAccessBoundaryResult.Available -> {
                    val refresh = admitAndScheduleDurableRefresh(
                        request = request,
                        evaluation = evaluation,
                        input = input,
                        queueProvider = queueProvider,
                        schedulerProvider = schedulerProvider,
                        persistedDecision = queueAdmission.persistedDecision,
                    )
                    StrategyCacheServedWithDurableRefreshResult(
                        evaluation = evaluation,
                        evaluatedCacheState = access.evaluatedCacheState,
                        freshness = access.freshness,
                        refresh = refresh,
                    )
                }
            }
        }

        private suspend fun admitAndScheduleDurableRefresh(
            request: StrategySynchronizationRequest,
            evaluation: StrategyEvaluationResult,
            input: StrategyOperationInput.CacheFirstDurableRefresh,
            queueProvider: QueueIdempotentAdmissionProvider,
            schedulerProvider: SchedulerProvider,
            persistedDecision: io.dataloom.api.strategy.PersistedStrategyDecision,
        ): StrategyCacheDurableRefreshResult {
            val admittedAt = clock.now()
            val entry = try {
                QueueEntry(
                    id = input.queueEntryId,
                    synchronizationRequest = request.request,
                    state = QueueEntryState.PENDING,
                    enqueuedAt = admittedAt,
                    availableAt = admittedAt,
                    metadata = durableRefreshMetadata(input.scheduleId),
                    strategyDecision = persistedDecision,
                    strategyPlan = evaluation.plan,
                )
            } catch (_: IllegalArgumentException) {
                return StrategyCacheDurableRefreshResult.QueueFailed(
                    queueEntryId = input.queueEntryId,
                    scheduleId = input.scheduleId,
                    error = DurableRefreshAdmissionConstructionError,
                    completedAt = clock.now(),
                )
            }

            return when (val result = queueProvider.admit(QueueEnqueueRequest(entry))) {
                is ProviderOperationResult.Failure ->
                    StrategyCacheDurableRefreshResult.QueueFailed(
                        queueEntryId = input.queueEntryId,
                        scheduleId = input.scheduleId,
                        error = result.error,
                        completedAt = clock.now(),
                    )
                is ProviderOperationResult.Success ->
                    mapDurableQueueAdmission(
                        input = input,
                        schedulerProvider = schedulerProvider,
                        admission = result.value,
                    )
            }
        }

        private suspend fun mapDurableQueueAdmission(
            input: StrategyOperationInput.CacheFirstDurableRefresh,
            schedulerProvider: SchedulerProvider,
            admission: QueueIdempotentAdmissionResult,
        ): StrategyCacheDurableRefreshResult {
            if (admission.queueEntryId != input.queueEntryId) {
                return StrategyCacheDurableRefreshResult.QueueFailed(
                    queueEntryId = input.queueEntryId,
                    scheduleId = input.scheduleId,
                    error = DurableRefreshQueueIdentityMismatchError,
                    completedAt = clock.now(),
                )
            }
            return when (admission) {
                is QueueIdempotentAdmissionResult.Accepted ->
                    scheduleDurableRefresh(
                        input = input,
                        schedulerProvider = schedulerProvider,
                        queueDisposition =
                            StrategyCacheDurableQueueAdmissionDisposition.ACCEPTED,
                        queueState = admission.currentState,
                    )
                is QueueIdempotentAdmissionResult.AlreadyAccepted ->
                    when (admission.currentState) {
                        QueueEntryState.PENDING ->
                            scheduleDurableRefresh(
                                input = input,
                                schedulerProvider = schedulerProvider,
                                queueDisposition =
                                    StrategyCacheDurableQueueAdmissionDisposition
                                        .ALREADY_ACCEPTED,
                                queueState = admission.currentState,
                            )
                        QueueEntryState.LEASED,
                        QueueEntryState.RETRY_WAITING,
                        -> StrategyCacheDurableRefreshResult.AlreadyInProgress(
                            queueEntryId = input.queueEntryId,
                            scheduleId = input.scheduleId,
                            queueState = admission.currentState,
                            completedAt = clock.now(),
                        )
                        QueueEntryState.COMPLETED,
                        QueueEntryState.FAILED,
                        QueueEntryState.CANCELLED,
                        QueueEntryState.DEAD_LETTER,
                        -> StrategyCacheDurableRefreshResult.AlreadyTerminal(
                            queueEntryId = input.queueEntryId,
                            scheduleId = input.scheduleId,
                            queueState = admission.currentState,
                            completedAt = clock.now(),
                        )
                    }
                is QueueIdempotentAdmissionResult.IdentityConflict ->
                    StrategyCacheDurableRefreshResult.IdentityConflict(
                        queueEntryId = input.queueEntryId,
                        scheduleId = input.scheduleId,
                        currentState = admission.currentState,
                        completedAt = clock.now(),
                    )
            }
        }

        private suspend fun scheduleDurableRefresh(
            input: StrategyOperationInput.CacheFirstDurableRefresh,
            schedulerProvider: SchedulerProvider,
            queueDisposition: StrategyCacheDurableQueueAdmissionDisposition,
            queueState: QueueEntryState,
        ): StrategyCacheDurableRefreshResult {
            val request = ScheduleRequest(
                id = input.scheduleId,
                synchronizationRequest = null,
                constraints = ScheduleConstraints(
                    connectivity = ConnectivityRequirement.AVAILABLE,
                ),
                existingPolicy = ExistingSchedulePolicy.KEEP,
            )
            return when (val result = schedulerProvider.schedule(request)) {
                is ProviderOperationResult.Failure ->
                    StrategyCacheDurableRefreshResult.ScheduleFailed(
                        queueEntryId = input.queueEntryId,
                        scheduleId = input.scheduleId,
                        queueAdmissionDisposition = queueDisposition,
                        queueState = queueState,
                        error = result.error,
                        completedAt = clock.now(),
                    )
                is ProviderOperationResult.Success -> {
                    if (result.value.id != input.scheduleId) {
                        StrategyCacheDurableRefreshResult.ScheduleFailed(
                            queueEntryId = input.queueEntryId,
                            scheduleId = input.scheduleId,
                            queueAdmissionDisposition = queueDisposition,
                            queueState = queueState,
                            error = DurableRefreshScheduleIdentityMismatchError,
                            completedAt = clock.now(),
                        )
                    } else {
                        StrategyCacheDurableRefreshResult.Scheduled(
                            queueEntryId = input.queueEntryId,
                            scheduleId = input.scheduleId,
                            queueAdmissionDisposition = queueDisposition,
                            queueState = queueState,
                            receipt = result.value,
                            completedAt = clock.now(),
                        )
                    }
                }
            }
        }
        """
    )
    text = replace_once(
        text,
        "    private suspend fun executeLocalServing(\n",
        durable_methods + "    private suspend fun executeLocalServing(\n",
        "local-serving method",
    )

    supported_plan = class_block(
        """
        private fun isSupportedDurableRefreshPlan(
            request: StrategySynchronizationRequest,
            evaluation: StrategyEvaluationResult,
        ): Boolean {
            val plan = evaluation.plan
            val continuation = plan.durableContinuation ?: return false
            return request.request.direction == SynchronizationDirection.PULL &&
                (
                    request.evidence.cacheState == StrategyCacheState.FRESH ||
                        request.evidence.cacheState == StrategyCacheState.STALE
                    ) &&
                plan.effectiveStrategy == BuiltInSynchronizationStrategy.CACHE_FIRST &&
                plan.disposition == StrategyDisposition.SERVE_AND_REFRESH &&
                plan.operations == listOf(
                    StrategyOperation.SERVE_LOCAL,
                    StrategyOperation.ENQUEUE_DURABLE_WORK,
                    StrategyOperation.SCHEDULE_REFRESH,
                ) &&
                plan.requiredCapabilities == setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.CACHE_ACCESS,
                    StrategyProviderCapability.QUEUE,
                    StrategyProviderCapability.SCHEDULER,
                ) &&
                plan.dataOrigin == StrategyDataOrigin.LOCAL &&
                plan.fallbackPlan == null &&
                continuation.operations == listOf(
                    StrategyOperation.READ_CHECKPOINT,
                    StrategyOperation.PULL_REMOTE,
                    StrategyOperation.PERSIST_REMOTE,
                ) &&
                continuation.requiredCapabilities == setOf(
                    StrategyProviderCapability.STORAGE,
                    StrategyProviderCapability.TRANSPORT,
                ) &&
                continuation.dataOrigin == StrategyDataOrigin.REMOTE &&
                continuation.consistency == plan.consistency &&
                continuation.evaluatedCacheState == request.evidence.cacheState &&
                continuation.fallbackPlan == null
        }
        """
    )
    text = replace_once(
        text,
        "    private fun isSupportedRemotePlan(\n",
        supported_plan + "    private fun isSupportedRemotePlan(\n",
        "remote-plan method",
    )

    helpers = "\n\n" + dedent(
        """
        private fun durableRefreshMetadata(
            scheduleId: io.dataloom.api.identifier.ScheduleId,
        ): DataLoomMetadata = DataLoomMetadata.of(
            mapOf(DURABLE_REFRESH_SCHEDULE_ID_METADATA_KEY to scheduleId.value),
        )

        private const val DURABLE_REFRESH_SCHEDULE_ID_METADATA_KEY: String =
            "dataloom-schedule-id"

        private object DurableRefreshAdmissionConstructionError : DataLoomError {
            override val code: ErrorCode =
                ErrorCode("STRATEGY_DURABLE_REFRESH_ADMISSION_CONSTRUCTION_FAILED")
            override val category: ErrorCategory = ErrorCategory.STATE
            override val severity: ErrorSeverity = ErrorSeverity.ERROR
            override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
            override val message: String =
                "Durable cache refresh work could not be represented by the accepted queue model."
            override val cause: Throwable? = null
        }

        private object DurableRefreshQueueIdentityMismatchError : DataLoomError {
            override val code: ErrorCode =
                ErrorCode("STRATEGY_DURABLE_REFRESH_QUEUE_IDENTITY_MISMATCH")
            override val category: ErrorCategory = ErrorCategory.PROVIDER
            override val severity: ErrorSeverity = ErrorSeverity.ERROR
            override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
            override val message: String =
                "Queue provider returned a different durable refresh identity."
            override val cause: Throwable? = null
        }

        private object DurableRefreshScheduleIdentityMismatchError : DataLoomError {
            override val code: ErrorCode =
                ErrorCode("STRATEGY_DURABLE_REFRESH_SCHEDULE_IDENTITY_MISMATCH")
            override val category: ErrorCategory = ErrorCategory.PROVIDER
            override val severity: ErrorSeverity = ErrorSeverity.ERROR
            override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
            override val message: String =
                "Scheduler provider returned a different durable refresh identity."
            override val cause: Throwable? = null
        }
        """
    ).strip("\n") + "\n"
    EXECUTOR_PATH.write_text(text.rstrip() + helpers)


def rewrite_dependency_neutral_test() -> None:
    text = TEST_PATH.read_text()
    text = replace_once(
        text,
        "import kotlinx.coroutines.test.runTest\n",
        "import kotlin.coroutines.Continuation\n"
        "import kotlin.coroutines.EmptyCoroutineContext\n"
        "import kotlin.coroutines.startCoroutine\n",
        "runTest import",
    )
    count = text.count("= runTest {")
    if count == 0:
        raise RuntimeError("Expected durable-refresh runTest usages")
    text = text.replace("= runTest {", "= runSuspend {")

    helper = class_block(
        """
        /** Runs the deterministic, immediately completing suspend test body. */
        private fun <T> runSuspend(block: suspend () -> T): T {
            var outcome: Result<T>? = null
            block.startCoroutine(
                object : Continuation<T> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<T>) {
                        outcome = result
                    }
                },
            )
            return requireNotNull(outcome) {
                "Durable refresh test operation did not complete synchronously."
            }.getOrThrow()
        }
        """
    )
    text = replace_once(
        text,
        "    private companion object {\n",
        helper + "    private companion object {\n",
        "companion object",
    )
    TEST_PATH.write_text(text)


def main() -> None:
    apply_reviewed_patch()
    reconstruct_executor()
    rewrite_dependency_neutral_test()


if __name__ == "__main__":
    main()
