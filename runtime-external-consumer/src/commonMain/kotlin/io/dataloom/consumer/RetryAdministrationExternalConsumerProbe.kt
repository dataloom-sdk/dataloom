package io.dataloom.consumer

import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.retry.RetryAdministrationAction
import io.dataloom.api.retry.RetryAdministrationAuthorizer
import io.dataloom.api.retry.RetryAdministrationCommandId
import io.dataloom.api.retry.RetryAdministrationExecutor
import io.dataloom.api.retry.RetryAdministrationPrincipalId
import io.dataloom.api.retry.RetryAdministrationReason
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.retry.RetryAdministrationStateStore
import io.dataloom.api.retry.RetryFailureSnapshot
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.RetryAdministrationCoordinator
import io.dataloom.runtime.retry.RetryAdministrationResult

public fun retryAdministrationRequestExternalProbe(): RetryAdministrationRequest =
    RetryAdministrationRequest(
        commandId = RetryAdministrationCommandId("external-command"),
        queueEntryId = QueueEntryId("external-entry"),
        principalId = RetryAdministrationPrincipalId("external-operator"),
        requestedAt = DataLoomInstant(epochMilliseconds = 1L),
        action = RetryAdministrationAction.RECLASSIFY_AND_REQUEUE,
        reason = RetryAdministrationReason("external authorized recovery"),
        originalFailure = RetryFailureSnapshot(
            code = ErrorCode("EXTERNAL_FAILURE"),
            category = ErrorCategory.POLICY,
            severity = ErrorSeverity.ERROR,
            recoverability = Recoverability.NON_RECOVERABLE,
        ),
    )

public suspend fun retryAdministrationCoordinatorExternalProbe(
    clock: DataLoomClock,
    authorizer: RetryAdministrationAuthorizer,
    stateStore: RetryAdministrationStateStore,
    executor: RetryAdministrationExecutor,
    request: RetryAdministrationRequest,
): RetryAdministrationResult = RetryAdministrationCoordinator(
    clock = clock,
    authorizer = authorizer,
    stateStore = stateStore,
    executor = executor,
).execute(request)
