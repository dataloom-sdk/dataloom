package io.dataloom.runtime.strategy

import io.dataloom.api.change.ChangeSet
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.execution.StrategyProviderSet
import io.dataloom.api.model.SynchronizationDirection
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.strategy.StrategyEvaluationResult
import io.dataloom.api.strategy.StrategyOperationInput
import io.dataloom.api.strategy.StrategyOperation
import io.dataloom.api.strategy.StrategySynchronizationRequest
import io.dataloom.api.strategy.StrategyTransportOutput
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest

/**
 * Executes a validated network-only plan using transport and no other provider.
 *
 * This type has no storage, queue, scheduler, or connectivity dependency, so
 * those providers cannot be invoked accidentally on any branch.
 */
internal class NetworkOnlyStrategyExecutor(
    private val clock: DataLoomClock,
) {
    public suspend fun execute(
        request: StrategySynchronizationRequest,
        evaluation: StrategyEvaluationResult,
        providers: StrategyProviderSet,
    ): StrategySynchronizationExecutionResult {
        val input = request.input as StrategyOperationInput.DirectTransport
        val transport = requireNotNull(providers.transportProvider) {
            "Network-only execution requires a resolved transport provider."
        }

        return when (request.request.direction) {
            SynchronizationDirection.PUSH -> {
                when (
                    val pushed = push(
                        request = request,
                        changeSet = requireNotNull(input.outboundChangeSet),
                        transport = transport,
                    )
                ) {
                    is DirectPushResult.Failed -> failed(evaluation, pushed.error)
                    is DirectPushResult.Succeeded -> executed(
                        evaluation,
                        StrategyTransportOutput.Pushed(pushed.acknowledgement),
                    )
                }
            }

            SynchronizationDirection.PULL -> {
                when (val pulled = pull(request, input, transport)) {
                    is ProviderOperationResult.Failure -> failed(evaluation, pulled.error)
                    is ProviderOperationResult.Success -> executed(
                        evaluation,
                        StrategyTransportOutput.Pulled(pulled.value),
                    )
                }
            }

            SynchronizationDirection.BIDIRECTIONAL -> {
                when (
                    val pushed = push(
                        request = request,
                        changeSet = requireNotNull(input.outboundChangeSet),
                        transport = transport,
                    )
                ) {
                    is DirectPushResult.Failed -> failed(evaluation, pushed.error)
                    is DirectPushResult.Succeeded -> {
                        when (val pulled = pull(request, input, transport)) {
                            is ProviderOperationResult.Failure ->
                                failed(
                                    evaluation = evaluation,
                                    error = pulled.error,
                                    completedOperations = listOf(
                                        StrategyOperation.PUSH_REMOTE,
                                    ),
                                    partialOutput = StrategyTransportOutput.Pushed(
                                        pushed.acknowledgement,
                                    ),
                                )
                            is ProviderOperationResult.Success ->
                                executed(
                                    evaluation,
                                    StrategyTransportOutput.Bidirectional(
                                        acknowledgement = pushed.acknowledgement,
                                        pullResult = pulled.value,
                                    ),
                                )
                        }
                    }
                }
            }
        }
    }

    private suspend fun push(
        request: StrategySynchronizationRequest,
        changeSet: ChangeSet,
        transport: io.dataloom.api.transport.TransportProvider,
    ): DirectPushResult =
        when (
            val outcome = transport.pushChanges(
                PushChangesRequest(
                    request = request.request,
                    changeSet = changeSet,
                ),
            )
        ) {
            is ProviderOperationResult.Failure -> DirectPushResult.Failed(outcome.error)
            is ProviderOperationResult.Success -> {
                val error = validateAcknowledgement(changeSet, outcome.value)
                if (error == null) {
                    DirectPushResult.Succeeded(outcome.value)
                } else {
                    DirectPushResult.Failed(error)
                }
            }
        }

    private suspend fun pull(
        request: StrategySynchronizationRequest,
        input: StrategyOperationInput.DirectTransport,
        transport: io.dataloom.api.transport.TransportProvider,
    ): ProviderOperationResult<PullChangesResult> =
        transport.pullChanges(
            PullChangesRequest(
                request = request.request,
                entityTypes = input.entityTypes,
                maxEvents = input.maxEvents,
                checkpoint = input.checkpoint,
            ),
        )

    private fun executed(
        evaluation: StrategyEvaluationResult,
        output: StrategyTransportOutput,
    ): StrategySynchronizationExecutionResult =
        StrategySynchronizationExecutionResult.Executed(
            evaluation = evaluation,
            completedAt = clock.now(),
            output = output,
        )

    private fun failed(
        evaluation: StrategyEvaluationResult,
        error: DataLoomError,
        completedOperations: List<StrategyOperation> = emptyList(),
        partialOutput: StrategyTransportOutput? = null,
    ): StrategySynchronizationExecutionResult =
        StrategySynchronizationExecutionResult.Failed(
            evaluation = evaluation,
            completedAt = clock.now(),
            error = error,
            transportAttempted = true,
            completedOperations = completedOperations,
            partialOutput = partialOutput,
        )

    private fun validateAcknowledgement(
        changeSet: ChangeSet,
        acknowledgement: ChangeSetAcknowledgement,
    ): DataLoomError? {
        if (acknowledgement.changeSetId != changeSet.id) {
            return contractError(
                code = "DL-STRATEGY-NETWORK-ACK-CHANGESET-MISMATCH",
                message = "Acknowledgement changeSetId does not match the pushed ChangeSet.id.",
            )
        }

        val pushedIds = changeSet.events.map { it.id }.toSet()
        val acknowledgedIds = acknowledgement.events.map { it.eventId }.toSet()
        if (acknowledgedIds.any { it !in pushedIds }) {
            return contractError(
                code = "DL-STRATEGY-NETWORK-ACK-UNKNOWN-EVENT",
                message = "Acknowledgement references an unknown change-event identifier.",
            )
        }
        if (pushedIds.any { it !in acknowledgedIds }) {
            return contractError(
                code = "DL-STRATEGY-NETWORK-ACK-MISSING-EVENT",
                message = "Acknowledgement is missing one or more pushed change-event identifiers.",
            )
        }
        return null
    }

    private fun contractError(code: String, message: String): DataLoomError =
        NetworkOnlyContractError(
            code = ErrorCode(code),
            message = message,
        )

    private sealed interface DirectPushResult {
        public data class Succeeded(
            public val acknowledgement: ChangeSetAcknowledgement,
        ) : DirectPushResult

        public data class Failed(
            public val error: DataLoomError,
        ) : DirectPushResult
    }

    private data class NetworkOnlyContractError(
        override val code: ErrorCode,
        override val message: String,
        override val category: ErrorCategory = ErrorCategory.VALIDATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
