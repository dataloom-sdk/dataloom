package io.dataloom.api.connectivity

import io.dataloom.api.context.ExecutionContext

/**
 * Immutable request to query the current device-level connectivity state from
 * a [ConnectivityProvider].
 *
 * [ConnectivityCheckRequest] carries the execution context for the connectivity
 * query and is passed to [ConnectivityProvider.currentConnectivity].
 *
 * ## Construction behaviour
 *
 * Construction does not query network state, read platform connectivity APIs,
 * or trigger any platform callbacks.
 *
 * ## Equality
 *
 * Equality compares [context] by value.
 *
 * @param context required execution context for this connectivity check.
 */
public data class ConnectivityCheckRequest(
    /** Required execution context for this connectivity check. */
    public val context: ExecutionContext,
)
