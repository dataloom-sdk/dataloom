package io.dataloom.platform.ios.connectivity.internal

/**
 * Platform-independent snapshot of the raw fields a single NWPathMonitor
 * update reports for the current network path.
 *
 * [NetworkPathObservation] intentionally carries only the primitive fields
 * [classifyPath] needs to build a [io.dataloom.api.connectivity.ConnectivitySnapshot].
 * It does not wrap or expose `nw_path_t`, `NWPathMonitor`, or any other
 * Network.framework handle -- see [currentNetworkPathObservation] for the one
 * place this module reads those platform types.
 */
internal data class NetworkPathObservation(
    /** Raw path status reported by NWPathMonitor for this observation. */
    val status: NetworkPathStatus,

    /**
     * `true` when NWPathMonitor reports the path as expensive (for example,
     * a cellular or personal-hotspot connection).
     */
    val isExpensive: Boolean,

    /**
     * `true` when NWPathMonitor reports the path as constrained (for
     * example, iOS Low Data Mode).
     */
    val isConstrained: Boolean,
)

/**
 * Platform-independent mirror of `nw_path_status_t`.
 *
 * Deliberately not bound to the `nw_path_status_t` C enum value ordering --
 * [currentNetworkPathObservation] performs the translation from the raw
 * platform enum into this closed set.
 */
internal enum class NetworkPathStatus {
    /** No path status has been reported yet, or the path is invalid. */
    INVALID,

    /** `nw_path_status_satisfied` -- the path is usable. */
    SATISFIED,

    /** `nw_path_status_unsatisfied` -- the path is not usable. */
    UNSATISFIED,

    /** `nw_path_status_satisfiable` -- the path could become usable. */
    SATISFIABLE,
}
