package io.dataloom.platform.ios.connectivity.internal

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_status_unsatisfied
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * The only file in this module that touches Network.framework.
 *
 * Starts a fresh `nw_path_monitor_t`, blocks the calling thread on a
 * dispatch semaphore until the monitor's first update handler callback
 * fires, cancels the monitor, and returns a platform-independent
 * [NetworkPathObservation]. No monitor, queue, or semaphore outlives this
 * single call -- there is no automatic monitoring, caching, or retry.
 *
 * This is a blocking call by construction: `NWPathMonitor`'s C API is
 * callback-based, and [ConnectivityProvider][io.dataloom.api.connectivity.ConnectivityProvider]
 * requires one bounded synchronous query rather than a stream. Callers
 * reach this from [io.dataloom.platform.ios.connectivity.AppleConnectivityProvider.currentConnectivity],
 * a `suspend` function; the blocking work happens on whatever dispatcher the
 * caller's coroutine is already running on, matching the same tradeoff
 * `ConnectivityManager.getNetworkCapabilities` makes on the Android side.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun currentNetworkPathObservation(): NetworkPathObservation {
    val monitor = nw_path_monitor_create()
    val queue = dispatch_queue_create("io.dataloom.platform.ios.connectivity.path-monitor", null)
    val semaphore = dispatch_semaphore_create(0)

    var observation = NetworkPathObservation(
        status = NetworkPathStatus.INVALID,
        isExpensive = false,
        isConstrained = false,
    )

    nw_path_monitor_set_queue(monitor, queue)
    nw_path_monitor_set_update_handler(monitor) { path ->
        observation = NetworkPathObservation(
            status = when (nw_path_get_status(path)) {
                nw_path_status_satisfied -> NetworkPathStatus.SATISFIED
                nw_path_status_unsatisfied -> NetworkPathStatus.UNSATISFIED
                nw_path_status_satisfiable -> NetworkPathStatus.SATISFIABLE
                else -> NetworkPathStatus.INVALID
            },
            isExpensive = nw_path_is_expensive(path),
            isConstrained = nw_path_is_constrained(path),
        )
        dispatch_semaphore_signal(semaphore)
    }
    nw_path_monitor_start(monitor)
    dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)
    nw_path_monitor_cancel(monitor)

    return observation
}
