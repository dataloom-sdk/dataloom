package io.dataloom.runtime.observation.health

import io.dataloom.api.operational.OperationalEventOutboxScope
import io.dataloom.api.operational.OperationalEventOutboxStateObservation
import io.dataloom.api.operational.OperationalEventOutboxStateObserver
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.operational.OperationalEventOutboxProcessingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** How one [io.dataloom.runtime.operational.DurableOperationalEventOutboxProcessor.process] cycle ended. */
public enum class OperationalEventOutboxProcessingCycleOutcome {
    /** The scope had nothing pending. */
    NO_WORK,

    /** The cycle read the scope and ran the handler over what it read (possibly nothing matched the filter). */
    PROCESSED,

    /** The initial read failed; no handler ran. */
    READ_FAILURE,
}

/**
 * What the most recent processing cycle for a scope did, as counts only.
 *
 * @param entriesLeftPending entries the cycle presented but did not
 *   acknowledge: handler `Skipped` + `Failed`, plus `Processed` entries whose
 *   acknowledgement failed. This is a fact about that cycle, **not** a live
 *   count of stuck entries -- they may have been handled since.
 */
public data class OperationalEventOutboxProcessingCycleObservation(
    public val outcome: OperationalEventOutboxProcessingCycleOutcome,
    public val skipped: Int,
    public val failed: Int,
    public val acknowledgeFailed: Int,
    public val observedAt: DataLoomInstant,
) {
    public val entriesLeftPending: Int get() = skipped + failed + acknowledgeFailed
}

/**
 * Everything [OperationalEventOutboxHealthTracker] currently knows about one
 * outbox scope. Either part may be absent: [stateObservation] until the
 * scope's state has been read or written by an observing outbox, and
 * [lastProcessingCycle] until a tracked processor has run a cycle for it.
 */
public data class OperationalEventOutboxObservedState(
    public val scope: OperationalEventOutboxScope,
    public val stateObservation: OperationalEventOutboxStateObservation?,
    public val lastProcessingCycle: OperationalEventOutboxProcessingCycleObservation?,
)

/**
 * The synchronous health read path for the durable operational-event outbox.
 *
 * ## The guarantee -- and its limit
 *
 * [DurableOperationalEventOutbox][io.dataloom.api.operational.DurableOperationalEventOutbox]
 * reads suspend over a durable store, so a snapshot that must not suspend
 * cannot read it. This class is the chosen alternative: the outbox is given
 * this tracker as its `stateObserver` and **pushes** a bounded summary to it
 * after every state it loads-and-evaluates or writes, and
 * [snapshot] returns the latest of those. So:
 *
 * - [snapshot] never suspends, never performs I/O and never touches the
 *   outbox or its store.
 * - Each value is the state *this process's outbox instance last saw or
 *   wrote*, carrying its own `observedAt` and store version. It is **not** the
 *   store's current state: another instance or process sharing the store may
 *   have changed the scope since, and a scope this process has never touched
 *   is absent altogether.
 * - Whether an observation is old enough to distrust is the consumer's call:
 *   [dataLoomHealthSnapshot] applies
 *   [DataLoomHealthThresholds.outboxObservationStaleAfter] and reports a
 *   `stale` flag; nothing here refreshes a value on its own or pretends an old
 *   value is current.
 *
 * Observations of one scope are merged by store version, so an observation
 * arriving late from a racing call (an older version) never replaces a newer
 * one. Two outbox instances with *different* stores that share a scope name
 * share one entry here (versions are per store); give distinct outboxes
 * distinct scopes, as the builder's five outboxes already have.
 *
 * ## Processing cycles
 *
 * The outbox does not know what a handler decided, so "did the last cycle
 * leave entries pending" comes from
 * [io.dataloom.runtime.operational.DurableOperationalEventOutboxProcessor],
 * which reports each cycle here when constructed with this tracker.
 *
 * Thread-safe: state is held in one atomically updated flow value.
 *
 * @param clock timestamps processing cycles only; state observations carry the
 *   outbox's own clock reading.
 */
public class OperationalEventOutboxHealthTracker(
    private val clock: DataLoomClock,
) : OperationalEventOutboxStateObserver {

    private val states = MutableStateFlow<Map<OperationalEventOutboxScope, OperationalEventOutboxObservedState>>(emptyMap())

    override fun onStateObserved(observation: OperationalEventOutboxStateObservation) {
        states.update { current ->
            val existing = current[observation.scope]
            val held = existing?.stateObservation
            if (held != null && (observation.storeVersion ?: NO_VERSION) < (held.storeVersion ?: NO_VERSION)) {
                current
            } else {
                current + (
                    observation.scope to OperationalEventOutboxObservedState(
                        scope = observation.scope,
                        stateObservation = observation,
                        lastProcessingCycle = existing?.lastProcessingCycle,
                    )
                    )
            }
        }
    }

    /** Records how a processing cycle over [scope] ended. */
    public fun recordProcessingCycle(scope: OperationalEventOutboxScope, result: OperationalEventOutboxProcessingResult) {
        val cycle = when (result) {
            is OperationalEventOutboxProcessingResult.NoWork ->
                OperationalEventOutboxProcessingCycleObservation(
                    OperationalEventOutboxProcessingCycleOutcome.NO_WORK, 0, 0, 0, clock.now(),
                )
            is OperationalEventOutboxProcessingResult.ReadFailure ->
                OperationalEventOutboxProcessingCycleObservation(
                    OperationalEventOutboxProcessingCycleOutcome.READ_FAILURE, 0, 0, 0, clock.now(),
                )
            is OperationalEventOutboxProcessingResult.Processed ->
                OperationalEventOutboxProcessingCycleObservation(
                    outcome = OperationalEventOutboxProcessingCycleOutcome.PROCESSED,
                    skipped = result.summary.skipped,
                    failed = result.summary.failed,
                    acknowledgeFailed = result.summary.acknowledgeFailed,
                    observedAt = clock.now(),
                )
        }
        states.update { current ->
            val existing = current[scope]
            current + (
                scope to OperationalEventOutboxObservedState(
                    scope = scope,
                    stateObservation = existing?.stateObservation,
                    lastProcessingCycle = cycle,
                )
                )
        }
    }

    /**
     * The latest thing known about every scope this tracker has heard of,
     * ordered by scope name. Empty if nothing has been observed. Synchronous
     * and side-effect free; see this class's documentation for exactly what
     * the values do and do not promise.
     */
    public fun snapshot(): List<OperationalEventOutboxObservedState> =
        states.value.values.sortedBy { it.scope.value }

    private companion object {
        const val NO_VERSION: Long = -1L
    }
}
