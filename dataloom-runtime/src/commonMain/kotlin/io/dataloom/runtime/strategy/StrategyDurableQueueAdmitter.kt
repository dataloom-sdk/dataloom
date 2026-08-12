package io.dataloom.runtime.strategy

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.provider.SynchronizationProviderBindings
import io.dataloom.api.runtime.RuntimeDependencies
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.queue.QueuedSynchronizationWork
import io.dataloom.runtime.submission.QueueSubmissionPreflight
import io.dataloom.runtime.submission.QueueSubmissionPreflightResult
import io.dataloom.runtime.submission.QueuedSynchronizationSubmission
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder

/** Outcome of one attempted durable-queue admission for an evaluated strategy plan. */
internal sealed interface StrategyDurableQueueAdmissionOutcome {

    /** No [QueuedSynchronizationWorkEncoder] was configured; the caller keeps its current behavior. */
    data object NotConfigured : StrategyDurableQueueAdmissionOutcome

    /** The plan was durably enqueued under [queueEntryId]. */
    data class Admitted(val queueEntryId: QueueEntryId) : StrategyDurableQueueAdmissionOutcome

    /** The plan or resolved providers cannot support durable admission. */
    data class Rejected(
        val reason: StrategyExecutionRejectionReason,
    ) : StrategyDurableQueueAdmissionOutcome

    /** Encoding or the queue provider itself failed. */
    data class Failed(val error: DataLoomError) : StrategyDurableQueueAdmissionOutcome
}

/**
 * Converts one evaluated strategy plan requiring `ENQUEUE_DURABLE_WORK` into a
 * real, persisted [io.dataloom.api.queue.QueueEntry].
 *
 * ## What this bridges
 *
 * [StrategyQueueAdmissionEvaluator] already validates plan eligibility and
 * builds the immutable [io.dataloom.api.strategy.PersistedStrategyDecision]
 * but performs no I/O. [io.dataloom.runtime.submission.QueueSubmissionPreflight]
 * already encodes and structurally validates a
 * [QueuedSynchronizationSubmission] but requires an application-owned
 * [QueuedSynchronizationWorkEncoder] instance and a caller-generated
 * [QueueEntryId]/`availableAt`. Nothing in this codebase previously connected
 * the two for strategy-evaluation-triggered admission — this class is that
 * connection.
 *
 * ## Opt-in, backward compatible
 *
 * [encoder] is `null` by default. When `null`, [admit] always returns
 * [StrategyDurableQueueAdmissionOutcome.NotConfigured] without resolving
 * providers, generating an identifier, reading the clock, or calling any
 * provider — callers fall back to their pre-existing behavior unchanged
 * (e.g. [StrategyExecutionRejectionReason.DURABLE_REFRESH_NOT_YET_SUPPORTED]).
 * Configuring an encoder ([io.dataloom.runtime.facade.DataLoomBuilder.queueSubmissionEncoder]
 * or [io.dataloom.runtime.facade.DataLoomBuilder.queueSubmissionConfiguration])
 * is the only way to opt into real durable admission from strategy evaluation.
 *
 * ## Identifier and clock semantics
 *
 * [QueueEntryId] is generated fresh via
 * [io.dataloom.api.runtime.RuntimeIdentifierGenerators.queueEntryIds] for
 * every admission attempt — there is no idempotency key derived from the
 * strategy decision, so a caller that retries the same logical
 * `DataLoom.synchronize` call after a [StrategyDurableQueueAdmissionOutcome.Failed]
 * outcome may enqueue a duplicate entry; application-level deduplication is
 * the caller's responsibility, the same boundary
 * [io.dataloom.runtime.submission.DataLoomQueueSubmission] already documents.
 * `availableAt` is always `clock.now()` — the durable work this class admits
 * is meant to become eligible immediately, not scheduled for a future time.
 */
internal class StrategyDurableQueueAdmitter(
    private val clock: DataLoomClock,
    private val runtimeDependencies: RuntimeDependencies,
    private val encoder: QueuedSynchronizationWorkEncoder?,
) {

    suspend fun admit(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategyDurableQueueAdmissionOutcome {
        val workEncoder = encoder ?: return StrategyDurableQueueAdmissionOutcome.NotConfigured

        val admission = when (
            val result = StrategyQueueAdmissionEvaluator.evaluate(evaluation)
        ) {
            is StrategyQueueAdmissionResult.Admitted -> result
            is StrategyQueueAdmissionResult.Rejected -> return StrategyDurableQueueAdmissionOutcome.Rejected(
                StrategyExecutionRejectionReason.UNSUPPORTED_PLAN,
            )
        }

        val queueProvider = providers.queueProvider
            ?: return StrategyDurableQueueAdmissionOutcome.Rejected(
                StrategyExecutionRejectionReason.DURABLE_ADMISSION_PROVIDERS_INCOMPLETE,
            )
        val storageProviderId = providers.storageProvider?.descriptor?.id
            ?: return StrategyDurableQueueAdmissionOutcome.Rejected(
                StrategyExecutionRejectionReason.DURABLE_ADMISSION_PROVIDERS_INCOMPLETE,
            )
        val transportProviderId = providers.transportProvider?.descriptor?.id
            ?: return StrategyDurableQueueAdmissionOutcome.Rejected(
                StrategyExecutionRejectionReason.DURABLE_ADMISSION_PROVIDERS_INCOMPLETE,
            )

        val work = QueuedSynchronizationWork(
            request = request.request,
            bindings = SynchronizationProviderBindings(
                storageProviderId = storageProviderId,
                transportProviderId = transportProviderId,
                schedulerProviderId = providers.schedulerProvider?.descriptor?.id,
                connectivityProviderId = providers.connectivityProvider?.descriptor?.id,
                queueProviderId = queueProvider.descriptor.id,
            ),
            strategyDecision = admission.persistedDecision,
            strategyPlan = admission.plan,
        )
        val submission = QueuedSynchronizationSubmission(
            queueEntryId = runtimeDependencies.identifiers.queueEntryIds.generate(),
            work = work,
            availableAt = clock.now(),
        )

        return when (val prepared = QueueSubmissionPreflight(workEncoder).prepare(submission)) {
            is QueueSubmissionPreflightResult.EncodingRejected ->
                StrategyDurableQueueAdmissionOutcome.Failed(prepared.error)
            is QueueSubmissionPreflightResult.ContractViolation ->
                StrategyDurableQueueAdmissionOutcome.Failed(prepared.error)
            is QueueSubmissionPreflightResult.Ready -> when (
                val enqueued = queueProvider.enqueue(prepared.request)
            ) {
                is ProviderOperationResult.Success ->
                    StrategyDurableQueueAdmissionOutcome.Admitted(submission.queueEntryId)
                is ProviderOperationResult.Failure ->
                    StrategyDurableQueueAdmissionOutcome.Failed(enqueued.error)
            }
        }
    }
}
