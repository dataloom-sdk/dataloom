@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.processcontentionproof

import kotlinx.coroutines.delay
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
 * Small file-signal helpers shared by [AppleUnresolvedConflictLogContentionProof]
 * and [AppleResolvedConflictDecisionLogContentionProof] -- the same
 * touch-a-marker-file/busy-poll-for-a-marker-file substitute for an
 * in-process `CyclicBarrier` [AppleCircuitBreakerProbeContentionProof]
 * already establishes for the circuit-breaker domain (see that object's own
 * KDoc, "Why file-based polling substitutes for Android's `CyclicBarrier`",
 * for the full reasoning -- not repeated here). Factored out here since both
 * new conflict-log domains need the identical mechanism and, unlike the
 * circuit-breaker domain, have no opener/racer asymmetry of their own to
 * keep textually separate.
 *
 * [AppleCircuitBreakerProbeContentionProof] itself is deliberately left with
 * its own private, byte-for-byte-equivalent copy of this same logic rather
 * than refactored to call these functions: that object is already-proven,
 * real-macOS-CI-green production infrastructure (see
 * `docs/apple/process-contention-proof.md`'s "Update: first real macOS CI
 * run" section), and it is not worth even a same-behavior refactor's small
 * risk to something that already works for real, on real CI, when a few
 * duplicated lines accomplish the identical thing with zero risk to it.
 */
internal fun touchContentionMarkerFile(path: String) {
    val descriptor = open(path, O_WRONLY or O_CREAT or O_TRUNC, S_IRUSR or S_IWUSR)
    check(descriptor >= 0) { "Failed to create marker file at $path" }
    close(descriptor)
}

internal fun contentionMarkerFileExists(path: String): Boolean = access(path, F_OK) == 0

/**
 * Busy-polls (5ms intervals) for [goSignalPath] to appear, bounded by
 * [maxPollAttempts]. Returns `true` once observed, `false` if the bound was
 * exhausted first without ever seeing it.
 */
internal suspend fun awaitContentionGoSignal(goSignalPath: String, maxPollAttempts: Int): Boolean {
    var attempt = 0
    while (attempt < maxPollAttempts) {
        if (contentionMarkerFileExists(goSignalPath)) return true
        delay(CONTENTION_POLL_INTERVAL_MILLISECONDS)
        attempt += 1
    }
    return false
}

internal const val CONTENTION_POLL_INTERVAL_MILLISECONDS = 5L

// 5ms * 12,000 == 60s, matching AppleCircuitBreakerProbeContentionProof's own
// DEFAULT_MAX_POLL_ATTEMPTS -- generous relative to the CI script's own
// per-step timeouts, while still failing loudly rather than hanging forever
// if the host script's "go" file never appears.
internal const val DEFAULT_CONFLICT_CONTENTION_MAX_POLL_ATTEMPTS = 12_000
