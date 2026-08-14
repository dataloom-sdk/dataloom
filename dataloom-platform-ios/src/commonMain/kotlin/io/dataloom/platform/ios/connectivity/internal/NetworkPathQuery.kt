package io.dataloom.platform.ios.connectivity.internal

/**
 * Performs one bounded synchronous query of the current NWPathMonitor
 * network path and returns it as a platform-independent
 * [NetworkPathObservation].
 *
 * This is the one place in this module that touches Network.framework. The
 * `actual` implementation starts an `NWPathMonitor`, blocks the calling
 * thread until its first update handler callback fires, cancels the
 * monitor, and returns -- it does not register a long-lived callback, does
 * not poll, and does not retain the monitor past this single call.
 *
 * Must not throw for ordinary "no network" conditions; a genuine platform
 * failure is expected to propagate as an exception, which
 * [io.dataloom.platform.ios.connectivity.AppleConnectivityProvider] maps to
 * a canonical [io.dataloom.api.error.DataLoomError].
 */
internal expect fun currentNetworkPathObservation(): NetworkPathObservation
