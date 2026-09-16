@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processcontentionproof

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.time.AppleDataLoomClock
import io.dataloom.runtime.retry.AppleFileCircuitBreakerStateStore
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerPermission
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.access
import platform.posix.close
import platform.posix.open

/**
 * Real production Apple counterpart to
 * `AndroidCircuitBreakerProbeContentionInstrumentedTest`
 * (`dataloom-queue-room/src/androidTest/kotlin/io/dataloom/queue/room/`),
 * driving the exact same real [CircuitBreakerCoordinator]/
 * [CircuitBreakerExecutionGate] pair against the same production
 * [AppleFileCircuitBreakerStateStore] that two genuinely separate iOS
 * Simulator *app processes* (not two threads in one process) race against
 * -- closing the "cross-process probe contention ... genuinely blocked"
 * gap `docs/apple/process-termination-proof.md` and this repository's
 * market-readiness dashboard both named.
 *
 * ## How two genuinely separate Simulator app processes race on one file
 *
 * iOS has no `android:process`-equivalent manifest attribute -- one app
 * bundle is one process -- so this proof uses two genuinely different app
 * targets/bundle identifiers (`ProcessContentionProofAppA`/
 * `ProcessContentionProofAppB`, `apple-process-contention-proof-app/`)
 * instead, each `xcrun simctl launch`ed as its own real OS process. What
 * lets those two independent apps race on the *same* on-disk state file
 * without Apple's App Groups entitlement (which requires a paid Apple
 * Developer Program membership -- see
 * `docs/apple/cross-process-contention-investigation.md` for why that path,
 * and an app-extension-based alternative, both dead-end on the same
 * paid-account wall) is a separate, well-documented iOS Simulator property:
 * **the iOS Simulator does not sandbox apps against the host Mac's
 * filesystem the way a real device does** -- a Simulator-hosted app has the
 * same filesystem access as any other macOS process run by the host user,
 * including paths outside its own app container. Both apps are launched
 * with a shared absolute directory path (via `simctl launch`'s own
 * documented `SIMCTL_CHILD_*` environment-forwarding convention, pointing
 * at a plain host-filesystem directory the CI script itself also reads from
 * directly), so [AppleFileCircuitBreakerStateStore]'s existing, unmodified,
 * `flock`-based cross-process advisory locking -- already designed to
 * serialize "multiple store instances and cooperating app processes that
 * use the same directory and file name" (see that store's own class doc) --
 * enforces mutual exclusion across the two real OS processes exactly the
 * way it would across any two arbitrary processes on the same machine. This
 * is a genuinely different mechanism from Android's `android:process`, not
 * a same-shaped port of it, but it produces the same proof-relevant
 * property: two independently-launched OS processes contending for the
 * same durable-state record through the store's own compare-and-set, never
 * a test-only mutex.
 *
 * ## Why file-based polling substitutes for Android's `CyclicBarrier`
 *
 * Android's proof releases two already-launched, already-warm threads
 * together with an in-process `CyclicBarrier`. Two genuinely separate OS
 * processes launched by a host shell script have no such shared in-memory
 * primitive. This proof's substitute: both racing processes launch, reach
 * [waitForGoSignalThenAttemptProbe], and immediately begin busy-polling
 * (5ms intervals) for a shared "go" file's existence -- an OS-level
 * file-existence check, not a test-only synchronization object -- that
 * neither process controls. Only once the host CI script (which
 * independently confirms both processes are alive and already polling, via
 * each process's own separate "ready" marker file, before it acts) creates
 * that file do both processes race to call the real
 * [CircuitBreakerCoordinator.acquire] as fast after observing it as they
 * can. This is coarser-grained than an in-process barrier -- polling
 * resolution, not a single scheduler wakeup -- and, like the Android test's
 * own documented boundary, does not claim nanosecond-identical dispatch.
 * The assertions this proof's CI job makes do not depend on which process
 * wins, only that mutual exclusion holds regardless of order.
 *
 * ## Scope versus `AppleCircuitBreakerProcessTerminationProof`
 *
 * Unlike that class (`#94`'s single-process kill/relaunch proof,
 * `apple-process-termination-proof/`), which drives
 * [AppleFileCircuitBreakerStateStore] directly with hand-built state
 * records to keep its Swift-interop surface to primitive types only, this
 * proof drives the real [CircuitBreakerCoordinator]/
 * [CircuitBreakerExecutionGate] pair -- matching
 * `CircuitBreakerProbeContentionContentProviderBase`'s own choice for the
 * Android precedent, since the entire point of *this* proof is exercising
 * the coordinator's own contention-handling logic
 * (`AccessTransition.StartProbe`, its compare-and-set retry loop, and
 * `CircuitBreakerRejectionReason.PROBE_IN_FLIGHT`), not just the
 * lower-level store underneath it.
 */
public object AppleCircuitBreakerProbeContentionProof {

    private val scope: CircuitBreakerScope = CircuitBreakerScope.provider(
        ProviderId("apple-circuit-probe-contention"),
    )
    private val clock = AppleDataLoomClock()

    /**
     * Drives two real injected failures through the real
     * [CircuitBreakerExecutionGate]/[CircuitBreakerCoordinator] pair against
     * a fresh [AppleFileCircuitBreakerStateStore] rooted at [directoryPath],
     * opening the circuit, then touches [readyMarkerPath] so the host CI
     * script can confirm this process has finished setup and is about to
     * enter its wait loop.
     *
     * Called only by the "opener" app process (`ProcessContentionProofAppA`);
     * the non-opening racer (`ProcessContentionProofAppB`) touches its own
     * ready marker directly from Swift instead, since it has no setup work
     * of its own to do.
     */
    public fun openCircuitAndSignalReady(directoryPath: String, readyMarkerPath: String): Unit = runBlocking {
        val gate = CircuitBreakerExecutionGate(
            CircuitBreakerCoordinator(
                configuration = configuration(),
                clock = clock,
                stateStore = AppleFileCircuitBreakerStateStore(directoryPath),
            ),
        )
        val failure = CircuitProtectedOperationResult.Failure(InjectedTransportFailure)
        gate.execute<Unit>(scope) { failure }
        gate.execute<Unit>(scope) { failure }
        touchFile(readyMarkerPath)
    }

    /**
     * Busy-polls (5ms intervals, [maxPollAttempts] bound) for [goSignalPath]
     * to appear, then immediately calls the real
     * [CircuitBreakerCoordinator.acquire] against a fresh
     * [AppleFileCircuitBreakerStateStore] rooted at [directoryPath] and
     * returns the classified outcome. Called by both racing app processes.
     */
    public fun waitForGoSignalThenAttemptProbe(
        directoryPath: String,
        goSignalPath: String,
        maxPollAttempts: Int = DEFAULT_MAX_POLL_ATTEMPTS,
    ): ProcessContentionProofResult = runBlocking {
        var observed = false
        var attempt = 0
        while (attempt < maxPollAttempts) {
            if (fileExists(goSignalPath)) {
                observed = true
                break
            }
            delay(POLL_INTERVAL_MILLISECONDS)
            attempt += 1
        }
        if (!observed) {
            return@runBlocking ProcessContentionProofResult(
                outcome = "TIMED_OUT_WAITING_FOR_GO_SIGNAL",
                rejectionReason = "",
                probeGeneration = -1L,
            )
        }

        val coordinator = CircuitBreakerCoordinator(
            configuration = configuration(),
            clock = clock,
            stateStore = AppleFileCircuitBreakerStateStore(directoryPath),
        )
        when (val permission = coordinator.acquire(scope)) {
            CircuitBreakerPermission.Allowed -> ProcessContentionProofResult(
                outcome = "ALLOWED_NO_CIRCUIT",
                rejectionReason = "",
                probeGeneration = -1L,
            )
            is CircuitBreakerPermission.ProbeAllowed -> ProcessContentionProofResult(
                outcome = "ALLOWED",
                rejectionReason = "",
                probeGeneration = permission.permit.generation,
            )
            is CircuitBreakerPermission.Rejected -> ProcessContentionProofResult(
                outcome = "REJECTED",
                rejectionReason = permission.reason.name,
                probeGeneration = -1L,
            )
            is CircuitBreakerPermission.PersistenceFailure -> ProcessContentionProofResult(
                outcome = "PERSISTENCE_FAILURE",
                rejectionReason = "",
                probeGeneration = -1L,
            )
            CircuitBreakerPermission.ContentionLimitReached -> ProcessContentionProofResult(
                outcome = "CONTENTION_LIMIT",
                rejectionReason = "",
                probeGeneration = -1L,
            )
        }
    }

    /**
     * A short, generous-margin configuration mirroring
     * `CircuitBreakerProbeContentionContentProviderBase`'s own Android
     * configuration exactly: [CircuitBreakerConfiguration.failureThreshold]
     * = 2 real failures opens the circuit almost instantly;
     * [CircuitBreakerConfiguration.openDuration] = 400ms is long enough that
     * app setup/launch comfortably finishes before it elapses, but short
     * enough that the CI script only needs a brief real sleep to guarantee
     * it has elapsed before racing; the wide [CircuitBreakerConfiguration.failureWindow]
     * and [CircuitBreakerConfiguration.halfOpenProbeLeaseDuration] exist
     * purely to avoid any timing coincidence unrelated to the actual race
     * under test.
     */
    private fun configuration(): CircuitBreakerConfiguration = CircuitBreakerConfiguration(
        failureThreshold = 2,
        failureWindow = SchedulingDelay(30_000L),
        openDuration = SchedulingDelay(400L),
        halfOpenProbeLeaseDuration = SchedulingDelay(5_000L),
    )

    private fun touchFile(path: String) {
        val descriptor = open(path, O_WRONLY or O_CREAT or O_TRUNC, S_IRUSR or S_IWUSR)
        check(descriptor >= 0) { "Failed to create marker file at $path" }
        close(descriptor)
    }

    private fun fileExists(path: String): Boolean = access(path, F_OK) == 0

    private object InjectedTransportFailure : DataLoomError {
        override val code: ErrorCode = ErrorCode("CIRCUIT_PROBE_CONTENTION_INJECTED_TRANSPORT_FAILURE")
        override val category: ErrorCategory = ErrorCategory.NETWORK
        override val severity: ErrorSeverity = ErrorSeverity.ERROR
        override val recoverability: Recoverability = Recoverability.RECOVERABLE
        override val message: String = "Sanitized injected failure for Apple probe-contention proof."
        override val cause: Throwable? = null
    }

    private const val POLL_INTERVAL_MILLISECONDS = 5L

    // 5ms * 12,000 = 60s -- generous relative to the CI script's own
    // per-step timeouts (see the new CI job), while still failing loudly
    // rather than hanging forever if the host script's "go" file never
    // appears.
    private const val DEFAULT_MAX_POLL_ATTEMPTS = 12_000
}
