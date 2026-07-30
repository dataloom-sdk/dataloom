package io.dataloom.runtime.retry

import io.dataloom.api.circuit.CircuitBreakerCompareAndSetRequest
import io.dataloom.api.circuit.CircuitBreakerCompareAndSetResult
import io.dataloom.api.circuit.CircuitBreakerLoadResult
import io.dataloom.api.circuit.CircuitBreakerPhase
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerState
import io.dataloom.api.circuit.CircuitBreakerStateRecord
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant

/**
 * Deterministic closed/open/half-open circuit-breaker coordinator.
 *
 * The coordinator uses atomic compare-and-set persistence for every transition.
 * One half-open generation permits exactly one probe. Concurrent contenders see
 * either a compare-and-set conflict or a persisted probe-in-flight rejection.
 */
public class CircuitBreakerCoordinator(
    private val configuration: CircuitBreakerConfiguration,
    private val clock: DataLoomClock,
    private val stateStore: CircuitBreakerStateStore,
) {
    /** Checks whether an operation may start for [scope]. */
    public suspend fun acquire(
        scope: CircuitBreakerScope,
    ): CircuitBreakerPermission {
        val observedAt = clock.now()
        repeat(configuration.maximumStateUpdateAttempts) {
            val current = when (val loaded = load(scope)) {
                is LoadOutcome.Failure -> {
                    return CircuitBreakerPermission.PersistenceFailure(loaded.error)
                }
                is LoadOutcome.Success -> loaded.record
            }

            when (val transition = evaluateAccess(current?.state, scope, observedAt)) {
                AccessTransition.Allowed -> return CircuitBreakerPermission.Allowed
                is AccessTransition.Rejected -> {
                    return CircuitBreakerPermission.Rejected(
                        reason = transition.reason,
                        retryAt = transition.retryAt,
                    )
                }
                is AccessTransition.StartProbe -> {
                    when (val update = compareAndSet(current, transition.nextState)) {
                        is UpdateOutcome.Failure -> {
                            return CircuitBreakerPermission.PersistenceFailure(update.error)
                        }
                        UpdateOutcome.Conflict -> Unit
                        is UpdateOutcome.Updated -> {
                            return CircuitBreakerPermission.ProbeAllowed(
                                permit = transition.permit,
                                record = update.record,
                            )
                        }
                    }
                }
            }
        }
        return CircuitBreakerPermission.ContentionLimitReached
    }

    /** Records a successful operation or controlled half-open probe. */
    public suspend fun recordSuccess(
        scope: CircuitBreakerScope,
        probePermit: CircuitBreakerProbePermit? = null,
    ): CircuitBreakerRecordResult = recordOutcome(
        scope = scope,
        outcome = CircuitOutcome.SUCCESS,
        probePermit = probePermit,
    )

    /** Records an eligible failure or failed controlled half-open probe. */
    public suspend fun recordFailure(
        scope: CircuitBreakerScope,
        probePermit: CircuitBreakerProbePermit? = null,
    ): CircuitBreakerRecordResult = recordOutcome(
        scope = scope,
        outcome = CircuitOutcome.FAILURE,
        probePermit = probePermit,
    )

    private suspend fun recordOutcome(
        scope: CircuitBreakerScope,
        outcome: CircuitOutcome,
        probePermit: CircuitBreakerProbePermit?,
    ): CircuitBreakerRecordResult {
        val observedAt = clock.now()
        repeat(configuration.maximumStateUpdateAttempts) {
            val current = when (val loaded = load(scope)) {
                is LoadOutcome.Failure -> {
                    return CircuitBreakerRecordResult.PersistenceFailure(loaded.error)
                }
                is LoadOutcome.Success -> loaded.record
            }

            when (val transition = evaluateOutcome(
                current = current?.state,
                scope = scope,
                observedAt = observedAt,
                outcome = outcome,
                probePermit = probePermit,
            )) {
                OutcomeTransition.NoChange -> return CircuitBreakerRecordResult.Ignored
                OutcomeTransition.StaleProbe -> return CircuitBreakerRecordResult.StaleProbe
                is OutcomeTransition.ClockRegression -> {
                    return CircuitBreakerRecordResult.ClockRegression(
                        observedAt = observedAt,
                        persistedAt = transition.persistedAt,
                    )
                }
                is OutcomeTransition.Update -> {
                    when (val update = compareAndSet(current, transition.nextState)) {
                        is UpdateOutcome.Failure -> {
                            return CircuitBreakerRecordResult.PersistenceFailure(update.error)
                        }
                        UpdateOutcome.Conflict -> Unit
                        is UpdateOutcome.Updated -> {
                            return CircuitBreakerRecordResult.Recorded(update.record)
                        }
                    }
                }
            }
        }
        return CircuitBreakerRecordResult.ContentionLimitReached
    }

    private suspend fun load(scope: CircuitBreakerScope): LoadOutcome =
        when (val result = stateStore.load(scope)) {
            is ProviderOperationResult.Failure -> LoadOutcome.Failure(result.error)
            is ProviderOperationResult.Success -> when (val value = result.value) {
                CircuitBreakerLoadResult.Missing -> LoadOutcome.Success(null)
                is CircuitBreakerLoadResult.Found -> {
                    check(value.record.state.scope == scope) {
                        "CircuitBreakerStateStore returned a record for a different scope."
                    }
                    LoadOutcome.Success(value.record)
                }
            }
        }

    private suspend fun compareAndSet(
        current: CircuitBreakerStateRecord?,
        nextState: CircuitBreakerState,
    ): UpdateOutcome = when (val result = stateStore.compareAndSet(
        CircuitBreakerCompareAndSetRequest(
            scope = nextState.scope,
            expectedVersion = current?.version,
            nextState = nextState,
        ),
    )) {
        is ProviderOperationResult.Failure -> UpdateOutcome.Failure(result.error)
        is ProviderOperationResult.Success -> when (val value = result.value) {
            is CircuitBreakerCompareAndSetResult.Conflict -> UpdateOutcome.Conflict
            is CircuitBreakerCompareAndSetResult.Updated -> UpdateOutcome.Updated(value.record)
        }
    }

    private fun evaluateAccess(
        current: CircuitBreakerState?,
        scope: CircuitBreakerScope,
        observedAt: DataLoomInstant,
    ): AccessTransition {
        current ?: return AccessTransition.Allowed
        if (observedAt.epochMilliseconds < current.updatedAt.epochMilliseconds) {
            return AccessTransition.Rejected(CircuitBreakerRejectionReason.CLOCK_REGRESSION)
        }

        return when (current.phase) {
            CircuitBreakerPhase.CLOSED -> AccessTransition.Allowed
            CircuitBreakerPhase.HALF_OPEN -> AccessTransition.Rejected(
                CircuitBreakerRejectionReason.PROBE_IN_FLIGHT,
            )
            CircuitBreakerPhase.OPEN -> {
                val retryAt = checkNotNull(current.openUntil)
                if (observedAt.epochMilliseconds < retryAt.epochMilliseconds) {
                    AccessTransition.Rejected(
                        reason = CircuitBreakerRejectionReason.OPEN,
                        retryAt = retryAt,
                    )
                } else if (current.probeGeneration == Long.MAX_VALUE) {
                    AccessTransition.Rejected(
                        CircuitBreakerRejectionReason.PROBE_GENERATION_EXHAUSTED,
                    )
                } else {
                    val generation = current.probeGeneration + 1L
                    AccessTransition.StartProbe(
                        nextState = CircuitBreakerState(
                            scope = scope,
                            phase = CircuitBreakerPhase.HALF_OPEN,
                            consecutiveFailures = 0,
                            failureWindowStartedAt = null,
                            openUntil = null,
                            probeGeneration = generation,
                            probeInFlight = true,
                            updatedAt = observedAt,
                        ),
                        permit = CircuitBreakerProbePermit(scope, generation),
                    )
                }
            }
        }
    }

    private fun evaluateOutcome(
        current: CircuitBreakerState?,
        scope: CircuitBreakerScope,
        observedAt: DataLoomInstant,
        outcome: CircuitOutcome,
        probePermit: CircuitBreakerProbePermit?,
    ): OutcomeTransition {
        if (current != null && observedAt.epochMilliseconds < current.updatedAt.epochMilliseconds) {
            return OutcomeTransition.ClockRegression(current.updatedAt)
        }

        return when (outcome) {
            CircuitOutcome.SUCCESS -> evaluateSuccess(current, scope, observedAt, probePermit)
            CircuitOutcome.FAILURE -> evaluateFailure(current, scope, observedAt, probePermit)
        }
    }

    private fun evaluateSuccess(
        current: CircuitBreakerState?,
        scope: CircuitBreakerScope,
        observedAt: DataLoomInstant,
        probePermit: CircuitBreakerProbePermit?,
    ): OutcomeTransition = when (current?.phase) {
        null -> OutcomeTransition.NoChange
        CircuitBreakerPhase.OPEN -> OutcomeTransition.NoChange
        CircuitBreakerPhase.CLOSED -> {
            if (current.consecutiveFailures == 0) {
                OutcomeTransition.NoChange
            } else {
                OutcomeTransition.Update(closedState(scope, observedAt, current.probeGeneration))
            }
        }
        CircuitBreakerPhase.HALF_OPEN -> {
            if (!matchesProbe(current, probePermit)) {
                OutcomeTransition.StaleProbe
            } else {
                OutcomeTransition.Update(closedState(scope, observedAt, current.probeGeneration))
            }
        }
    }

    private fun evaluateFailure(
        current: CircuitBreakerState?,
        scope: CircuitBreakerScope,
        observedAt: DataLoomInstant,
        probePermit: CircuitBreakerProbePermit?,
    ): OutcomeTransition = when (current?.phase) {
        null -> firstFailure(scope, observedAt)
        CircuitBreakerPhase.OPEN -> OutcomeTransition.NoChange
        CircuitBreakerPhase.HALF_OPEN -> {
            if (!matchesProbe(current, probePermit)) {
                OutcomeTransition.StaleProbe
            } else {
                OutcomeTransition.Update(openState(scope, observedAt, current.probeGeneration))
            }
        }
        CircuitBreakerPhase.CLOSED -> closedFailure(current, observedAt)
    }

    private fun firstFailure(
        scope: CircuitBreakerScope,
        observedAt: DataLoomInstant,
    ): OutcomeTransition = if (configuration.failureThreshold == 1) {
        OutcomeTransition.Update(openState(scope, observedAt, probeGeneration = 0L))
    } else {
        OutcomeTransition.Update(
            CircuitBreakerState(
                scope = scope,
                phase = CircuitBreakerPhase.CLOSED,
                consecutiveFailures = 1,
                failureWindowStartedAt = observedAt,
                openUntil = null,
                probeGeneration = 0L,
                probeInFlight = false,
                updatedAt = observedAt,
            ),
        )
    }

    private fun closedFailure(
        current: CircuitBreakerState,
        observedAt: DataLoomInstant,
    ): OutcomeTransition {
        val startedAt = current.failureWindowStartedAt
        val insideWindow = startedAt != null &&
            observedAt.epochMilliseconds - startedAt.epochMilliseconds <=
            configuration.failureWindow.milliseconds
        val reachesThreshold = insideWindow &&
            current.consecutiveFailures >= configuration.failureThreshold - 1

        if (configuration.failureThreshold == 1 || reachesThreshold) {
            return OutcomeTransition.Update(
                openState(current.scope, observedAt, current.probeGeneration),
            )
        }

        return OutcomeTransition.Update(
            CircuitBreakerState(
                scope = current.scope,
                phase = CircuitBreakerPhase.CLOSED,
                consecutiveFailures = if (insideWindow) current.consecutiveFailures + 1 else 1,
                failureWindowStartedAt = if (insideWindow) startedAt else observedAt,
                openUntil = null,
                probeGeneration = current.probeGeneration,
                probeInFlight = false,
                updatedAt = observedAt,
            ),
        )
    }

    private fun closedState(
        scope: CircuitBreakerScope,
        observedAt: DataLoomInstant,
        probeGeneration: Long,
    ): CircuitBreakerState = CircuitBreakerState(
        scope = scope,
        phase = CircuitBreakerPhase.CLOSED,
        consecutiveFailures = 0,
        failureWindowStartedAt = null,
        openUntil = null,
        probeGeneration = probeGeneration,
        probeInFlight = false,
        updatedAt = observedAt,
    )

    private fun openState(
        scope: CircuitBreakerScope,
        observedAt: DataLoomInstant,
        probeGeneration: Long,
    ): CircuitBreakerState = CircuitBreakerState(
        scope = scope,
        phase = CircuitBreakerPhase.OPEN,
        consecutiveFailures = 0,
        failureWindowStartedAt = null,
        openUntil = DataLoomInstant(
            addSaturated(
                observedAt.epochMilliseconds,
                configuration.openDuration.milliseconds,
            ),
        ),
        probeGeneration = probeGeneration,
        probeInFlight = false,
        updatedAt = observedAt,
    )

    private fun matchesProbe(
        current: CircuitBreakerState,
        permit: CircuitBreakerProbePermit?,
    ): Boolean = permit != null &&
        permit.scope == current.scope &&
        permit.generation == current.probeGeneration
}

private enum class CircuitOutcome {
    SUCCESS,
    FAILURE,
}

private sealed interface AccessTransition {
    data object Allowed : AccessTransition

    data class Rejected(
        val reason: CircuitBreakerRejectionReason,
        val retryAt: DataLoomInstant? = null,
    ) : AccessTransition

    data class StartProbe(
        val nextState: CircuitBreakerState,
        val permit: CircuitBreakerProbePermit,
    ) : AccessTransition
}

private sealed interface OutcomeTransition {
    data object NoChange : OutcomeTransition
    data object StaleProbe : OutcomeTransition
    data class ClockRegression(val persistedAt: DataLoomInstant) : OutcomeTransition
    data class Update(val nextState: CircuitBreakerState) : OutcomeTransition
}

private sealed interface LoadOutcome {
    data class Success(val record: CircuitBreakerStateRecord?) : LoadOutcome
    data class Failure(val error: io.dataloom.api.error.DataLoomError) : LoadOutcome
}

private sealed interface UpdateOutcome {
    data object Conflict : UpdateOutcome
    data class Updated(val record: CircuitBreakerStateRecord) : UpdateOutcome
    data class Failure(val error: io.dataloom.api.error.DataLoomError) : UpdateOutcome
}

private fun addSaturated(left: Long, right: Long): Long {
    if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE
    return left + right
}
