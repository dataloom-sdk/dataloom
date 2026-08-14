package io.dataloom.platform.ios.connectivity.internal

import io.dataloom.api.connectivity.ConnectivitySnapshot
import io.dataloom.api.connectivity.ConnectivityStatus

/**
 * Translates a raw [NetworkPathObservation] into the canonical
 * [ConnectivitySnapshot] shape.
 *
 * This function is pure and platform-independent: it performs no I/O, holds
 * no platform network handle, and does not itself query NWPathMonitor --
 * see [currentNetworkPathObservation] for that. Kept separate from
 * [io.dataloom.platform.ios.connectivity.AppleConnectivityProvider] so the
 * classification rules below are unit-testable without an iOS host,
 * simulator, or device.
 *
 * Classification rules:
 * - [NetworkPathStatus.SATISFIED] maps to [ConnectivityStatus.AVAILABLE].
 * - [NetworkPathStatus.SATISFIABLE] maps to [ConnectivityStatus.LIMITED]:
 *   the platform reports the path could become usable, but it is not
 *   currently validated usable connectivity.
 * - [NetworkPathStatus.UNSATISFIED] maps to [ConnectivityStatus.UNAVAILABLE].
 * - [NetworkPathStatus.INVALID] maps to [ConnectivityStatus.UNKNOWN]: no
 *   NWPathMonitor update was observed, so connectivity cannot be determined.
 *
 * Metering: [ConnectivitySnapshot.isMetered] is `true` when NWPathMonitor
 * reports the path as expensive or constrained, `false` when neither applies
 * to a satisfied path, and `null` whenever the path is not satisfied --
 * metering state is meaningless for a path that has no usable connectivity,
 * and `false` must never be reported in that case (see
 * [ConnectivitySnapshot.isMetered]'s "must not be treated as unmetered"
 * contract).
 */
internal fun classifyPath(observation: NetworkPathObservation): ConnectivitySnapshot {
    val status = when (observation.status) {
        NetworkPathStatus.SATISFIED -> ConnectivityStatus.AVAILABLE
        NetworkPathStatus.SATISFIABLE -> ConnectivityStatus.LIMITED
        NetworkPathStatus.UNSATISFIED -> ConnectivityStatus.UNAVAILABLE
        NetworkPathStatus.INVALID -> ConnectivityStatus.UNKNOWN
    }

    val isMetered = if (observation.status == NetworkPathStatus.SATISFIED) {
        observation.isExpensive || observation.isConstrained
    } else {
        null
    }

    return ConnectivitySnapshot(status = status, isMetered = isMetered)
}
